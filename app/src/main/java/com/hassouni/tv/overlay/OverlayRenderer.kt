package com.hassouni.tv.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import com.hassouni.tv.data.OverlayState

/** Draws every live overlay into one transparent frame-sized bitmap. RootEncoder's full-frame
 * GL image filter composites this bitmap over the incoming UVC frame before encoding. */
class OverlayRenderer(private val width: Int, private val height: Int) {
    val bitmap: Bitmap = Bitmap.createBitmap(width.coerceAtLeast(2), height.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 10, 11, 20) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = height * 0.004f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = height * 0.045f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val accent = Paint(text).apply { color = Color.parseColor("#FFD54F") }
    private var tickerX = width.toFloat()
    private var logo: Bitmap? = null

    @Synchronized
    fun setLogo(source: Bitmap?) {
        logo?.takeIf { it !== source && !it.isRecycled }?.recycle()
        logo = source?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.let {
            val targetHeight = (height * 0.12f).toInt().coerceAtLeast(1)
            val targetWidth = (targetHeight.toFloat() * it.width / it.height).toInt().coerceAtLeast(1)
            // Always create an owned bitmap. createScaledBitmap may return the input
            // instance unchanged when its dimensions already match, and callers may
            // recycle the source stream bitmap immediately after this method returns.
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { scaled ->
                Canvas(scaled).drawBitmap(
                    it,
                    null,
                    RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
            }
        }
    }

    /** Called when the state changes and periodically while the ticker is active. */
    @Synchronized
    fun render(state: OverlayState) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        logo?.takeIf { !it.isRecycled }?.let {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (state.watermarkOpacity.coerceIn(0f, 1f) * 255).toInt()
            }
            canvas.drawBitmap(it, width - it.width - width * 0.02f, height * 0.03f, paint)
        }
        if (state.scoreEnabled) {
            val box = RectF(width * 0.02f, height * 0.03f, width * 0.55f, height * 0.13f)
            canvas.drawRoundRect(box, height * 0.018f, height * 0.018f, panel)
            canvas.drawRoundRect(box, height * 0.018f, height * 0.018f, outline)
            val y = box.centerY() + text.textSize / 3f
            val score = "${state.home}  ${state.homeScore} - ${state.awayScore}  ${state.away}"
            canvas.drawText(ellipsize(score, text, box.width() - 32f), box.left + 16f, y, text)
            if (state.minute.isNotBlank()) {
                val minute = "${state.minute}'"
                canvas.drawText(minute, box.right - accent.measureText(minute) - 14f, box.bottom + text.textSize, accent)
            }
        }
        if (state.tickerEnabled && state.tickerText.isNotBlank()) {
            val bar = RectF(0f, height * 0.91f, width.toFloat(), height.toFloat())
            canvas.drawRect(bar, panel)
            canvas.drawLine(0f, bar.top, width.toFloat(), bar.top, outline)
            canvas.drawText(state.tickerText, tickerX, bar.centerY() + text.textSize / 3f, text)
            tickerX -= width * 0.06f
            if (tickerX < -text.measureText(state.tickerText)) tickerX = width.toFloat()
        } else {
            tickerX = width.toFloat()
        }
    }

    private fun ellipsize(value: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        val suffix = "…"
        var result = value
        while (result.isNotEmpty() && paint.measureText(result + suffix) > maxWidth) result = result.dropLast(1)
        return result + suffix
    }

    @Synchronized
    fun release() {
        logo?.takeIf { !it.isRecycled }?.recycle()
        logo = null
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
