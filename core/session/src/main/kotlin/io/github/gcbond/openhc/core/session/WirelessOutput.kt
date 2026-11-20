package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.AuthFailure
import java.util.UUID

/**
 * [WirelessReceiverSession] 每个方法的返回，**按顺序处理**：
 * - [Common]：与有线相同的 TCP 动作和会话事件（[SessionAction] / [SessionEvent]），照有线的规则执行；
 * - [WirelessAction]：要平台层执行的蓝牙、WiFi 动作；
 * - [WirelessEvent]：无线特有的事件，[WirelessEvent.Ended] 总在最后。
 *
 * 用 [SocketRunner] 执行时，经 [WirelessRunnerBridge] 分流，它保证两边按这里的顺序执行。
 */
sealed interface WirelessOutput {
    /** 与有线相同的输出。 */
    class Common(val output: SessionOutput) : WirelessOutput {
        override fun toString() = output.toString()
    }
}

/** 无线连接里的平台资源，日志和结束原因里用。 */
enum class WirelessChannel(val label: String) {
    /** 手机连进来的蓝牙控制连接（服务 UUID 见 [io.github.gcbond.openhc.core.nearby.NearbySession.BLUETOOTH_SERVICE_UUID]）。 */
    BLUETOOTH_CONTROL("蓝牙控制通道"),

    /** 认证用的 RFCOMM 子通道。 */
    BLUETOOTH_AUTH("蓝牙认证子通道"),

    /** 车机开的热点。 */
    WIFI_NETWORK("WiFi 网络"),
}

/**
 * 要平台层做的蓝牙、WiFi 动作。资源用编排器分配的 [id] 标识（与 TCP 资源的 id 不重复），
 * 结果按 id 交回 [WirelessReceiverSession] 对应的方法。
 */
sealed class WirelessAction : WirelessOutput {
    abstract val id: Int

    /**
     * 开 RFCOMM 服务端：不加密、不要求认证，带 SDP 服务记录（UUID = [uuid]，服务名随意）。
     * - 开好后调 `onListening(id, 通道号)`。[needChannelNumber] 为 true 时通道号要回给手机，必须交回系统分配的
     *   真实通道号（1～30）；拿不到就交 0，会话会拒绝这次请求并结束。为 false 时交 0 也行。
     * - **只接受一条连接**，这条连接的 id 是 [acceptId]：接受后调 `onAccepted(acceptId, 对端蓝牙地址)`，
     *   读到的数据调 `onData(acceptId, …)`，读到结尾调 `onClosed(acceptId)`，出错调 `onIoError(acceptId, …)`。
     * - 开不了调 `onIoError(id, 说明)`。
     */
    class ListenRfcomm(
        override val id: Int,
        val channel: WirelessChannel,
        val uuid: UUID,
        val acceptId: Int,
        val needChannelNumber: Boolean,
    ) : WirelessAction() {
        override fun toString() = "ListenRfcomm($id, ${channel.name}, $uuid, 接受为 $acceptId)"
    }

    /** 把 [bytes] 原样写到 RFCOMM 连接 [id]（已是完整的线上字节）。写失败调 `onIoError`。 */
    class RfcommWrite(override val id: Int, val bytes: ByteArray) : WirelessAction() {
        override fun toString() = "RfcommWrite($id, ${bytes.size} 字节)"
    }

    /** 关闭 RFCOMM 监听或连接 [id]。之后它的数据和错误都不用再交回。 */
    class RfcommClose(override val id: Int) : WirelessAction() {
        override fun toString() = "RfcommClose($id)"
    }

    /**
     * 开热点：名称、密码、信道都由系统定，平台把实际值交回。成功调 `onWifiNetworkReady(id, 网络信息)`；
     * 失败调 `onIoError(id, 说明)`；开好之后热点没了调 `onClosed(id)`。
     */
    class StartWifiNetwork(override val id: Int) : WirelessAction() {
        override fun toString() = "StartWifiNetwork($id)"
    }

    /** 关掉 [StartWifiNetwork] 开的热点。 */
    class StopWifiNetwork(override val id: Int) : WirelessAction() {
        override fun toString() = "StopWifiNetwork($id)"
    }
}

/** 平台开好热点后交回的实际信息，全部原样写进给手机的连接串。 */
class WifiNetwork(
    /** 车机在热点上的 IPv4（热点网关），手机连它。 */
    val localIp: String,
    /** 热点的网络名（SSID）。 */
    val ssid: String,
    /** 热点网卡的 MAC（BSSID）。 */
    val bssid: String,
    /** WPA2-PSK 明文密码。 */
    val passphrase: String,
    /** 实际的中心频率（MHz）。手机按它找网，应当是真实值；平台读不到真实信道时只能交回推测值。 */
    val frequencyMhz: Int,
) {
    override fun toString() = "WifiNetwork($localIp, $frequencyMhz MHz，网络名与密码不显示)"
}

/** 无线特有的事件，界面和日志用。toString 不含连接码、地址和密码。 */
sealed class WirelessEvent : WirelessOutput {

    /** 蓝牙监听已开好：把二维码（[qrCode]）和 6 位连接码（[pinCode]）显示给用户。 */
    class PairingReady(val qrCode: String, val pinCode: String) : WirelessEvent() {
        override fun toString() = "PairingReady(内容不显示)"
    }

    /** 手机经蓝牙连上了车机（扫码或回连）。[address] 是手机的蓝牙地址。 */
    class PhoneBluetoothConnected(val address: String) : WirelessEvent() {
        override fun toString() = "PhoneBluetoothConnected(地址不显示)"
    }

    /** 蓝牙控制通道握手完成。 */
    class BluetoothHandshakeCompleted(val encrypted: Boolean) : WirelessEvent() {
        override fun toString() = "BluetoothHandshakeCompleted(加密=$encrypted)"
    }

    /** 数据通道协商完成：手机确认了频率（热点这时已经开好），接下来在热点上开控制监听。 */
    class DataChannelNegotiated(val frequencyMhz: Int) : WirelessEvent() {
        override fun toString() = "DataChannelNegotiated($frequencyMhz MHz)"
    }

    /** 车机的热点已开好（常在手机发协商请求之前）。 */
    class WifiNetworkReady(val frequencyMhz: Int) : WirelessEvent() {
        override fun toString() = "WifiNetworkReady($frequencyMhz MHz)"
    }

    /**
     * 连接串已发给手机，等手机连上热点、连车机的 TCP 端口 [port]。[phoneMac] 是手机报的 WiFi MAC（小写），只用于日志；
     * 会话以手机连上这个端口为准。
     */
    class ConnectionInfoSent(
        val port: Int,
        val phoneMac: String,
        /** 连接串端口后那一段填的信道号（由报给手机的频率换算）。 */
        val channel: Int,
    ) : WirelessEvent() {
        override fun toString() = "ConnectionInfoSent($port，信道 $channel)"
    }

    /** 会话结束。只出现一次，总在最后；之前已给出关闭全部资源的动作。 */
    class Ended(val reason: WirelessEndReason) : WirelessEvent() {
        override fun toString() = "Ended($reason)"
    }
}

/** 无线连接的阶段，结束原因里用。 */
enum class WirelessStep(val label: String) {
    BLUETOOTH("蓝牙控制通道"),
    AUTH("认证"),
    NEGOTIATION("数据通道协商"),
    WIFI_NETWORK("WiFi 组网"),
    PHONE_JOIN("手机加入 WiFi"),
}

/** 无线会话结束的原因。toString 不含密钥和设备标识。 */
sealed class WirelessEndReason {
    /** 本机调用了 stop。 */
    object LocalStop : WirelessEndReason() {
        override fun toString() = "本机停止"
    }

    /** WiFi 阶段（与有线相同的设备会话和投屏）结束了，[reason] 是那一段给的原因。 */
    class Wired(val reason: EndReason) : WirelessEndReason() {
        override fun toString() = reason.toString()
    }

    class AuthFailed(val failure: AuthFailure) : WirelessEndReason() {
        override fun toString() = "认证失败（$failure）"
    }

    /** 建立 WiFi 数据通道之前的某一步失败（超时、手机拒绝、对端关闭、平台出错、数据不合法等）。 */
    class Failed(val step: WirelessStep, val detail: String) : WirelessEndReason() {
        override fun toString() = "${step.label}失败（$detail）"
    }

    /** 编排器自己出了不该出的错（程序缺陷），已尽量按正常顺序收尾。 */
    class InternalError(val message: String) : WirelessEndReason() {
        override fun toString() = "内部错误（$message）"
    }
}
