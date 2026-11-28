package io.github.gcbond.openhc.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.connection.Step
import io.github.gcbond.openhc.connection.VideoClarity
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.ClarityPreferences
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.BootStartPolicy
import io.github.gcbond.openhc.wifi.CarHotspotAutoPreferences
import io.github.gcbond.openhc.wireless.AutoHold
import io.github.gcbond.openhc.wireless.WirelessNotice
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessStep
import io.github.gcbond.openhc.wireless.WirelessUiState

/** 首页上和有线连接有关的操作。 */
@androidx.compose.runtime.Immutable
internal class HomeActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onRetryNow: () -> Unit,
    val onGrantVpn: () -> Unit,
    val onResetTrustStore: () -> Unit,
    /** 车机上插着华为 / 荣耀手机。 */
    val phonePlugged: () -> Boolean,
)

/** 内容宽到这个值，扫码页左右排；超过 [DUAL_PANE_MIN] 时首页进入现代车机双栏座舱布局。 */
private val WIDE_CARD_MIN = 640.dp
private val DUAL_PANE_MIN = 780.dp
private val TOP_BADGE_MIN = 560.dp

/**
 * 首页：旗舰极简新能源车机座舱布局。
 * - 顶栏：左侧品牌徽标 + 应用名 + 实时状态胶囊，右侧丝滑日夜切换 + 「设置」；
 * - 宽屏主区：左侧为「车机 × 手机互联视觉主舞台」与主行动按钮，右侧为可操作座舱快捷控制坞（连接模式卡 + 2×2 快捷磁贴 + 设置/版本条）；
 * - 等扫码时切换为全宽左右分栏扫码卡片；窄屏或矮屏自动平滑回落为单列滚动。
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
    val appSettings = remember(context) { AppSettings(context) }
    val clarityPrefs = remember(context) { ClarityPreferences(context) }
    val audioPrefs = remember(context) { AudioPreferences(context) }
    val carHotspotAutoPrefs = remember(context) { CarHotspotAutoPreferences(context) }
    val identityLoaded = identity is IdentityLoader.Result.Loaded
    // 设置是在设置页里改的，回到首页时首页重新组合，这里每次读到的都是新值。
    val wiredOnly = prefs.wiredOnly
    val wiredState = remember(wired) { wired.withoutIdentityNotice() }
    val plugged = remember(wiredState.phase, wiredState.notice, wireless.phase) { actions.phonePlugged() }
    val scanningQr = wireless.phase == WirelessPhase.WAITING_FOR_SCAN && wireless.qrCode != null
    var bootOn by remember(context, wiredState.phase, wireless.phase) { mutableStateOf(BootStart.isEnabled(context)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .cockpitBackground()
            .systemBarsPadding(),
    ) {
        val compact = maxHeight < Dimens.CompactHeight
        val side = if (maxWidth >= Dimens.WIDE_PAGE_MIN) Dimens.PageSide else Dimens.PageSideNarrow
        val contentWidth = maxWidth - side * 2
        val wide = contentWidth >= WIDE_CARD_MIN
        val fill = maxHeight >= 420.dp && (wide || maxHeight >= maxWidth)
        val dualPane = fill && contentWidth >= DUAL_PANE_MIN && !scanningQr && wiredState.phase != Phase.PAIRING
        val gap = if (compact) 12.dp else 18.dp
        val showTopBadge = contentWidth >= TOP_BADGE_MIN

        CompositionLocalProvider(LocalCompact provides compact) {
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (fill) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = side),
            ) {
                HomeTopBar(
                    wired = wiredState,
                    wireless = wireless,
                    wiredOnly = wiredOnly,
                    showBadge = showTopBadge,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(top = if (compact) 10.dp else 16.dp),
                )
                IdentityHomeNotice(identity, Modifier.padding(top = gap))
                WifiRestoreCard(Modifier.padding(top = gap))
                HomeBanners(wiredState, wireless, wiredOnly, bootOn, wirelessActions, Modifier.padding(top = gap))

                val bodyModifier = (if (fill) Modifier.weight(1f) else Modifier)
                    .fillMaxWidth()
                    .padding(top = gap, bottom = if (dualPane) (if (compact) 12.dp else 20.dp) else if (compact) 8.dp else 12.dp)

                if (dualPane) {
                    Row(
                        bodyModifier,
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 20.dp),
                    ) {
                        PrimaryCockpitCard(
                            wired = wiredState,
                            wireless = wireless,
                            identityLoaded = identityLoaded,
                            wiredOnly = wiredOnly,
                            plugged = plugged,
                            actions = actions,
                            wirelessActions = wirelessActions,
                            onShowProjection = onShowProjection,
                            fillHeight = true,
                            wide = wide,
                            dualPane = true,
                            modifier = Modifier
                                .weight(1.12f)
                                .fillMaxHeight(),
                        )
                        CockpitControlDock(
                            wired = wiredState,
                            wireless = wireless,
                            prefs = prefs,
                            appSettings = appSettings,
                            clarityPrefs = clarityPrefs,
                            audioPrefs = audioPrefs,
                            carHotspotAutoPrefs = carHotspotAutoPrefs,
                            bootOn = bootOn,
                            onToggleBoot = {
                                BootStart.setEnabled(context, !bootOn)
                                bootOn = BootStart.isEnabled(context)
                            },
                            wiredOnly = wiredOnly,
                            plugged = plugged,
                            identityLoaded = identityLoaded,
                            actions = actions,
                            wirelessActions = wirelessActions,
                            onOpenSettings = onOpenSettings,
                            modifier = Modifier
                                .weight(0.94f)
                                .fillMaxHeight(),
                        )
                    }
                } else {
                    PrimaryCockpitCard(
                        wired = wiredState,
                        wireless = wireless,
                        identityLoaded = identityLoaded,
                        wiredOnly = wiredOnly,
                        plugged = plugged,
                        actions = actions,
                        wirelessActions = wirelessActions,
                        onShowProjection = onShowProjection,
                        fillHeight = fill,
                        wide = wide,
                        dualPane = false,
                        modifier = bodyModifier,
                    )
                }
                if (!dualPane) {
                    HomeFooter(prefs)
                }
            }
        }
    }
}

/** 顶栏：左侧品牌徽标 + 名称 + 实时状态胶囊，右侧丝滑深浅色切换 + 「设置」。 */
@Composable
private fun HomeTopBar(
    wired: ConnectionUiState,
    wireless: WirelessUiState,
    wiredOnly: Boolean,
    showBadge: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (badgeText, badgeTone) = topStatusBadge(wired, wireless, wiredOnly)
    TopBar(
        start = {
            AppMark()
            PageTitle(stringResource(R.string.app_name))
            if (showBadge) {
                StatusBadge(badgeText, badgeTone)
            }
        },
        end = {
            ThemeToggle()
            TopBarButton(stringResource(R.string.action_settings), onOpenSettings, glyph = Glyphs.Gear)
        },
        modifier = modifier,
    )
}

@Composable
private fun topStatusBadge(
    wired: ConnectionUiState,
    wireless: WirelessUiState,
    wiredOnly: Boolean,
): Pair<String, BadgeTone> = when {
    wired.phase == Phase.PROJECTING || wireless.phase == WirelessPhase.PROJECTING ->
        stringResource(R.string.home_badge_projecting) to BadgeTone.POSITIVE
    wired.phase == Phase.USB_DEBUGGING_ON || wired.phase == Phase.REPLUG_REQUIRED ->
        stringResource(R.string.home_badge_attention) to BadgeTone.WARN
    wireless.phase == WirelessPhase.WAITING_FOR_SCAN ->
        stringResource(R.string.home_badge_scanning) to BadgeTone.ACCENT
    wired.phase != Phase.IDLE || wireless.phase != WirelessPhase.IDLE ->
        stringResource(R.string.home_badge_connecting) to BadgeTone.ACCENT
    wiredOnly ->
        stringResource(R.string.home_badge_wired_only) to BadgeTone.NEUTRAL
    wireless.autoHold?.needsAction() == true ->
        stringResource(R.string.home_badge_attention) to BadgeTone.WARN
    wireless.autoHold == AutoHold.READY ->
        stringResource(R.string.home_badge_ready) to BadgeTone.POSITIVE
    else ->
        stringResource(R.string.home_badge_idle) to BadgeTone.NEUTRAL
}

/**
 * 主控卡片：左侧「车机 × 手机互联视觉主舞台」与核心操作按钮；扫码时全宽左右排；
 * 窄屏单栏模式下附带两种连接方式简介。
 */
@Composable
private fun PrimaryCockpitCard(
    wired: ConnectionUiState,
    wireless: WirelessUiState,
    identityLoaded: Boolean,
    wiredOnly: Boolean,
    plugged: Boolean,
    actions: HomeActions,
    wirelessActions: WirelessActions,
    onShowProjection: () -> Unit,
    fillHeight: Boolean,
    wide: Boolean,
    dualPane: Boolean,
    modifier: Modifier,
) {
    CardBox(modifier) {
        when {
            wired.phase != Phase.IDLE -> {
                val warning = wired.phase == Phase.USB_DEBUGGING_ON || wired.phase == Phase.REPLUG_REQUIRED ||
                    wired.step == Step.PHONE_SWITCH_SLOW
                val projecting = wired.phase == Phase.PROJECTING
                val mode = when {
                    warning -> TotemMode.WARNING
                    projecting -> TotemMode.PROJECTING
                    wired.phase == Phase.STOPPING -> TotemMode.IDLE
                    else -> TotemMode.CONNECTING
                }
                val stepIndex = wiredStepIndex(wired)
                Hero(
                    fillHeight,
                    top = {
                        StatusHead(
                            mode = mode,
                            wired = true,
                            title = phaseTitle(wired.phase),
                            titleColor = if (warning) Palette.Warn else Palette.Text,
                            stepIndex = stepIndex,
                        )
                    },
                    body = { WiredBody(wired, warning) },
                    buttons = { WiredButtons(wired, actions, onShowProjection) },
                )
            }
            wireless.phase != WirelessPhase.IDLE -> {
                val qr = wireless.qrCode.takeIf { wireless.phase == WirelessPhase.WAITING_FOR_SCAN }
                if (qr != null) {
                    WirelessScanContent(wireless, qr, wirelessActions, fillHeight, wide)
                } else {
                    val projecting = wireless.phase == WirelessPhase.PROJECTING
                    val mode = when {
                        projecting -> TotemMode.PROJECTING
                        wireless.phase == WirelessPhase.STOPPING -> TotemMode.IDLE
                        else -> TotemMode.CONNECTING
                    }
                    val stepIndex = wirelessStepIndex(wireless)
                    Hero(
                        fillHeight,
                        top = {
                            StatusHead(
                                mode = mode,
                                wired = false,
                                title = wirelessPhaseTitle(wireless),
                                titleColor = Palette.Text,
                                stepIndex = stepIndex,
                            )
                        },
                        body = { WirelessStatusBody(wireless) },
                        buttons = { WirelessActiveButtons(wireless, wirelessActions) },
                    )
                }
            }
            else -> {
                val primaryFocus = remember { FocusRequester() }
                val view = LocalView.current
                LaunchedEffect(wiredOnly, plugged, identityLoaded) {
                    if (!view.isInTouchMode) {
                        runCatching { primaryFocus.requestFocus() }
                    }
                }
                Hero(
                    fillHeight,
                    top = {
                        if (dualPane) {
                            CockpitIdleHero(wiredOnly, plugged)
                        } else {
                            Ways(wiredOnly, wide)
                        }
                    },
                    body = {
                        if (dualPane) {
                            if (!wiredOnly && wireless.autoHold != AutoHold.READY) {
                                WirelessIdleLines(wireless)
                            }
                        } else {
                            IdleLines(wireless, wiredOnly)
                        }
                    },
                    buttons = {
                        IdleButtons(wired, wireless, identityLoaded, wiredOnly, plugged, actions, wirelessActions, primaryFocus)
                    },
                    buttonsFirst = true,
                )
            }
        }
    }
}

/** 宽屏双栏左卡空闲主视觉：极简车机 × 手机矢量互联图腾 + 大号干脆标题 + 单行引导。 */
@Composable
private fun CockpitIdleHero(wiredOnly: Boolean, plugged: Boolean) {
    val compact = LocalCompact.current
    CockpitLinkTotem(
        mode = if (plugged) TotemMode.PLUGGED_READY else TotemMode.IDLE,
        wired = wiredOnly || plugged,
    )
    Text(
        stringResource(R.string.home_connect_title),
        style = UiType.cardTitle(compact),
        color = Palette.Text,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = if (compact) 10.dp else 16.dp),
    )
    Text(
        stringResource(if (wiredOnly) R.string.home_hero_subtitle_wired else R.string.home_hero_subtitle_both),
        style = UiType.cardText(compact),
        color = Palette.Text2,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .widthIn(max = 380.dp)
            .padding(top = 6.dp),
    )
}

/** 宽屏双栏右侧可操作座舱快捷控制坞：连接模式卡 + 可选插线胶囊 + 2×2 快捷控制磁贴 + 底部更多设置条。 */
@Composable
private fun CockpitControlDock(
    wired: ConnectionUiState,
    wireless: WirelessUiState,
    prefs: WirelessPreferences,
    appSettings: AppSettings,
    clarityPrefs: ClarityPreferences,
    audioPrefs: AudioPreferences,
    carHotspotAutoPrefs: CarHotspotAutoPreferences,
    bootOn: Boolean,
    onToggleBoot: () -> Unit,
    wiredOnly: Boolean,
    plugged: Boolean,
    identityLoaded: Boolean,
    actions: HomeActions,
    wirelessActions: WirelessActions,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompact.current
    val context = LocalContext.current
    val isSessionActive = wired.phase != Phase.IDLE || wireless.phase != WirelessPhase.IDLE
    val nextConnectToast = stringResource(R.string.home_tile_next_connect_toast)

    var connectOnBt by remember(prefs, wired.phase, wireless.phase, wiredOnly) { mutableStateOf(prefs.connectOnBluetooth) }
    var audioOn by remember(audioPrefs, wired.phase, wireless.phase) { mutableStateOf(audioPrefs.enabled) }
    var clarity by remember(clarityPrefs, wired.phase, wireless.phase) { mutableStateOf(clarityPrefs.clarity) }

    val knownCount = prefs.knownPhoneList.size
    val modeShort = when {
        prefs.carHotspot && carHotspotAutoPrefs.enabled -> stringResource(R.string.home_mode_short_car_auto)
        prefs.carHotspot -> stringResource(R.string.home_mode_short_car)
        prefs.wifiDirect -> stringResource(R.string.home_mode_short_direct)
        else -> stringResource(R.string.home_mode_short_hotspot)
    }
    val modeTitle = if (wiredOnly) {
        stringResource(R.string.home_dock_wired_only_title)
    } else {
        stringResource(R.string.home_dock_connection_title, modeShort)
    }
    val modeBadge: Pair<String, BadgeTone> = when {
        wireless.phase == WirelessPhase.PROJECTING -> stringResource(R.string.home_badge_projecting) to BadgeTone.POSITIVE
        wireless.phase != WirelessPhase.IDLE -> stringResource(R.string.home_badge_connecting) to BadgeTone.ACCENT
        wiredOnly -> stringResource(R.string.home_badge_wired_only) to BadgeTone.NEUTRAL
        connectOnBt -> stringResource(R.string.home_card_badge_auto_on) to BadgeTone.POSITIVE
        else -> stringResource(R.string.home_card_badge_auto_off) to BadgeTone.NEUTRAL
    }
    val modeSub = buildString {
        if (!wiredOnly) {
            append(
                if (knownCount > 0) {
                    stringResource(R.string.home_card_known_phones, knownCount)
                } else {
                    stringResource(R.string.home_card_no_known_phones)
                },
            )
            if (!plugged) {
                append(" · ")
                append(stringResource(R.string.home_dock_wired_ready_inline))
            }
        } else {
            append(stringResource(R.string.home_dock_wired_ready_inline))
        }
    }

    val spacing = if (compact) 10.dp else 12.dp
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        // A. 顶部实时连接模式卡
        LiveConnectionCard(
            glyph = if (wiredOnly) Glyphs.Plug else Glyphs.Wifi,
            title = modeTitle,
            subtitle = modeSub,
            badge = modeBadge.first,
            badgeTone = modeBadge.second,
            highlighted = wireless.phase != WirelessPhase.IDLE || wired.phase != Phase.IDLE,
            onClick = {
                if (!wiredOnly && !isSessionActive && identityLoaded) {
                    wirelessActions.onStart()
                } else {
                    onOpenSettings()
                }
            },
        )

        // A2. 独立兄弟胶囊：检测到有线手机且空闲时一键启动有线（绝不嵌套在上方卡片内，兼容遥控器焦点）
        if (plugged && !isSessionActive) {
            WiredPluggedPill(onClick = actions.onStart)
        }

        // B. 中央 2×2 可操作快捷控制磁贴（三段式排版：顶图标与指示 + 中主值 + 底说明，饱满紧实无空洞）
        val clarityMb = (clarity.bitrate ?: 0) / 1_000_000
        val clarityValue = when (clarity) {
            VideoClarity.STANDARD -> stringResource(R.string.settings_clarity_standard_short)
            VideoClarity.HIGH -> stringResource(R.string.settings_clarity_high_short)
            VideoClarity.HIGHEST -> stringResource(R.string.settings_clarity_highest_short)
        }
        val claritySub = when (clarity) {
            VideoClarity.STANDARD -> stringResource(R.string.home_tile_clarity_standard)
            VideoClarity.HIGH -> stringResource(R.string.home_tile_clarity_high, clarityMb)
            VideoClarity.HIGHEST -> stringResource(R.string.home_tile_clarity_highest, clarityMb)
        }
        val clarityLevel = VideoClarity.entries.indexOf(clarity) + 1

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                QuickControlTile(
                    glyph = Glyphs.AutoLink,
                    title = stringResource(R.string.home_tile_auto_reconnect),
                    value = when {
                        wiredOnly -> stringResource(R.string.home_tile_state_off)
                        connectOnBt -> stringResource(R.string.home_tile_auto_on)
                        else -> stringResource(R.string.home_tile_auto_off)
                    },
                    subtitle = when {
                        wiredOnly -> stringResource(R.string.home_tile_auto_disabled)
                        connectOnBt -> if (knownCount > 0) stringResource(R.string.home_card_known_phones, knownCount) else stringResource(R.string.home_card_no_known_phones)
                        else -> stringResource(R.string.home_tile_auto_off)
                    },
                    active = !wiredOnly && connectOnBt,
                    enabled = !wiredOnly,
                    role = Role.Switch,
                    trailing = {
                        TileStateTag(
                            text = stringResource(if (!wiredOnly && connectOnBt) R.string.home_tile_state_on else R.string.home_tile_state_off),
                            active = !wiredOnly && connectOnBt,
                        )
                    },
                    onClick = {
                        val next = !connectOnBt
                        connectOnBt = next
                        prefs.connectOnBluetooth = next
                        (context.applicationContext as? OpenHcApp)?.wireless?.onConnectOnBluetoothChanged()
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                QuickControlTile(
                    glyph = if (audioOn) Glyphs.Speaker else Glyphs.SpeakerOff,
                    title = stringResource(R.string.home_tile_audio),
                    value = if (audioOn) audioPrefs.codec.label else stringResource(R.string.home_tile_audio_off),
                    subtitle = if (audioOn) {
                        stringResource(R.string.home_tile_audio_on, audioPrefs.codec.label)
                    } else {
                        stringResource(R.string.home_tile_audio_off)
                    },
                    active = audioOn,
                    role = Role.Switch,
                    trailing = {
                        TileStateTag(
                            text = stringResource(if (audioOn) R.string.home_tile_state_on else R.string.home_tile_state_off),
                            active = audioOn,
                        )
                    },
                    onClick = {
                        val next = !audioOn
                        audioOn = next
                        audioPrefs.enabled = next
                        if (isSessionActive) Toast.makeText(context, nextConnectToast, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                QuickControlTile(
                    glyph = Glyphs.Monitor,
                    title = stringResource(R.string.home_tile_clarity),
                    value = clarityValue,
                    subtitle = claritySub,
                    active = clarity != VideoClarity.STANDARD,
                    role = Role.Button,
                    trailing = { TierBars(level = clarityLevel, total = VideoClarity.entries.size) },
                    onClick = {
                        val entries = VideoClarity.entries
                        val next = entries[(entries.indexOf(clarity) + 1) % entries.size]
                        clarity = next
                        clarityPrefs.clarity = next
                        if (isSessionActive) Toast.makeText(context, nextConnectToast, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                QuickControlTile(
                    glyph = Glyphs.Power,
                    title = stringResource(R.string.home_tile_boot),
                    value = stringResource(if (bootOn) R.string.home_tile_boot_on else R.string.home_tile_boot_off),
                    subtitle = stringResource(R.string.settings_boot_summary),
                    active = bootOn,
                    positiveTone = true,
                    role = Role.Switch,
                    trailing = {
                        TileStateTag(
                            text = stringResource(if (bootOn) R.string.home_tile_state_on else R.string.home_tile_state_off),
                            active = bootOn,
                            positive = true,
                        )
                    },
                    onClick = onToggleBoot,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        // C. 底部精简「更多设置」与版本号入口条
        MoreSettingsStrip(
            scalePercent = appSettings.uiScalePercent,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun LiveConnectionCard(
    glyph: ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    badgeTone: BadgeTone,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val compact = LocalCompact.current
    val shape = RoundedCornerShape(Dimens.SubCardRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val borderColor = if (highlighted) Palette.TileActiveBorder else Palette.CardBorder
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interactionSource, pressedScale = 0.985f)
            .clip(shape)
            .background(if (highlighted) Palette.TileActive else Palette.Card)
            .topEdgeSheen(Dimens.SubCardRadius)
            .border(1.dp, borderColor, shape)
            .dpadFocusRing(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = if (compact) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(
            glyph = glyph,
            large = false,
            tint = if (highlighted) Palette.Accent else Palette.Text2,
            background = if (highlighted) Palette.Tint else Palette.CardElevated,
        )
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title,
                    style = UiType.rowTitle(compact).copy(fontWeight = FontWeight.SemiBold),
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(badge, badgeTone)
            }
            Text(
                subtitle,
                style = UiType.footer(compact),
                color = Palette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun WiredPluggedPill(onClick: () -> Unit) {
    val compact = LocalCompact.current
    val shape = RoundedCornerShape(Dimens.SubCardRadius)
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interactionSource, pressedScale = 0.98f)
            .clip(shape)
            .background(Palette.TilePositive)
            .border(1.dp, Palette.TilePositiveBorder, shape)
            .dpadFocusRing(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = if (compact) 9.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Glyphs.Plug, contentDescription = null, tint = Palette.Positive, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.home_dock_wired_plugged_action),
            style = UiType.action(compact).copy(fontWeight = FontWeight.SemiBold),
            color = Palette.Positive,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Glyphs.Chevron, contentDescription = null, tint = Palette.Positive, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun QuickControlTile(
    glyph: ImageVector,
    title: String,
    value: String,
    subtitle: String,
    active: Boolean,
    role: Role,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    positiveTone: Boolean = false,
) {
    val compact = LocalCompact.current
    val shape = RoundedCornerShape(Dimens.SubCardRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val targetBg = when {
        !enabled -> Palette.Card
        active && positiveTone -> Palette.TilePositive
        active -> Palette.TileActive
        else -> Palette.Card
    }
    val targetBorder = when {
        !enabled -> Palette.CardBorder
        active && positiveTone -> Palette.TilePositiveBorder
        active -> Palette.TileActiveBorder
        else -> Palette.CardBorder
    }
    val targetIconBg = when {
        !enabled -> Palette.CardElevated
        active && positiveTone -> Palette.Positive
        active -> Palette.Accent
        else -> Palette.CardElevated
    }
    val targetIconTint = when {
        !enabled -> Palette.Muted
        active -> Palette.OnAccent
        else -> Palette.Text2
    }
    val bg by animateColorAsState(targetValue = targetBg, animationSpec = tween(160), label = "tile_bg")
    val border by animateColorAsState(targetValue = targetBorder, animationSpec = tween(160), label = "tile_border")
    val iconBg by animateColorAsState(targetValue = targetIconBg, animationSpec = tween(160), label = "tile_icon_bg")
    val iconTint by animateColorAsState(targetValue = targetIconTint, animationSpec = tween(160), label = "tile_tint")

    Column(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .pressScale(interactionSource, enabled = enabled, pressedScale = 0.965f)
            .clip(shape)
            .background(bg)
            .topEdgeSheen(Dimens.SubCardRadius)
            .border(1.dp, border, shape)
            .dpadFocusRing(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = role,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .size(if (compact) 36.dp else 40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(glyph, contentDescription = null, tint = iconTint, modifier = Modifier.size(if (compact) 19.dp else 21.dp))
            }
            trailing()
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = UiType.footer(compact).copy(fontWeight = FontWeight.Medium),
                color = Palette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = UiType.status.copy(fontSize = if (compact) 17.sp else 20.sp),
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = UiType.footer(compact),
                color = if (active) Palette.Text2 else Palette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun TileStateTag(text: String, active: Boolean, positive: Boolean = false) {
    val fg = when {
        active && positive -> Palette.Positive
        active -> Palette.Accent
        else -> Palette.Muted
    }
    val bg = when {
        active && positive -> Palette.PositiveBackground
        active -> Palette.Tint
        else -> Palette.CardElevated
    }
    Text(
        text = text,
        style = UiType.footer(true).copy(fontWeight = FontWeight.SemiBold),
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** 清晰度三段式能量条：远距离大屏一眼辨识当前画质档位。 */
@Composable
private fun TierBars(level: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..total) {
            Box(
                Modifier
                    .size(width = 14.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(if (i <= level) Palette.Accent else Palette.Muted.copy(alpha = 0.28f)),
            )
        }
    }
}

@Composable
private fun MoreSettingsStrip(scalePercent: Int, onClick: () -> Unit) {
    val compact = LocalCompact.current
    val shape = RoundedCornerShape(Dimens.SubCardRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val hint = if (scalePercent != 100) {
        stringResource(R.string.home_chip_scale, scalePercent) + " · " + stringResource(R.string.home_dock_more_hint)
    } else {
        stringResource(R.string.home_dock_more_hint)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interactionSource, pressedScale = 0.985f)
            .clip(shape)
            .background(Palette.Card)
            .topEdgeSheen(Dimens.SubCardRadius)
            .border(1.dp, Palette.CardBorder, shape)
            .dpadFocusRing(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Glyphs.Gear, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.home_dock_more_settings),
            style = UiType.action(compact).copy(fontWeight = FontWeight.Medium),
            color = Palette.Text,
            maxLines = 1,
        )
        Text(
            hint,
            style = UiType.footer(compact),
            color = Palette.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.home_version, BuildConfig.VERSION_NAME),
            style = UiType.footer(compact),
            color = Palette.Muted,
            maxLines = 1,
        )
        Icon(Glyphs.Chevron, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(16.dp))
    }
}

/** 无线连接当前所处的三段进度（-1 表示不显示进度条）。 */
private fun wirelessStepIndex(state: WirelessUiState): Int = when (state.phase) {
    WirelessPhase.PREPARING -> 0
    WirelessPhase.CONNECTING -> when (state.step) {
        WirelessStep.BLUETOOTH_HANDSHAKE,
        WirelessStep.WAITING_AUTH,
        WirelessStep.CONFIRM_ON_PHONE,
        WirelessStep.AUTH_PAIRING,
        WirelessStep.AUTH_TRUSTED,
        null,
        -> 0
        WirelessStep.NEGOTIATING_WIFI,
        WirelessStep.SENDING_NETWORK,
        WirelessStep.WAITING_PHONE_JOIN,
        WirelessStep.WIFI_HANDSHAKE,
        WirelessStep.DEVICE_SESSION,
        -> 1
        WirelessStep.WAITING_PROJECTION,
        WirelessStep.NEGOTIATING_PROJECTION,
        -> 2
    }
    else -> -1
}

/** 有线连接当前所处的三段进度（-1 表示不显示进度条）。 */
private fun wiredStepIndex(state: ConnectionUiState): Int = when (state.phase) {
    Phase.PREPARING, Phase.WAITING_USB_PERMISSION, Phase.CONFIRM_ON_PHONE, Phase.PAIRING -> 0
    Phase.CONNECTING -> when (state.step) {
        Step.OPENING_NETWORK, Step.WAITING_PHONE_LINK, Step.HANDSHAKE, Step.WAITING_AUTH, Step.AUTHENTICATING, null -> 1
        Step.DEVICE_SESSION, Step.WAITING_PROJECTION, Step.NEGOTIATING -> 2
        else -> 0
    }
    else -> -1
}

/**
 * 居中的一屏：[top]（互联图腾和状态，或两种连法），[body]（说明），[buttons]。[buttonsFirst] 时按钮紧跟在 [top] 下面、
 * 说明放最后（空闲时「开始连接」要显眼）。[fillHeight] 时在卡片里上下居中，内容多了能滚动。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Hero(
    fillHeight: Boolean,
    top: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    buttons: @Composable () -> Unit,
    buttonsFirst: Boolean = false,
) {
    val compact = LocalCompact.current
    val gap = if (compact) 14.dp else 22.dp
    val buttonRow: @Composable () -> Unit = {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { buttons() }
    }
    val content: @Composable ColumnScope.() -> Unit = {
        top()
        Spacer(Modifier.height(gap))
        CompositionLocalProvider(LocalCenteredText provides true) {
            if (buttonsFirst) {
                buttonRow()
                Spacer(Modifier.height(gap / 2))
                body()
            } else {
                body()
                Spacer(Modifier.height(gap))
                buttonRow()
            }
        }
    }
    if (fillHeight) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = content,
            )
        }
    } else {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** 进行中的状态：动态车机×手机互联图腾、标题、可选三步连接进度条。 */
@Composable
private fun StatusHead(
    mode: TotemMode,
    wired: Boolean,
    title: String,
    titleColor: Color,
    stepIndex: Int,
) {
    val compact = LocalCompact.current
    CockpitLinkTotem(mode = mode, wired = wired)
    Text(
        title,
        style = UiType.cardTitle(compact),
        color = titleColor,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = if (compact) 10.dp else 14.dp),
    )
    if (stepIndex in 0..2) {
        ConnectionStepBar(
            steps = listOf(
                stringResource(R.string.home_step_handshake),
                stringResource(R.string.home_step_channel),
                stringResource(R.string.home_step_projection),
            ),
            activeStep = stepIndex,
            modifier = Modifier.padding(top = if (compact) 12.dp else 18.dp),
        )
    }
}

/** 单栏窄屏/矮屏空闲态：两种连法卡片。 */
@Composable
private fun Ways(wiredOnly: Boolean, wide: Boolean) {
    val scan: @Composable (Modifier) -> Unit = { m ->
        WayTile(Glyphs.Wifi, stringResource(R.string.home_way_scan_title), stringResource(R.string.home_way_scan_text), m)
    }
    val cable: @Composable (Modifier) -> Unit = { m ->
        WayTile(
            Glyphs.Plug,
            stringResource(R.string.home_way_cable_title),
            stringResource(if (wiredOnly) R.string.home_way_cable_text_only else R.string.home_way_cable_text),
            m,
        )
    }
    when {
        wiredOnly -> cable(Modifier.widthIn(max = WAY_TILE_MAX).fillMaxWidth())
        wide -> Row(
            Modifier.widthIn(max = WAY_TILE_MAX * 2 + 20.dp).fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            scan(Modifier.weight(1f).fillMaxHeight())
            cable(Modifier.weight(1f).fillMaxHeight())
        }
        else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            scan(Modifier.fillMaxWidth())
            cable(Modifier.fillMaxWidth())
        }
    }
}

private val WAY_TILE_MAX = 380.dp

/** 一种连法：微描边次级卡片、大图标、名称和一句话。 */
@Composable
private fun WayTile(glyph: ImageVector, title: String, text: String, modifier: Modifier) {
    val compact = LocalCompact.current
    val shape = RoundedCornerShape(Dimens.SubCardRadius)
    Column(
        modifier
            .clip(shape)
            .background(Palette.CardElevated)
            .topEdgeSheen(Dimens.SubCardRadius)
            .border(1.dp, Palette.CardBorder, shape)
            .padding(horizontal = 20.dp, vertical = if (compact) 16.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(glyph, large = true)
        Text(
            title,
            style = UiType.cardTitle(compact).copy(fontSize = UiType.rowTitle(compact).fontSize * 1.12f),
            color = Palette.Text,
            modifier = Modifier.padding(top = if (compact) 10.dp else 14.dp),
        )
        Text(
            text,
            style = UiType.rowSummary(compact),
            color = Palette.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 空闲时的小字：单栏模式下显示车机蓝牙和无线方式（或只用数据线），以及自动连接提示。 */
@Composable
private fun IdleLines(wireless: WirelessUiState, wiredOnly: Boolean) {
    if (wiredOnly) {
        Line(stringResource(R.string.home_connect_wired_only_note), Palette.Muted)
    } else {
        Line(stringResource(R.string.wireless_tagline) + " " + wirelessNetworkText(wireless), Palette.Muted)
        WirelessIdleLines(wireless)
    }
}

/**
 * 空闲时要车主注意的事，放在卡片上方的琥珀色提示条里：上次连接为什么结束、为什么开始不了、自动连接缺什么、
 * 比亚迪更新后后台启动放行提醒（缺的能直接去处理时带一个按钮）。连接进行中这些写在卡片里。
 */
@Composable
private fun HomeBanners(
    wired: ConnectionUiState,
    wireless: WirelessUiState,
    wiredOnly: Boolean,
    bootEnabled: Boolean,
    actions: WirelessActions,
    modifier: Modifier,
) {
    if (wired.phase != Phase.IDLE || wireless.phase != WirelessPhase.IDLE) return
    val wiredNotice = wired.notice?.let { noticeText(it) }
    val wirelessNotice = wireless.notice?.takeUnless { wiredOnly || it == WirelessNotice.WiredOnly }?.let { wirelessNoticeText(it) }
    // 缺身份参数由上面的身份提示卡片说，不重复。
    val hold = wireless.autoHold?.takeIf { !wiredOnly && it.needsAction() && it != AutoHold.IDENTITY_MISSING }
    val holdText = hold?.let { wirelessAutoHoldText(it) }
    val context = LocalContext.current
    val bydAutostart = remember(context) { BootStart.bydAutostartIntent(context) }
    val bootMissingNotice = if (
        bootEnabled &&
        bydAutostart != null &&
        !BootStartPolicy.bootSeenSinceInstall(BootStart.lastRecord(context), BootStart.installedAtMillis(context))
    ) {
        stringResource(R.string.home_banner_byd_autostart)
    } else {
        null
    }
    if (wiredNotice == null && wirelessNotice == null && holdText == null && bootMissingNotice == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        wiredNotice?.let { WarnBanner(it) }
        wirelessNotice?.let { WarnBanner(it) }
        if (bootMissingNotice != null && bydAutostart != null) {
            val noSettingsPage = stringResource(R.string.settings_boot_no_settings_page)
            WarnBanner(
                bootMissingNotice,
                action = stringResource(R.string.home_banner_byd_autostart_action),
                onAction = {
                    val opened = listOf(bydAutostart, BootStart.appDetailsIntent(context)).any { intent ->
                        try {
                            context.startActivity(intent)
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        }
                    }
                    if (!opened) Toast.makeText(context, noSettingsPage, Toast.LENGTH_LONG).show()
                },
            )
        }
        if (hold != null && holdText != null) {
            val (label, onAction) = when (hold) {
                AutoHold.OVERLAY_MISSING, AutoHold.UI_NOT_SHOWN, AutoHold.BLUETOOTH_ADDRESS_MISSING ->
                    stringResource(R.string.home_banner_open_settings) to actions.onOpenSettings
                AutoHold.BLUETOOTH_OFF -> stringResource(R.string.wireless_action_enable_bt) to actions.onEnableBluetooth
                AutoHold.PERMISSIONS_MISSING -> stringResource(R.string.wireless_action_grant) to actions.onStart
                else -> null to null
            }
            WarnBanner(holdText, action = label, onAction = onAction)
        }
    }
}

@Composable
private fun IdleButtons(
    wired: ConnectionUiState,
    wireless: WirelessUiState,
    identityLoaded: Boolean,
    wiredOnly: Boolean,
    plugged: Boolean,
    actions: HomeActions,
    wirelessActions: WirelessActions,
    primaryFocus: FocusRequester,
) {
    val primaryModifier = Modifier.focusRequester(primaryFocus)
    when {
        wired.notice == Notice.VpnConsentRequired ->
            PillButton(stringResource(R.string.action_grant_vpn), actions.onGrantVpn, modifier = primaryModifier, wide = true)
        wired.notice is Notice.TrustStoreCorrupt ->
            PillButton(stringResource(R.string.action_reset_trust), actions.onResetTrustStore, modifier = primaryModifier, wide = true)
        else -> {
            // 手机插着却没在连（点过停止、还没同意 VPN 授权、插线时界面没开）：给一个连它的按钮，不用拔了重插。
            if (plugged) {
                PillButton(
                    stringResource(R.string.home_connect_plugged),
                    actions.onStart,
                    modifier = if (wiredOnly) primaryModifier else Modifier,
                    primary = wiredOnly,
                    wide = wiredOnly,
                    glyph = Glyphs.Plug,
                )
            }
            if (!wiredOnly) WirelessIdleButtons(wireless, identityLoaded, wirelessActions, primaryModifier)
        }
    }
}

/** 有线连接中的正文：现在在做什么，配对时一排连接码，要注意的事。 */
@Composable
private fun WiredBody(state: ConnectionUiState, warning: Boolean) {
    val compact = LocalCompact.current
    BodyText(phaseHint(state), if (warning) Palette.Warn else Palette.Text2)
    val pin = state.pinCode
    if (state.phase == Phase.PAIRING && pin != null) {
        BoxWithConstraints(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(top = 14.dp),
        ) {
            val (box, gap) = QrPinLayout.pinRow(
                maxWidth.value,
                pin.length,
                if (compact) QrPinLayout.PIN_BOX_COMPACT else QrPinLayout.PIN_BOX,
            )
            PinBoxes(pin, box.dp, gap.dp, Modifier.fillMaxWidth(), description = stringResource(R.string.home_pin_description, pin))
        }
    }
    state.notice?.let { Line(noticeText(it), Palette.Warn) }
}

@Composable
private fun WiredButtons(state: ConnectionUiState, actions: HomeActions, onShowProjection: () -> Unit) {
    if (state.phase == Phase.PROJECTING) {
        PillButton(stringResource(R.string.action_show_projection), onShowProjection, glyph = Glyphs.Monitor)
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

/** 页脚：单栏模式下显示当前模式与版本。 */
@Composable
private fun HomeFooter(prefs: WirelessPreferences, modifier: Modifier = Modifier) {
    val style = UiType.footer(LocalCompact.current)
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = if (LocalCompact.current) 8.dp else 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(stringResource(R.string.home_mode, modeSummary(prefs)), style = style, color = Palette.Muted, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.home_version, BuildConfig.VERSION_NAME), style = style, color = Palette.Muted)
    }
}

/** 当前模式一句话：「无线 + 有线」或「只用数据线」，后面接开着的自动连接选项。 */
@Composable
internal fun modeSummary(prefs: WirelessPreferences): String {
    if (prefs.wiredOnly) return stringResource(R.string.home_mode_wired_only)
    val parts = mutableListOf(stringResource(R.string.home_mode_both))
    if (prefs.connectOnBluetooth) parts += stringResource(R.string.home_mode_connect_on_bt)
    return parts.joinToString(" · ")
}
