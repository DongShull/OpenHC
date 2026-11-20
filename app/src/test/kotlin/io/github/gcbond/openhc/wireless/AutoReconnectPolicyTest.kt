package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.core.session.WirelessEvent
import io.github.gcbond.openhc.wireless.AutoReconnectPolicy.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 自动回连的进入条件、结束后的退避，以及「等回连时让给有线」。 */
class AutoReconnectPolicyTest {

    private fun ok(
        enabled: Boolean = true,
        wiredOnly: Boolean = false,
        wiredActive: Boolean = false,
        identityLoaded: Boolean = true,
        pairedPhones: Int = 1,
        bluetoothOn: Boolean = true,
        hotspotSupported: Boolean = true,
        permissionsGranted: Boolean = true,
        advertisingSupported: Boolean = true,
        bluetoothAddressKnown: Boolean = true,
    ) = AutoReconnectPolicy.Conditions(
        enabled = enabled,
        wiredOnly = wiredOnly,
        wiredActive = wiredActive,
        identityLoaded = identityLoaded,
        pairedPhones = pairedPhones,
        bluetoothOn = bluetoothOn,
        hotspotSupported = hotspotSupported,
        permissionsGranted = permissionsGranted,
        advertisingSupported = advertisingSupported,
        bluetoothAddressKnown = bluetoothAddressKnown,
    )

    @Test
    fun entersWhenEverythingIsReady() {
        assertNull(AutoReconnectPolicy().hold(ok()))
    }

    @Test
    fun eachMissingConditionHoldsWithItsReason() {
        val p = AutoReconnectPolicy()
        assertEquals(AutoHold.DISABLED, p.hold(ok(enabled = false)))
        assertEquals(AutoHold.WIRED_ACTIVE, p.hold(ok(wiredActive = true)))
        assertEquals(AutoHold.HOTSPOT_UNSUPPORTED, p.hold(ok(hotspotSupported = false)))
        assertEquals(AutoHold.IDENTITY_MISSING, p.hold(ok(identityLoaded = false)))
        assertEquals(AutoHold.NO_PAIRED_PHONE, p.hold(ok(pairedPhones = 0)))
        assertEquals(AutoHold.BLUETOOTH_OFF, p.hold(ok(bluetoothOn = false)))
        assertEquals(AutoHold.PERMISSIONS_MISSING, p.hold(ok(permissionsGranted = false)))
        assertEquals(AutoHold.ADVERTISING_UNSUPPORTED, p.hold(ok(advertisingSupported = false)))
        assertEquals(AutoHold.BLUETOOTH_ADDRESS_MISSING, p.hold(ok(bluetoothAddressKnown = false)))
    }

    @Test
    fun switchAndWiredComeBeforeTheRest() {
        val p = AutoReconnectPolicy()
        assertEquals(AutoHold.DISABLED, p.hold(ok(enabled = false, wiredActive = true, bluetoothOn = false)))
        assertEquals(AutoHold.WIRED_ACTIVE, p.hold(ok(wiredActive = true, pairedPhones = 0, bluetoothOn = false)))
        // 系统开不了本地热点是这台车机一直存在的情况，排在「没有配对过的手机」等可以补救的原因前面。
        assertEquals(AutoHold.HOTSPOT_UNSUPPORTED, p.hold(ok(hotspotSupported = false, identityLoaded = false, pairedPhones = 0)))
    }

    @Test
    fun wiredOnlyComesFirstEvenForABluetoothTrigger() {
        val p = AutoReconnectPolicy()
        assertEquals(AutoHold.WIRED_ONLY, p.hold(ok(wiredOnly = true)))
        assertEquals(AutoHold.WIRED_ONLY, p.hold(ok(wiredOnly = true, enabled = false)))
        assertEquals(AutoHold.WIRED_ONLY, p.hold(ok(wiredOnly = true), bluetoothTrigger = true))
    }

    @Test
    fun bluetoothTriggerEntersWithTheSwitchOffAndClearsThePause() {
        val p = AutoReconnectPolicy()
        assertEquals(AutoHold.DISABLED, p.hold(ok(enabled = false)))
        assertNull(p.hold(ok(enabled = false), bluetoothTrigger = true), "开关关着，手机蓝牙连上照样进入")
        // 其余条件照旧挡。
        assertEquals(AutoHold.NO_PAIRED_PHONE, p.hold(ok(enabled = false, pairedPhones = 0), bluetoothTrigger = true))
        assertEquals(AutoHold.WIRED_ACTIVE, p.hold(ok(wiredActive = true), bluetoothTrigger = true))

        p.afterAttempt(Outcome.USER_STOP)
        p.afterAttempt(Outcome.ENDED_WITHOUT_PHONE)
        assertEquals(AutoHold.PAUSED_BY_USER, p.hold(ok()))
        p.onBluetoothTrigger()
        assertFalse(p.pausedByUser, "手机蓝牙连上当作用户回到车上")
        assertEquals(0, p.consecutiveFailures)
        assertNull(p.hold(ok()))
    }

    @Test
    fun failuresBeforeAPhoneBackOffAndCap() {
        val p = AutoReconnectPolicy(longArrayOf(2_000, 5_000, 15_000))
        assertEquals(2_000L, p.afterAttempt(Outcome.ENDED_WITHOUT_PHONE))
        assertEquals(5_000L, p.afterAttempt(Outcome.ENDED_WITHOUT_PHONE))
        assertEquals(15_000L, p.afterAttempt(Outcome.ENDED_WITHOUT_PHONE))
        assertEquals(15_000L, p.afterAttempt(Outcome.ENDED_WITHOUT_PHONE), "超出取最后一个")
        assertEquals(4, p.consecutiveFailures)
        // 手机连上过：清零，很快回到等待。
        assertEquals(2_000L, p.afterAttempt(Outcome.ENDED_AFTER_PHONE))
        assertEquals(0, p.consecutiveFailures)
        assertEquals(2_000L, p.afterAttempt(Outcome.ENDED_WITHOUT_PHONE))
    }

    @Test
    fun aLongCalmWaitDoesNotCountAsARepeatedFailure() {
        val p = AutoReconnectPolicy(longArrayOf(2_000, 5_000, 15_000))
        p.afterAttempt(Outcome.ENDED_WITHOUT_PHONE)
        p.afterAttempt(Outcome.ENDED_WITHOUT_PHONE)
        // 平稳等了比最长退避还久之后才出错：重新从第一档算。
        assertEquals(2_000L, p.afterAttempt(Outcome.ENDED_WITHOUT_PHONE, durationMillis = 600_000))
        assertEquals(1, p.consecutiveFailures)
    }

    @Test
    fun userStopPausesUntilTheUserStartsOrFlipsTheSwitch() {
        val p = AutoReconnectPolicy()
        assertNull(p.afterAttempt(Outcome.USER_STOP))
        assertTrue(p.pausedByUser)
        assertEquals(AutoHold.PAUSED_BY_USER, p.hold(ok()))
        p.onUserStart()
        assertFalse(p.pausedByUser)
        assertNull(p.hold(ok()))

        p.afterAttempt(Outcome.USER_STOP)
        p.onSettingChanged()
        assertNull(p.hold(ok()))
    }

    @Test
    fun yieldAndRestartComeBackSoon() {
        val p = AutoReconnectPolicy()
        assertEquals(AutoReconnectPolicy.YIELD_RECHECK_MILLIS, p.afterAttempt(Outcome.YIELDED))
        assertEquals(AutoReconnectPolicy.RESTART_DELAY_MILLIS, p.afterAttempt(Outcome.RESTART))
        assertFalse(p.pausedByUser)
        assertEquals(0, p.consecutiveFailures)
    }

    @Test
    fun waitingForAPhoneDoesNotBlockWired() {
        val qr = "hicar://car/connect?devicename=OpenHC-Test&mac=0A:1B:2C:3D:4E:5F&moduleid=000000&submoduleid=00&pin=135790&ability=6"
        fun run(vararg signals: WirelessSignal) = signals.fold(WirelessUiState()) { s, sig -> WirelessReducer.reduce(s, sig) }

        assertFalse(AutoReconnectPolicy.blocksWired(WirelessUiState()), "空闲不挡")

        val preparing = run(WirelessSignal.AttemptStarted(1, autoReconnect = true))
        assertFalse(AutoReconnectPolicy.blocksWired(preparing))
        val waiting = run(WirelessSignal.AttemptStarted(1, autoReconnect = true), WirelessSignal.Wireless(WirelessEvent.PairingReady(qr, "135790")))
        assertTrue(waiting.waitingForReconnect)
        assertFalse(AutoReconnectPolicy.blocksWired(waiting))
        // 让出时的收尾也不挡。
        assertFalse(AutoReconnectPolicy.blocksWired(WirelessReducer.reduce(waiting, WirelessSignal.StopRequested)))

        // 手机经蓝牙连上之后就是在进行的连接了：挡。
        val connected = WirelessReducer.reduce(waiting, WirelessSignal.Wireless(WirelessEvent.PhoneBluetoothConnected("02:00:00:00:00:01")))
        assertFalse(connected.waitingForReconnect)
        assertTrue(AutoReconnectPolicy.blocksWired(connected))
        assertTrue(AutoReconnectPolicy.blocksWired(WirelessReducer.reduce(connected, WirelessSignal.StopRequested)))

        // 用户点开始的扫码连接照旧挡。
        val manual = run(WirelessSignal.AttemptStarted(2), WirelessSignal.Wireless(WirelessEvent.PairingReady(qr, "135790")))
        assertTrue(AutoReconnectPolicy.blocksWired(manual))
    }
}
