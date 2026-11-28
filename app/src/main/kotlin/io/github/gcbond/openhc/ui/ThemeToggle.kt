package io.github.gcbond.openhc.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.AppSettings

/** 丝滑平移胶囊深浅色切换：自动（随车机大灯 / 昼夜切换）、浅色、深色。 */
@Composable
internal fun ThemeToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val compact = LocalCompact.current
    val mode = Palette.themeMode
    val pick = { next: Int ->
        settings.themeMode = next
        Palette.themeMode = next
        Palette.dark = settings.resolveDark(context)
    }
    val autoWidth = if (compact) 46.dp else 52.dp
    val iconWidth = if (compact) 38.dp else 42.dp
    val slotHeight = if (compact) 32.dp else 36.dp
    val gap = 2.dp

    val targetOffset = when (mode) {
        AppSettings.THEME_LIGHT -> autoWidth + gap
        AppSettings.THEME_DARK -> autoWidth + iconWidth + gap * 2
        else -> 0.dp
    }
    val targetWidth = if (mode == AppSettings.THEME_AUTO) autoWidth else iconWidth
    val springSpec = spring<Dp>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    val slideX by animateDpAsState(targetValue = targetOffset, animationSpec = springSpec, label = "theme_slide_x")
    val slideW by animateDpAsState(targetValue = targetWidth, animationSpec = springSpec, label = "theme_slide_w")

    Box(
        modifier
            .clip(CircleShape)
            .background(Palette.SegmentBackground)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset(slideX.roundToPx(), 0) }
                .size(slideW, slotHeight)
                .clip(CircleShape)
                .background(Palette.SegmentSelected)
                .border(0.5.dp, Palette.SegmentSelectedBorder, CircleShape),
        )
        Row(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            ThemeTextChoice(
                label = stringResource(R.string.theme_auto),
                selected = mode == AppSettings.THEME_AUTO,
                width = autoWidth,
                height = slotHeight,
                compact = compact,
            ) { pick(AppSettings.THEME_AUTO) }
            ThemeChoice(
                glyph = Glyphs.Sun,
                label = stringResource(R.string.theme_light),
                selected = mode == AppSettings.THEME_LIGHT,
                selectedTint = Palette.Sun,
                width = iconWidth,
                height = slotHeight,
            ) { pick(AppSettings.THEME_LIGHT) }
            ThemeChoice(
                glyph = Glyphs.Moon,
                label = stringResource(R.string.theme_dark),
                selected = mode == AppSettings.THEME_DARK,
                selectedTint = Palette.Moon,
                width = iconWidth,
                height = slotHeight,
            ) { pick(AppSettings.THEME_DARK) }
        }
    }
}

@Composable
private fun ThemeTextChoice(
    label: String,
    selected: Boolean,
    width: Dp,
    height: Dp,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val textColor by animateColorAsState(
        targetValue = if (selected) Palette.Text else Palette.Muted,
        animationSpec = tween(160),
        label = "theme_text_color",
    )
    Box(
        Modifier
            .size(width, height)
            .clip(CircleShape)
            .dpadFocusRing(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = UiType.footer(compact).copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = textColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun ThemeChoice(
    glyph: ImageVector,
    label: String,
    selected: Boolean,
    selectedTint: Color,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) selectedTint else Palette.Muted,
        animationSpec = tween(160),
        label = "theme_icon_tint",
    )
    Box(
        Modifier
            .size(width, height)
            .clip(CircleShape)
            .dpadFocusRing(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, contentDescription = label, tint = tint, modifier = Modifier.size(17.dp))
    }
}
