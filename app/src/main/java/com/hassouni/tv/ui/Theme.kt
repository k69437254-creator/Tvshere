package com.hassouni.tv.ui

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
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

val Bg = Color(0xFF090B13); val Cyan = Color(0xFF65D8F4); val Magenta = Color(0xFFFF5C9D)
val Violet = Color(0xFF9672FF); val Danger = Color(0xFFFF526E); val Ok = Color(0xFF45D6A0); val Warn = Color(0xFFFFC86B)
val NeonBrush = Brush.linearGradient(listOf(Cyan, Violet, Magenta))
val LocalDarkTheme = androidx.compose.runtime.compositionLocalOf { true }
val LocalSetDarkTheme = androidx.compose.runtime.compositionLocalOf<(Boolean) -> Unit> { {} }

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
        val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("tvshere_preferences", Context.MODE_PRIVATE) }
    var darkTheme by remember { mutableStateOf(preferences.getBoolean("dark_mode", true)) }
    val setDarkTheme: (Boolean) -> Unit = { enabled ->
        darkTheme = enabled
        preferences.edit().putBoolean("dark_mode", enabled).apply()
    }
    val scheme = if (darkTheme) darkColorScheme(
        primary = Cyan, secondary = Violet, tertiary = Magenta,
        background = Color(0xFF090B13), surface = Color(0xFF151A29),
        onBackground = Color(0xFFF4F6FF), onSurface = Color(0xFFF4F6FF),
        surfaceVariant = Color(0xFF20283A), onSurfaceVariant = Color(0xFFBCC5D8)
    ) else lightColorScheme(
        primary = Color(0xFF5140A8), secondary = Color(0xFF6A4BC3), tertiary = Color(0xFFB52C6B),
        background = Color(0xFFF4F5FA), surface = Color.White,
        onBackground = Color(0xFF171827), onSurface = Color(0xFF171827),
        surfaceVariant = Color(0xFFE8EAF2), onSurfaceVariant = Color(0xFF555B6F)
    )
    CompositionLocalProvider(LocalDarkTheme provides darkTheme, LocalSetDarkTheme provides setDarkTheme) {
        MaterialTheme(colorScheme = scheme, typography = t, content = content)
    }
}

/** بطاقة زجاجية (Glassmorphism) بحدود نيون متدرجة */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.animateContentSize().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
        .border(BorderStroke(1.dp, Brush.linearGradient(listOf(Violet.copy(.62f), MaterialTheme.colorScheme.onSurface.copy(.06f), Cyan.copy(.34f)))), RoundedCornerShape(20.dp))
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
