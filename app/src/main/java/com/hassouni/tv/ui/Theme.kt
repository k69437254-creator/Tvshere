package com.hassouni.tv.ui

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp

val Bg = Color(0xFF0A0B14); val Cyan = Color(0xFF00E5FF); val Magenta = Color(0xFFFF2BD6)
val Violet = Color(0xFF7C4DFF); val Danger = Color(0xFFFF3B5C); val Ok = Color(0xFF00E676); val Warn = Color(0xFFFFD54F)
val NeonBrush = Brush.linearGradient(listOf(Cyan, Violet, Magenta))

fun cairo(ctx: Context): FontFamily = runCatching {
    val has = ctx.assets.list("fonts")?.contains("Cairo-Regular.ttf") == true
    if (!has) FontFamily.Default else FontFamily(
        Font("fonts/Cairo-Regular.ttf", ctx.assets, FontWeight.Normal),
        Font("fonts/Cairo-Bold.ttf", ctx.assets, FontWeight.Bold))
}.getOrDefault(FontFamily.Default)

@Composable
fun HassouniTheme(content: @Composable () -> Unit) {
    val ff = cairo(LocalContext.current)
    val base = Typography()
    val t = Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = ff), bodyMedium = base.bodyMedium.copy(fontFamily = ff),
        titleLarge = base.titleLarge.copy(fontFamily = ff, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = ff, fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontFamily = ff), labelSmall = base.labelSmall.copy(fontFamily = ff))
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Magenta, background = Bg, surface = Bg,
        onBackground = Color.White, onSurface = Color.White), typography = t, content = content)
}

/** بطاقة زجاجية (Glassmorphism) بحدود نيون متدرجة */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.06f))
        .border(BorderStroke(1.dp, Brush.linearGradient(listOf(Cyan.copy(.5f), Color.White.copy(.05f), Magenta.copy(.5f)))), RoundedCornerShape(20.dp))
        .padding(16.dp), content = content)
}

/** زر مع تأثير ضغط (scale) + اهتزاز لمسي */
@Composable
fun NeonButton(text: String, modifier: Modifier = Modifier, color: Color = Cyan, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "s")
    val hap = LocalHapticFeedback.current
    OutlinedButton(onClick = { hap.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        modifier = modifier.scale(s), interactionSource = src, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, color), colors = ButtonDefaults.outlinedButtonColors(contentColor = color)) { Text(text) }
}

/** حلقة توهج نيون نابضة حول زر البث */
fun Modifier.neonRing(active: Boolean, color: Color): Modifier = composed {
    val inf = rememberInfiniteTransition(label = "glow")
    val pulse by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    drawBehind {
        val a = if (active) pulse else 0.35f
        for (i in 1..4) drawCircle(color.copy(alpha = a / (i + 1)), radius = size.minDimension / 2 + i * 8.dp.toPx(), style = Stroke(3.dp.toPx()))
    }
}
