package org.telegram.max

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/** Ячейки раздела MAX в стиле списков Telegram: те же размеры, цвета темы и шрифты. */

private val dividerPaint = Paint()

private fun drawDivider(canvas: Canvas, width: Int, height: Int, leftDp: Float) {
    dividerPaint.color = Theme.getColor(Theme.key_divider)
    canvas.drawRect(AndroidUtilities.dp(leftDp).toFloat(), height - 1f, width.toFloat(), height.toFloat(), dividerPaint)
}

/** Строка списка чатов: аватарка 54dp, имя и время, превью, счётчик непрочитанных. */
class MaxChatCell(context: Context) : FrameLayout(context) {

    private val avatar = BackupImageView(context)
    private val title = TextView(context)
    private val time = TextView(context)
    private val preview = TextView(context)
    private val counter = TextView(context)
    private val check = android.widget.ImageView(context)
    private val half = android.widget.ImageView(context)
    var needDivider = true

    init {
        setWillNotDraw(false)
        // Размеры — как в DialogCell Telegram: аватарка 56dp, текст с 78dp, превью в две строки.
        avatar.setRoundRadius(AndroidUtilities.dp(28f))
        addView(avatar, LayoutHelper.createFrame(56, 56f, Gravity.START or Gravity.TOP, 10f, 8f, 0f, 0f))

        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
        title.typeface = AndroidUtilities.bold()
        title.setTextColor(Theme.getColor(Theme.key_chats_name))
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        title.includeFontPadding = false
        addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.START, 78f, 11f, 74f, 0f))

        time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
        time.setTextColor(Theme.getColor(Theme.key_chats_date))
        time.includeFontPadding = false
        addView(time, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.END, 0f, 13f, 15f, 0f))

        // Галочки списка диалогов Telegram: list_check и list_halfcheck в цветах темы.
        check.setImageResource(org.telegram.messenger.R.drawable.list_check)
        half.setImageResource(org.telegram.messenger.R.drawable.list_halfcheck)
        addView(check, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.END, 0f, 12f, 0f, 0f))
        addView(half, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.END, 0f, 12f, 0f, 0f))

        preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
        preview.setTextColor(Theme.getColor(Theme.key_chats_message_threeLines))
        preview.maxLines = 2
        preview.ellipsize = TextUtils.TruncateAt.END
        preview.includeFontPadding = false
        preview.setLineSpacing(AndroidUtilities.dp(1f).toFloat(), 1f)
        addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.START, 78f, 34f, 50f, 0f))

        counter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
        counter.typeface = AndroidUtilities.bold()
        counter.gravity = Gravity.CENTER
        counter.minWidth = AndroidUtilities.dp(23f)
        counter.setPadding(AndroidUtilities.dp(7f), 0, AndroidUtilities.dp(7f), 0)
        counter.setTextColor(Theme.getColor(Theme.key_chats_unreadCounterText))
        counter.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(11.5f), Theme.getColor(Theme.key_chats_unreadCounter))
        addView(counter, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 23f, Gravity.TOP or Gravity.END, 0f, 40f, 14f, 0f))
    }

    fun bind(chat: MaxChat) {
        val name = chat.title
        title.text = name
        MaxImages.bindAvatar(avatar, chat.avatarKey, name, chat.avatarUrl, 56)
        val last = chat.lastMessage
        time.text = if (chat.sortTime > 0) LocaleController.stringForMessageListDate(chat.sortTime / 1000) else ""
        val mine = last != null && last.isOutgoing && !chat.isSaved
        val read = mine && chat.isReadByOthers(last!!.time)
        check.visibility = if (mine) VISIBLE else GONE
        half.visibility = if (read) VISIBLE else GONE
        val checkColor = Theme.getColor(if (read) Theme.key_chats_sentReadCheck else Theme.key_chats_sentCheck)
        check.colorFilter = android.graphics.PorterDuffColorFilter(checkColor, android.graphics.PorterDuff.Mode.SRC_IN)
        half.colorFilter = android.graphics.PorterDuffColorFilter(checkColor, android.graphics.PorterDuff.Mode.SRC_IN)
        // Галочки стоят слева от времени: сдвигаем их на ширину подписи.
        time.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val timeRight = AndroidUtilities.dp(15f) + time.measuredWidth + AndroidUtilities.dp(3f)
        (check.layoutParams as LayoutParams).rightMargin = timeRight + AndroidUtilities.dp(if (read) 4f else 0f)
        (half.layoutParams as LayoutParams).rightMargin = timeRight
        check.requestLayout()
        half.requestLayout()
        val typingUser = MaxClient.typingIn(chat.id)
        preview.setTextColor(Theme.getColor(if (typingUser != null) Theme.key_chats_actionMessage else Theme.key_chats_message_threeLines))
        if (typingUser != null) {
            preview.text = MaxText.chatSubtitle(chat)
        } else preview.text = MaxBubbles.emojify(when {
            last == null -> if (chat.isSaved) "Сохраняйте сюда заметки" else "Нет сообщений"
            last.isService -> last.displayText
            last.isOutgoing && !chat.isSaved -> "Вы: " + last.displayText
            !chat.isDialog && !chat.isChannel -> {
                val author = MaxContacts.get(last.senderId)?.firstName?.ifEmpty { null }
                if (author != null) "$author: ${last.displayText}" else last.displayText
            }
            else -> last.displayText
        }, preview)
        title.text = MaxBubbles.emojify(name, title)
        if (chat.unread > 0) {
            counter.visibility = VISIBLE
            counter.text = if (chat.unread > 999) "999+" else chat.unread.toString()
        } else {
            counter.visibility = GONE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(72f) + 1, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        if (needDivider) drawDivider(canvas, measuredWidth, measuredHeight, 78f)
    }
}

/** Строка контакта: аватарка 46dp, имя, телефон. Используется и для «найти по номеру». */
class MaxContactCell(context: Context) : FrameLayout(context) {

    private val avatar = BackupImageView(context)
    private val name = TextView(context)
    private val subtitle = TextView(context)
    var needDivider = true

    init {
        setWillNotDraw(false)
        avatar.setRoundRadius(AndroidUtilities.dp(23f))
        addView(avatar, LayoutHelper.createFrame(46, 46f, Gravity.START or Gravity.CENTER_VERTICAL, 14f, 0f, 0f, 0f))

        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        name.isSingleLine = true
        name.ellipsize = TextUtils.TruncateAt.END
        addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.START, 72f, 10f, 16f, 0f))

        subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        subtitle.isSingleLine = true
        addView(subtitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.START, 72f, 33f, 16f, 0f))
    }

    fun bind(contact: MaxContact) {
        name.text = contact.name
        subtitle.text = contact.phoneText
        MaxImages.bindAvatar(avatar, contact.id, contact.name, contact.avatarUrl, 46)
    }

    /** Строка-действие: иконка-плашка цвета акцента и подпись. */
    fun bindAction(text: String, hint: String) {
        name.text = text
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2))
        subtitle.text = hint
        avatar.setImageDrawable(
            Theme.createCircleDrawableWithIcon(AndroidUtilities.dp(46f), org.telegram.messenger.R.drawable.msg_search)
        )
    }

    fun resetColors() {
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64f), MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        if (needDivider) drawDivider(canvas, measuredWidth, measuredHeight, 72f)
    }
}
