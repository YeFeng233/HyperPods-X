package com.hyper.podsrx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val Accent = Color(0xff4285f4)
internal val ChargingGreen = Color(0xff279961)

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(0.dp), content = content)
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 15.sp, color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp).semantics { heading() })
        SettingsCard(content)
    }
}

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 18.sp, color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceSecondary)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
internal fun RowItem(title: String, subtitle: String = "", enabled: Boolean = true, value: String = "", action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = action)
        .heightIn(min = 66.dp).padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 18.sp, color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceSecondary)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        if (value.isNotBlank()) Text(value, modifier = Modifier.widthIn(max = 108.dp).padding(end = 8.dp),
            fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Chevron()
    }
}

@Composable
internal fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.weight(1.2f))
    }
}

@Composable
internal fun Action(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(MiuixTheme.colorScheme.primary)
        .clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val primary = MiuixTheme.colorScheme.primary
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) primary else primary.copy(alpha = .09f))
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).heightIn(min = 48.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, color = if (selected) Color.White else if (enabled) primary else MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

@Composable
internal fun NavigationButton(label: String, back: Boolean, onClick: () -> Unit) {
    val color = MiuixTheme.colorScheme.onBackground
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            if (back) {
                val stroke = 1.8.dp.toPx()
                drawLine(color, Offset(size.width * .12f, size.height * .5f), Offset(size.width * .88f, size.height * .5f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .12f, size.height * .5f), Offset(size.width * .42f, size.height * .2f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * .12f, size.height * .5f), Offset(size.width * .42f, size.height * .8f), stroke, StrokeCap.Round)
            } else {
                for (x in listOf(.2f, .5f, .8f)) drawCircle(color, 1.6.dp.toPx(), Offset(size.width * x, size.height / 2))
            }
        }
    }
}

@Composable
private fun Chevron() {
    val color = MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = .55f)
    Canvas(Modifier.size(12.dp, 18.dp)) {
        val path = Path().apply { moveTo(size.width * .3f, size.height * .22f); lineTo(size.width * .7f, size.height * .5f); lineTo(size.width * .3f, size.height * .78f) }
        drawPath(path, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
internal fun BatteryGlyph(battery: BatteryPresentation) {
    val color = when {
        battery.charging -> ChargingGreen
        battery.level != null && battery.level <= 20 -> Color(0xffd95151)
        else -> MiuixTheme.colorScheme.onSurfaceSecondary
    }
    Canvas(Modifier.size(25.dp, 13.dp)) {
        val body = size.width * .86f
        val stroke = 1.2.dp.toPx()
        drawRoundRect(color, Offset(stroke / 2, stroke / 2), Size(body - stroke, size.height - stroke), CornerRadius(2.dp.toPx()), style = Stroke(stroke))
        drawRoundRect(color, Offset(size.width * .91f, size.height * .3f), Size(size.width * .07f, size.height * .4f), CornerRadius(1.dp.toPx()))
        val inset = 2.6.dp.toPx()
        battery.level?.takeIf { it > 0 }?.let {
            drawRoundRect(color, Offset(inset, inset), Size((body - inset * 2) * it / 100f, size.height - inset * 2), CornerRadius(.7.dp.toPx()))
        }
    }
}

@Composable
internal fun ListeningGlyph(mode: Int, color: Color) {
    Canvas(Modifier.size(30.dp)) {
        scale(size.width / 32f, size.height / 32f, pivot = Offset.Zero) {
            val personX = if (mode == 4) 12f else 16f
            drawCircle(color, 5.4f, Offset(personX, 12.5f))
            drawRoundRect(color, Offset(personX - 9f, 22f), Size(18f, 8f), CornerRadius(4f))
            if (mode == 4) {
                for ((center, radius) in listOf(Offset(25f, 9f) to 5.5f, Offset(20f, 2.5f) to 2.7f)) {
                    drawPath(Path().apply {
                        moveTo(center.x, center.y - radius)
                        quadraticTo(center.x + radius * .18f, center.y - radius * .18f, center.x + radius, center.y)
                        quadraticTo(center.x + radius * .18f, center.y + radius * .18f, center.x, center.y + radius)
                        quadraticTo(center.x - radius * .18f, center.y + radius * .18f, center.x - radius, center.y)
                        quadraticTo(center.x - radius * .18f, center.y - radius * .18f, center.x, center.y - radius)
                        close()
                    }, color)
                }
                return@scale
            }
            if (mode == 1) return@scale
            val ring = Stroke(width = 1.8f, cap = StrokeCap.Round,
                pathEffect = if (mode == 3) PathEffect.dashPathEffect(floatArrayOf(1f, 3.4f)) else null)
            drawArc(color, -220f, 260f, false, Offset(6.5f, 2f), Size(19f, 19f), style = ring)
            if (mode == 2) drawArc(color.copy(alpha = .55f), -220f, 260f, false, Offset(3f, -1.5f), Size(26f, 26f), style = Stroke(1.6f, cap = StrokeCap.Round))
        }
    }
}
