package org.telegram.max

import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Расшифровки голосовых MAX (аналог Komet TranscriptionCache/TranscriptionPushHandler):
 * текст запрашивается по нажатию кнопки «Т», кэшируется по messageId и встраивается в пузырь.
 */
object MaxVoice {

    private const val FAILED = "Не удалось распознать текст"
    private const val RETRY_DELAY_MS = 2_500L
    private const val MAX_POLLS = 8

    private val texts = ConcurrentHashMap<Long, String>()
    private val expanded = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val pending = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val handler = Handler(Looper.getMainLooper())

    /** Вызывается на UI-потоке после изменения состояния расшифровки. */
    var onChanged: (() -> Unit)? = null

    fun transcript(messageId: Long): String? = texts[messageId]

    fun isExpanded(messageId: Long): Boolean = expanded.contains(messageId)

    fun isPending(messageId: Long): Boolean = pending.contains(messageId)

    /**
     * Кнопка «Т»: свернуть показанный текст или запросить и показать его.
     * Повторный запрос из кэша не нужен, ошибку можно запросить заново.
     */
    fun toggle(chatId: Long, messageId: Long, audioId: Long) {
        if (messageId == 0L) return
        if (expanded.remove(messageId)) {
            notifyChange()
            return
        }
        expanded.add(messageId)
        if (texts[messageId] == FAILED) texts.remove(messageId)
        if (!texts.containsKey(messageId) && !pending.contains(messageId)) request(chatId, messageId, audioId)
        notifyChange()
    }

    private fun request(chatId: Long, messageId: Long, audioId: Long) {
        if (chatId == 0L || messageId == 0L || audioId == 0L) return
        if (!pending.add(messageId)) return
        attempt(chatId, messageId, audioId, 0)
    }

    /** Push 293: сервер сам присылает готовый текст. */
    fun acceptPush(messageId: Long, text: String?) {
        if (messageId == 0L) return
        pending.remove(messageId)
        store(messageId, text)
    }

    private fun attempt(chatId: Long, messageId: Long, audioId: Long, tries: Int) {
        MaxClient.requestTranscription(chatId, messageId, audioId) { status, text ->
            when {
                status == 1 -> {
                    pending.remove(messageId)
                    store(messageId, text)
                }
                status == 0 && tries < MAX_POLLS ->
                    handler.postDelayed({ attempt(chatId, messageId, audioId, tries + 1) }, RETRY_DELAY_MS)
                else -> {
                    pending.remove(messageId)
                    store(messageId, FAILED)
                }
            }
        }
    }

    private fun store(messageId: Long, text: String?) {
        texts[messageId] = text?.ifEmpty { FAILED } ?: FAILED
        notifyChange()
    }

    private fun notifyChange() {
        handler.post { onChanged?.invoke() }
    }
}
