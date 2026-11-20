package io.github.gcbond.openhc.wireless

// 「无线自动回连」什么时候进入等待、一次连接结束后多久再来。本文件是纯逻辑，不引用安卓类，由 JVM 单元测试覆盖；
// 接线见 WirelessController。

/** 自动回连现在没在等的原因。界面上只在空闲时显示一行说明，不当成错误。 */
enum class AutoHold {
    /** 设置里开着「只用数据线连接」：完全不做无线。 */
    WIRED_ONLY,

    /** 设置里关了。 */
    DISABLED,

    /** 用户在车机上点了停止：本次运行不再自动等待，点「开始无线连接」恢复。 */
    PAUSED_BY_USER,

    /** 有线连接在进行。 */
    WIRED_ACTIVE,

    /** 车机系统是安卓 8.0 以下，没有本地热点：用不了无线连接。 */
    HOTSPOT_UNSUPPORTED,

    /** 没有身份参数。 */
    IDENTITY_MISSING,

    /** 还没有配对过的手机（有线、无线配对的都算）。 */
    NO_PAIRED_PHONE,

    BLUETOOTH_OFF,

    /** 缺运行时权限（含安卓 12 起的蓝牙广播权限）。 */
    PERMISSIONS_MISSING,

    /** 车机报告不支持 BLE 广播。 */
    ADVERTISING_UNSUPPORTED,

    /** 读不到车机真实的蓝牙地址。 */
    BLUETOOTH_ADDRESS_MISSING,

    /** 应用在后台，系统不让起前台服务；回到界面时再试。 */
    FOREGROUND_UNAVAILABLE,

    /** 其他原因开始不了（见日志）。 */
    START_FAILED,
}

/**
 * 自动回连的进入条件和结束后的退避。只在一个线程上调用。
 *
 * - 进入等待要同时满足：没开「只用数据线连接」、开关开着（手机蓝牙连上触发的不看这一条）、用户没在本次运行里点过停止、
 *   有线没在进行、系统能开本地热点、有身份参数、有配对记录、蓝牙开着、权限齐、车机支持 BLE 广播、读得到车机真实蓝牙地址
 *   （[hold] 返回 null）。不满足只说明原因，不弹框、不报错。
 * - 手机蓝牙连上（[onBluetoothTrigger]）当作用户回到车上：清掉「点过停止」和失败计数。
 * - 一次连接（不论是自动进入的还是用户点开始的）结束后按结果决定多久后再判断（[afterAttempt]）：
 *   用户停止 → 本次运行不再自动等待；让给有线 → 过一会儿再看（有线真的开始了就会挡住）；
 *   手机连上过（认证、组网失败或投屏后结束）→ 很快回到等待；手机还没来就出错 → 按连续失败次数退避。
 */
internal class AutoReconnectPolicy(
    /** 手机还没来就出错时的退避（毫秒），按连续失败次数取，超出取最后一个。 */
    private val backoffMillis: LongArray = DEFAULT_BACKOFF_MILLIS,
) {
    init {
        require(backoffMillis.isNotEmpty() && backoffMillis.all { it > 0 }) { "退避时间要大于 0" }
    }

    /** 判断时的条件快照。 */
    class Conditions(
        val enabled: Boolean,
        val wiredOnly: Boolean = false,
        val wiredActive: Boolean,
        val identityLoaded: Boolean,
        val pairedPhones: Int,
        val bluetoothOn: Boolean,
        /** 系统能开本地热点（安卓 8.0 起）。 */
        val hotspotSupported: Boolean,
        val permissionsGranted: Boolean,
        val advertisingSupported: Boolean,
        val bluetoothAddressKnown: Boolean,
    )

    /** 一次连接是怎么结束的。 */
    enum class Outcome {
        /** 用户在车机上点了停止（或前台服务被系统结束）。 */
        USER_STOP,

        /** 让给有线：有线要开始了。 */
        YIELDED,

        /** 设置可能变了，按新设置重开（手机还没连上）。 */
        RESTART,

        /** 手机还没连上就结束了（监听开不了、蓝牙被关等）。 */
        ENDED_WITHOUT_PHONE,

        /** 手机经蓝牙连上过：认证、组网失败，或投屏后结束。 */
        ENDED_AFTER_PHONE,
    }

    /** 用户在本次运行里点过停止。 */
    var pausedByUser: Boolean = false
        private set

    /** 手机还没来就出错的连续次数。 */
    var consecutiveFailures: Int = 0
        private set

    /**
     * 现在能不能进入等待：能返回 null，不能返回原因（按优先级取第一个）。
     * [bluetoothTrigger]：这次是手机蓝牙连上触发的，「无线自动回连」开关关着也照样进入。
     */
    fun hold(c: Conditions, bluetoothTrigger: Boolean = false): AutoHold? = when {
        c.wiredOnly -> AutoHold.WIRED_ONLY
        !c.enabled && !bluetoothTrigger -> AutoHold.DISABLED
        pausedByUser -> AutoHold.PAUSED_BY_USER
        c.wiredActive -> AutoHold.WIRED_ACTIVE
        !c.hotspotSupported -> AutoHold.HOTSPOT_UNSUPPORTED
        !c.identityLoaded -> AutoHold.IDENTITY_MISSING
        c.pairedPhones <= 0 -> AutoHold.NO_PAIRED_PHONE
        !c.bluetoothOn -> AutoHold.BLUETOOTH_OFF
        !c.permissionsGranted -> AutoHold.PERMISSIONS_MISSING
        !c.advertisingSupported -> AutoHold.ADVERTISING_UNSUPPORTED
        !c.bluetoothAddressKnown -> AutoHold.BLUETOOTH_ADDRESS_MISSING
        else -> null
    }

    /**
     * 一次连接结束后：返回多少毫秒后再判断要不要进入等待；null 表示不自动再来。
     *
     * @param durationMillis 这次连接从开始到结束多久。手机还没来就出错、但之前已经平稳等了很久的，不算连续失败。
     */
    fun afterAttempt(outcome: Outcome, durationMillis: Long = 0): Long? = when (outcome) {
        Outcome.USER_STOP -> {
            pausedByUser = true
            null
        }
        Outcome.YIELDED -> YIELD_RECHECK_MILLIS
        Outcome.RESTART -> RESTART_DELAY_MILLIS
        Outcome.ENDED_WITHOUT_PHONE -> {
            if (durationMillis >= backoffMillis.last()) consecutiveFailures = 0
            consecutiveFailures++
            backoffMillis[minOf(consecutiveFailures, backoffMillis.size) - 1]
        }
        Outcome.ENDED_AFTER_PHONE -> {
            consecutiveFailures = 0
            backoffMillis[0]
        }
    }

    /** 用户自己点了「开始无线连接」：恢复自动回连，清掉失败计数。 */
    fun onUserStart() {
        pausedByUser = false
        consecutiveFailures = 0
    }

    /** 用户在设置里切了开关：恢复（关的话由 [hold] 挡住），清掉失败计数。 */
    fun onSettingChanged() {
        pausedByUser = false
        consecutiveFailures = 0
    }

    /** 手机的经典蓝牙连上了车机：当作用户回到车上，恢复自动回连、清掉失败计数。 */
    fun onBluetoothTrigger() {
        pausedByUser = false
        consecutiveFailures = 0
    }

    companion object {
        val DEFAULT_BACKOFF_MILLIS = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)

        /** 让给有线之后多久再看一次：有线真的开始了会挡住，没开始成（例如没给 VPN 授权）就回到等待。 */
        const val YIELD_RECHECK_MILLIS = 5_000L

        /** 按新设置重开前留一点时间，让上一轮的蓝牙监听、广播、GATT 在系统里撤干净。 */
        const val RESTART_DELAY_MILLIS = 1_000L

        /**
         * 无线挡不挡有线：在进行的无线连接要挡，但「等已配对的手机回连」时手机还没连上，有线来了就让给有线。
         */
        fun blocksWired(state: WirelessUiState): Boolean = state.active && !state.waitingForReconnect
    }
}
