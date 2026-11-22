package org.telegram.max

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Emoji
import org.telegram.messenger.LocaleController
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.MessageDrawable
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.MotionBackgroundDrawable
import org.telegram.ui.Components.MsgClockDrawable

/**
 * Оформление сообщений из деталей Telegram: тот же пузырь `MessageDrawable` (хвостик,
 * скругления по настройке, градиенты и цвета темы), те же галочки и часики, те же
 * эмодзи через `Emoji.replaceEmoji`, время в конце последней строки.
 */
object MaxBubbles {

    /**
     * Пузырь Telegram. `MessageDrawable` красит себя только после `setTop`, поэтому
     * зовём его перед каждой отрисовкой. Хвостик рисуется, когда следующее сообщение не
     * от того же автора ([bottomNear] = false); место под него — 8dp сбоку, поэтому у
     * содержимого со стороны хвостика отступ больше.
     */
    class Bubble(private val out: Boolean, media: Boolean) :
        MessageDrawable(if (media) MessageDrawable.TYPE_MEDIA else MessageDrawable.TYPE_TEXT, out, false) {
        var topNear = false
        var bottomNear = false

        override fun draw(canvas: Canvas) {
            val b = bounds
            setTop(0, b.width(), b.height(), topNear, bottomNear)
            super.draw(canvas)
            // Пока градиент переезжает на следующую позицию (после отправки сообщения),
            // кадры обновляет MotionBackgroundDrawable, но перерисовать сам вид должен он же:
            // в чате Telegram это делает ChatActivity, слушая invalidateMotionBackground.
            if (out && posAnimationProgress < 1f) invalidateSelf()
        }

        private val posAnimationProgress: Float
            get() = MessageDrawable.motionBackground[0]?.posAnimationProgress ?: 1f

        override fun getCurrentColor(key: Int): Int = Theme.getColor(key, null)
    }

    /**
     * Анимированные цвета сообщений Telegram: при каждом новом исходящем сообщении общий
     * `MotionBackgroundDrawable` (в `MessageDrawable` он статический, один на все пузыри)
     * переезжает на следующую позицию градиента, и пузыри плавно перекрашиваются.
     * В чате Telegram это делает `ChatActivity.rotateMotionBackgroundDrawable`.
     */
    fun rotateOutGradient(wallpaper: Drawable? = null) {
        if (wallpaper is MotionBackgroundDrawable) wallpaper.switchToNextPosition()
        MessageDrawable.motionBackground[0]?.switchToNextPosition()
    }

    /** Отступы содержимого внутри пузыря: со стороны хвостика 17dp, с другой 11dp. */
    fun applyPadding(view: View, out: Boolean, media: Boolean) {
        val tail = AndroidUtilities.dp(if (media) 10f else 17f)
        val side = AndroidUtilities.dp(if (media) 4f else 11f)
        val top = AndroidUtilities.dp(if (media) 4f else 7f)
        val bottom = AndroidUtilities.dp(if (media) 4f else 7f)
        if (out) view.setPadding(side, top, tail, bottom) else view.setPadding(tail, top, side, bottom)
    }

    /** Эмодзи как в Telegram. */
    fun emojify(text: CharSequence, view: TextView): CharSequence =
        Emoji.replaceEmoji(text, view.paint.fontMetricsInt, false)

    /** Сколько эмодзи в сообщении, если оно целиком из них (крупные, без пузыря); иначе 0. */
    fun bigEmojiCount(text: String): Int {
        if (text.isEmpty() || text.length > 24) return 0
        if (!Emoji.fullyConsistsOfEmojis(text)) return 0
        val count = Emoji.parseEmojis(text).size
        return if (count in 1..3) count else 0
    }

    /** Размер крупных эмодзи, как у Telegram: одно — самое крупное. */
    fun bigEmojiSize(count: Int): Float = SharedConfig.fontSize + when (count) {
        1 -> 24f
        2 -> 18f
        else -> 14f
    }

    /**
     * Текст с местом под время в конце последней строки: невидимый промежуток шириной
     * с подпись времени. Если строка короткая — время встаёт рядом с текстом, если
     * длинная — промежуток переносится, и время уходит на свою строку, как в Telegram.
     */
    fun withTimeRoom(text: CharSequence, roomPx: Int): CharSequence {
        val out = SpannableStringBuilder(text)
        out.append(" ")
        val start = out.length
        out.append(" ")
        out.setSpan(Spacer(roomPx), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    private class Spacer(private val width: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = width

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
    }

    enum class Status { NONE, CLOCK, SENT, READ, FAILED }
    enum class Mode { IN, OUT, MEDIA }

    /**
     * Подпись «изм. 12:30 ✓✓» из деталей Telegram: галочки — `msg_check_s` и
     * `msg_halfcheck_s` в цветах темы (отправлено / прочитано), отправка — часики
     * `MsgClockDrawable`. Поверх фото — светлая на тёмной плашке.
     */
    class Meta(context: Context) : LinearLayout(context) {
        private val edited = TextView(context)
        private val time = TextView(context)
        private val checks = FrameLayout(context)
        private val check = ImageView(context)
        private val half = ImageView(context)
        private val clock = MsgClockDrawable()
        private val clockView = ImageView(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            edited.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f)
            edited.text = "изм."
            addView(edited, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 4f, 0f))
            time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f)
            addView(time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            check.setImageResource(org.telegram.messenger.R.drawable.msg_check_s)
            half.setImageResource(org.telegram.messenger.R.drawable.msg_halfcheck_s)
            checks.addView(check, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START or Gravity.CENTER_VERTICAL, 0f, 0f, 0f, 0f))
            checks.addView(half, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START or Gravity.CENTER_VERTICAL, 4f, 0f, 0f, 0f))
            addView(checks, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 3f, 0f, 0f, 0f))
            clockView.setImageDrawable(clock)
            addView(clockView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 3f, 0f, 0f, 0f))
        }

        fun bind(timeMs: Long, isEdited: Boolean, status: Status, mode: Mode) {
            time.text = LocaleController.getInstance().formatterDay.format(timeMs)
            edited.visibility = if (isEdited) VISIBLE else GONE
            val textColor = Theme.getColor(
                when (mode) {
                    Mode.IN -> Theme.key_chat_inTimeText
                    Mode.OUT -> Theme.key_chat_outTimeText
                    Mode.MEDIA -> Theme.key_chat_mediaTimeText
                }
            )
            time.setTextColor(if (status == Status.FAILED) Theme.getColor(Theme.key_text_RedRegular) else textColor)
            edited.setTextColor(textColor)

            checks.visibility = if (status == Status.SENT || status == Status.READ) VISIBLE else GONE
            half.visibility = if (status == Status.READ) VISIBLE else GONE
            val checkColor = Theme.getColor(
                when {
                    mode == Mode.MEDIA -> Theme.key_chat_mediaSentCheck
                    status == Status.READ -> Theme.key_chat_outSentCheckRead
                    else -> Theme.key_chat_outSentCheck
                }
            )
            check.colorFilter = PorterDuffColorFilter(checkColor, PorterDuff.Mode.SRC_IN)
            half.colorFilter = PorterDuffColorFilter(checkColor, PorterDuff.Mode.SRC_IN)

            clockView.visibility = if (status == Status.CLOCK) VISIBLE else GONE
            clock.setColor(if (mode == Mode.MEDIA) Theme.getColor(Theme.key_chat_mediaSentCheck) else Theme.getColor(Theme.key_chat_outSentClock))

            if (mode == Mode.MEDIA) {
                background = Theme.createRoundRectDrawable(AndroidUtilities.dp(10f), Theme.getColor(Theme.key_chat_mediaTimeBackground))
                setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(2f), AndroidUtilities.dp(6f), AndroidUtilities.dp(2f))
            } else {
                background = null
                setPadding(0, 0, 0, 0)
            }
        }

        /** Ширина подписи — под неё в конце текста оставляем место. */
        fun measureWidth(): Int {
            measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            return measuredWidth
        }
    }
}
