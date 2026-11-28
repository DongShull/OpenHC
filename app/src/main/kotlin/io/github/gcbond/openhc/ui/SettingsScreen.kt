package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ScreenSpec
import io.github.gcbond.openhc.settings.AppSettings

/** 内容宽到这个值才在左边排分类目录。 */
private val RAIL_MIN = 700.dp

/**
 * 设置页：顶栏左边「返回」和「设置」，右边生效说明与深浅色切换。宽屏左边是带圆角容器与高亮竖条的分类导航栏，
 * 右边顶部显示当前分区图标、标题与说明，下方呈现该类设置卡片；窄屏回落为单列滚动。
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
        val side = if (maxWidth >= Dimens.WIDE_PAGE_MIN) Dimens.PageSide else Dimens.PageSideNarrow
        val rail = maxWidth - side * 2 >= RAIL_MIN
        val content: @Composable (SettingsCategory) -> Unit = { category ->
            CategoryContent(category, settings, screen, systemBluetoothAddress, wireless)
        }
        CompositionLocalProvider(LocalCompact provides compact) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = side),
            ) {
                SettingsTopBar(onBack, showHint = rail, Modifier.padding(top = if (compact) 10.dp else 16.dp))
                if (rail) {
                    var selected by rememberSaveable { mutableStateOf(SettingsCategory.PICTURE) }
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = if (compact) 12.dp else 18.dp, bottom = if (compact) 10.dp else 16.dp),
                    ) {
                        CardBox(
                            Modifier
                                .width(if (compact) 204.dp else 238.dp)
                                .fillMaxHeight(),
                            radius = Dimens.SubCardRadius,
                            contentPadding = 10.dp,
                        ) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .selectableGroup(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                for (category in SettingsCategory.values()) {
                                    NavItem(category, category == selected) { selected = category }
                                }
                            }
                            Text(
                                stringResource(R.string.home_version, BuildConfig.VERSION_NAME),
                                style = UiType.footer(compact),
                                color = Palette.Muted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(if (compact) 16.dp else 22.dp))
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            CategoryHeader(selected, Modifier.padding(bottom = if (compact) 10.dp else 14.dp))
                            CompositionLocalProvider(LocalGroupTitles provides false) { content(selected) }
                            VersionFooter()
                        }
                    }
                } else {
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
                        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
                    ) {
                        items(SettingsCategory.values(), key = { it.ordinal }) { category ->
                            content(category)
                        }
                        item(key = "footer") {
                            VersionFooter()
                        }
                    }
                }
            }
        }
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

/** 宽屏右侧详情面板顶部的分区大标题：图标徽章 + 标题 + 一句话说明。 */
@Composable
private fun CategoryHeader(category: SettingsCategory, modifier: Modifier = Modifier) {
    val compact = LocalCompact.current
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(category.glyph(), large = false)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(category.label),
                style = UiType.title(compact),
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(category.subtitle),
                style = UiType.rowSummary(compact),
                color = Palette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
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

/** 左边目录的一项：选中时左侧高亮竖条 + 柔蓝底色 + 细描边。 */
@Composable
private fun NavItem(category: SettingsCategory, selected: Boolean, onClick: () -> Unit) {
    val compact = LocalCompact.current
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 46.dp else 52.dp)
            .clip(shape)
            .background(if (selected) Palette.NavSelected else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, Palette.TintBorder, shape) else Modifier)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(start = 8.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.5.dp)
                .height(if (compact) 20.dp else 24.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (selected) Palette.Accent else Color.Transparent),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            category.glyph(),
            contentDescription = null,
            tint = if (selected) Palette.Accent else Palette.Muted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(11.dp))
        Text(
            stringResource(category.label),
            style = UiType.nav(compact).copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) Palette.NavSelectedText else Palette.Text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 顶栏：左边「返回」和「设置」，右边生效提示与深浅色切换。 */
@Composable
private fun SettingsTopBar(onBack: () -> Unit, showHint: Boolean, modifier: Modifier = Modifier) {
    TopBar(
        start = {
            TopBarButton(stringResource(R.string.action_back), onBack, glyph = Glyphs.Back)
            PageTitle(stringResource(R.string.settings_title))
        },
        end = {
            if (showHint) {
                Text(
                    stringResource(R.string.settings_apply_hint),
                    style = UiType.footer(LocalCompact.current),
                    color = Palette.Muted,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
            }
            ThemeToggle()
        },
        modifier = modifier,
    )
}

/** 最下面的说明行。 */
@Composable
private fun VersionFooter() {
    Text(
        stringResource(R.string.home_tagline),
        style = UiType.footer(LocalCompact.current),
        color = Palette.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp),
    )
}
