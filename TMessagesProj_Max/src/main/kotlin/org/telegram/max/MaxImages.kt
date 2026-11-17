package org.telegram.max

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Аватарки MAX. Свой маленький загрузчик: ImageLoader Telegram завязан на файлы MTProto,
 * а у MAX это обычные HTTPS-ссылки (baseUrl). Кеш в памяти, загрузка в фоне.
 */
object MaxImages {

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3)

    /**
     * Ставит в [view] аватарку: сразу заглушку с инициалами в цветах Telegram, потом
     * картинку, если есть ссылка. Тег вида защищает от переиспользованной ячейки.
     */
    fun bindAvatar(view: BackupImageView, key: Long, name: String, url: String?, sizeDp: Int) {
        val placeholder = AvatarDrawable()
        val parts = name.trim().split(" ", limit = 2)
        placeholder.setInfo(key, parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "")
        view.setTag(url)
        val cached = url?.let { synchronized(cache) { cache.get(it) } }
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(placeholder)
        if (url == null || synchronized(failed) { failed.contains(url) }) return
        val sizePx = AndroidUtilities.dp(sizeDp.toFloat())
        pool.execute {
            val bitmap = download(url, sizePx)
            if (bitmap == null) {
                synchronized(failed) { failed.add(url) }
                return@execute
            }
            synchronized(cache) { cache.put(url, bitmap) }
            AndroidUtilities.runOnUIThread {
                if (view.tag == url) view.setImageBitmap(bitmap)
            }
        }
    }

    /**
     * Картинка вложения (фото, превью видео, стикер) в [view]. Пока грузится — заливка
     * цветом [placeholder]. Тег защищает от переиспользованной ячейки.
     */
    fun bindImage(view: BackupImageView, url: String?, maxPx: Int, placeholder: Int) {
        view.setTag(url)
        val key = url?.let { "$it#$maxPx" }
        val cached = key?.let { synchronized(cache) { cache.get(it) } }
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(android.graphics.drawable.ColorDrawable(placeholder))
        if (url == null || key == null || synchronized(failed) { failed.contains(url) }) return
        pool.execute {
            val bitmap = download(url, maxPx)
            if (bitmap == null) {
                synchronized(failed) { failed.add(url) }
                return@execute
            }
            synchronized(cache) { cache.put(key, bitmap) }
            AndroidUtilities.runOnUIThread {
                if (view.tag == url) view.setImageBitmap(bitmap)
            }
        }
    }

    /** Картинка целиком для просмотра на весь экран; колбэк на главном потоке. */
    fun loadFull(url: String, maxPx: Int, onDone: (Bitmap?) -> Unit) {
        pool.execute {
            val bitmap = download(url, maxPx)
            AndroidUtilities.runOnUIThread { onDone(bitmap) }
        }
    }

    private fun download(url: String, sizePx: Int): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        val bytes = try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Throwable) {
        MaxLog.e("аватарка не загрузилась", e)
        null
    }
}
