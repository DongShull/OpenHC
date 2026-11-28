package io.github.gcbond.openhc.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ScreenSpec
import io.github.gcbond.openhc.settings.AppSettings

/** 屏幕宽到这个值进入左右全高分栏设置视图（类似 HarmonyOS / iPadOS 设置页）。 */
private val RAIL_MIN = 720.dp

/** 右侧设置内容的最大可读宽度，避免宽屏上开关与文字拉得过远。 */
private val DETAIL_CONTENT_MAX = 820.dp

/**
 * 设置页：
 * - 宽屏：全高极简左右分栏——左侧整条导航栏（返回 + 标题 + 分类列表 + 版本），中间 1px 细线分隔，
 *   右侧聚焦可读宽度的纯文字大标题、紧凑主题切换与干净分组卡片；
 * - 窄屏：单列顶栏 + 滚动分组列表。
 */
@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    screen: ScreenSpec,
    systemBluetoothAddress: String?,
    onBack: () -> Unit,
) {
    val wireless = rememberWirelessSettingsState()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .cockpitBackground()
            .systemBarsPadding(),
    ) {
        val compact = maxHeight < Dimens.CompactHeight
        val rail = maxWidth >= RAIL_MIN
        val content: @Composable (SettingsCategory) -> Unit = { category ->
            CategoryContent(category, settings, screen, systemBluetoothAddress, wireless)
        }
        CompositionLocalProvider(LocalCompact provides compact) {
            if (rail) {
                var selected by rememberSaveable { mutableStateOf(SettingsCategory.PICTURE) }
                val detailScroll = rememberScrollState()
                LaunchedEffect(selected) {
                    detailScroll.scrollTo(0)
                }
                Row(Modifier.fillMaxSize()) {
                    SettingsSidebar(
                        selected = selected,
                        onSelect = { selected = it },
                        onBack = onBack,
                        compact = compact,
                    )
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        val minDetailHeight = maxHeight
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(detailScroll)
                                .padding(
                                    horizontal = if (compact) 26.dp else 36.dp,
                                    vertical = if (compact) 20.dp else 28.dp,
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val verticalPad = if (compact) 40.dp else 56.dp
                            Column(
                                Modifier
                                    .widthIn(max = DETAIL_CONTENT_MAX)
                                    .fillMaxWidth()
                                    .heightIn(min = (minDetailHeight - verticalPad).coerceAtLeast(0.dp)),
                            ) {
                                // ThemeToggle 固定在 AnimatedContent 之外，切分类时不闪烁。
                                DetailHeader(selected, compact)
                                Spacer(Modifier.size(if (compact) 14.dp else 18.dp))
                                AnimatedContent(
                                    targetState = selected,
                                    modifier = Modifier.fillMaxWidth(),
                                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(90)) },
                                    label = "settings_category",
                                ) { category ->
                                    CompositionLocalProvider(LocalGroupTitles provides false) { content(category) }
                                }
                                Spacer(Modifier.weight(1f, fill = true))
                                DetailFooter(compact)
                            }
                        }
                    }
                }
            } else {
                val side = if (maxWidth >= Dimens.WIDE_PAGE_MIN) Dimens.PageSide else Dimens.PageSideNarrow
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = side),
                ) {
                    TopBar(
                        start = {
                            TopBarButton(stringResource(R.string.action_back), onBack, glyph = Glyphs.Back)
                            PageTitle(stringResource(R.string.settings_title))
                        },
                        end = { ThemeToggle() },
                        modifier = Modifier.padding(top = if (compact) 10.dp else 16.dp),
                    )
                    Text(
                        stringResource(R.string.settings_apply_hint),
                        style = UiType.footer(compact),
                        color = Palette.Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
                    ) {
                        items(SettingsCategory.values(), key = { it.ordinal }) { category ->
                            content(category)
                        }
                        item(key = "footer") {
                            NarrowVersionFooter()
                        }
                    }
                }
            }
        }
    }
}

/** 左侧全高极简导航栏：无外框套娃，右侧一条极淡发丝线与内容区分隔。 */
@Composable
private fun SettingsSidebar(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    onBack: () -> Unit,
    compact: Boolean,
) {
    val backInteraction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(if (compact) 220.dp else 256.dp)
            .fillMaxHeight()
            .background(Palette.RailBackground)
            .drawBehind {
                drawRect(
                    color = Palette.Divider,
                    topLeft = Offset(size.width - 1f, 0f),
                    size = Size(1f, size.height),
                )
            }
            .padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 14.dp else 22.dp,
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = if (compact) 16.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(if (compact) 36.dp else 40.dp)
                    .pressScale(backInteraction, pressedScale = 0.94f)
                    .clip(CircleShape)
                    .background(Palette.Button)
                    .dpadFocusRing(CircleShape)
                    .clickable(
                        interactionSource = backInteraction,
                        indication = LocalIndication.current,
                        role = Role.Button,
                        onClick = onBack,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Glyphs.Back,
                    contentDescription = stringResource(R.string.action_back),
                    tint = Palette.ButtonText,
                    modifier = Modifier.size(20.dp),
                )
            }
            PageTitle(stringResource(R.string.settings_title))
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (category in SettingsCategory.values()) {
                NavItem(category, category == selected) { onSelect(category) }
            }
        }

        VersionUpdateCard(
            compact = compact,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** 右侧顶部：干净纯文字大标题（平滑切换），右侧固定常驻日夜切换胶囊不闪烁。 */
@Composable
private fun DetailHeader(category: SettingsCategory, compact: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AnimatedContent(
            targetState = category,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(80)) },
            label = "settings_header_title",
        ) { current ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(current.label),
                    style = UiType.title(compact).copy(fontSize = if (compact) 22.sp else 26.sp),
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(current.subtitle) + " · " + stringResource(R.string.settings_apply_hint),
                    style = UiType.rowSummary(compact),
                    color = Palette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        ThemeToggle()
    }
}

/** 设置的分类：左边目录的一项，对应一个分组。 */
private enum class SettingsCategory(val label: Int, val subtitle: Int) {
    PICTURE(R.string.settings_group_picture, R.string.settings_subtitle_picture),
    CONNECTION(R.string.settings_group_connection, R.string.settings_subtitle_connection),
    STARTUP(R.string.settings_boot_title, R.string.settings_subtitle_startup),
    AUDIO(R.string.settings_audio, R.string.settings_subtitle_audio),
    KEYS(R.string.settings_media_keys, R.string.settings_subtitle_keys),
    DIAGNOSTICS(R.string.settings_group_diagnostics, R.string.settings_subtitle_diagnostics),
}

private fun SettingsCategory.glyph(): ImageVector = when (this) {
    SettingsCategory.PICTURE -> Glyphs.Monitor
    SettingsCategory.CONNECTION -> Glyphs.Wifi
    SettingsCategory.STARTUP -> Glyphs.Power
    SettingsCategory.AUDIO -> Glyphs.Speaker
    SettingsCategory.KEYS -> Glyphs.Wheel
    SettingsCategory.DIAGNOSTICS -> Glyphs.Document
}

/** 一类设置的内容。 */
@Composable
private fun CategoryContent(
    category: SettingsCategory,
    settings: AppSettings,
    screen: ScreenSpec,
    systemBluetoothAddress: String?,
    wireless: WirelessSettingsState,
) {
    when (category) {
        SettingsCategory.PICTURE -> PictureSettingsSection(settings, screen)
        SettingsCategory.CONNECTION -> SettingsGroup(stringResource(R.string.settings_group_connection)) {
            WiredOnlySetting(wireless)
            WirelessModeSetting(wireless)
            BluetoothAddressSection(settings, systemBluetoothAddress)
            UsbAutoConnectSettingsSection()
            RestoreWifiSetting()
        }
        SettingsCategory.STARTUP ->
            BootStartSettingsSection(autoConnect = wireless.connectOnBluetooth && !wireless.wiredOnly) {
                ConnectOnBluetoothSetting(wireless)
            }
        SettingsCategory.AUDIO -> AudioSettingsSection()
        SettingsCategory.KEYS -> MediaKeySettingsSection()
        SettingsCategory.DIAGNOSTICS -> DiagnosticsSettingsSection()
    }
}

/** 导航项：平滑过渡的极简高光胶囊 + 左侧精致指示竖条，带微按压反馈。 */
@Composable
private fun NavItem(category: SettingsCategory, selected: Boolean, onClick: () -> Unit) {
    val compact = LocalCompact.current
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val bgColor by animateColorAsState(
        targetValue = if (selected) Palette.NavSelected else Color.Transparent,
        animationSpec = tween(160),
        label = "nav_bg",
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) Palette.Accent else Palette.Muted,
        animationSpec = tween(160),
        label = "nav_icon",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) Palette.NavSelectedText else Palette.Text2,
        animationSpec = tween(160),
        label = "nav_text",
    )
    val indicatorHeight by animateDpAsState(
        targetValue = if (selected) 18.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "nav_indicator",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 46.dp else 50.dp)
            .pressScale(interactionSource, pressedScale = 0.98f)
            .clip(shape)
            .background(bgColor)
            .dpadFocusRing(shape)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(indicatorHeight)
                .clip(CircleShape)
                .background(Palette.Accent),
        )
        Icon(
            category.glyph(),
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(19.dp),
        )
        Text(
            stringResource(category.label),
            style = UiType.nav(compact).copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailFooter(compact: Boolean) {
    Text(
        stringResource(R.string.home_tagline),
        style = UiType.footer(compact),
        color = Palette.Muted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, start = 4.dp),
    )
}

@Composable
private fun NarrowVersionFooter() {
    val compact = LocalCompact.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VersionUpdateCard(compact = compact)
        Text(
            stringResource(R.string.home_tagline),
            style = UiType.footer(compact),
            color = Palette.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
