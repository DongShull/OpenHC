package io.github.gcbond.openhc.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.WirelessPreferences

/**
 * 无线连接的几项设置在设置页里分在两组：「连接方式」里是只用数据线连接、无线自动回连，「启动」里是蓝牙连上自动连接；
 * 无线自动回连开着时另有一组 BLE 广播的调试项。三个开关切换立即生效；调试项下次开始等待时生效
 * （从设置页回去时，还没连上手机的等待会按新设置重开）。
 *
 * 这几行分在不同的组里、又互相影响（只用数据线连接开着时提示别的不起作用，自动回连关着时不显示调试组），
 * 所以状态放在这里，由设置页创建一份（[rememberWirelessSettingsState]）传给各行。
 */
@Stable
internal class WirelessSettingsState(context: Context) {
    private val prefs = WirelessPreferences(context)
    private val controller = (context.applicationContext as? OpenHcApp)?.wireless

    var wiredOnly by mutableStateOf(prefs.wiredOnly)
        private set
    var connectOnBluetooth by mutableStateOf(prefs.connectOnBluetooth)
        private set
    var autoReconnect by mutableStateOf(prefs.autoReconnect)
        private set
    var republishSeconds by mutableIntStateOf(prefs.republishSeconds)
        private set
    var declareRandomAddress by mutableStateOf(prefs.declareRandomAddress)
        private set
    var gattServer by mutableStateOf(prefs.gattServer)
        private set

    fun changeWiredOnly(value: Boolean) {
        wiredOnly = value
        prefs.wiredOnly = value
        controller?.onWiredOnlyChanged()
    }

    fun changeConnectOnBluetooth(value: Boolean) {
        connectOnBluetooth = value
        prefs.connectOnBluetooth = value
        controller?.onConnectOnBluetoothChanged()
    }

    fun changeAutoReconnect(value: Boolean) {
        autoReconnect = value
        prefs.autoReconnect = value
        controller?.onAutoReconnectSettingChanged()
    }

    fun changeRepublishSeconds(value: Int) {
        republishSeconds = value
        prefs.republishSeconds = value
    }

    fun changeDeclareRandomAddress(value: Boolean) {
        declareRandomAddress = value
        prefs.declareRandomAddress = value
    }

    fun changeGattServer(value: Boolean) {
        gattServer = value
        prefs.gattServer = value
    }
}

@Composable
internal fun rememberWirelessSettingsState(): WirelessSettingsState {
    val context = LocalContext.current
    return remember(context) { WirelessSettingsState(context) }
}

/** 只用数据线连接（默认关）：开着时无线关掉，提示自动回连、蓝牙连上自动连接都不起作用。 */
@Composable
internal fun WiredOnlySetting(state: WirelessSettingsState) {
    SwitchRow(
        title = stringResource(R.string.settings_wireless_wired_only),
        checked = state.wiredOnly,
        description = stringResource(R.string.settings_wireless_wired_only_hint),
        note = if (state.wiredOnly) stringResource(R.string.settings_wireless_wired_only_note) else null,
        onCheckedChange = state::changeWiredOnly,
    )
}

/** 无线自动回连（默认开）。 */
@Composable
internal fun AutoReconnectSetting(state: WirelessSettingsState) {
    SwitchRow(
        title = stringResource(R.string.settings_wireless_auto),
        checked = state.autoReconnect,
        description = stringResource(R.string.settings_wireless_auto_hint),
        onCheckedChange = state::changeAutoReconnect,
    )
}

/** 蓝牙连上自动连接（默认关）。 */
@Composable
internal fun ConnectOnBluetoothSetting(state: WirelessSettingsState) {
    SwitchRow(
        title = stringResource(R.string.settings_wireless_connect_on_bt),
        checked = state.connectOnBluetooth,
        description = stringResource(R.string.settings_wireless_connect_on_bt_hint),
        onCheckedChange = state::changeConnectOnBluetooth,
    )
}

/** BLE 广播的调试项：只在无线自动回连开着时显示，下次开始等待时生效。 */
@Composable
internal fun WirelessDebugSection(state: WirelessSettingsState) {
    if (!state.autoReconnect) return
    SettingsGroup(stringResource(R.string.settings_wireless_debug)) {
        val choices = WirelessPreferences.REPUBLISH_CHOICES
        val labels = choices.map { seconds ->
            when {
                seconds == 0 -> stringResource(R.string.settings_wireless_republish_off)
                seconds % 60 == 0 -> stringResource(R.string.settings_wireless_republish_minutes, seconds / 60)
                else -> stringResource(R.string.settings_wireless_republish_seconds, seconds)
            }
        }
        ChoiceRow(
            title = stringResource(R.string.settings_wireless_republish),
            options = labels,
            selected = choices.indexOf(state.republishSeconds),
            description = stringResource(R.string.settings_wireless_republish_hint),
        ) { index -> state.changeRepublishSeconds(choices[index]) }
        SwitchRow(
            title = stringResource(R.string.settings_wireless_random),
            checked = state.declareRandomAddress,
            description = stringResource(R.string.settings_wireless_random_hint),
            onCheckedChange = state::changeDeclareRandomAddress,
        )
        SwitchRow(
            title = stringResource(R.string.settings_wireless_gatt),
            checked = state.gattServer,
            description = stringResource(R.string.settings_wireless_gatt_hint),
            onCheckedChange = state::changeGattServer,
        )
    }
}
