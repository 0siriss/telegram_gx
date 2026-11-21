package org.telegram.max

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Осциллограмма голосового как в Komet: столбики из `wave`, прогресс по позиции MediaPlayer,
 * тап по волне — перемотка.
 */
class VoiceWaveView(context: Context) : View(context) {

    private var amps: IntArray = IntArray(0)
    private var activeColor = 0xffffffff.toInt()
    private var inactiveColor = 0x66ffffff
    private var trackId: Long = 0L
    private var durationMs: Long = 0L
    private var progress: Float = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barRect = RectF()

    private val ticker = object : Runnable {
        override fun run() {
            if (trackId == 0L || trackId != MaxAudio.playingId) {
                if (progress != 0f) {
                    progress = 0f
                    invalidate()
                }
                return
            }
            val total = durationMs.takeIf { it > 0L } ?: 1L
            progress = (MaxAudio.positionMs() / total.toFloat()).coerceIn(0f, 1f)
            invalidate()
            postOnAnimation(this)
        }
    }

    fun setWave(wave: IntArray) {
        amps = wave
        invalidate()
    }

    fun colors(active: Int, inactive: Int) {
        activeColor = active
        inactiveColor = inactive
        invalidate()
    }

    fun bindTrack(messageId: Long, duration: Long) {
        trackId = messageId
        durationMs = duration
        progress = 0f
        removeCallbacks(ticker)
        if (messageId != 0L && messageId == MaxAudio.playingId) postOnAnimation(ticker)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        if (h <= 0f || w <= 0f) return
        val center = h / 2f

        if (amps.isEmpty()) {
            paint.color = inactiveColor
            barRect.set(0f, center - 1.5f, w, center + 1.5f)
            canvas.drawRoundRect(barRect, 1.5f, 1.5f, paint)
            if (progress > 0f) {
                paint.color = activeColor
                barRect.set(0f, center - 1.5f, w * progress, center + 1.5f)
                canvas.drawRoundRect(barRect, 1.5f, 1.5f, paint)
            }
            return
        }

        val n = amps.size
        var maxAmp = 1
        for (a in amps) maxAmp = max(maxAmp, a)
        val slot = w / n
        val barW = (slot * 0.55f).coerceIn(1f, 3f)
        val radius = barW / 2f

        for (i in 0 until n) {
            val bh = (amps[i] / maxAmp.toFloat() * h).coerceAtLeast(2f)
            val x = i * slot + (slot - barW) / 2f
            paint.color = if ((i + 0.5f) / n <= progress) activeColor else inactiveColor
            barRect.set(x, center - bh / 2f, x + barW, center + bh / 2f)
            canvas.drawRoundRect(barRect, radius, radius, paint)
        }

        if (progress > 0f && trackId == MaxAudio.playingId) {
            val x = (w * progress).coerceIn(1.5f, w - 1.5f)
            paint.color = activeColor
            barRect.set(x - 1.5f, 0f, x + 1.5f, h)
            canvas.drawRoundRect(barRect, 1.5f, 1.5f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (trackId == 0L || trackId != MaxAudio.playingId) return super.onTouchEvent(event)
        if (durationMs <= 0L || width <= 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val fraction = (event.x / width).coerceIn(0f, 1f)
                MaxAudio.seekTo((durationMs * fraction).roundToInt())
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
