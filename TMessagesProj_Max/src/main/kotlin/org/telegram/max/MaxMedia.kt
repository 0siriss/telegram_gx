package org.telegram.max

import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.Components.LayoutHelper

/** Открытие вложений MAX: фото на весь экран, скачивание файлов, внешние плееры. */
object MaxMedia {

    /** Фото на весь экран; касание закрывает, «Сохранить» кладёт в Галерею. */
    fun showPhoto(activity: Activity, url: String) = showPhotos(activity, listOf(url), 0)

    /**
     * Несколько фото на весь экран (аватарки профиля): листаются свайпом влево-вправо,
     * сверху «N из M». Касание закрывает, «Сохранить» кладёт текущее в Галерею.
     */
    fun showPhotos(activity: Activity, urls: List<String>, start: Int) {
        if (urls.isEmpty()) return
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(activity)
        root.setBackgroundColor(Color.BLACK)

        val image = ImageView(activity)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val progress = ProgressBar(activity)
        root.addView(progress, LayoutHelper.createFrame(48, 48f, Gravity.CENTER, 0f, 0f, 0f, 0f))

        val counter = TextView(activity)
        counter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
        counter.setTextColor(Color.WHITE)
        counter.typeface = AndroidUtilities.bold()
        root.addView(counter, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0f, 32f, 0f, 0f))

        var index = start.coerceIn(0, urls.size - 1)
        val size = maxOf(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y).coerceAtLeast(1080)
        fun load() {
            val url = urls[index]
            counter.visibility = if (urls.size > 1) android.view.View.VISIBLE else android.view.View.GONE
            counter.text = "${index + 1} из ${urls.size}"
            progress.visibility = android.view.View.VISIBLE
            image.setImageDrawable(null)
            MaxImages.loadFull(url, size) { bitmap ->
                if (urls[index] != url) return@loadFull
                progress.visibility = android.view.View.GONE
                if (bitmap != null) image.setImageBitmap(bitmap)
                else Toast.makeText(activity, "Фото не загрузилось", Toast.LENGTH_SHORT).show()
            }
        }

        val save = TextView(activity)
        save.text = "Сохранить"
        save.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
        save.setTextColor(Color.WHITE)
        save.setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))
        save.setOnClickListener { download(activity, urls[index], "max_" + System.currentTimeMillis() + ".jpg", Environment.DIRECTORY_PICTURES) }
        root.addView(save, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM or Gravity.END, 0f, 0f, 8f, 24f))

        val gestures = android.view.GestureDetector(activity, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean = true
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                dialog.dismiss()
                return true
            }
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > AndroidUtilities.dp(80f)) {
                    dialog.dismiss()
                    return true
                }
                if (Math.abs(dx) < AndroidUtilities.dp(40f)) return false
                val next = if (dx < 0) index + 1 else index - 1
                if (next in urls.indices) {
                    index = next
                    load()
                }
                return true
            }
        })
        image.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        dialog.setContentView(root)
        dialog.show()
        load()
    }

    /** Скачивание через системный менеджер загрузок: прогресс в шторке, файл в «Загрузках». */
    fun download(context: Context, url: String, name: String, folder: String = Environment.DIRECTORY_DOWNLOADS) {
        try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(safe)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(folder, "MAX/$safe")
            manager.enqueue(request)
            Toast.makeText(context, "Скачивается: $safe", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            MaxLog.e("скачивание не началось", e)
            Toast.makeText(context, "Не удалось скачать: " + MaxLog.describe(e), Toast.LENGTH_SHORT).show()
        }
    }

    /** Ссылку открывает подходящее приложение: видео — плеер, геопозиция — карты. */
    fun openExternal(context: Context, uri: String, mime: String? = null) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            if (mime != null) intent.setDataAndType(Uri.parse(uri), mime) else intent.data = Uri.parse(uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(context, "Нет приложения, чтобы открыть это", Toast.LENGTH_SHORT).show()
        }
    }

    /** Размер файла по-человечески. */
    fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> String.format("%.1f КБ", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f МБ", bytes / 1024.0 / 1024.0)
        else -> String.format("%.2f ГБ", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    /** Длительность: у MAX в миллисекундах (у Komet голосовые так и записываются). */
    fun durationText(ms: Long): String {
        val total = ms / 1000
        return String.format("%d:%02d", total / 60, total % 60)
    }
}
