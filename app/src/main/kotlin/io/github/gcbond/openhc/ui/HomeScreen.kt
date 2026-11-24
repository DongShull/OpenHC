package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.connection.Step
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessUiState

/** 首页上和有线连接有关的操作。 */
internal class HomeActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onRetryNow: () -> Unit,
    val onGrantVpn: () -> Unit,
    val onResetTrustStore: () -> Unit,
)

/** 内容宽不到这个值就排单栏（上无线、下有线，整页滚动）。 */
private val TWO_COLUMN_MIN = 640.dp

/** 等扫码时无线卡片占的比例大一些（二维码要地方），其余时候两张一样宽。 */
private const val WIRELESS_WEIGHT_SCANNING = 2.2f

/**
 * 首页：顶栏左边应用名、正中深浅色切换、右边「设置」；下面并排两张卡片，左边无线、右边有线，各自一个图标、
 * 名称和状态、一两句说明，按钮在卡片底部；页脚是当前模式和版本。缺身份参数、或车机 WiFi 要车主自己打开时，
 * 顶上一条提示。任何一边在投屏时，那张卡片里有「回到投屏画面」。窄屏排成一列：无线在上、有线在下，整页滚动。
 */
@Composable
internal fun HomeScreen(
    wired: ConnectionUiState,
    wireless: WirelessUiState,
    identity: IdentityLoader.Result,
    actions: HomeActions,
    wirelessActions: WirelessActions,
    onShowProjection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { WirelessPreferences(context) }
    val identityLoaded = identity is IdentityLoader.Result.Loaded
    // 设置是在设置页里改的，回到首页时首页重新组合，这里每次读到的都是新值。
    val wirelessOff = prefs.wiredOnly && !wireless.active
    val wiredState = wired.withoutIdentityNotice()
    val scanning = !wirelessOff && wireless.phase == WirelessPhase.WAITING_FOR_SCAN && wireless.qrCode != null
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .systemBarsPadding(),
    ) {
        val compact = maxHeight < Dimens.CompactHeight
        val side = if (maxWidth >= Dimens.WIDE_PAGE_MIN) Dimens.PageSide else Dimens.PageSideNarrow
        val twoColumn = maxWidth - side * 2 >= TWO_COLUMN_MIN
        val gap = if (compact) 12.dp else 20.dp
        CompositionLocalProvider(LocalCompact provides compact) {
            if (twoColumn) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = side),
                ) {
                    HomeTopBar(onOpenSettings, Modifier.padding(top = if (compact) 10.dp else 18.dp))
                    IdentityHomeNotice(identity, Modifier.padding(top = gap))
                    WifiRestoreCard(Modifier.padding(top = gap))
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = gap, bottom = if (compact) 8.dp else 12.dp),
                    ) {
                        WirelessCard(
                            wireless,
                            identityLoaded,
                            wirelessOff,
                            wirelessActions,
                            fillHeight = true,
                            wide = true,
                            modifier = Modifier
                                .weight(if (scanning) WIRELESS_WEIGHT_SCANNING else 1f)
                                .fillMaxHeight(),
                        )
                        Spacer(Modifier.width(gap))
                        WiredCard(
                            wiredState,
                            identityLoaded,
                            actions,
                            onShowProjection,
                            fillHeight = true,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    HomeFooter(prefs)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = side),
                ) {
                    HomeTopBar(onOpenSettings, Modifier.padding(top = 12.dp))
                    IdentityHomeNotice(identity, Modifier.padding(top = 12.dp))
                    WifiRestoreCard(Modifier.padding(top = 12.dp))
                    WirelessCard(
                        wireless,
                        identityLoaded,
                        wirelessOff,
                        wirelessActions,
                        fillHeight = false,
                        wide = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    WiredCard(
                        wiredState,
                        identityLoaded,
                        actions,
                        onShowProjection,
                        fillHeight = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    HomeFooter(prefs, Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

/** 顶栏：左边应用标记和名称，正中深浅色切换，右边「设置」（设置页的「返回」在左边同一高度）。 */
@Composable
private fun HomeTopBar(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    TopBar(
        start = {
            AppMark()
            Spacer(Modifier.width(12.dp))
            PageTitle(stringResource(R.string.app_name))
        },
        end = { TopBarButton(stringResource(R.string.action_settings), onOpenSettings, glyph = Glyphs.Gear) },
        modifier = modifier,
    )
}

/** 卡片顶上一行：左边图标，右边名称和状态（状态要用户处理时用提醒色）。 */
@Composable
internal fun CardHeader(glyph: ImageVector, title: String, status: String, statusColor: Color) {
    val compact = LocalCompact.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(glyph)
        Spacer(Modifier.width(if (compact) 12.dp else 16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = UiType.cardTitle(compact), color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = UiType.cardHeader(compact), color = statusColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 卡片的正文和按钮：[fillHeight] 时正文占满中间（内容多了能滚动），按钮贴在卡片底部；否则依次排下来。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.CardBody(
    fillHeight: Boolean,
    body: @Composable ColumnScope.() -> Unit,
    buttons: @Composable () -> Unit,
) {
    val compact = LocalCompact.current
    val top = if (compact) 10.dp else 16.dp
    if (fillHeight) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = top)
                .verticalScroll(rememberScrollState()),
            content = body,
        )
    } else {
        Column(Modifier.fillMaxWidth().padding(top = top), content = body)
    }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(top = if (compact) 10.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        buttons()
    }
}

/** 「有线连接」卡片：状态、提示，配对时一排连接码，上次结束的原因，底部操作按钮。 */
@Composable
private fun WiredCard(
    state: ConnectionUiState,
    identityLoaded: Boolean,
    actions: HomeActions,
    onShowProjection: () -> Unit,
    fillHeight: Boolean,
    modifier: Modifier,
) {
    val compact = LocalCompact.current
    // 要用户动手处理的状态（关 USB 调试、拔线重插、手机迟迟不响应）用提醒色。
    val warning = state.phase == Phase.USB_DEBUGGING_ON || state.phase == Phase.REPLUG_REQUIRED ||
        state.step == Step.PHONE_SWITCH_SLOW
    CardBox(modifier) {
        CardHeader(
            Glyphs.Plug,
            stringResource(R.string.home_wired_title),
            phaseTitle(state.phase),
            if (warning) Palette.Warn else Palette.Accent,
        )
        CardBody(
            fillHeight,
            body = {
                Text(phaseHint(state), style = UiType.cardText(compact), color = if (warning) Palette.Warn else Palette.Text2)
                val pin = state.pinCode
                if (state.phase == Phase.PAIRING && pin != null) {
                    BoxWithConstraints(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                    ) {
                        val (box, gap) = QrPinLayout.pinRow(
                            maxWidth.value,
                            pin.length,
                            if (compact) QrPinLayout.PIN_BOX_COMPACT else QrPinLayout.PIN_BOX,
                        )
                        PinBoxes(
                            pin,
                            box.dp,
                            gap.dp,
                            Modifier.fillMaxWidth(),
                            description = stringResource(R.string.home_pin_description, pin),
                        )
                    }
                }
                state.notice?.let {
                    Text(noticeText(it), style = UiType.hint, color = Palette.Warn, modifier = Modifier.padding(top = 10.dp))
                }
            },
            buttons = {
                if (state.phase == Phase.IDLE) {
                    when (state.notice) {
                        Notice.VpnConsentRequired ->
                            PillButton(stringResource(R.string.action_grant_vpn), actions.onGrantVpn)
                        is Notice.TrustStoreCorrupt ->
                            PillButton(stringResource(R.string.action_reset_trust), actions.onResetTrustStore)
                        else ->
                            PillButton(stringResource(R.string.action_start), actions.onStart, enabled = identityLoaded)
                    }
                } else {
                    if (state.phase == Phase.PROJECTING) {
                        PillButton(stringResource(R.string.action_show_projection), onShowProjection)
                    }
                    if (state.phase == Phase.REPLUG_REQUIRED) {
                        PillButton(stringResource(R.string.action_retry_now), actions.onRetryNow, primary = false)
                    }
                    PillButton(
                        stringResource(R.string.action_stop),
                        actions.onStop,
                        primary = false,
                        enabled = state.phase != Phase.STOPPING,
                    )
                }
            },
        )
    }
}

/** 页脚：左边当前模式，右边版本。 */
@Composable
private fun HomeFooter(prefs: WirelessPreferences, modifier: Modifier = Modifier) {
    val style = UiType.footer(LocalCompact.current)
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = if (LocalCompact.current) 8.dp else 14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(stringResource(R.string.home_mode, modeSummary(prefs)), style = style, color = Palette.Muted, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.home_version, BuildConfig.VERSION_NAME), style = style, color = Palette.Muted)
    }
}

/** 当前模式一句话：「无线 + 有线」或「只用数据线」，后面接开着的自动连接选项。首页页脚和设置页底部共用。 */
@Composable
internal fun modeSummary(prefs: WirelessPreferences): String {
    if (prefs.wiredOnly) return stringResource(R.string.home_mode_wired_only)
    val parts = mutableListOf(stringResource(R.string.home_mode_both))
    if (prefs.connectOnBluetooth) parts += stringResource(R.string.home_mode_connect_on_bt)
    return parts.joinToString(" · ")
}
