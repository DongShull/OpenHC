package io.github.gcbond.openhc.wifi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 在车机上跑一次开关小程序的结果（[ScanSwitchMain] 的输出，加上连不上 ADB 的几种情况）。 */
enum class ScanSwitchResult {
    /** 开关改好了。 */
    DONE,

    /** 检查：ADB 授权了、系统有这个接口，整条路走得通。 */
    READY,

    /** 车机本机的网络 ADB 没开（端口连不上）。 */
    ADB_OFF,

    /** 车机没授权这个应用调试（没点允许）。 */
    NOT_APPROVED,

    /** 车机只接受加密的「无线调试」，不支持。 */
    TLS_ONLY,

    /** 车机系统里没有这个开关。 */
    UNSUPPORTED,

    /** 系统拒绝了 shell 身份调用。 */
    DENIED,

    /** 其他错误：超时、输出不对。 */
    FAILED,
}

/** 在车机 shell 里跑开关小程序的命令行，以及解析它的输出。 */
object ScanSwitchCommand {
    /** 小程序的入口类（安装包里的 [ScanSwitchMain]）。 */
    const val MAIN_CLASS = "io.github.gcbond.openhc.wifi.ScanSwitchMain"

    fun line(apkPath: String, command: String): String {
        require(command == ScanSwitchMain.PAUSE || command == ScanSwitchMain.RESTORE || command == ScanSwitchMain.CHECK)
        return "CLASSPATH=${quote(apkPath)} app_process /system/bin $MAIN_CLASS $command"
    }

    /** 找 `OPENHC_SCAN_SWITCH|结果|方法` 那一行；没有（小程序超时退出、起不来）或结果不认识时算 [ScanSwitchResult.FAILED]。 */
    fun parse(output: String): ScanSwitchResult {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(ScanSwitchMain.HEADER + "|") }
            ?: return ScanSwitchResult.FAILED
        return when (line.split('|').getOrNull(1)) {
            "DONE" -> ScanSwitchResult.DONE
            "READY" -> ScanSwitchResult.READY
            "UNSUPPORTED" -> ScanSwitchResult.UNSUPPORTED
            "DENIED" -> ScanSwitchResult.DENIED
            else -> ScanSwitchResult.FAILED
        }
    }

    /** 单引号括起来，路径里的单引号按 shell 规矩转义。 */
    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
}

/** 跑开关小程序（阻塞，几秒到十几秒）。[ask] 为真时车机不认这个应用的钥匙就弹「允许调试吗」等车主点。 */
fun interface ScanSwitchRunner {
    fun run(command: String, ask: Boolean): ScanSwitchResult
}

/** 「我们暂停了扫描、还没恢复」的记号，存在磁盘上：进程中途没了，下次启动按它恢复。 */
interface ScanPauseJournal {
    var pending: Boolean
}

/** 调度：都在同一个后台线程上按顺序跑。 */
interface ScanPauseWorker {
    fun execute(task: () -> Unit)

    /** [delayMillis] 后跑；返回取消用的函数。 */
    fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit
}

/**
 * 「连接时暂停车机 WiFi 扫描」（可选，默认关）。只用在「5 GHz 直连」投屏期间：车机 WiFi 开着却没连网络，系统会定时扫描
 * 找网络，单射频每扫一次都要离开直连信道，画面和声音跟着顿一下。经车机本机的网络 ADB 关掉系统的自动连网，结束时开回来。
 *
 * 只做能还原的改动，规则：
 * - 暂停前先在磁盘上记下「待恢复」，再去改；进程中途没了，下次启动、开机或点火、开始无线连接时（[recover]）按记号恢复；
 * - 投屏结束（[sessionEnded]）、关掉设置（[settingChanged]）时恢复；恢复失败（例如 ADB 一时连不上）每 [RETRY_MILLIS]
 *   重试，最多 [RETRY_LIMIT] 次，记号留着等下次 [recover]；
 * - 暂停没成功（ADB 没开、没授权、系统不支持）就什么都没改，记号清掉；结果不明（超时）时记号留着，按要恢复处理；
 * - 后台一律不弹授权框；只有车主在设置里打开时（[check]）才请求授权。
 *
 * 不是所有扫描都停得了：应用自己调扫描接口、定位服务的扫描不归这个开关管。
 */
class ScanPause(
    private val runner: ScanSwitchRunner,
    private val journal: ScanPauseJournal,
    private val enabled: () -> Boolean,
    private val worker: ScanPauseWorker,
    private val log: (String) -> Unit,
) {
    enum class Phase { IDLE, CHECKING, PAUSING, PAUSED, RESTORING }

    /** 设置页显示用：正在做什么、上一次的结果。 */
    data class Status(val phase: Phase = Phase.IDLE, val last: ScanSwitchResult? = null)

    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    // 下面的状态只在 worker 线程上读写。
    private var owner: Int? = null
    private var retries = 0
    private var cancelRetry: (() -> Unit)? = null

    /**
     * 补恢复：上次暂停了、还没恢复（应用中途退出，或者恢复一直没成功）就恢复。进程启动、开机或点火、每次开始无线连接时调
     * （有的车熄火不结束进程，只靠进程启动时查可能好几天都查不到）。[why] 只用于日志。
     */
    fun recover(why: String) = worker.execute {
        if (journal.pending && owner == null) {
            log("上次暂停了车机 WiFi 扫描还没恢复，现在恢复（$why）")
            restore()
        }
    }

    /** 一次「5 GHz 直连」投屏开始了（[id] 是这次连接的编号）。设置关着时什么都不做。 */
    fun sessionStarted(id: Int) = worker.execute {
        if (!enabled()) return@execute
        owner = id
        cancelRetries()
        val hadPending = journal.pending
        journal.pending = true
        update(Phase.PAUSING)
        val result = runner.run(ScanSwitchMain.PAUSE, ask = false)
        when (result) {
            ScanSwitchResult.DONE -> log("投屏期间暂停车机 WiFi 扫描：已暂停")
            ScanSwitchResult.FAILED -> log("暂停车机 WiFi 扫描结果不明（${result}），结束时照样恢复")
            else -> {
                // 什么都没改；之前留下的待恢复记号保留。
                if (!hadPending) journal.pending = false
                log("没能暂停车机 WiFi 扫描：$result")
            }
        }
        // 跑的这几秒里连接要是结束了，[sessionEnded] 排在后面，会接着恢复。
        update(if (journal.pending) Phase.PAUSED else Phase.IDLE, result)
    }

    /** 这次投屏结束（不管是不是直连、有没有暂停过都可以调）：暂停过就恢复。 */
    fun sessionEnded(id: Int) = worker.execute {
        if (owner != id) return@execute
        owner = null
        if (journal.pending) restore()
    }

    /** 设置开关变了：关掉时马上恢复（哪怕投屏还在进行）。打开在下一次投屏时生效。 */
    fun settingChanged(on: Boolean) = worker.execute {
        if (on) return@execute
        if (journal.pending) {
            log("关掉了「暂停车机 WiFi 扫描」，马上恢复")
            restore()
        }
    }

    /** 车主在设置里打开时检查一遍：会请求授权（车机弹框），确认小程序跑得起来。不改动扫描。 */
    fun check() = worker.execute {
        update(Phase.CHECKING)
        val result = runner.run(ScanSwitchMain.CHECK, ask = true)
        log("检查「暂停车机 WiFi 扫描」：$result")
        update(if (journal.pending && owner != null) Phase.PAUSED else Phase.IDLE, result)
    }

    private fun restore(retry: Boolean = false) {
        cancelRetries()
        if (!retry) retries = 0
        update(Phase.RESTORING)
        val result = runner.run(ScanSwitchMain.RESTORE, ask = false)
        when (result) {
            ScanSwitchResult.DONE -> {
                journal.pending = false
                log("已恢复车机 WiFi 扫描")
            }
            // 系统没有这个开关、或者不让改：之前也不可能改成，不用再试。
            ScanSwitchResult.UNSUPPORTED, ScanSwitchResult.DENIED -> {
                journal.pending = false
                log("恢复车机 WiFi 扫描：$result，不再重试")
            }
            else -> {
                if (retries < RETRY_LIMIT) {
                    retries++
                    log("恢复车机 WiFi 扫描没成功（$result），${RETRY_MILLIS / 1000} 秒后第 $retries 次重试")
                    cancelRetry = worker.schedule(RETRY_MILLIS) {
                        cancelRetry = null
                        if (journal.pending && owner == null) restore(retry = true)
                    }
                } else {
                    log("恢复车机 WiFi 扫描一直没成功（$result），下次开始无线连接、点火或打开应用时再试")
                }
            }
        }
        update(Phase.IDLE, result)
    }

    private fun cancelRetries() {
        cancelRetry?.invoke()
        cancelRetry = null
    }

    private fun update(phase: Phase, last: ScanSwitchResult? = mutableStatus.value.last) {
        mutableStatus.value = Status(phase, last)
    }

    companion object {
        const val RETRY_MILLIS = 30_000L
        const val RETRY_LIMIT = 10
    }
}
