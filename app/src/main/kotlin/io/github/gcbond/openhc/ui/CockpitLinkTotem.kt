package io.github.gcbond.openhc.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

internal enum class TotemMode { IDLE, PLUGGED_READY, CONNECTING, PROJECTING, WARNING }

/**
 * 极简现代车机 × 手机互联视觉图腾（纯矢量 Canvas 绘制，零位图与零逐帧 Path 分配）：
 * - 空闲：静谧微光呼吸；
 * - 已插线就绪：柔和绿色有线通道呼吸提示；
 * - 连接中：手机 → 车机方向流动光点与克制同心环脉冲；
 * - 投屏中：低功耗常亮绿色互联光晕与大屏激活态；
 * - 异常/提醒：静止琥珀色虚线通道与菱形告警节点。
 */
@Composable
internal fun CockpitLinkTotem(
    mode: TotemMode,
    wired: Boolean,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompact.current
    val phaseState: State<Float> = when (mode) {
        TotemMode.IDLE, TotemMode.PLUGGED_READY -> {
            val transition = rememberInfiniteTransition(label = "totem_idle")
            transition.animateFloat(
                initialValue = 0.30f,
                targetValue = 0.78f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "idle_breath",
            )
        }
        TotemMode.CONNECTING -> {
            val transition = rememberInfiniteTransition(label = "totem_connect")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "connect_flow",
            )
        }
        TotemMode.PROJECTING -> {
            val transition = rememberInfiniteTransition(label = "totem_projecting")
            transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 0.85f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 4200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "projecting_aura",
            )
        }
        TotemMode.WARNING -> remember { mutableFloatStateOf(0.5f) }
    }

    val surfaceFill = Palette.CardElevated
    val mutedStroke = Palette.Text2.copy(alpha = 0.42f)
    val softDetail = Palette.Muted.copy(alpha = 0.32f)
    val accent = when (mode) {
        TotemMode.PLUGGED_READY, TotemMode.PROJECTING -> Palette.Positive
        TotemMode.WARNING -> Palette.Warn
        TotemMode.IDLE, TotemMode.CONNECTING -> Palette.Accent
    }
    val upperPath = remember { Path() }
    val lowerPath = remember { Path() }
    val diamondPath = remember { Path() }
    val warnDash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) }

    Canvas(
        modifier.size(
            width = if (compact) 232.dp else 284.dp,
            height = if (compact) 94.dp else 116.dp,
        ),
    ) {
        val phase = phaseState.value
        val w = size.width
        val h = size.height
        val strokeThin = 1.5.dp.toPx()
        val strokeStrong = 1.8.dp.toPx()

        // 1. 左侧：车机中控大屏轮廓
        val carLeft = w * 0.04f
        val carTop = h * 0.14f
        val carW = w * 0.36f
        val carH = h * 0.64f
        val carRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
        val carBorder = if (mode == TotemMode.IDLE) mutedStroke else accent.copy(alpha = 0.82f)

        drawRoundRect(
            color = surfaceFill,
            topLeft = Offset(carLeft, carTop),
            size = Size(carW, carH),
            cornerRadius = carRadius,
        )
        if (mode == TotemMode.PROJECTING) {
            drawRoundRect(
                color = accent.copy(alpha = 0.13f),
                topLeft = Offset(carLeft + strokeThin, carTop + strokeThin),
                size = Size(carW - strokeThin * 2, carH - strokeThin * 2),
                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
            )
        }
        drawRoundRect(
            color = carBorder,
            topLeft = Offset(carLeft, carTop),
            size = Size(carW, carH),
            cornerRadius = carRadius,
            style = Stroke(width = strokeStrong),
        )
        // 中控屏内部极简双栏示意块（左地图 + 右卡片条）
        val innerPad = 6.dp.toPx()
        val mapW = (carW - innerPad * 3) * 0.56f
        val mapH = carH - innerPad * 2
        val innerTint = if (mode == TotemMode.PROJECTING) accent.copy(alpha = 0.26f) else softDetail
        drawRoundRect(
            color = innerTint,
            topLeft = Offset(carLeft + innerPad, carTop + innerPad),
            size = Size(mapW, mapH),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        )
        val rightX = carLeft + innerPad * 2 + mapW
        val rightW = carW - innerPad * 3 - mapW
        val cardH = (mapH - 4.dp.toPx()) * 0.5f
        drawRoundRect(
            color = innerTint,
            topLeft = Offset(rightX, carTop + innerPad),
            size = Size(rightW, cardH),
            cornerRadius = CornerRadius(3.5.dp.toPx(), 3.5.dp.toPx()),
        )
        drawRoundRect(
            color = innerTint.copy(alpha = innerTint.alpha * 0.72f),
            topLeft = Offset(rightX, carTop + innerPad + cardH + 4.dp.toPx()),
            size = Size(rightW, cardH),
            cornerRadius = CornerRadius(3.5.dp.toPx(), 3.5.dp.toPx()),
        )
        if (mode == TotemMode.PROJECTING) {
            drawCircle(
                color = accent,
                radius = 3.dp.toPx(),
                center = Offset(carLeft + innerPad + 6.dp.toPx(), carTop + innerPad + 6.dp.toPx()),
            )
        }
        // 中控底座横线
        val standY = carTop + carH + 7.dp.toPx()
        drawLine(
            color = mutedStroke.copy(alpha = 0.55f),
            start = Offset(carLeft + carW * 0.22f, standY),
            end = Offset(carLeft + carW * 0.78f, standY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // 2. 右侧：手机轮廓
        val phoneW = w * 0.18f
        val phoneH = h * 0.74f
        val phoneLeft = w * 0.78f
        val phoneTop = (h - phoneH) * 0.5f
        val phoneRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
        val phoneBorder = when (mode) {
            TotemMode.IDLE -> mutedStroke
            else -> accent.copy(alpha = 0.85f)
        }
        drawRoundRect(
            color = surfaceFill,
            topLeft = Offset(phoneLeft, phoneTop),
            size = Size(phoneW, phoneH),
            cornerRadius = phoneRadius,
        )
        drawRoundRect(
            color = phoneBorder,
            topLeft = Offset(phoneLeft, phoneTop),
            size = Size(phoneW, phoneH),
            cornerRadius = phoneRadius,
            style = Stroke(width = strokeStrong),
        )
        // 听筒细线 + 底部手势条
        drawLine(
            color = mutedStroke,
            start = Offset(phoneLeft + phoneW * 0.34f, phoneTop + 6.dp.toPx()),
            end = Offset(phoneLeft + phoneW * 0.66f, phoneTop + 6.dp.toPx()),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = if (mode == TotemMode.IDLE) softDetail else accent.copy(alpha = 0.55f),
            start = Offset(phoneLeft + phoneW * 0.30f, phoneTop + phoneH - 6.dp.toPx()),
            end = Offset(phoneLeft + phoneW * 0.70f, phoneTop + phoneH - 6.dp.toPx()),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // 3. 中央互联通道
        val linkLeft = carLeft + carW + 8.dp.toPx()
        val linkRight = phoneLeft - 8.dp.toPx()
        val midX = (linkLeft + linkRight) * 0.5f
        val midY = h * 0.48f
        val arcLift = h * 0.16f

        upperPath.rewind()
        upperPath.moveTo(linkRight, midY)
        upperPath.quadraticTo(midX, midY - arcLift, linkLeft, midY)

        lowerPath.rewind()
        lowerPath.moveTo(linkLeft, midY)
        lowerPath.quadraticTo(midX, midY + arcLift, linkRight, midY)

        when (mode) {
            TotemMode.WARNING -> {
                drawLine(
                    color = accent.copy(alpha = 0.8f),
                    start = Offset(linkLeft, midY),
                    end = Offset(linkRight, midY),
                    strokeWidth = strokeThin,
                    cap = StrokeCap.Round,
                    pathEffect = warnDash,
                )
                val r = 6.dp.toPx()
                diamondPath.rewind()
                diamondPath.moveTo(midX, midY - r)
                diamondPath.lineTo(midX + r, midY)
                diamondPath.lineTo(midX, midY + r)
                diamondPath.lineTo(midX - r, midY)
                diamondPath.close()
                drawPath(diamondPath, color = accent)
            }
            TotemMode.CONNECTING -> {
                if (wired) {
                    drawLine(
                        color = accent.copy(alpha = 0.28f),
                        start = Offset(linkLeft, midY),
                        end = Offset(linkRight, midY),
                        strokeWidth = strokeThin,
                        cap = StrokeCap.Round,
                    )
                } else {
                    drawPath(upperPath, color = accent.copy(alpha = 0.28f), style = Stroke(width = strokeThin, cap = StrokeCap.Round))
                    drawPath(lowerPath, color = accent.copy(alpha = 0.16f), style = Stroke(width = strokeThin, cap = StrokeCap.Round))
                }
                // 3 颗错峰流动光点：从手机流向车机
                for (i in 0..2) {
                    val t = (phase + i / 3f) % 1f
                    val u = 1f - t
                    val x = u * u * linkRight + 2f * u * t * midX + t * t * linkLeft
                    val y = if (wired) {
                        midY
                    } else {
                        u * u * midY + 2f * u * t * (midY - arcLift) + t * t * midY
                    }
                    val dotAlpha = sin(t * PI).toFloat().coerceIn(0f, 1f)
                    drawCircle(
                        color = accent.copy(alpha = dotAlpha),
                        radius = 3.2.dp.toPx(),
                        center = Offset(x, y),
                    )
                }
                // 克制同心扩散环
                for (wave in 0..1) {
                    val p = (phase + wave * 0.5f) % 1f
                    val radius = (6.dp + 14.dp * p).toPx()
                    drawCircle(
                        color = accent.copy(alpha = (1f - p) * 0.30f),
                        radius = radius,
                        center = Offset(midX, midY),
                        style = Stroke(width = 1.25.dp.toPx()),
                    )
                }
                drawCircle(color = accent, radius = 3.5.dp.toPx(), center = Offset(midX, midY))
            }
            TotemMode.PROJECTING -> {
                drawCircle(
                    color = accent.copy(alpha = 0.08f * phase),
                    radius = 20.dp.toPx(),
                    center = Offset(midX, midY),
                )
                drawCircle(
                    color = accent.copy(alpha = 0.14f * phase),
                    radius = 11.dp.toPx(),
                    center = Offset(midX, midY),
                )
                if (wired) {
                    drawLine(
                        color = accent.copy(alpha = 0.85f),
                        start = Offset(linkLeft, midY),
                        end = Offset(linkRight, midY),
                        strokeWidth = strokeStrong,
                        cap = StrokeCap.Round,
                    )
                } else {
                    drawPath(upperPath, color = accent.copy(alpha = 0.85f), style = Stroke(width = strokeStrong, cap = StrokeCap.Round))
                    drawPath(lowerPath, color = accent.copy(alpha = 0.35f), style = Stroke(width = strokeThin, cap = StrokeCap.Round))
                }
                drawCircle(color = accent, radius = 4.dp.toPx(), center = Offset(midX, midY))
            }
            TotemMode.IDLE, TotemMode.PLUGGED_READY -> {
                if (wired || mode == TotemMode.PLUGGED_READY) {
                    drawLine(
                        color = accent.copy(alpha = 0.35f + 0.35f * phase),
                        start = Offset(linkLeft, midY),
                        end = Offset(linkRight, midY),
                        strokeWidth = strokeThin,
                        cap = StrokeCap.Round,
                    )
                } else {
                    drawPath(
                        upperPath,
                        color = accent.copy(alpha = 0.25f + 0.38f * phase),
                        style = Stroke(width = strokeThin, cap = StrokeCap.Round),
                    )
                    drawPath(
                        lowerPath,
                        color = accent.copy(alpha = 0.16f + 0.24f * phase),
                        style = Stroke(width = strokeThin, cap = StrokeCap.Round),
                    )
                }
                drawCircle(
                    color = accent.copy(alpha = 0.14f * phase),
                    radius = 12.dp.toPx(),
                    center = Offset(midX, midY),
                )
                drawCircle(
                    color = accent.copy(alpha = 0.55f + 0.4f * phase),
                    radius = 3.5.dp.toPx(),
                    center = Offset(midX, midY),
                )
            }
        }
    }
}
