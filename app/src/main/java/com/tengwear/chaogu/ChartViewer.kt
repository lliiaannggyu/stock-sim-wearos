package com.tengwear.chaogu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 图表视图变换: scale 缩放倍数, pan 平移像素。
 * 绘制坐标 p 映射到屏幕: p * scale + pan
 */
class ChartView {
    val scale = Animatable(1f)
    val pan = Animatable(Offset.Zero, Offset.VectorConverter)

    /**
     * 绘制模型统一为「左上角原点」: 屏幕坐标 = 内容坐标 * s + pan (pivot=0)。
     * 因此以屏幕点 point 为锚缩放时, 保持其下方内容点不动:
     *   pan' = point * (1 - ratio) + pan * ratio
     * (旧代码错误地减去了中心 c, 那是 pivot=中心 的公式, 与 pivot=0 的绘制不匹配,
     *  导致放大后图像被推到屏幕外 —— 表现为「放大后加载不出图像」。)
     */
    suspend fun transform(point: Offset, panDelta: Offset, zoomFactor: Float, size: Size) {
        val old = scale.value
        val target = (old * zoomFactor).coerceIn(1f, MAX_SCALE)
        val ratio = if (old > 0f) target / old else 1f
        val nt = point * (1f - ratio) + pan.value * ratio + panDelta
        scale.snapTo(target)
        pan.snapTo(clampPan(nt, size, target))
    }

    /** 以屏幕上 point 为中心平滑缩放 (point 为相对 Canvas 左上角的绝对坐标) */
    suspend fun zoomAround(point: Offset, canvasSize: Size, newScale: Float, animated: Boolean) {
        val old = scale.value
        val target = newScale.coerceIn(1f, MAX_SCALE)
        val ratio = if (old > 0f) target / old else 1f
        val nt = point * (1f - ratio) + pan.value * ratio
        if (animated) {
            coroutineScope {
                launch { scale.animateTo(target, spring()) }
                launch { pan.animateTo(clampPan(nt, canvasSize, target), spring()) }
            }
        } else {
            scale.snapTo(target)
            pan.snapTo(clampPan(nt, canvasSize, target))
        }
    }

    /**
     * 限制平移: 内容绘制在 [0,W]x[0,H], 放大 s 倍后屏幕宽度为 W*s。
     * 要能把内容「右边缘」拖到屏幕右缘 => pan.x = -W*(s-1);
     * 要能把内容「左边缘」拖到屏幕左缘 => pan.x = 0。
     * 所以合法区间是 [-W*(s-1), 0] (单向!), 再加少量回弹余量。
     * 旧代码用对称区间 ±W*(s-1)/2, 只能拖到一半 —— 即「限位无法滑到边缘」。
     */
    private fun clampPan(p: Offset, size: Size, s: Float): Offset {
        val minX = -size.width * (s - 1f) - size.width * 0.10f
        val minY = -size.height * (s - 1f) - size.height * 0.10f
        val maxX = size.width * 0.10f
        val maxY = size.height * 0.10f
        return Offset(p.x.coerceIn(minX, maxX), p.y.coerceIn(minY, maxY))
    }

    suspend fun reset() {
        coroutineScope {
            launch { scale.animateTo(1f, spring()) }
            launch { pan.animateTo(Offset.Zero, spring()) }
        }
    }

    companion object {
        const val MAX_SCALE = 24f
        val STEPS = floatArrayOf(1f, 2f, 4f, 8f, 16f)
    }
}

/** 双击后的下一档缩放; 到顶则回到 1x (重置) */
fun nextStep(current: Float): Float {
    for (s in ChartView.STEPS) if (s > current + 0.15f) return s
    return 1f
}

/**
 * 全屏图表手势层: 双击放大 / 三击退出 / 双指缩放+平移 / 表冠缩放 / 单指拖拽平移。
 * 内部 Canvas 填满该 Box, 故 pointer 坐标与 Canvas 坐标一致。
 */
@Composable
fun ChartGestureBox(
    view: ChartView,
    onExit: () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val canvasSize = remember { androidx.compose.runtime.mutableStateOf(Size.Zero) }
    val lastDouble = remember { mutableLongStateOf(0L) }

    Box(
        Modifier
            .fillMaxSize()
            // 表冠无级缩放 (以触点/视图中心为锚)
            // 三星/部分国产表表冠产生的是 horizontalScrollPixels 而非 vertical,
            // 故两个分量都读, 取绝对值较大者, 否则三星表上表冠完全无反应。
            .onRotaryScrollEvent { ev ->
                val v = ev.verticalScrollPixels
                val h = ev.horizontalScrollPixels
                val px = if (kotlin.math.abs(h) > kotlin.math.abs(v)) h else v
                val factor = (1f + px / 180f).coerceIn(0.78f, 1.28f)
                if (canvasSize.value != Size.Zero) {
                    val c = Offset(canvasSize.value.width / 2f, canvasSize.value.height / 2f)
                    scope.launch { view.transform(c, Offset.Zero, factor, canvasSize.value) }
                }
                true
            }
            // 双击放大 / 三击退出
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        lastDouble.value = System.currentTimeMillis()
                        val sz = canvasSize.value
                        if (sz != Size.Zero) {
                            val target = nextStep(view.scale.value)
                            scope.launch { view.zoomAround(pos, sz, target, animated = true) }
                        }
                    },
                    onTap = {
                        if (System.currentTimeMillis() - lastDouble.value < 500L) {
                            lastDouble.value = 0L
                            onExit()
                        }
                    }
                )
            }
            // 双指缩放+平移 / 单指拖拽平移
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val sz = canvasSize.value
                    if (sz != Size.Zero) {
                        scope.launch { view.transform(centroid, pan, zoom, sz) }
                    }
                }
            }
            .onSizeChanged { canvasSize.value = Size(it.width.toFloat(), it.height.toFloat()) }
    ) {
        content()
    }
}

/** 全屏分时图: 矢量缩放 */
@Composable
fun FullscreenMinute(
    data: List<Float>?, vols: List<Float>?, prevClose: Double, view: ChartView,
    modifier: Modifier
) {
    Canvas(modifier) {
        val s = view.scale.value
        val pan = view.pan.value
        translate(pan.x, pan.y) {
            scale(s, pivot = Offset.Zero) {
                drawMinuteContent(this, data, vols, prevClose, size)
            }
        }
    }
}

/** 全屏 K 线图 */
@Composable
fun FullscreenCandle(data: List<Candle>?, view: ChartView, modifier: Modifier) {
    Canvas(modifier) {
        val s = view.scale.value
        val pan = view.pan.value
        translate(pan.x, pan.y) {
            scale(s, pivot = Offset.Zero) {
                drawCandleContent(this, data, size)
            }
        }
    }
}

/** 分时图绘制核心 (无 padding, 由 Canvas size 决定) */
fun drawMinuteContent(
    scope: DrawScope, data: List<Float>?, vols: List<Float>?, prevClose: Double, size: Size
) = with(scope) {
    if (data == null || data.size < 2 || prevClose <= 0) return@with
    val base = prevClose.toFloat()
    val mn = minOf(data.min(), base)
    val mx = maxOf(data.max(), base)
    val rng = (mx - mn).takeIf { it > 0 } ?: 1f
    val volH = size.height * 0.24f
    val priceH = size.height - volH
    fun y(v: Float) = priceH - 8f - (v - mn) / rng * (priceH - 16f)
    val col = if (data.last() >= base) Up else Down
    // 昨收基准虚线
    drawLine(
        col.copy(alpha = 0.5f), Offset(0f, y(base)), Offset(size.width, y(base)),
        strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    )
    val path = Path()
    data.forEachIndexed { i, v ->
        val x = i.toFloat() / (data.size - 1) * size.width
        if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
    }
    drawPath(path, col, style = Stroke(3f, cap = StrokeCap.Round))
    val fill = Path().apply {
        addPath(path); lineTo(size.width, priceH); lineTo(0f, priceH); close()
    }
    drawPath(fill, col.copy(alpha = 0.14f))
    drawCircle(col, radius = 5f, center = Offset(size.width, y(data.last())))
    // 成交量
    if (vols != null && vols.isNotEmpty()) {
        val maxVol = vols.max().takeIf { it > 0 } ?: 1f
        val bw = size.width / data.size * 0.7f
        data.indices.forEach { i ->
            val x = i.toFloat() / maxOf(1, data.size - 1) * (size.width - bw)
            val h = (vols[i] / maxVol) * (volH - 8f)
            val c = if (i > 0 && data[i] < data[i - 1]) Down else Up
            drawRect(c.copy(alpha = 0.5f), Offset(x, size.height - h), Size(bw, h))
        }
    }
}

/** K 线绘制核心 */
fun drawCandleContent(scope: DrawScope, data: List<Candle>?, size: Size) = with(scope) {
    if (data.isNullOrEmpty()) return@with
    val volH = size.height * 0.22f
    val priceH = size.height - volH
    val hi = data.maxOf { it.high }; val lo = data.minOf { it.low }
    val rng = (hi - lo).takeIf { it > 0 } ?: 1f
    fun y(v: Float) = priceH - 6f - (v - lo) / rng * (priceH - 14f)
    val slot = size.width / data.size
    val bw = (slot * 0.62f).coerceAtLeast(1.5f)
    val maxVol = data.maxOf { it.vol }.takeIf { it > 0 } ?: 1f
    data.forEachIndexed { i, c ->
        val cx = i * slot + slot / 2
        val up = c.close >= c.open
        val col = if (up) Up else Down
        drawLine(col, Offset(cx, y(c.high)), Offset(cx, y(c.low)), strokeWidth = 1.5f)
        val top = y(maxOf(c.open, c.close)); val bot = y(minOf(c.open, c.close))
        drawRect(col, Offset(cx - bw / 2, top), Size(bw, (bot - top).coerceAtLeast(2f)))
        val vh = c.vol / maxVol * (volH - 6f)
        drawRect(col.copy(alpha = 0.5f), Offset(cx - bw / 2, size.height - vh), Size(bw, vh))
    }
    drawLine(
        Up.copy(alpha = 0.5f), Offset(0f, y(data.last().close)),
        Offset(size.width, y(data.last().close)), strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
    )
}
