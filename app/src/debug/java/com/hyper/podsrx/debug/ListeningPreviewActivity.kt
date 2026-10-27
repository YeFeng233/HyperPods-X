package com.hyper.podsrx.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.ui.ListeningControls
import com.hyper.podsrx.ui.GlasmioAppTheme
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlasmioBackdropProvider
import io.github.neoruaa.glasmio.glass.glassBackdropSource
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Isolated layout fixture. Never updates the repository or sends hardware commands. */
class ListeningPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = intent.getBooleanExtra("dark", false)
            var state by remember { mutableStateOf(PodsState(connected = true,
                controls = mapOf(0x0d to listOf(4), 0x2e to listOf(50)))) }
            var writes by remember { mutableIntStateOf(0) }
            val scale = intent.getFloatExtra("font", 1f).coerceIn(1f, 2f)
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalLayoutDirection provides if (intent.getBooleanExtra("rtl", false)) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                GlasmioAppTheme(isDark=dark) {
                    val colors=LocalGlassColors.current
                    val backdrop=rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.matchParentSize().glassBackdropSource(backdrop).background(colors.groupedBackground))
                        GlasmioBackdropProvider(backdrop) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("聆听布局预览", style=IosTypography.title1, color=colors.content)
                        Text("模拟数据 · 不连接耳机", style=IosTypography.footnote, color=colors.secondaryContent)
                        Column(Modifier.widthIn(max = intent.getIntExtra("width", 600).coerceIn(280, 600).dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            ListeningControls(state,
                                onModeChange = { state = state.copy(controls = state.controls + (0x0d to listOf(it))) },
                                onStrengthChange = { writes++; state = state.copy(controls = state.controls + (0x2e to listOf(it))) })
                            Text("模拟提交 $writes 次 · 0x2e = ${state.controls[0x2e]?.firstOrNull()}", style=IosTypography.footnote, color=colors.secondaryContent)
                            Text("读取中", style=IosTypography.body, color=colors.content)
                            ListeningControls(state.copy(controls = mapOf(0x0d to listOf(4))), onModeChange = {}, onStrengthChange = {})
                            Text("断开连接", style=IosTypography.body, color=colors.content)
                            ListeningControls(PodsState(), onModeChange = {}, onStrengthChange = {})
                        }
                    }
                        }
                    }
                }
            }
        }
    }
}
