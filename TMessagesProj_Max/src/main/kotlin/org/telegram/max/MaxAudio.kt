package org.telegram.max

import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Проигрывание голосовых MAX. Одно за раз: новое останавливает предыдущее. У голосового
 * в MAX прямая ссылка в поле url вложения, её MediaPlayer открывает сам.
 */
object MaxAudio {

    private var player: MediaPlayer? = null

    /** Какое сообщение играет сейчас (id), 0 — ничего. */
    @Volatile
    var playingId: Long = 0L
        private set

    var onChanged: (() -> Unit)? = null

    fun toggle(messageId: Long, url: String, onError: (Throwable) -> Unit) {
        if (playingId == messageId) {
            stop()
            return
        }
        stop()
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            p.setDataSource(url)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, what, extra ->
                stop()
                onError(IllegalStateException("не удалось воспроизвести ($what/$extra)"))
                true
            }
            p.prepareAsync()
            player = p
            playingId = messageId
            onChanged?.invoke()
        } catch (e: Throwable) {
            stop()
            onError(e)
        }
    }

    fun stop() {
        try {
            player?.release()
        } catch (ignored: Throwable) {
        }
        player = null
        if (playingId != 0L) {
            playingId = 0L
            onChanged?.invoke()
        }
    }
}
