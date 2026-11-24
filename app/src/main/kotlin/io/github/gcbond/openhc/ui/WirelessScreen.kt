package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.wireless.WirelessNotice
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessUiState

/** 首页「无线连接」卡片上的操作。 */
internal class WirelessActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onShowProjection: () -> Unit,
    /** 去本应用的设置页（填车机蓝牙地址、关「只用数据线连接」）。 */
    val onOpenSettings: () -> Unit,
    /** 去系统里本应用的设置页（权限被永久拒绝时）。 */
    val onOpenAppSettings: () -> Unit,
    val onEnableBluetooth: () -> Unit,
    val onResetTrustStore: () -> Unit,
)

/**
 * 首页「无线连接」卡片：顶上图标、名称和状态。等扫码时是二维码和下面一排连接码（宽屏时二维码在左、说明在右），
 * 其余时候是一两句说明、要注意的事，按钮在卡片底部。
 *
 * [wirelessOff]：设置里开着「只用数据线连接」、也没有正在进行的无线连接，整张卡片写明无线已关闭。
 * [fillHeight]：两栏时卡片撑满高度，二维码按剩下的空间算大小；单栏（整页滚动）时按宽度算。
 * [wide]：卡片够宽，等扫码时二维码和说明左右排。
 */
@Composable
internal fun WirelessCard(
    state: WirelessUiState,
    identityLoaded: Boolean,
    wirelessOff: Boolean,
    actions: WirelessActions,
    fillHeight: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val qr = state.qrCode.takeIf { state.phase == WirelessPhase.WAITING_FOR_SCAN }
    val problem = !wirelessOff && state.notice != null
    val status = if (wirelessOff) stringResource(R.string.home_wireless_off_title) else wirelessPhaseTitle(state)
    CardBox(modifier) {
        CardHeader(Glyphs.Wifi, stringResource(R.string.home_wireless_title), status, if (problem) Palette.Warn else Palette.Accent)
        if (qr != null) {
            if (fillHeight && wide) WideScanContent(state, qr, actions) else ScanContent(state, qr, actions, fillHeight)
        } else {
            CardBody(
                fillHeight,
                body = { StatusBody(state, wirelessOff) },
                buttons = { WirelessButtons(state, identityLoaded, wirelessOff, actions) },
            )
        }
    }
}

/** 宽屏等扫码：左边二维码和连接码（按卡片剩下的高度取大小），右边说明和要注意的事，底下「停止」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.WideScanContent(state: WirelessUiState, qr: String, actions: WirelessActions) {
    val compact = LocalCompact.current
    Row(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(top = if (compact) 8.dp else 14.dp),
    ) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            val pin = state.pinCode
            val size = QrPinLayout.plan(maxWidth.value, maxHeight.value, pin?.length ?: 0, compact)
            QrAndPin(qr, pin, size)
        }
        Spacer(Modifier.width(if (compact) 16.dp else 28.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            ScanTexts(state)
        }
    }
    FlowRow(Modifier.fillMaxWidth().padding(top = if (compact) 8.dp else 14.dp), horizontalArrangement = Arrangement.End) {
        PillButton(
            stringResource(R.string.action_stop),
            actions.onStop,
            primary = false,
            enabled = state.phase != WirelessPhase.STOPPING,
        )
    }
}

/** 窄屏或整页滚动时等扫码：说明，二维码和连接码（按宽度、剩下的高度取大小），要注意的事，「停止」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ScanContent(state: WirelessUiState, qr: String, actions: WirelessActions, fillHeight: Boolean) {
    val compact = LocalCompact.current
    BoxWithConstraints(
        (if (fillHeight) Modifier.weight(1f) else Modifier)
            .fillMaxWidth()
            .padding(top = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val pin = state.pinCode
        val height = if (constraints.hasBoundedHeight) maxHeight.value else Float.POSITIVE_INFINITY
        val size = QrPinLayout.plan(maxWidth.value, height, pin?.length ?: 0, compact)
        QrAndPin(qr, pin, size)
    }
    ScanTexts(state)
    FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
        PillButton(
            stringResource(R.string.action_stop),
            actions.onStop,
            primary = false,
            enabled = state.phase != WirelessPhase.STOPPING,
        )
    }
}

/** 二维码，下面一排连接码。 */
@Composable
private fun QrAndPin(qr: String, pin: String?, size: QrPinSize) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(QrPinLayout.QR_MARGIN.dp))
        // 码图自带 2 个模块的白边：外面再补约 2 个模块的白边，静区凑够 4 个模块，好扫。浅色时白卡片上再描一圈细线。
        QrCodeImage(
            qr,
            Modifier
                .size(size.qr.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, Palette.Divider, RoundedCornerShape(12.dp))
                .padding((size.qr * QR_EXTRA_QUIET).dp),
        )
        if (pin != null && size.pinBox > 0f) {
            Spacer(Modifier.height(size.pinTop.dp))
            PinBoxes(pin, size.pinBox.dp, size.pinGap.dp, description = stringResource(R.string.home_pin_description, pin))
        }
        Spacer(Modifier.height(QrPinLayout.QR_MARGIN.dp))
    }
}

/** 等扫码时的文字：怎么扫，要注意的事，无线方式和广播情况。 */
@Composable
private fun ScanTexts(state: WirelessUiState) {
    val compact = LocalCompact.current
    Text(wirelessHint(state), style = UiType.cardText(compact), color = Palette.Text2)
    if (state.addressMismatch) Line(stringResource(R.string.wireless_warning_manual_address), Palette.Warn)
    state.notice?.let { Line(wirelessNoticeText(it), Palette.Warn) }
    Line(wirelessNetworkText(state), Palette.Muted)
    state.beacon?.let { wirelessBeaconText(it) }?.let { Line(it, Palette.Muted) }
}

/** 二维码外面补的白边占边长的比例（约 2 个模块）。 */
private const val QR_EXTRA_QUIET = 0.04f

/** 没有二维码时的正文：一两句说明，连接码（手机连上蓝牙、还没认证完时还留着），要注意的事。 */
@Composable
private fun StatusBody(state: WirelessUiState, wirelessOff: Boolean) {
    val compact = LocalCompact.current
    val message = if (wirelessOff) stringResource(R.string.home_wireless_off_note) else wirelessHint(state)
    Text(message, style = UiType.cardText(compact), color = Palette.Text2)
    if (state.phase == WirelessPhase.IDLE && !wirelessOff) Line(stringResource(R.string.wireless_tagline), Palette.Muted)
    if (!wirelessOff) Line(wirelessNetworkText(state), Palette.Muted)
    state.pinCode?.let { pin ->
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            val (box, gap) = QrPinLayout.pinRow(
                maxWidth.value,
                pin.length,
                if (compact) QrPinLayout.PIN_BOX_COMPACT else QrPinLayout.PIN_BOX,
            )
            PinBoxes(pin, box.dp, gap.dp, Modifier.fillMaxWidth(), description = stringResource(R.string.home_pin_description, pin))
        }
    }
    if (state.addressMismatch && state.phase != WirelessPhase.IDLE) {
        Line(stringResource(R.string.wireless_warning_manual_address), Palette.Warn)
    }
    // 「只用数据线连接」开着时，卡片本身已经写明了，不再重复这条提示。
    state.notice?.takeUnless { wirelessOff && it == WirelessNotice.WiredOnly }?.let { Line(wirelessNoticeText(it), Palette.Warn) }
    // 自动回连没在等的原因：只是说明，不当成错误。
    val hold = state.autoHold
    if (state.phase == WirelessPhase.IDLE && hold != null && !wirelessOff) {
        wirelessAutoHoldText(hold)?.let { Line(it, Palette.Muted) }
    }
    state.beacon?.let { wirelessBeaconText(it) }?.let { Line(it, Palette.Muted) }
}

@Composable
private fun Line(text: String, color: Color) {
    Text(text, style = UiType.hint, color = color, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun WirelessButtons(state: WirelessUiState, identityLoaded: Boolean, wirelessOff: Boolean, actions: WirelessActions) {
    val start = stringResource(R.string.wireless_action_start)
    when {
        state.phase != WirelessPhase.IDLE -> {
            if (state.phase == WirelessPhase.PROJECTING) {
                PillButton(stringResource(R.string.action_show_projection), actions.onShowProjection)
            }
            PillButton(
                stringResource(R.string.action_stop),
                actions.onStop,
                primary = false,
                enabled = state.phase != WirelessPhase.STOPPING,
            )
        }
        wirelessOff -> PillButton(stringResource(R.string.wireless_action_open_settings), actions.onOpenSettings)
        else -> when (val notice = state.notice) {
            is WirelessNotice.PermissionsDenied -> if (notice.permanently) {
                PillButton(stringResource(R.string.wireless_action_app_settings), actions.onOpenAppSettings)
                PillButton(start, actions.onStart, primary = false)
            } else {
                PillButton(stringResource(R.string.wireless_action_grant), actions.onStart)
            }
            WirelessNotice.BluetoothOff -> {
                PillButton(stringResource(R.string.wireless_action_enable_bt), actions.onEnableBluetooth)
                PillButton(start, actions.onStart, primary = false)
            }
            WirelessNotice.BluetoothAddressMissing ->
                PillButton(stringResource(R.string.wireless_action_fill_bt), actions.onOpenSettings)
            WirelessNotice.WiredOnly ->
                PillButton(stringResource(R.string.wireless_action_open_settings), actions.onOpenSettings)
            is WirelessNotice.TrustStoreCorrupt ->
                PillButton(stringResource(R.string.action_reset_trust), actions.onResetTrustStore)
            else -> PillButton(start, actions.onStart, enabled = identityLoaded)
        }
    }
}
