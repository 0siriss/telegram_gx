package org.telegram.max

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Подписи для шапки чата и профиля, в формулировках Telegram. */
object MaxText {

    /** Статус человека: «в сети», «был(а) в 14:05», «был(а) вчера в 21:40», «был(а) 12.11.26». */
    fun presence(userId: Long): String {
        val p = MaxPresence.get(userId) ?: return "был(а) недавно"
        if (p.online) return "в сети"
        if (p.seen <= 0) return "был(а) недавно"
        val seenMs = p.seen * 1000
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = seenMs }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(seenMs))
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "был(а) в $time"
            sameYear && dayDiff == 1 -> "был(а) вчера в $time"
            else -> "был(а) " + SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(seenMs))
        }
    }

    /** Время истории, как в Telegram: «5 мин назад», «3 ч назад», «вчера». */
    fun lastSeenTime(timeMs: Long): String {
        val diff = (System.currentTimeMillis() - timeMs) / 1000
        return when {
            diff < 60 -> "только что"
            diff < 3600 -> "${diff / 60} мин назад"
            diff < 86_400 -> "${diff / 3600} ч назад"
            else -> "вчера"
        }
    }

    fun members(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        val word = when {
            mod10 == 1 && mod100 != 11 -> "участник"
            mod10 in 2..4 && mod100 !in 12..14 -> "участника"
            else -> "участников"
        }
        return "$count $word"
    }

    /** Подпись под именем чата: печатает → статус собеседника → число участников. */
    fun chatSubtitle(chat: MaxChat): String {
        val typingUser = MaxClient.typingIn(chat.id)
        if (typingUser != null) {
            if (chat.isDialog) return "печатает…"
            val name = MaxContacts.get(typingUser)?.firstName?.ifEmpty { null } ?: "Кто-то"
            return "$name печатает…"
        }
        return when {
            chat.isSaved -> ""
            chat.isDialog -> presence(chat.peerId)
            chat.isChannel -> "канал"
            chat.participants.isNotEmpty() -> members(chat.participants.size)
            else -> "группа"
        }
    }
}
