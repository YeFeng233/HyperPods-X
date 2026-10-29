// Adapted from NEORUAA/HyperPods-X PR #1 (ae9fa5e), GPL-3.0-or-later.
package com.hyper.podsrx.ui.ios

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font as NativeFont
import android.graphics.fonts.FontFamily as NativeFontFamily
import android.graphics.fonts.FontStyle as NativeFontStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.glasmioTypography
import io.github.neoruaa.glasmio.icons.GlasmioIconProvider
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All variations share the same process-owned native font buffers. */
data class HyperPodsFontAssets(val symbolTypeface: Typeface?, val typography: Typography)

object HyperPodsFonts {
    private var assets: HyperPodsFontAssets? = null

    @Synchronized fun load(context: Context): HyperPodsFontAssets {
        assets?.let { return it }
        val regular = try {
            NativeFont.Builder(context.applicationContext.assets, "sf_pro.ttf").build()
        } catch (_: IOException) {
            return HyperPodsFontAssets(null, glasmioTypography()).also { assets = it }
        }
        val italic = try {
            NativeFont.Builder(context.applicationContext.assets, "sf_pro_italic.ttf").build()
        } catch (_: IOException) { null }
        return HyperPodsFontAssets(typeface(regular), typography(regular, italic)).also { assets = it }
    }

    private fun typeface(font: NativeFont): Typeface =
        Typeface.CustomFallbackBuilder(NativeFontFamily.Builder(font).build()).setStyle(font.style).build()

    private fun typography(regular: NativeFont, italic: NativeFont?): Typography {
        val base = glasmioTypography()
        val families = mutableMapOf<Float, FontFamily>()
        val typefaces = mutableMapOf<Triple<Float, Float, FontStyle>, Typeface>()
        val weights = listOf(400 to 400f, 500 to 510f, 510 to 510f, 590 to 590f, 591 to 590.7999878f, 600 to 590f, 700 to 700f)
        val styles = if (italic == null) listOf(FontStyle.Normal) else listOf(FontStyle.Normal, FontStyle.Italic)
        fun resolve(style: TextStyle): TextStyle {
            val opticalSize = style.fontSize.value.coerceIn(17f, 28f)
            val family = families.getOrPut(opticalSize) {
                FontFamily(weights.flatMap { (request, axis) ->
                    styles.map { slant ->
                        val (weight, yAxis) = if (slant == FontStyle.Italic) when (request) {
                            500, 510 -> 508f to 436f
                            590, 591, 600 -> 590.800003f to 419.199997f
                            700 -> 700f to 430f
                            else -> axis to 400f
                        } else axis to 400f
                        val face = typefaces.getOrPut(Triple(weight, opticalSize, slant)) {
                            val source = if (slant == FontStyle.Italic) requireNotNull(italic) else regular
                            val variation = "'wght' $weight, 'opsz' $opticalSize" +
                                if (slant == FontStyle.Italic) ", 'YAXS' $yAxis" else ""
                            typeface(NativeFont.Builder(source).setWeight(weight.roundToInt())
                                .setSlant(if (slant == FontStyle.Italic) NativeFontStyle.FONT_SLANT_ITALIC else NativeFontStyle.FONT_SLANT_UPRIGHT)
                                .setFontVariationSettings(variation).build())
                        }
                        SharedNativeFont(FontWeight(request), face, slant)
                    }
                })
            }
            return style.copy(fontFamily = family)
        }
        return base.copy(
            displayLarge = resolve(base.displayLarge), displayMedium = resolve(base.displayMedium), displaySmall = resolve(base.displaySmall),
            headlineLarge = resolve(base.headlineLarge), headlineMedium = resolve(base.headlineMedium), headlineSmall = resolve(base.headlineSmall),
            titleLarge = resolve(base.titleLarge), titleMedium = resolve(base.titleMedium), titleSmall = resolve(base.titleSmall),
            bodyLarge = resolve(base.bodyLarge), bodyMedium = resolve(base.bodyMedium), bodySmall = resolve(base.bodySmall),
            labelLarge = resolve(base.labelLarge), labelMedium = resolve(base.labelMedium), labelSmall = resolve(base.labelSmall),
        )
    }
}

private class SharedNativeFont(
    override val weight: FontWeight,
    val face: Typeface,
    override val style: FontStyle,
) : AndroidFont(FontLoadingStrategy.Blocking, SharedNativeFontLoader, FontVariation.Settings())

private object SharedNativeFontLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface = (font as SharedNativeFont).face
    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface = loadBlocking(context, font)
}

@Composable
fun GlasmioAppTheme(isDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val resolver = LocalFontFamilyResolver.current
    val assets by produceState<HyperPodsFontAssets?>(null, context, resolver) {
        value = withContext(Dispatchers.IO) {
            HyperPodsFonts.load(context).also { loaded ->
                val typography = loaded.typography
                listOf(typography.displayLarge, typography.displayMedium, typography.displaySmall,
                    typography.headlineLarge, typography.headlineMedium, typography.headlineSmall,
                    typography.titleLarge, typography.titleMedium, typography.titleSmall,
                    typography.bodyLarge, typography.bodyMedium, typography.bodySmall,
                    typography.labelLarge, typography.labelMedium, typography.labelSmall)
                    .mapNotNull { it.fontFamily }.distinct().forEach { resolver.preload(it) }
            }
        }
    }
    val loaded = assets
    if (loaded == null) {
        Box(Modifier.background(if (isDark) Color.Black else Color(0xfff2f2f7)))
    } else {
        GlasmioTheme(isDark = isDark, typography = loaded.typography, letterSpacing = .2.sp) {
            GlasmioIconProvider(symbolTypeface = loaded.symbolTypeface, content = content)
        }
    }
}
