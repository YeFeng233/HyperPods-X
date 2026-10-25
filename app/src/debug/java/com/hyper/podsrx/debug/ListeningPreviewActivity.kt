package com.hyper.podsrx.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyper.podsrx.device.PodsState
import com.hyper.podsrx.ui.ListeningControls
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Isolated layout fixture. Never updates the repository or sends hardware commands. */
class ListeningPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = intent.getBooleanExtra("dark", false)
            val colors = if (dark) darkColorScheme(primary = Color(0xff80acff), background = Color(0xff141416),
                surface = Color(0xff252528), surfaceContainer = Color(0xff252528))
            else lightColorScheme(primary = Color(0xff4285f4), background = Color(0xfff2f2f6), surface = Color.White,
                surfaceContainer = Color.White, onSurfaceSecondary = Color(0xff77777e))
            var state by remember { mutableStateOf(PodsState(connected = true,
                controls = mapOf(0x0d to listOf(4), 0x2e to listOf(50)))) }
            var writes by remember { mutableIntStateOf(0) }
            val scale = intent.getFloatExtra("font", 1f).coerceIn(1f, 2f)
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalLayoutDirection provides if (intent.getBooleanExtra("rtl", false)) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MiuixTheme(colors = colors) {
                    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("聆听布局预览", fontSize = 28.sp, color = colors.primary)
                        Text("模拟数据 · 不连接耳机", fontSize = 14.sp, color = colors.onBackground)
                        Column(Modifier.widthIn(max = intent.getIntExtra("width", 600).coerceIn(280, 600).dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            ListeningControls(state,
                                onModeChange = { state = state.copy(controls = state.controls + (0x0d to listOf(it))) },
                                onStrengthChange = { writes++; state = state.copy(controls = state.controls + (0x2e to listOf(it))) })
                            Text("模拟提交 $writes 次 · 0x2e = ${state.controls[0x2e]?.firstOrNull()}", fontSize = 13.sp, color = colors.onBackground)
                            Text("读取中", fontSize = 16.sp, color = colors.onBackground)
                            ListeningControls(state.copy(controls = mapOf(0x0d to listOf(4))), onModeChange = {}, onStrengthChange = {})
                            Text("断开连接", fontSize = 16.sp, color = colors.onBackground)
                            ListeningControls(PodsState(), onModeChange = {}, onStrengthChange = {})
                        }
                    }
                }
            }
        }
    }
}
