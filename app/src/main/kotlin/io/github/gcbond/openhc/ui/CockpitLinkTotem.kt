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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

internal enum class TotemMode { IDLE, PLUGGED_READY, CONNECTING, PROJECTING, WARNING }

/**
 * 旗舰座舱「车机 × 手机」矢量互联图腾（纯矢量 Canvas 绘制，零位图与零逐帧分配）：
 * - 空闲：双平行柔光信号轨静谧呼吸；
 * - 已插线就绪：翠绿有线数据轨呼吸提示；
 * - 连接中：上轨手机→车机流光传输 + 下轨车机→手机回控光点 + 方向微标；
 * - 投屏中：低功耗常亮翠绿双轨与中控大屏激活态；
 * - 异常/提醒：静止琥珀色虚线与菱形告警节点。
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
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 3200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "idle_stream",
            )
        }
        TotemMode.CONNECTING -> {
            val transition = rememberInfiniteTransition(label = "totem_connect")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "connect_flow",
            )
        }
        TotemMode.PROJECTING -> {
            val transition = rememberInfiniteTransition(label = "totem_projecting")
            transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 0.88f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 3800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "projecting_aura",
            )
        }
        TotemMode.WARNING -> remember { mutableFloatStateOf(0.5f) }
    }

    val surfaceFill = Palette.CardElevated
    val innerScreenBg = Palette.SegmentBackground
    val mutedStroke = Palette.Text2.copy(alpha = 0.42f)
    val softDetail = Palette.Muted.copy(alpha = 0.28f)
    val accent = when (mode) {
        TotemMode.PLUGGED_READY, TotemMode.PROJECTING -> Palette.Positive
        TotemMode.WARNING -> Palette.Warn
        TotemMode.IDLE, TotemMode.CONNECTING -> Palette.Accent
    }
    val routePath = remember { Path() }
    val chevronPath = remember { Path() }
    val diamondPath = remember { Path() }
    val warnDash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) }

    Canvas(
        modifier.size(
            width = if (compact) 244.dp else 296.dp,
            height = if (compact) 96.dp else 118.dp,
        ),
    ) {
        val phase = phaseState.value
        val breath = (0.5f - 0.5f * kotlin.math.cos(phase * 2.0 * PI)).toFloat()
        val w = size.width
        val h = size.height
        val strokeThin = 1.5.dp.toPx()
        val strokeStrong = 1.8.dp.toPx()

        // 1. 左侧：车机中控大屏硬件轮廓（含左侧 Dock + 地图导航曲线 + 右侧双卡片）
        val carLeft = w * 0.03f
        val carTop = h * 0.12f
        val carW = w * 0.39f
        val carH = h * 0.66f
        val carRadius = CornerRadius(11.dp.toPx(), 11.dp.toPx())
        val carBorder = if (mode == TotemMode.IDLE) mutedStroke else accent.copy(alpha = 0.82f)

        drawRoundRect(
            color = surfaceFill,
            topLeft = Offset(carLeft, carTop),
            size = Size(carW, carH),
            cornerRadius = carRadius,
        )
        val bezel = 3.5.dp.toPx()
        drawRoundRect(
            color = innerScreenBg,
            topLeft = Offset(carLeft + bezel, carTop + bezel),
            size = Size(carW - bezel * 2, carH - bezel * 2),
            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
        )
        if (mode == TotemMode.PROJECTING) {
            drawRoundRect(
                color = accent.copy(alpha = 0.10f * phase),
                topLeft = Offset(carLeft + bezel, carTop + bezel),
                size = Size(carW - bezel * 2, carH - bezel * 2),
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

        // 中控屏内部：左侧细 Dock 栏 + 主导航区 + 右侧双小组件
        val pad = 7.dp.toPx()
        val dockW = 7.dp.toPx()
        val innerH = carH - pad * 2
        val innerTint = if (mode == TotemMode.PROJECTING) accent.copy(alpha = 0.28f) else softDetail
        drawRoundRect(
            color = innerTint.copy(alpha = innerTint.alpha * 1.15f),
            topLeft = Offset(carLeft + pad, carTop + pad),
            size = Size(dockW, innerH),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
        val mapLeft = carLeft + pad + dockW + 4.dp.toPx()
        val remainW = carW - pad * 2 - dockW - 8.dp.toPx()
        val mapW = remainW * 0.58f
        drawRoundRect(
            color = innerTint,
            topLeft = Offset(mapLeft, carTop + pad),
            size = Size(mapW, innerH),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
        )
        // 地图区极简导航路线
        routePath.rewind()
        routePath.moveTo(mapLeft + mapW * 0.24f, carTop + pad + innerH * 0.78f)
        routePath.quadraticTo(
            mapLeft + mapW * 0.34f,
            carTop + pad + innerH * 0.44f,
            mapLeft + mapW * 0.72f,
            carTop + pad + innerH * 0.28f,
        )
        drawPath(
            path = routePath,
            color = accent.copy(alpha = if (mode == TotemMode.IDLE) 0.45f else 0.85f),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(
            color = accent.copy(alpha = if (mode == TotemMode.IDLE) 0.75f else 1f),
            radius = 2.4.dp.toPx(),
            center = Offset(mapLeft + mapW * 0.72f, carTop + pad + innerH * 0.28f),
        )

        val rightX = mapLeft + mapW + 4.dp.toPx()
        val rightW = remainW - mapW
        val cardH = (innerH - 4.dp.toPx()) * 0.5f
        drawRoundRect(
            color = innerTint,
            topLeft = Offset(rightX, carTop + pad),
            size = Size(rightW, cardH),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        )
        drawRoundRect(
            color = innerTint.copy(alpha = innerTint.alpha * 0.72f),
            topLeft = Offset(rightX, carTop + pad + cardH + 4.dp.toPx()),
            size = Size(rightW, cardH),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        )

        // 中控悬浮底座线
        val standY = carTop + carH + 7.dp.toPx()
        drawLine(
            color = mutedStroke.copy(alpha = 0.55f),
            start = Offset(carLeft + carW * 0.24f, standY),
            end = Offset(carLeft + carW * 0.76f, standY),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // 2. 右侧：手机硬件轮廓（含微缩内屏与状态条）
        val phoneW = w * 0.17f
        val phoneH = h * 0.76f
        val phoneLeft = w * 0.80f
        val phoneTop = (h - phoneH) * 0.5f
        val phoneRadius = CornerRadius(9.dp.toPx(), 9.dp.toPx())
        val phoneBorder = if (mode == TotemMode.IDLE) mutedStroke else accent.copy(alpha = 0.85f)
        drawRoundRect(
            color = surfaceFill,
            topLeft = Offset(phoneLeft, phoneTop),
            size = Size(phoneW, phoneH),
            cornerRadius = phoneRadius,
        )
        val pBezel = 3.dp.toPx()
        drawRoundRect(
            color = innerScreenBg,
            topLeft = Offset(phoneLeft + pBezel, phoneTop + pBezel),
            size = Size(phoneW - pBezel * 2, phoneH - pBezel * 2),
            cornerRadius = CornerRadius(6.5.dp.toPx(), 6.5.dp.toPx()),
        )
        drawRoundRect(
            color = phoneBorder,
            topLeft = Offset(phoneLeft, phoneTop),
            size = Size(phoneW, phoneH),
            cornerRadius = phoneRadius,
            style = Stroke(width = strokeStrong),
        )
        // 顶部灵动听筒条 + 屏幕内极简卡片 + 底部手势条
        drawLine(
            color = mutedStroke,
            start = Offset(phoneLeft + phoneW * 0.34f, phoneTop + 7.dp.toPx()),
            end = Offset(phoneLeft + phoneW * 0.66f, phoneTop + 7.dp.toPx()),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawRoundRect(
            color = accent.copy(alpha = if (mode == TotemMode.IDLE) 0.16f else 0.26f),
            topLeft = Offset(phoneLeft + 7.dp.toPx(), phoneTop + 16.dp.toPx()),
            size = Size(phoneW - 14.dp.toPx(), phoneH * 0.24f),
            cornerRadius = CornerRadius(3.5.dp.toPx(), 3.5.dp.toPx()),
        )
        drawRoundRect(
            color = softDetail,
            topLeft = Offset(phoneLeft + 7.dp.toPx(), phoneTop + 16.dp.toPx() + phoneH * 0.24f + 4.dp.toPx()),
            size = Size(phoneW - 14.dp.toPx(), phoneH * 0.18f),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
        drawLine(
            color = if (mode == TotemMode.IDLE) softDetail else accent.copy(alpha = 0.6f),
            start = Offset(phoneLeft + phoneW * 0.30f, phoneTop + phoneH - 6.dp.toPx()),
            end = Offset(phoneLeft + phoneW * 0.70f, phoneTop + phoneH - 6.dp.toPx()),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // 3. 中央互联通道（双平行流光信号轨，干净利落，告别对称弧线）
        val linkLeft = carLeft + carW + 10.dp.toPx()
        val linkRight = phoneLeft - 10.dp.toPx()
        val midX = (linkLeft + linkRight) * 0.5f
        val midY = h * 0.47f
        val railGap = 7.dp.toPx()
        val topY = midY - railGap
        val botY = midY + railGap

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
                        color = accent.copy(alpha = 0.25f),
                        start = Offset(linkLeft, midY),
                        end = Offset(linkRight, midY),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    for (i in 0..2) {
                        val t = (phase + i / 3f) % 1f
                        val x = linkRight - (linkRight - linkLeft) * t
                        val dotAlpha = sin(t * PI).toFloat().coerceIn(0f, 1f)
                        drawCircle(accent.copy(alpha = dotAlpha), 3.dp.toPx(), Offset(x, midY))
                    }
                } else {
                    // 上轨：手机 → 车机投屏数据流
                    drawLine(
                        color = accent.copy(alpha = 0.26f),
                        start = Offset(linkLeft, topY),
                        end = Offset(linkRight, topY),
                        strokeWidth = strokeThin,
                        cap = StrokeCap.Round,
                    )
                    // 下轨：车机 → 手机触控回传
                    drawLine(
                        color = accent.copy(alpha = 0.16f),
                        start = Offset(linkLeft, botY),
                        end = Offset(linkRight, botY),
                        strokeWidth = strokeThin,
                        cap = StrokeCap.Round,
                    )
                    for (i in 0..1) {
                        val t = (phase + i * 0.5f) % 1f
                        val x = linkRight - (linkRight - linkLeft) * t
                        val a = sin(t * PI).toFloat().coerceIn(0f, 1f)
                        drawCircle(accent.copy(alpha = a * 0.24f), 6.dp.toPx(), Offset(x, topY))
                        drawCircle(accent.copy(alpha = a), 2.8.dp.toPx(), Offset(x, topY))
                    }
                    val backT = (phase + 0.25f) % 1f
                    val backX = linkLeft + (linkRight - linkLeft) * backT
                    val backA = sin(backT * PI).toFloat().coerceIn(0f, 1f) * 0.7f
                    drawCircle(accent.copy(alpha = backA), 2.2.dp.toPx(), Offset(backX, botY))
                }
            }
            TotemMode.PROJECTING -> {
                if (wired) {
                    drawLine(
                        color = accent.copy(alpha = 0.16f * phase),
                        start = Offset(linkLeft, midY),
                        end = Offset(linkRight, midY),
                        strokeWidth = 8.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = accent.copy(alpha = 0.9f),
                        start = Offset(linkLeft, midY),
                        end = Offset(linkRight, midY),
                        strokeWidth = 2.2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                } else {
                    drawLine(
                        color = accent.copy(alpha = 0.14f * phase),
                        start = Offset(linkLeft, topY),
                        end = Offset(linkRight, topY),
                        strokeWidth = 6.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = accent.copy(alpha = 0.88f),
                        start = Offset(linkLeft, topY),
                        end = Offset(linkRight, topY),
                        strokeWidth = strokeStrong,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = accent.copy(alpha = 0.42f),
                        start = Offset(linkLeft, botY),
                        end = Offset(linkRight, botY),
                        strokeWidth = strokeThin,
                        cap = StrokeCap.Round,
                    )
                }
            }
            TotemMode.IDLE, TotemMode.PLUGGED_READY -> {
                if (wired || mode == TotemMode.PLUGGED_READY) {
                    drawLine(
                        color = accent.copy(alpha = 0.28f + 0.35f * breath),
                        start = Offset(linkLeft, midY),
                        end = Offset(linkRight, midY),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    val plugX = linkRight - (linkRight - linkLeft) * phase
                    val a = sin(phase * PI).toFloat().coerceIn(0f, 1f)
                    drawCircle(accent.copy(alpha = a * 0.85f), 2.8.dp.toPx(), Offset(plugX, midY))
                } else {
                    // 双平行无线轨 + 方向小箭标，克制且无歧义
                    drawLine(
                        color = accent.copy(alpha = 0.22f + 0.18f * breath),
                        start = Offset(linkLeft, topY),
                        end = Offset(linkRight, topY),
                        strokeWidth = strokeThin,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = accent.copy(alpha = 0.14f + 0.12f * breath),
                        start = Offset(linkLeft, botY),
                        end = Offset(linkRight, botY),
                        strokeWidth = strokeThin,
                        cap = StrokeCap.Round,
                    )
                    // 上轨左向轻柔光点（手机 → 车机）
                    val xTop = linkRight - (linkRight - linkLeft) * phase
                    val aTop = sin(phase * PI).toFloat().coerceIn(0f, 1f)
                    drawCircle(accent.copy(alpha = aTop * 0.22f), 5.5.dp.toPx(), Offset(xTop, topY))
                    drawCircle(accent.copy(alpha = aTop * 0.9f), 2.5.dp.toPx(), Offset(xTop, topY))

                    // 下轨右向微光点（车机 → 手机）
                    val pBot = (phase + 0.5f) % 1f
                    val xBot = linkLeft + (linkRight - linkLeft) * pBot
                    val aBot = sin(pBot * PI).toFloat().coerceIn(0f, 1f) * 0.55f
                    drawCircle(accent.copy(alpha = aBot), 2.dp.toPx(), Offset(xBot, botY))

                    // 上轨末端极细左向箭头提示投屏方向
                    val arrowSize = 3.2.dp.toPx()
                    chevronPath.rewind()
                    chevronPath.moveTo(linkLeft + arrowSize, topY - arrowSize)
                    chevronPath.lineTo(linkLeft, topY)
                    chevronPath.lineTo(linkLeft + arrowSize, topY + arrowSize)
                    drawPath(
                        path = chevronPath,
                        color = accent.copy(alpha = 0.45f + 0.25f * breath),
                        style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}
