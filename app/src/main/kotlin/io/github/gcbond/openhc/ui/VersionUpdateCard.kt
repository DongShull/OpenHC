package io.github.gcbond.openhc.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.update.AppUpdateLogic
import io.github.gcbond.openhc.update.AppUpdateLogic.DownloadRoute
import io.github.gcbond.openhc.update.AppUpdater
import io.github.gcbond.openhc.update.UpdateState
import java.util.Locale

/**
 * 设置页侧边栏「诊断」下方的版本与更新卡片：
 * 实时展示当前版本、GitHub 检查状态、下载进度，点击展开详情弹窗支持直连优先 + ghfast.top 代理回退下载与覆盖安装。
 */
@Composable
internal fun VersionUpdateCard(
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by AppUpdater.state.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AppUpdater.checkIfNeeded()
    }

    val shape = RoundedCornerShape(Dimens.SubCardRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val highlighted = state is UpdateState.Available || state is UpdateState.Downloading
    val positive = state is UpdateState.ReadyToInstall

    val targetBg = when {
        positive -> Palette.TilePositive
        highlighted -> Palette.TileActive
        else -> Palette.CardElevated
    }
    val targetBorder = when {
        positive -> Palette.TilePositiveBorder
        highlighted -> Palette.TileActiveBorder
        else -> Palette.CardBorder
    }
    val bg by animateColorAsState(targetBg, tween(160), label = "ver_card_bg")
    val border by animateColorAsState(targetBorder, tween(160), label = "ver_card_border")

    val (badgeText, badgeFg, badgeBg, subtitleText) = cardPresentation(state)

    Column(
        modifier
            .fillMaxWidth()
            .pressScale(interactionSource, pressedScale = 0.975f)
            .clip(shape)
            .background(bg)
            .topEdgeSheen(Dimens.SubCardRadius)
            .border(1.dp, border, shape)
            .dpadFocusRing(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = { showDialog = true },
            )
            .padding(horizontal = 13.dp, vertical = if (compact) 10.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    text = stringResource(R.string.update_card_version, BuildConfig.VERSION_NAME),
                    style = UiType.rowTitle(compact).copy(fontWeight = FontWeight.SemiBold),
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = badgeText,
                style = UiType.footer(true).copy(fontWeight = FontWeight.SemiBold),
                color = badgeFg,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(badgeBg)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = subtitleText,
                style = UiType.footer(true),
                color = if (highlighted || positive) Palette.Text2 else Palette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Glyphs.Chevron,
                contentDescription = null,
                tint = Palette.Muted,
                modifier = Modifier.size(14.dp),
            )
        }
        if (state is UpdateState.Downloading) {
            val dl = state as UpdateState.Downloading
            val fraction = if (dl.totalBytes > 0L) (dl.downloadedBytes.toFloat() / dl.totalBytes.toFloat()).coerceIn(0.03f, 1f) else 0.35f
            val animated by animateFloatAsState(fraction, tween(150), label = "ver_card_prog")
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Palette.Divider),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animated)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(Palette.Accent),
                )
            }
        }
    }

    if (showDialog) {
        VersionUpdateDialog(
            state = state,
            onDismiss = { showDialog = false },
        )
    }
}

private data class CardPresentation(
    val badge: String,
    val badgeFg: Color,
    val badgeBg: Color,
    val subtitle: String,
)

@Composable
private fun cardPresentation(state: UpdateState): CardPresentation = when (state) {
    UpdateState.Idle -> CardPresentation(
        badge = stringResource(R.string.update_badge_idle),
        badgeFg = Palette.Text2,
        badgeBg = Palette.Button,
        subtitle = stringResource(R.string.update_sub_idle),
    )
    UpdateState.Checking -> CardPresentation(
        badge = stringResource(R.string.update_badge_checking),
        badgeFg = Palette.Accent,
        badgeBg = Palette.Tint,
        subtitle = stringResource(R.string.update_sub_checking),
    )
    is UpdateState.UpToDate -> CardPresentation(
        badge = stringResource(R.string.update_badge_latest),
        badgeFg = Palette.Positive,
        badgeBg = Palette.PositiveBackground,
        subtitle = stringResource(R.string.update_sub_latest),
    )
    is UpdateState.Available -> CardPresentation(
        badge = stringResource(R.string.update_badge_available),
        badgeFg = Palette.OnAccent,
        badgeBg = Palette.Accent,
        subtitle = stringResource(R.string.update_sub_available, state.release.versionName),
    )
    is UpdateState.Downloading -> {
        val pct = if (state.totalBytes > 0L) ((state.downloadedBytes * 100L) / state.totalBytes).toInt().coerceIn(0, 100) else -1
        CardPresentation(
            badge = if (pct >= 0) stringResource(R.string.update_badge_downloading, pct) else stringResource(R.string.update_badge_downloading_indet),
            badgeFg = Palette.Accent,
            badgeBg = Palette.Tint,
            subtitle = stringResource(
                if (state.route == DownloadRoute.PROXY) R.string.update_sub_downloading_proxy else R.string.update_sub_downloading_direct,
            ),
        )
    }
    is UpdateState.ReadyToInstall -> CardPresentation(
        badge = stringResource(R.string.update_badge_ready),
        badgeFg = Palette.Positive,
        badgeBg = Palette.PositiveBackground,
        subtitle = stringResource(R.string.update_sub_ready, state.release.versionName),
    )
    is UpdateState.Failed -> CardPresentation(
        badge = stringResource(R.string.update_badge_failed),
        badgeFg = Palette.Warn,
        badgeBg = Palette.WarnBackground,
        subtitle = stringResource(R.string.update_sub_failed),
    )
}

@Composable
private fun VersionUpdateDialog(
    state: UpdateState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val compact = LocalCompact.current
    var canInstall by remember(context) { mutableStateOf(AppUpdater.canInstallPackages(context)) }
    val installPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canInstall = AppUpdater.canInstallPackages(context)
        val ready = AppUpdater.state.value as? UpdateState.ReadyToInstall
        if (canInstall && ready != null) {
            if (!AppUpdater.launchInstaller(context, ready.apkPath)) {
                Toast.makeText(context, context.getString(R.string.update_install_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    val releaseUrl = when (state) {
        is UpdateState.Available -> state.release.htmlUrl
        is UpdateState.Downloading -> state.release.htmlUrl
        is UpdateState.ReadyToInstall -> state.release.htmlUrl
        is UpdateState.Failed -> state.release?.htmlUrl ?: AppUpdateLogic.RELEASES_PAGE_URL
        else -> AppUpdateLogic.RELEASES_PAGE_URL
    }
    val noBrowserMsg = stringResource(R.string.update_no_browser)
    val installFailedMsg = stringResource(R.string.update_install_failed)

    DialogCard(
        title = stringResource(R.string.update_dialog_title),
        onDismiss = onDismiss,
        dismissOnClickOutside = state !is UpdateState.Downloading,
        buttons = {
            ActionButton(
                text = stringResource(R.string.update_action_github),
                onClick = {
                    if (!AppUpdater.openReleasesPage(context, releaseUrl)) {
                        Toast.makeText(context, noBrowserMsg, Toast.LENGTH_LONG).show()
                    }
                },
            )
            when (state) {
                is UpdateState.Downloading -> {
                    ActionButton(
                        text = stringResource(R.string.update_action_cancel),
                        kind = ActionKind.DANGER,
                        onClick = { AppUpdater.cancelDownload() },
                    )
                }
                is UpdateState.Available -> {
                    ActionButton(
                        text = stringResource(R.string.update_action_close),
                        onClick = onDismiss,
                    )
                    ActionButton(
                        text = stringResource(R.string.update_action_download),
                        kind = ActionKind.PRIMARY,
                        onClick = { AppUpdater.download(context, state.release) },
                    )
                }
                is UpdateState.ReadyToInstall -> {
                    ActionButton(
                        text = stringResource(R.string.update_action_close),
                        onClick = onDismiss,
                    )
                    if (canInstall) {
                        ActionButton(
                            text = stringResource(R.string.update_action_install),
                            kind = ActionKind.PRIMARY,
                            onClick = {
                                if (!AppUpdater.launchInstaller(context, state.apkPath)) {
                                    Toast.makeText(context, installFailedMsg, Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                    } else {
                        ActionButton(
                            text = stringResource(R.string.update_action_grant_install),
                            kind = ActionKind.PRIMARY,
                            onClick = {
                                try {
                                    installPermLauncher.launch(AppUpdater.unknownSourcesIntent(context))
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, installFailedMsg, Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                    }
                }
                is UpdateState.Failed -> {
                    ActionButton(
                        text = stringResource(R.string.update_action_close),
                        onClick = onDismiss,
                    )
                    val retryRelease = state.release
                    ActionButton(
                        text = stringResource(if (retryRelease != null) R.string.update_action_download else R.string.update_action_check),
                        kind = ActionKind.PRIMARY,
                        onClick = {
                            if (retryRelease != null) AppUpdater.download(context, retryRelease) else AppUpdater.checkNow()
                        },
                    )
                }
                else -> {
                    ActionButton(
                        text = stringResource(R.string.update_action_close),
                        onClick = onDismiss,
                    )
                    ActionButton(
                        text = stringResource(R.string.update_action_check),
                        kind = ActionKind.PRIMARY,
                        enabled = state !is UpdateState.Checking,
                        onClick = { AppUpdater.checkNow() },
                    )
                }
            }
        },
    ) {
        // 版本对比面板
        val infoShape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(infoShape)
                .background(Palette.CardElevated)
                .border(1.dp, Palette.CardBorder, infoShape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VersionKeyValue(
                label = stringResource(R.string.update_dialog_current),
                value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                compact = compact,
            )
            val latestValue = when (state) {
                UpdateState.Idle -> "—"
                UpdateState.Checking -> stringResource(R.string.update_badge_checking)
                is UpdateState.UpToDate -> "v${state.latestVersion} · ${stringResource(R.string.update_badge_latest)}"
                is UpdateState.Available -> "v${state.release.versionName}"
                is UpdateState.Downloading -> "v${state.release.versionName}"
                is UpdateState.ReadyToInstall -> "v${state.release.versionName}"
                is UpdateState.Failed -> state.release?.let { "v${it.versionName}" } ?: "—"
            }
            val latestColor = when (state) {
                is UpdateState.UpToDate, is UpdateState.ReadyToInstall -> Palette.Positive
                is UpdateState.Available, is UpdateState.Downloading -> Palette.Accent
                is UpdateState.Failed -> Palette.Warn
                else -> Palette.Text
            }
            VersionKeyValue(
                label = stringResource(R.string.update_dialog_latest),
                value = latestValue,
                valueColor = latestColor,
                compact = compact,
            )
        }

        Spacer(Modifier.height(10.dp))

        // 通道策略说明或当前实际通道
        val routeLine = when (state) {
            is UpdateState.Downloading -> stringResource(
                if (state.route == DownloadRoute.PROXY) R.string.update_dialog_route_proxy else R.string.update_dialog_route_direct,
            )
            is UpdateState.ReadyToInstall -> stringResource(
                if (state.route == DownloadRoute.PROXY) R.string.update_dialog_route_proxy else R.string.update_dialog_route_direct,
            )
            is UpdateState.Available -> stringResource(R.string.update_dialog_route_hint)
            is UpdateState.UpToDate -> stringResource(
                if (state.route == DownloadRoute.PROXY) R.string.update_dialog_route_proxy else R.string.update_dialog_route_direct,
            )
            else -> stringResource(R.string.update_dialog_route_hint)
        }
        Text(routeLine, style = UiType.footer(compact), color = Palette.Muted)

        // 下载进度条
        if (state is UpdateState.Downloading) {
            Spacer(Modifier.height(12.dp))
            val fraction = if (state.totalBytes > 0L) (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0.02f, 1f) else 0.35f
            val animated by animateFloatAsState(fraction, tween(150), label = "dialog_prog")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Palette.SegmentBackground),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animated)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Palette.Accent),
                )
            }
            Spacer(Modifier.height(6.dp))
            val progressText = if (state.totalBytes > 0L) {
                val pct = ((state.downloadedBytes * 100L) / state.totalBytes).toInt().coerceIn(0, 100)
                "${formatMb(state.downloadedBytes)} / ${formatMb(state.totalBytes)}（$pct%）"
            } else {
                formatMb(state.downloadedBytes)
            }
            Text(progressText, style = UiType.footer(compact), color = Palette.Text2)
        }

        // 就绪校验与安装权限提示
        if (state is UpdateState.ReadyToInstall) {
            Spacer(Modifier.height(10.dp))
            if (state.verifiedSha256) {
                Text(stringResource(R.string.update_dialog_verified), style = UiType.footer(compact), color = Palette.Positive)
            }
            if (!canInstall) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.update_dialog_need_permission), style = UiType.footer(compact), color = Palette.Warn)
            }
        }

        // 错误提示
        if (state is UpdateState.Failed) {
            Spacer(Modifier.height(10.dp))
            Text(state.message, style = UiType.rowSummary(compact), color = Palette.Warn)
        }

        // 更新日志预览
        val notes = when (state) {
            is UpdateState.Available -> state.release.notes
            is UpdateState.Downloading -> state.release.notes
            is UpdateState.ReadyToInstall -> state.release.notes
            else -> ""
        }
        if (notes.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                notes,
                style = UiType.footer(compact),
                color = Palette.Text2,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.SegmentBackground)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun VersionKeyValue(
    label: String,
    value: String,
    compact: Boolean,
    valueColor: Color = Palette.Text,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = UiType.rowSummary(compact), color = Palette.Muted)
        Text(
            value,
            style = UiType.rowSummary(compact).copy(fontWeight = FontWeight.SemiBold),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatMb(bytes: Long): String = String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
