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
    fun showPhoto(activity: Activity, url: String) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(activity)
        root.setBackgroundColor(Color.BLACK)

        val image = ImageView(activity)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val progress = ProgressBar(activity)
        root.addView(progress, LayoutHelper.createFrame(48, 48f, Gravity.CENTER, 0f, 0f, 0f, 0f))

        val save = TextView(activity)
        save.text = "Сохранить"
        save.textSize = 16f
        save.setTextColor(Color.WHITE)
        save.setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))
        save.setOnClickListener { download(activity, url, "max_" + System.currentTimeMillis() + ".jpg", Environment.DIRECTORY_PICTURES) }
        root.addView(save, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM or Gravity.END, 0f, 0f, 8f, 24f))

        root.setOnClickListener { dialog.dismiss() }
        image.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(root)
        dialog.show()

        val size = maxOf(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y).coerceAtLeast(1080)
        MaxImages.loadFull(url, size) { bitmap ->
            progress.visibility = android.view.View.GONE
            if (bitmap != null) image.setImageBitmap(bitmap)
            else Toast.makeText(activity, "Фото не загрузилось", Toast.LENGTH_SHORT).show()
        }
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
