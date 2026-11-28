package io.github.gcbond.openhc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// 现代新能源座舱风格配色、字号与公共尺寸：深色（深空蓝黑 + 冰蓝高光微描边）与浅色（通透冷瓷灰 + 纯白卡片）两套主题。

/**
 * 配色。各颜色按 [dark] 取深色或浅色那一套；[dark] 是快照状态，切换后读过它的界面都会重画，
 * 所以只能在组合、布局、绘制时读，不要存进 remember 或顶层常量。
 */
internal object Palette {
    /** 当前是不是深色。由 [MainActivity][io.github.gcbond.openhc.MainActivity] 按设置和车机夜间模式写入，[ThemeToggle] 切换。 */
    var dark by mutableStateOf(true)

    /** 当前选中的主题档位（0 自动跟随车机、1 浅色、2 深色）。 */
    var themeMode by mutableStateOf(0)

    private fun pick(dark: Long, light: Long) = Color(if (this.dark) dark else light)

    /** 页面底色。 */
    val Background get() = pick(0xFF070B14, 0xFFEEF2F7)

    /** 顶部地平线极淡氛围光（零模糊开销，增强座舱空间纵深感）。 */
    val BackgroundGlow get() = if (dark) Color(0x292563EB) else Color(0x162563EB)
    val BackgroundGlowEdge get() = if (dark) Color(0x1438BDF8) else Color(0x0D0EA5E9)

    /** 主卡片、分组框。 */
    val Card get() = pick(0xFF101726, 0xFFFFFFFF)

    /** 卡片内次级高光面板（右侧概览卡、子状态块）。 */
    val CardElevated get() = pick(0xFF152035, 0xFFF7FAFE)

    /** 卡片 1dp 玻璃高光描边。 */
    val CardBorder get() = if (dark) Color(0x1EFFFFFF) else Color(0xFFDFE7F1)

    /** 卡片顶部的微弱高光叠层。 */
    val CardTopSheen get() = if (dark) Color(0x0AFFFFFF) else Color(0x00FFFFFF)

    val Text get() = pick(0xFFF3F7FD, 0xFF0D172A)

    /** 比正文淡一档、仍要读的文字（卡片里的一句话说明）。 */
    val Text2 get() = pick(0xFFB9C6DA, 0xFF3A4B63)

    /** 次要文字：说明、页脚。 */
    val Muted get() = pick(0xFF7D8FA8, 0xFF63748C)

    /** 强调色、小标题、图标。 */
    val Accent get() = pick(0xFF5FA5FF, 0xFF1D56E0)

    /** 青蓝高光点缀（徽标、连接进度）。 */
    val AccentCyan get() = pick(0xFF38BDF8, 0xFF0284C7)

    /** 图标底下的轻透色块及边框。 */
    val Tint get() = if (dark) Color(0x263B82F6) else Color(0x142563EB)
    val TintBorder get() = if (dark) Color(0x4A5FA5FF) else Color(0x332563EB)

    /** 要用户注意的：缺东西、出错、需要重开才生效。 */
    val Warn get() = pick(0xFFFBBF24, 0xFFB45309)
    val WarnBackground get() = if (dark) Color(0x1AFBBF24) else Color(0xFFFFF8E6)
    val WarnBorder get() = if (dark) Color(0x5CFBBF24) else Color(0xFFF2C96B)

    /** 好的状态（开着、已连上、投屏中）。 */
    val Positive get() = pick(0xFF34D399, 0xFF16A34A)
    val PositiveBackground get() = if (dark) Color(0x1E34D399) else Color(0x1716A34A)
    val PositiveBorder get() = if (dark) Color(0x4D34D399) else Color(0x4016A34A)

    /** 主按钮、开关打开、选项选中。上面的字用 [OnAccent]。 */
    val On get() = Color(0xFF2563EB)
    val OnGradientStart get() = Color(0xFF3382FF)
    val OnGradientEnd get() = Color(0xFF1D52E0)
    val OnAccent get() = Color.White
    val SwitchOff get() = pick(0xFF2B374D, 0xFFCBD5E1)

    /** 次要按钮（「设置」「返回」「停止」、行内小按钮）的底色、描边和字色。 */
    val Button get() = pick(0xFF1A253B, 0xFFE6EDF7)
    val ButtonBorder get() = if (dark) Color(0x1CFFFFFF) else Color(0xFFD4DFEE)
    val ButtonText get() = pick(0xFFF0F4FA, 0xFF0F172A)

    /** 分段选择、声道一排、内嵌槽的底色。 */
    val SegmentBackground get() = pick(0xFF0B111F, 0xFFE9EFF7)
    val Divider get() = if (dark) Color(0x16FFFFFF) else Color(0xFFE3EAF3)

    /** 连接码方框。 */
    val PinBox get() = pick(0xFF172338, 0xFFF2F6FC)
    val PinText get() = pick(0xFFF8FAFC, 0xFF0F172A)

    /** 弹出框的底色。 */
    val Raised get() = pick(0xFF151F33, 0xFFFFFFFF)

    /** 设置页左边目录里选中的那一项。 */
    val NavSelected get() = if (dark) Color(0x283B82F6) else Color(0xFFE5EEFF)
    val NavSelectedText get() = pick(0xFFF5F8FE, 0xFF1D4ED8)

    /** 太阳、月亮选中时的颜色。 */
    val Sun get() = Color(0xFFF59E0B)
    val Moon get() = Color(0xFFFCD34D)
}

/** 轻量级座舱背景：纯色底 + 顶部与右侧极淡径向微光（单次 Canvas 绘制，无模糊开销）。 */
internal fun Modifier.cockpitBackground(): Modifier = drawBehind {
    drawRect(Palette.Background)
    val glowRadius = size.maxDimension * 0.65f
    drawRect(
        Brush.radialGradient(
            colors = listOf(Palette.BackgroundGlow, Color.Transparent),
            center = Offset(size.width * 0.25f, -size.height * 0.1f),
            radius = glowRadius,
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(Palette.BackgroundGlowEdge, Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.12f),
            radius = glowRadius * 0.75f,
        ),
    )
}

/** 公共尺寸。 */
internal object Dimens {
    /** 页面左右留白；窄屏（宽不到 [WIDE_PAGE_MIN]）收成 [PageSideNarrow]。 */
    val PageSide = 32.dp
    val PageSideNarrow = 16.dp
    val WIDE_PAGE_MIN = 600.dp

    /** 低于这个高度算矮屏：字号、间距收一档。 */
    val CompactHeight = 560.dp
    val CardRadius = 24.dp
    val SubCardRadius = 18.dp
    val CardPadding = 24.dp
    val CardPaddingCompact = 16.dp
}

/** 当前是不是矮屏（见 [Dimens.CompactHeight]），由各页面的根布局按实际高度提供。 */
internal val LocalCompact = compositionLocalOf { false }

/** 各处文字样式。只定字号、粗细，颜色由调用处给；字距一律为 0（中文加字距反而难看）。 */
internal object UiType {
    private val base = TextStyle(letterSpacing = 0.sp)

    fun title(compact: Boolean) = base.copy(fontSize = if (compact) 20.sp else 23.sp, fontWeight = FontWeight.Bold)
    val topButton = base.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val pill = base.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    fun action(compact: Boolean) = base.copy(fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.Medium)

    // 首页卡片
    fun cardTitle(compact: Boolean) = base.copy(fontSize = if (compact) 22.sp else 27.sp, fontWeight = FontWeight.Bold)
    fun cardText(compact: Boolean) = base.copy(fontSize = if (compact) 14.sp else 16.sp, lineHeight = 1.55.em)
    fun cardHeader(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold)
    val status = base.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold)
    val hint = base.copy(fontSize = 14.sp, lineHeight = 1.5.em)
    fun message(compact: Boolean) = base.copy(fontSize = if (compact) 17.sp else 20.sp, lineHeight = 1.5.em)
    fun footer(compact: Boolean) = base.copy(fontSize = if (compact) 12.sp else 13.sp, lineHeight = 1.4.em)

    // 设置页
    fun groupTitle(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.Bold)
    fun nav(compact: Boolean) = base.copy(fontSize = if (compact) 15.sp else 16.sp)
    fun rowTitle(compact: Boolean) = base.copy(fontSize = if (compact) 16.sp else 17.sp, fontWeight = FontWeight.Medium)
    fun rowSummary(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 14.sp, lineHeight = 1.45.em)
    fun rowDesc(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 14.sp, lineHeight = 1.5.em)
    fun rowNote(compact: Boolean) =
        base.copy(fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.Bold, lineHeight = 1.4.em)
    fun option(compact: Boolean) = base.copy(fontSize = if (compact) 14.sp else 15.sp)
    fun value(compact: Boolean) = base.copy(fontSize = if (compact) 14.sp else 15.sp, fontFamily = FontFamily.Monospace)
    fun chevron(compact: Boolean) = base.copy(fontSize = if (compact) 18.sp else 20.sp)
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF5FA5FF),
    onSecondary = Color(0xFF070B14),
    tertiary = Color(0xFF38BDF8),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFF3F7FD),
    surface = Color(0xFF101726),
    onSurface = Color(0xFFF3F7FD),
    surfaceVariant = Color(0xFF1A253B),
    onSurfaceVariant = Color(0xFF7D8FA8),
    surfaceContainerLowest = Color(0xFF070B14),
    surfaceContainerLow = Color(0xFF101726),
    surfaceContainer = Color(0xFF151F33),
    surfaceContainerHigh = Color(0xFF1A253B),
    surfaceContainerHighest = Color(0xFF202D47),
    outline = Color(0xFF7D8FA8),
    outlineVariant = Color(0xFF2B374D),
    error = Color(0xFFFBBF24),
    onError = Color(0xFF070B14),
    errorContainer = Color(0xFFFBBF24),
    onErrorContainer = Color(0xFF070B14),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EEFF),
    onPrimaryContainer = Color(0xFF1D4ED8),
    secondary = Color(0xFF1D56E0),
    onSecondary = Color.White,
    tertiary = Color(0xFF0284C7),
    background = Color(0xFFEEF2F7),
    onBackground = Color(0xFF0D172A),
    surface = Color.White,
    onSurface = Color(0xFF0D172A),
    surfaceVariant = Color(0xFFE6EDF7),
    onSurfaceVariant = Color(0xFF63748C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF7FAFE),
    surfaceContainerHighest = Color(0xFFE6EDF7),
    outline = Color(0xFF63748C),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFB45309),
    onError = Color.White,
    errorContainer = Color(0xFFFFF8E6),
    onErrorContainer = Color(0xFFB45309),
)

private val OpenHcTypography: Typography = Typography().let { d ->
    Typography(
        bodyLarge = d.bodyLarge.copy(letterSpacing = 0.sp),
        bodyMedium = d.bodyMedium.copy(letterSpacing = 0.sp),
        bodySmall = d.bodySmall.copy(letterSpacing = 0.sp),
        labelLarge = d.labelLarge.copy(letterSpacing = 0.sp),
        labelMedium = d.labelMedium.copy(letterSpacing = 0.sp),
        labelSmall = d.labelSmall.copy(letterSpacing = 0.sp),
        titleMedium = d.titleMedium.copy(letterSpacing = 0.sp),
        titleSmall = d.titleSmall.copy(letterSpacing = 0.sp),
    )
}

/**
 * 全应用的主题：按 [Palette.dark] 用深色或浅色。还在用的 Material 组件（输入框、弹框）从这里取色：
 * 主色是座舱蓝，「错误」用提醒用的琥珀色，和自己画的控件一致。
 */
@Composable
internal fun OpenHcTheme(content: @Composable () -> Unit) {
    val colors = if (Palette.dark) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colors, typography = OpenHcTypography, content = content)
}
