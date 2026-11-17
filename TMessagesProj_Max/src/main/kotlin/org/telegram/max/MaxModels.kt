package org.telegram.max

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Модели раздела MAX. Разбор полей сверен с Komet (lib/backend/modules/chat_parsing.dart,
 * contacts.dart, chat_preview.dart): там это проверено на живом сервере.
 */

/** Человек в MAX: из адресной книги, из участников чатов или найденный по номеру. */
class MaxContact(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val phone: Long,
    val avatarUrl: String?,
) {
    val name: String
        get() = "$firstName $lastName".trim().ifEmpty {
            if (phone > 0) "+$phone" else "Пользователь $id"
        }

    val phoneText: String
        get() = if (phone > 0) "+$phone" else ""

    companion object {
        fun parse(json: JSONObject?): MaxContact? {
            if (json == null) return null
            val id = json.optLong("id", 0L)
            if (id == 0L) return null
            var first = ""
            var last = ""
            preferredName(json.optJSONArray("names"))?.let {
                first = it.optString("firstName", "").ifEmpty { it.optString("name", "") }
                last = it.optString("lastName", "")
            }
            return MaxContact(
                id = id,
                firstName = first,
                lastName = last,
                phone = json.optLong("phone", 0L),
                avatarUrl = json.optString("baseUrl", "").ifEmpty { null },
            )
        }

        fun parseList(array: JSONArray?): List<MaxContact> {
            if (array == null) return emptyList()
            val out = ArrayList<MaxContact>(array.length())
            for (i in 0 until array.length()) parse(array.optJSONObject(i))?.let { out.add(it) }
            return out
        }

        /** Как у Komet: своё имя для контакта (CUSTOM), иначе имя в MAX (ONEME), иначе любое. */
        private fun preferredName(names: JSONArray?): JSONObject? {
            if (names == null) return null
            var oneme: JSONObject? = null
            var any: JSONObject? = null
            for (i in 0 until names.length()) {
                val n = names.optJSONObject(i) ?: continue
                if (any == null) any = n
                when (n.optString("type", "")) {
                    "CUSTOM" -> return n
                    "ONEME" -> if (oneme == null) oneme = n
                }
            }
            return oneme ?: any
        }
    }
}

/**
 * Все известные люди: из ответа на вход, из адресной книги и дозапрошенные по id.
 * Нужны, чтобы у диалогов были имена и аватарки — сами диалоги их не несут.
 */
object MaxContacts {
    private val known = ConcurrentHashMap<Long, MaxContact>()

    fun get(id: Long): MaxContact? = known[id]

    fun put(contact: MaxContact) {
        known[contact.id] = contact
    }

    fun putAll(list: List<MaxContact>) = list.forEach { put(it) }

    fun has(id: Long): Boolean = known.containsKey(id)

    fun clear() = known.clear()
}

/** Сообщение MAX: текст или подпись к вложению, служебные события — отдельным флагом. */
class MaxMessage(
    val id: Long,
    val cid: Long,
    val senderId: Long,
    val text: String,
    val time: Long,
    /** Подпись вложения («Фото», «Файл»…), если оно есть. */
    val attachLabel: String?,
    /** Служебное событие чата («создан чат», «вышел из чата»…). */
    val isService: Boolean,
) {
    /** Локальное, ещё не подтверждённое сервером. */
    @Volatile
    var pending: Boolean = false

    @Volatile
    var failed: Boolean = false

    val isOutgoing: Boolean
        get() = senderId != 0L && senderId == MaxStore.accountId

    /** Что показать в пузыре или в превью чата. */
    val displayText: String
        get() = when {
            text.isNotEmpty() && attachLabel != null && !isService -> "$attachLabel · $text"
            text.isNotEmpty() -> text
            attachLabel != null -> attachLabel
            else -> "Сообщение"
        }

    /** Ключ для поиска дублей: у локального есть только cid, у пришедшего — id. */
    fun sameAs(other: MaxMessage): Boolean =
        (id != 0L && id == other.id) || (cid != 0L && cid == other.cid)

    companion object {
        fun parse(json: JSONObject?): MaxMessage? {
            if (json == null) return null
            // id приходит то числом, то строкой.
            val id = json.optString("id", "").toLongOrNull() ?: json.optLong("id", 0L)
            val attach = firstAttach(json.optJSONArray("attaches"))
            val type = attach?.optString("_type", "")?.uppercase() ?: ""
            return MaxMessage(
                id = id,
                cid = json.optLong("cid", 0L),
                senderId = json.optLong("sender", 0L),
                text = json.optString("text", ""),
                time = json.optLong("time", 0L),
                attachLabel = if (attach != null) attachLabel(type, attach) else null,
                isService = type == "CONTROL",
            )
        }

        fun parseList(array: JSONArray?): List<MaxMessage> {
            if (array == null) return emptyList()
            val out = ArrayList<MaxMessage>(array.length())
            for (i in 0 until array.length()) parse(array.optJSONObject(i))?.let { out.add(it) }
            out.sortWith(compareBy({ it.time }, { it.id }))
            return out
        }

        /** Первое содержательное вложение; клавиатуру ботов пропускаем — у неё есть текст. */
        private fun firstAttach(attaches: JSONArray?): JSONObject? {
            if (attaches == null) return null
            for (i in 0 until attaches.length()) {
                val a = attaches.optJSONObject(i) ?: continue
                if (a.optString("_type", "").uppercase() == "INLINE_KEYBOARD") continue
                return a
            }
            return null
        }

        private fun attachLabel(type: String, attach: JSONObject): String = when (type) {
            "PHOTO" -> "Фото"
            "VIDEO" -> "Видео"
            "AUDIO" -> "Голосовое сообщение"
            "FILE" -> attach.optString("name", "").ifEmpty { "Файл" }
            "STICKER" -> "Стикер"
            "SHARE" -> attach.optString("title", "").ifEmpty { "Ссылка" }
            "POLL" -> "Опрос"
            "LOCATION" -> "Геопозиция"
            "CONTACT" -> "Контакт"
            "CALL" -> "Звонок"
            "CONTROL" -> controlLabel(attach.optString("event", ""))
            else -> "Вложение"
        }

        private fun controlLabel(event: String): String = when (event) {
            "new" -> "Чат создан"
            "add" -> "Добавлен участник"
            "joinByLink" -> "Участник вступил по ссылке"
            "leave" -> "Участник вышел из чата"
            "remove" -> "Участник удалён"
            "pin" -> "Сообщение закреплено"
            "changeTitle" -> "Название чата изменено"
            "changeIcon" -> "Фото чата изменено"
            else -> "Служебное сообщение"
        }
    }
}

/** Чат MAX: личный диалог (DIALOG), группа (CHAT) или канал (CHANNEL). */
class MaxChat(
    val id: Long,
    val type: String,
    private val rawTitle: String?,
    private val iconUrl: String?,
    val participants: List<Long>,
    @Volatile var lastMessage: MaxMessage?,
    @Volatile var unread: Int,
    private val lastEventTime: Long,
) {
    val isDialog: Boolean
        get() = type == "DIALOG"

    val isChannel: Boolean
        get() = type == "CHANNEL"

    /** «Избранное»: диалог с самим собой, у MAX это чат с id 0. */
    val isSaved: Boolean
        get() = id == 0L

    /** Собеседник личного диалога. У MAX id диалога — это XOR id двух собеседников. */
    val peerId: Long
        get() {
            if (!isDialog || isSaved) return 0L
            val me = MaxStore.accountId
            return participants.firstOrNull { it != me } ?: (id xor me)
        }

    val sortTime: Long
        get() = lastMessage?.time ?: lastEventTime

    val title: String
        get() = when {
            isSaved -> "Избранное"
            isDialog -> MaxContacts.get(peerId)?.name ?: rawTitle ?: "Пользователь $peerId"
            else -> rawTitle?.ifEmpty { null } ?: "Чат $id"
        }

    val avatarUrl: String?
        get() = if (isDialog) MaxContacts.get(peerId)?.avatarUrl else iconUrl

    /** Для аватарки-заглушки: стабильный цвет по id. */
    val avatarKey: Long
        get() = if (isDialog) peerId else id

    companion object {
        fun parse(json: JSONObject?): MaxChat? {
            if (json == null) return null
            if (!json.has("id")) return null
            val id = json.optLong("id", 0L)
            return MaxChat(
                id = id,
                type = json.optString("type", "DIALOG"),
                rawTitle = json.optString("title", "").ifEmpty { null },
                iconUrl = json.optString("baseIconUrl", "").ifEmpty { null },
                participants = participantIds(json.opt("participants")),
                lastMessage = MaxMessage.parse(json.optJSONObject("lastMessage")),
                unread = json.optInt("newMessages", 0),
                lastEventTime = json.optLong("lastEventTime", json.optLong("modified", 0L)),
            )
        }

        fun parseList(array: JSONArray?): List<MaxChat> {
            if (array == null) return emptyList()
            val out = ArrayList<MaxChat>(array.length())
            for (i in 0 until array.length()) parse(array.optJSONObject(i))?.let { out.add(it) }
            out.sortByDescending { it.sortTime }
            return out
        }

        /** Пустой диалог с человеком, с которым ещё не переписывались. */
        fun dialogWith(contactId: Long): MaxChat {
            val me = MaxStore.accountId
            return MaxChat(me xor contactId, "DIALOG", null, null, listOf(me, contactId), null, 0, 0L)
        }

        /** Участники приходят словарём «id -> отметка прочтения», иногда списком. */
        private fun participantIds(raw: Any?): List<Long> {
            val out = ArrayList<Long>()
            when (raw) {
                is JSONObject -> raw.keys().forEach { k -> k.toLongOrNull()?.let { out.add(it) } }
                is JSONArray -> for (i in 0 until raw.length()) {
                    val v = raw.opt(i)
                    when (v) {
                        is Number -> out.add(v.toLong())
                        is JSONObject -> v.optLong("id", 0L).takeIf { it != 0L }?.let { out.add(it) }
                    }
                }
            }
            return out
        }
    }
}
