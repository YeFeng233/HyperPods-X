package com.hyper.podsrx.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyper.podsrx.BuildConfig
import com.hyper.podsrx.R
import com.hyper.podsrx.i18n.rememberStrings
import com.hyper.podsrx.ui.effect.AboutBackground
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val AboutCardLight = listOf(
    BlendColorEntry(Color(0x340034f9), BlurBlendMode.Overlay),
    BlendColorEntry(Color(0xb3ffffff), BlurBlendMode.HardLight),
)
private val AboutCardDark = listOf(
    BlendColorEntry(Color(0x4da9a9a9), BlurBlendMode.Luminosity),
    BlendColorEntry(Color(0x1a9c9c9c), BlurBlendMode.PlusDarker),
)

@Composable
internal fun AboutPage(wide: Boolean, railInset: () -> Dp, onBack: () -> Unit, onLicenses: () -> Unit) {
    val strings = rememberStrings()
    val list = rememberLazyListState()
    val backdrop = rememberLayerBackdrop()
    val blur = isRuntimeShaderSupported()
    val dark = isSystemInDarkTheme()
    val heroHeight = if (wide) 200.dp else 302.dp
    val heroPx = with(LocalDensity.current) { heroHeight.toPx() }
    val progress = remember(list, heroPx) {
        derivedStateOf {
            if (list.firstVisibleItemIndex == 0) (list.firstVisibleItemScrollOffset / heroPx).coerceIn(0f, 1f) else 1f
        }
    }
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val uri = LocalUriHandler.current
    val blends = if (dark) AboutCardDark else AboutCardLight

    MiuixPageScaffold(
        title = strings(R.string.about),
        expandable = false,
        railInset = railInset,
        onBack = onBack,
        startsWithHeading = true,
        scrollState = list,
        titleAlpha = { ((progress.value - 0.35f) / 0.65f).coerceIn(0f, 1f) },
        barAlpha = { progress.value },
        background = { padding ->
            if (blur) AboutBackground(backdrop, wide) { 1f - progress.value }
            Column(
                Modifier.fillMaxWidth().railInset(railInset)
                    .padding(top = padding.calculateTopPadding() + if (wide) 24.dp else 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(R.drawable.ic_app_mark), contentDescription = null,
                    modifier = Modifier.size(if (wide) 62.dp else 88.dp).aboutFade({ progress.value }, .35f),
                    tint = MiuixTheme.colorScheme.onBackground)
                Text(strings(R.string.app_name), fontSize = 35.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp).aboutFade({ progress.value }, .20f))
                Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 5.dp).aboutFade({ progress.value }, .05f))
            }
        },
    ) {
        item("about.hero") { Spacer(Modifier.height(heroHeight)) }
        item("about.source") {
            Box {
                Spacer(Modifier.fillParentMaxHeight())
                Column(Modifier.fillMaxWidth()) {
                    SmallTitle(strings(R.string.open_source_projects))
                    val material = if (blur) Modifier.textureBlur(
                        backdrop = backdrop, shape = RoundedCornerShape(16.dp), blurRadius = 60f,
                        colors = BlurColors(blendColors = blends),
                    ) else Modifier
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp).then(material),
                        insideMargin = PaddingValues(0.dp),
                        colors = CardDefaults.defaultColors(if (blur) Color.Transparent else surface, Color.Transparent)) {
                        RowItem(strings(R.string.app_name)) {
                            uri.openUri("https://github.com/YeFeng233/HyperPods-X")
                        }
                        RowItem("miuix") {
                            uri.openUri("https://github.com/compose-miuix-ui/miuix")
                        }
                        RowItem(strings(R.string.open_source_licenses), action = onLicenses)
                    }
                }
            }
        }
    }
}

private fun Modifier.aboutFade(progress: () -> Float, start: Float): Modifier = graphicsLayer {
    val fraction = ((progress() - start) / .15f).coerceIn(0f, 1f)
    alpha = 1f - fraction
    translationY = -size.height * fraction * .2f
    scaleX = 1f - fraction * .05f
    scaleY = scaleX
}
