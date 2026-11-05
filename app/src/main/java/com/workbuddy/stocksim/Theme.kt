package com.workbuddy.stocksim

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.ScalingLazyListState
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/* ============================================================
 * 主题系统 —— 移植自 llx 的 AppOpsPro (ThemeSeed + SeedColorScheme)
 * 由种子色在 Lab 空间生成整套官方色阶风格的深色配色。
 * ============================================================ */

/** 种子色选项 (Material 500 档)，与 AppOpsPro 一致。 */
enum class ThemeSeed(val id: String, val label: String, val seed: Color?) {
    Default("default", "默认", Color(0xFF8D9199)),  // 原始中性灰
    Purple("purple", "紫", Color(0xFF6750A4)),
    Indigo("indigo", "靛", Color(0xFF3F51B5)),
    Blue("blue", "蓝", Color(0xFF2196F3)),
    Cyan("cyan", "青", Color(0xFF00BCD4)),
    Teal("teal", "蓝绿", Color(0xFF009688)),
    Green("green", "绿", Color(0xFF4CAF50)),
    Lime("lime", "黄绿", Color(0xFFAFB42B)),
    Amber("amber", "琥珀", Color(0xFFFFC107)),
    Orange("orange", "橙", Color(0xFFFF9800)),
    DeepOrange("deep_orange", "橘红", Color(0xFFFF5722)),
    Red("red", "红", Color(0xFFF44336)),
    Pink("pink", "粉", Color(0xFFE91E63)),
    BlueGrey("blue_grey", "灰蓝", Color(0xFF607D8B));

    companion object {
        fun fromId(id: String?): ThemeSeed = entries.firstOrNull { it.id == id } ?: Blue
        val colorSeeds: List<ThemeSeed> get() = entries.filter { it.seed != null }
    }
}

/** 应用配色方案（组合期可通过状态读取，切换主题即时生效）。 */
class AppScheme(
    val bg: Color, val surf: Color, val surfHigh: Color,
    val txt: Color, val txtMid: Color, val txtDim: Color,
    val pri: Color, val onPri: Color, val priDim: Color,
    val sec: Color, val ter: Color, val onTer: Color,
    val colors: Colors,
)

/** 当前主题（持久化由 Store 负责）。 */
var ThemeCurrent by mutableStateOf(ThemeSeed.Blue)
var SchemeState by mutableStateOf(schemeFor(ThemeSeed.Blue))

fun applyTheme(seed: ThemeSeed) {
    ThemeCurrent = seed
    SchemeState = schemeFor(seed)
}

/** 由种子色生成整套配色。seed=null 时回落原始中性灰。 */
fun schemeFor(seed: ThemeSeed): AppScheme {
    val s = seed.seed
        ?: return AppScheme(
            bg = Color(0xFF0F0F10), surf = Color(0xFF232326), surfHigh = Color(0xFF2C2C2E),
            txt = Color(0xFFE3E2E6), txtMid = Color(0xFFC4C6CD), txtDim = Color(0xFF8D9199),
            pri = Color(0xFFA8C7FA), onPri = Color(0xFF062E6F), priDim = Color(0xFF7EAFD8),
            sec = Color(0xFFC4C6CD), ter = Color(0xFFFDD663), onTer = Color(0xFF2E2500),
            colors = Colors(
                primary = Color(0xFFA8C7FA), primaryVariant = Color(0xFF7EAFD8),
                onPrimary = Color(0xFF062E6F),
                secondary = Color(0xFFC4C6CD), secondaryVariant = Color(0xFF9AA0A6),
                onSecondary = Color(0xFF1B1C1E),
                background = Color(0xFF0F0F10), onBackground = Color(0xFFE3E2E6),
                surface = Color(0xFF0F0F10), onSurface = Color(0xFFE3E2E6),
                onSurfaceVariant = Color(0xFFC4C6CD),
                error = Color(0xFFF2B8B5), onError = Color(0xFF601410)
            )
        )

    val hue = seedHue(s)
    val hueT = (hue + 60f) % 360f
    val cP = 36f; val cS = 16f; val cT = 24f; val cN = 10f; val cNv = 16f

    val bg = labColor(10f, cN, hue)
    val surf = labColor(16f, cN, hue)
    val surfHigh = labColor(22f, cN, hue)
    val txt = labColor(92f, cN, hue)
    val txtMid = labColor(80f, cNv, hue)
    val txtDim = labColor(62f, cN, hue)
    val pri = labColor(90f, cP, hue)
    val onPri = labColor(10f, cP, hue)
    val priDim = labColor(80f, cP, hue)
    val sec = labColor(90f, cS, hue)
    val ter = labColor(90f, cT, hueT)
    val onTer = labColor(10f, cT, hueT)

    return AppScheme(
        bg = bg, surf = surf, surfHigh = surfHigh,
        txt = txt, txtMid = txtMid, txtDim = txtDim,
        pri = pri, onPri = onPri, priDim = priDim,
        sec = sec, ter = ter, onTer = onTer,
        colors = Colors(
            primary = pri, primaryVariant = priDim, onPrimary = onPri,
            secondary = sec, secondaryVariant = labColor(80f, cS, hue),
            onSecondary = labColor(10f, cS, hue),
            background = bg, onBackground = txt,
            surface = bg, onSurface = txt,
            onSurfaceVariant = txtMid,
            error = Color(0xFFF2B8B5), onError = Color(0xFF601410)
        )
    )
}

/* ---- Lab ↔ sRGB 数学 (与 AppOpsPro 同款实现, D65 白点) ---- */

private const val XN = 0.95047f
private const val YN = 1.0f
private const val ZN = 1.08883f

internal fun seedHue(seed: Color): Float {
    val r = srgbToLinear(seed.red); val g = srgbToLinear(seed.green); val b = srgbToLinear(seed.blue)
    val x = 0.4124f * r + 0.3576f * g + 0.1805f * b
    val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
    val z = 0.0193f * r + 0.1192f * g + 0.9505f * b
    val a = 500f * (fLab(x / XN) - fLab(y / YN))
    val bb = 200f * (fLab(y / YN) - fLab(z / ZN))
    return (atan2(bb, a) * 180f / PI.toFloat() + 360f) % 360f
}

internal fun labColor(tone: Float, chroma: Float, hueDeg: Float): Color {
    var c = chroma
    while (c > 0.5f) {
        val rgb = labToRgb(tone, c, hueDeg)
        if (rgb[0] in 0f..1f && rgb[1] in 0f..1f && rgb[2] in 0f..1f)
            return Color(rgb[0], rgb[1], rgb[2])
        c -= 1f
    }
    val rgb = labToRgb(tone, 0f, hueDeg)
    return Color(rgb[0].coerceIn(0f, 1f), rgb[1].coerceIn(0f, 1f), rgb[2].coerceIn(0f, 1f))
}

private fun labToRgb(tone: Float, chroma: Float, hueDeg: Float): FloatArray {
    val rad = hueDeg * PI.toFloat() / 180f
    val a = chroma * cos(rad); val b = chroma * sin(rad)
    val fy = (tone + 16f) / 116f
    val fx = fy + a / 500f; val fz = fy - b / 200f
    val x = XN * fLabInv(fx); val y = YN * fLabInv(fy); val z = ZN * fLabInv(fz)
    return floatArrayOf(
        linearToSrgb(3.2406f * x - 1.5372f * y - 0.4986f * z),
        linearToSrgb(-0.9689f * x + 1.8758f * y + 0.0415f * z),
        linearToSrgb(0.0557f * x - 0.2040f * y + 1.0570f * z)
    )
}

private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(c: Float): Float =
    if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f

private fun fLab(t: Float): Float =
    if (t > 0.008856f) cbrt(t) else (7.787f * t + 16f / 116f)

private fun fLabInv(t: Float): Float {
    val t3 = t * t * t
    return if (t3 > 0.008856f) t3 else (t - 16f / 116f) / 7.787f
}

/* ============================================================
 * 堆叠卡片列表 —— 移植自 AppOpsPro 的 StackedShadow 思路
 * 卡片向上滚出（越过中线往屏顶）时阴影渐强，视觉上"浮"起来
 * 压住下方卡片；下方新滑出的卡片保持零阴影。
 * 适配 ScalingLazyColumn：从 layoutInfo 的可见项信息取位置。
 * ============================================================ */

@Composable
fun StackedCard(
    listState: ScalingLazyListState,
    index: Int,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val li = listState.layoutInfo
    val info = li.visibleItemsInfo.firstOrNull { it.index == index }
    val vh = (li.viewportEndOffset - li.viewportStartOffset).toFloat().takeIf { it > 0f } ?: 1f
    // 顶部相对视口比例: 0=屏顶 1=屏底
    val top = if (info != null) ((info.offset - li.viewportStartOffset).toFloat() / vh) else 1f
    val rise = ((0.42f - top) / 0.42f).coerceIn(0f, 1f)
    val strength = rise * rise * (3f - 2f * rise)  // smoothstep
    val cardShape: Shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .graphicsLayer {
                shadowElevation = (strength * 18f).dp.toPx()
                this.shape = cardShape
                clip = false
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
            }
            .clip(cardShape)
            .background(SchemeState.surfHigh)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart
    ) { content() }
}
