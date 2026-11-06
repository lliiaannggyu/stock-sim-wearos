package com.workbuddy.stocksim

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.LocalContentColor

/* ============================================================
 * 堆叠层次感 —— 移植自 AppOpsPro 的 StackedShadow.kt
 * (参考实现: 鹿了么 M3eList.kt 的 stackedShadow)
 *
 * 思路：不改底色，只给**被叠住的上方卡片**叠一层投影阴影 ——
 * 卡片越往上滚出，投影越重，视觉上"浮"起来压住下面的卡片；
 * 从下方新滑出来的卡片保持零阴影。
 *
 * 位置数据来自 TransformingLazyColumnItemScope 在 graphicsLayer 里注入的
 * scrollProgress（item 相对视口比例），不在组合期读任何 state，
 * 因此每帧实时跟随，也不会与 transformedHeight 的收窄打架。
 * ============================================================ */

/** 卡片滚到屏顶时的最大阴影高度（dp 值）。深色底上黑色阴影偏弱，故取值偏大。 */
private const val STACKED_SHADOW_MAX_ELEVATION = 18f

/**
 * 堆叠层次感：**被叠住的上方卡片加投影阴影、下方保持不变**。
 * 必须在 TransformingLazyColumn 的 item 内部调用（需要 ItemScope 的 scrollProgress）。
 *
 * 阴影规则（单向上方）：
 *  - 卡片顶部 top >= 0.42（还在中线附近及以下）→ 零阴影；
 *  - top < 0.42（越过中线往上方滚出）→ 越往上阴影越重，屏顶达到 maxElevation。
 */
fun TransformingLazyColumnItemScope.stackedShadow(
    shape: Shape,
    shadowColor: Color = Color.Black,
    maxElevation: Float = STACKED_SHADOW_MAX_ELEVATION,
): Modifier {
    val scope = this
    return Modifier.graphicsLayer {
        val top = resolveTopFraction(scope.run { this@graphicsLayer.scrollProgress })
        shadowElevation = if (top < 0f) 0f else (shadowStrength(top) * maxElevation).dp.toPx()
        this.shape = shape
        clip = false
        ambientShadowColor = shadowColor
        spotShadowColor = shadowColor
    }
}

/** 由「卡片顶部相对视口比例」解出阴影强度（0..1，单向上方，smoothstep）。 */
private fun shadowStrength(top: Float): Float {
    val rise = ((0.42f - top) / 0.42f).coerceIn(0f, 1f)
    return rise * rise * (3f - 2f * rise)
}

/** 从官方进度值解出「卡片顶部相对视口的比例」，不可用时返回 -1f 哨兵。 */
private fun resolveTopFraction(
    p: androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScrollProgress,
): Float {
    if (p.isUnspecified) return -1f
    val t = p.topOffsetFraction
    return if (t.isFinite()) t else -1f
}

/* ============================================================
 * BarCard —— 卡片形状与规格
 * 卡片统一为常规圆角矩形 (14dp), 不再做跑道形胶囊。
 * 配合 transformedHeight 使用, 堆叠过渡是「整行平滑收窄 + 变暗」。
 * ============================================================ */

/** 卡片圆角: 常规圆角矩形 */
val BarCapsuleShape = RoundedCornerShape(14.dp)

/** 条形胶囊高度：与 M3E 条形卡片一致（52dp）。 */
val BarCapsuleHeight = 52.dp

/**
 * 自绘条形胶囊卡片 (AppOpsPro 同款)。
 *
 * 不用 M3E 的 Button/Chip：复合组件自带容器绘制，放进 TransformingLazyColumn
 * 会和列表的 transformedHeight 叠加导致堆叠生硬。卡片背景完全自绘。
 *
 * 内部自动叠一层 [stackedShadow]：只有"往上滚、被上方叠住"的行卡浮起投影。
 */
@Composable
fun TransformingLazyColumnItemScope.BarCapsule(
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    secondaryContentColor: Color = contentColor,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    label: @Composable () -> Unit,
    secondaryLabel: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            // 堆叠层次感：在底色之前叠投影，沿卡片轮廓投射、不压内容。
            .then(stackedShadow(BarCapsuleShape))
            .fillMaxWidth()
            .height(BarCapsuleHeight)
            .clip(BarCapsuleShape)
            .background(containerColor)
            .then(clickModifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier.size(22.dp),
                contentAlignment = Alignment.Center
            ) {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    leading()
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (leading != null) 8.dp else 0.dp)
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                label()
            }
            if (secondaryLabel != null) {
                CompositionLocalProvider(LocalContentColor provides secondaryContentColor) {
                    secondaryLabel()
                }
            }
        }

        if (trailing != null) {
            Box(
                modifier = Modifier.padding(start = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    trailing()
                }
            }
        }
    }
}

/**
 * 多行高卡片（用于持仓/成交等需要三行内容的条目）。
 * 与 BarCapsule 同一套视觉（同形状/底色/阴影），但高度自适应、圆角固定。
 */
@Composable
fun TransformingLazyColumnItemScope.StackedPane(
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .then(stackedShadow(BarCapsuleShape))
            .fillMaxWidth()
            .clip(BarCapsuleShape)
            .background(containerColor)
            .then(clickModifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}
