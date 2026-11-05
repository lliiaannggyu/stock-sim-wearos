package com.workbuddy.stocksim

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/* ============================================================
 * 主题系统 —— 移植自 AppOpsPro (ThemeSeed + SeedColorScheme + Theme)
 * Wear Compose Material 3 (M3E) ColorScheme,
 * 由种子色在 Lab 空间生成整套官方色阶风格的深色配色。
 * ============================================================ */

/** 种子色选项 (Material 500 档)，与 AppOpsPro 一致。seed=null 表示默认中性灰。 */
enum class ThemeSeed(val id: String, val label: String, val seed: Color?) {
    Default("default", "默认", null),
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

/** 当前主题（持久化由 Store 负责），切换时整棵树重组换色。 */
var ThemeCurrent by mutableStateOf(ThemeSeed.Blue)

fun applyTheme(s: ThemeSeed) { ThemeCurrent = s }

/**
 * M3E 主题包装 (对应 AppOpsPro 的 AppOpsProTheme)。
 * Default → 中性灰 defaultWearColorScheme；其余种子色 → colorSchemeFromSeed。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val seed = ThemeCurrent
    val scheme = remember(seed) { colorSchemeFromSeed(seed.seed) }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * API 25+ 回退配色（Wear OS 深色基调，来自 AppOpsPro defaultWearColorScheme）。
 * Wear M3 的 ColorScheme 没有 surface/surfaceVariant 参数，
 * 用 surfaceContainer / surfaceContainerLow / surfaceContainerHigh 替代。
 * 也是 colorSchemeFromSeed 的中性底：只替换强调色族，表面色沿用中性灰。
 */
internal fun defaultWearColorScheme(): ColorScheme {
    return ColorScheme(
        primary = Color(0xFFA8C7FA),
        onPrimary = Color(0xFF00315D),
        primaryContainer = Color(0xFF004A77),
        onPrimaryContainer = Color(0xFFD3E3FD),
        secondary = Color(0xFFBFC6DC),
        onSecondary = Color(0xFF293041),
        secondaryContainer = Color(0xFF3F4759),
        onSecondaryContainer = Color(0xFFDBE2F9),
        tertiary = Color(0xFFDDBCE0),
        onTertiary = Color(0xFF3F2844),
        tertiaryContainer = Color(0xFF573E5C),
        onTertiaryContainer = Color(0xFFFAD7FC),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        background = Color(0xFF1B1B1F),
        onBackground = Color(0xFFE3E2E6),
        surfaceContainerLow = Color(0xFF1B1B1F),
        surfaceContainer = Color(0xFF1B1B1F),
        surfaceContainerHigh = Color(0xFF2B2B2F),
        onSurface = Color(0xFFE3E2E6),
        onSurfaceVariant = Color(0xFFC4C6D0),
        outline = Color(0xFF8E9099),
    )
}

private const val CHROMA_PRIMARY = 36f
private const val CHROMA_SECONDARY = 16f
private const val CHROMA_TERTIARY = 24f
private const val CHROMA_NEUTRAL = 10f
private const val CHROMA_NEUTRAL_VARIANT = 16f

/** 由种子色生成完整 ColorScheme（深色，TonalSpot + 同色系中性表面）。 */
fun colorSchemeFromSeed(seed: Color?): ColorScheme {
    if (seed == null) return defaultWearColorScheme()
    val hue = seedHue(seed)
    val hueT = (hue + 60f) % 360f
    return defaultWearColorScheme().copy(
        // ── 强调色族（TonalSpot）──
        primary = labColor(90f, CHROMA_PRIMARY, hue),
        primaryDim = labColor(80f, CHROMA_PRIMARY, hue),
        primaryContainer = labColor(30f, CHROMA_PRIMARY, hue),
        onPrimary = labColor(10f, CHROMA_PRIMARY, hue),
        onPrimaryContainer = labColor(95f, CHROMA_PRIMARY, hue),

        secondary = labColor(90f, CHROMA_SECONDARY, hue),
        secondaryDim = labColor(80f, CHROMA_SECONDARY, hue),
        secondaryContainer = labColor(30f, CHROMA_SECONDARY, hue),
        onSecondary = labColor(10f, CHROMA_SECONDARY, hue),
        onSecondaryContainer = labColor(95f, CHROMA_SECONDARY, hue),

        tertiary = labColor(90f, CHROMA_TERTIARY, hueT),
        tertiaryDim = labColor(80f, CHROMA_TERTIARY, hueT),
        tertiaryContainer = labColor(30f, CHROMA_TERTIARY, hueT),
        onTertiary = labColor(10f, CHROMA_TERTIARY, hueT),
        onTertiaryContainer = labColor(95f, CHROMA_TERTIARY, hueT),

        // ── 中性表面（带种子色相，低彩度）──
        background = labColor(10f, CHROMA_NEUTRAL, hue),
        onBackground = labColor(92f, CHROMA_NEUTRAL, hue),
        surfaceContainerLow = labColor(10f, CHROMA_NEUTRAL, hue),
        surfaceContainer = labColor(13f, CHROMA_NEUTRAL, hue),
        surfaceContainerHigh = labColor(18f, CHROMA_NEUTRAL, hue),
        onSurface = labColor(92f, CHROMA_NEUTRAL, hue),
        onSurfaceVariant = labColor(80f, CHROMA_NEUTRAL_VARIANT, hue),
        outline = labColor(60f, CHROMA_NEUTRAL_VARIANT, hue),
        outlineVariant = labColor(30f, CHROMA_NEUTRAL_VARIANT, hue),
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
