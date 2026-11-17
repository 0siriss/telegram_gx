package org.telegram.max

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView

/**
 * Полоса историй над списком чатов, как у Komet: аватарки в кольце, непросмотренные —
 * цветным градиентом историй Telegram (`key_stories_circle1/2`), просмотренные — серым.
 * Первым всегда своё кольцо: без историй — «+» для публикации.
 */
class MaxStoriesBar(context: Context, private val onOpen: (MaxStoryPreview) -> Unit, private val onAdd: () -> Unit) :
    RecyclerListView(context) {

    private var items: List<MaxStoryPreview?> = emptyList()

    init {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        clipToPadding = false
        setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(8f), AndroidUtilities.dp(6f), AndroidUtilities.dp(4f))
        adapter = Adapter()
        setOnItemClickListener(OnItemClickListener { _, position ->
            val preview = items.getOrNull(position)
            if (preview == null) onAdd() else onOpen(preview)
        })
        update()
    }

    fun update() {
        val me = MaxStore.accountId
        val all = MaxStoriesStore.previews
        val own = all.firstOrNull { it.owner.id == me }
        items = listOf(own) + all.filter { it.owner.id != me }
        adapter?.notifyDataSetChanged()
    }

    /** Есть ли что показать, кроме своего «+». */
    val hasStories: Boolean
        get() = MaxStoriesStore.previews.isNotEmpty()

    private inner class Adapter : SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = StoryCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(AndroidUtilities.dp(76f), ViewGroup.LayoutParams.WRAP_CONTENT)
            return Holder(cell)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as StoryCell).bind(items.getOrNull(position))
        }

        override fun getItemCount(): Int = items.size
    }

    private class StoryCell(context: Context) : LinearLayout(context) {
        private val ring = Ring(context)
        private val avatar = BackupImageView(context)
        private val plus = TextView(context)
        private val name = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val frame = FrameLayout(context)
            frame.addView(ring, LayoutHelper.createFrame(64, 64f))
            avatar.setRoundRadius(AndroidUtilities.dp(27f))
            frame.addView(avatar, LayoutHelper.createFrame(54, 54f, Gravity.CENTER, 0f, 0f, 0f, 0f))
            plus.text = "+"
            plus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
            plus.typeface = AndroidUtilities.bold()
            plus.gravity = Gravity.CENTER
            plus.setTextColor(0xffffffff.toInt())
            plus.background = Theme.createCircleDrawable(AndroidUtilities.dp(20f), Theme.getColor(Theme.key_chats_actionBackground))
            frame.addView(plus, LayoutHelper.createFrame(20, 20f, Gravity.BOTTOM or Gravity.END, 0f, 0f, 2f, 2f))
            addView(frame, LayoutHelper.createLinear(64, 64))
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f)
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.END
            name.gravity = Gravity.CENTER
            addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2f, 4f, 2f, 0f))
        }

        fun bind(preview: MaxStoryPreview?) {
            val me = MaxStore.accountId
            if (preview == null) {
                val mine = MaxContacts.get(me)
                MaxImages.bindAvatar(avatar, me, mine?.name ?: "Я", mine?.avatarUrl, 54)
                name.text = "Ваша история"
                plus.visibility = VISIBLE
                ring.mode = Ring.NONE
            } else {
                MaxImages.bindAvatar(avatar, preview.owner.id, preview.title, preview.avatarUrl, 54)
                name.text = if (preview.owner.id == me) "Ваша история" else preview.title.substringBefore(' ')
                plus.visibility = GONE
                ring.mode = if (preview.hasUnread) Ring.UNREAD else Ring.READ
            }
            name.setTextColor(Theme.getColor(
                if (preview?.hasUnread == true) Theme.key_windowBackgroundWhiteBlackText else Theme.key_windowBackgroundWhiteGrayText
            ))
            ring.invalidate()
        }
    }

    /** Кольцо вокруг аватарки. */
    class Ring(context: Context) : View(context) {
        var mode = UNREAD
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            if (mode == NONE) return
            val stroke = AndroidUtilities.dp(if (mode == UNREAD) 2.3f else 1.3f).toFloat()
            paint.strokeWidth = stroke
            if (mode == UNREAD) {
                paint.shader = LinearGradient(
                    0f, height.toFloat(), width.toFloat(), 0f,
                    Theme.getColor(Theme.key_stories_circle1), Theme.getColor(Theme.key_stories_circle2), Shader.TileMode.CLAMP
                )
            } else {
                paint.shader = null
                paint.color = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText) and 0x00ffffff or 0x66000000
            }
            rect.set(stroke, stroke, width - stroke, height - stroke)
            canvas.drawOval(rect, paint)
        }

        companion object {
            const val NONE = 0
            const val UNREAD = 1
            const val READ = 2
        }
    }
}
