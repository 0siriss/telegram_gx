package org.telegram.max

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/**
 * Просмотр историй, как в Telegram и Komet: полоски прогресса сверху, автор и время,
 * нажатие справа — дальше, слева — назад, фото идёт 6 секунд, видео — до конца.
 * Закончились истории владельца — переходим к следующему кольцу ленты.
 */
class MaxStoryViewerFragment(private var preview: MaxStoryPreview) : BaseFragment() {

    private lateinit var image: BackupImageView
    private lateinit var video: VideoView
    private lateinit var bars: LinearLayout
    private lateinit var avatar: BackupImageView
    private lateinit var title: TextView
    private lateinit var time: TextView
    private lateinit var status: TextView

    private var stories: List<MaxStory> = emptyList()
    private var index = 0
    private var startedAt = 0L
    private var paused = false

    override fun createView(context: Context): View {
        actionBar.setAddToContainer(false)
        val root = object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> pause(true)
                    MotionEvent.ACTION_UP -> {
                        pause(false)
                        if (event.eventTime - event.downTime < 300) {
                            if (event.x < width / 3f) step(-1) else step(1)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> pause(false)
                }
                return true
            }
        }
        root.setBackgroundColor(Color.BLACK)

        image = BackupImageView(context)
        root.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.CENTER, 0f, 0f, 0f, 0f))
        video = VideoView(context)
        video.visibility = View.GONE
        root.addView(video, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.CENTER, 0f, 0f, 0f, 0f))

        val shade = View(context)
        shade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x88000000.toInt(), 0))
        root.addView(shade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 140f, Gravity.TOP, 0f, 0f, 0f, 0f))

        val top = LinearLayout(context)
        top.orientation = LinearLayout.VERTICAL
        top.setPadding(AndroidUtilities.dp(8f), AndroidUtilities.statusBarHeight + AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), 0)
        bars = LinearLayout(context)
        bars.orientation = LinearLayout.HORIZONTAL
        top.addView(bars, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 2))
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        avatar = BackupImageView(context)
        avatar.setRoundRadius(AndroidUtilities.dp(16f))
        row.addView(avatar, LayoutHelper.createLinear(32, 32))
        val texts = LinearLayout(context)
        texts.orientation = LinearLayout.VERTICAL
        title = TextView(context)
        title.textSize = 15f
        title.typeface = AndroidUtilities.bold()
        title.setTextColor(Color.WHITE)
        title.isSingleLine = true
        texts.addView(title)
        time = TextView(context)
        time.textSize = 13f
        time.setTextColor(0xccffffff.toInt())
        texts.addView(time)
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0))
        val close = ImageView(context)
        close.setImageResource(org.telegram.messenger.R.drawable.ic_close_white)
        close.scaleType = ImageView.ScaleType.CENTER
        close.setOnClickListener { finishFragment() }
        row.addView(close, LayoutHelper.createLinear(40, 40))
        top.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 10f, 0f, 0f))
        root.addView(top, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP, 0f, 0f, 0f, 0f))

        status = TextView(context)
        status.textSize = 15f
        status.setTextColor(0xccffffff.toInt())
        status.gravity = Gravity.CENTER
        root.addView(status, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER, 0f, 0f, 0f, 0f))

        video.setOnCompletionListener { step(1) }
        video.setOnPreparedListener { startedAt = System.currentTimeMillis() }

        fragmentView = root
        loadOwner()
        return root
    }

    override fun isLightStatusBar(): Boolean = false

    private fun loadOwner() {
        title.text = preview.title
        MaxImages.bindAvatar(avatar, preview.owner.id, preview.title, preview.avatarUrl, 32)
        val cached = MaxStoriesStore.stories[preview.owner.id]
        if (!cached.isNullOrEmpty()) {
            start(cached)
            return
        }
        status.text = "Загружаем…"
        status.visibility = View.VISIBLE
        MaxClient.storiesOf(preview.owner) { list, error ->
            AndroidUtilities.runOnUIThread {
                if (fragmentView == null) return@runOnUIThread
                if (list.isNullOrEmpty()) {
                    status.text = if (error != null) "Не удалось загрузить: " + MaxLog.describe(error) else "Историй нет"
                    return@runOnUIThread
                }
                start(list)
            }
        }
    }

    private fun start(list: List<MaxStory>) {
        stories = list
        // Начинаем с первой непросмотренной, как Telegram.
        index = preview.read.coerceIn(0, list.size - 1)
        bars.removeAllViews()
        for (i in list.indices) {
            val bar = View(context ?: return)
            bars.addView(bar, LayoutHelper.createLinear(0, 2, 1f, if (i == 0) 0 else 3, 0, 0, 0))
        }
        show()
    }

    private fun show() {
        val story = stories.getOrNull(index) ?: return
        status.visibility = View.GONE
        time.text = MaxText.lastSeenTime(story.time)
        for (i in 0 until bars.childCount) {
            bars.getChildAt(i).setBackgroundColor(if (i < index) Color.WHITE else 0x55ffffff)
        }
        startedAt = System.currentTimeMillis()
        if (story.isVideo && story.url != null) {
            image.visibility = View.VISIBLE
            MaxImages.bindImage(image, story.thumbnail, AndroidUtilities.displaySize.y, Color.BLACK)
            video.visibility = View.VISIBLE
            video.setVideoURI(Uri.parse(story.url))
            video.start()
        } else {
            video.stopPlayback()
            video.visibility = View.GONE
            image.visibility = View.VISIBLE
            MaxImages.bindImage(image, story.url, AndroidUtilities.displaySize.y, Color.BLACK)
        }
        MaxClient.markStory(story)
        AndroidUtilities.cancelRunOnUIThread(ticker)
        AndroidUtilities.runOnUIThread(ticker, 50)
    }

    /** Полоска текущей истории заполняется; фото листается само через 6 секунд. */
    private val ticker = object : Runnable {
        override fun run() {
            val story = stories.getOrNull(index) ?: return
            if (!paused) {
                val duration = if (story.isVideo) (if (video.duration > 0) video.duration.toLong() else story.durationMs.coerceAtLeast(1L)) else PHOTO_MS
                val elapsed = if (story.isVideo) video.currentPosition.toLong() else System.currentTimeMillis() - startedAt
                val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                bars.getChildAt(index)?.let { bar ->
                    bar.background = progressDrawable(progress)
                }
                if (!story.isVideo && elapsed >= PHOTO_MS) {
                    step(1)
                    return
                }
            } else {
                startedAt += 50
            }
            AndroidUtilities.runOnUIThread(this, 50)
        }
    }

    private fun progressDrawable(progress: Float): android.graphics.drawable.Drawable {
        val done = GradientDrawable()
        done.setColor(Color.WHITE)
        val rest = GradientDrawable()
        rest.setColor(0x55ffffff)
        val layers = android.graphics.drawable.LayerDrawable(arrayOf(rest, android.graphics.drawable.ClipDrawable(done, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)))
        layers.getDrawable(1).level = (progress * 10_000).toInt()
        return layers
    }

    private fun pause(value: Boolean) {
        paused = value
        if (stories.getOrNull(index)?.isVideo == true) {
            if (value) video.pause() else video.start()
        }
    }

    private fun step(delta: Int) {
        val next = index + delta
        when {
            next < 0 -> show()
            next < stories.size -> {
                index = next
                show()
            }
            else -> nextOwner()
        }
    }

    /** Следующее кольцо с непросмотренным, иначе закрываемся. */
    private fun nextOwner() {
        val all = MaxStoriesStore.previews
        val pos = all.indexOfFirst { it.owner.id == preview.owner.id }
        val next = all.drop(pos + 1).firstOrNull { it.hasUnread }
        if (next == null) {
            finishFragment()
            return
        }
        preview = next
        video.stopPlayback()
        loadOwner()
    }

    override fun onPause() {
        super.onPause()
        pause(true)
    }

    override fun onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(ticker)
        if (::video.isInitialized) video.stopPlayback()
        super.onFragmentDestroy()
    }

    companion object {
        private const val PHOTO_MS = 6_000L
    }
}
