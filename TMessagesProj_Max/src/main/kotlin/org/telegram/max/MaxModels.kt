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

/**
 * Присланный контакт (вложение CONTACT). Поля как у Komet (ContactAttachment):
 * contactId или userId, name или firstName + lastName, phoneNumber, photoUrl или baseUrl.
 * Чего нет во вложении, берём из уже известных людей.
 */
class MaxSharedContact(val id: Long, val name: String, val phone: String, val photoUrl: String?) {
    companion object {
        fun parse(raw: JSONObject): MaxSharedContact {
            val id = raw.optLong("contactId", 0L).takeIf { it != 0L } ?: raw.optLong("userId", 0L)
            val known = if (id != 0L) MaxContacts.get(id) else null
            val full = "${raw.optString("firstName", "")} ${raw.optString("lastName", "")}".trim()
            val name = raw.optString("name", "").trim().ifEmpty { full }.ifEmpty { known?.name ?: "" }.ifEmpty { "Контакт" }
            val rawPhone = raw.opt("phoneNumber")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: raw.opt("phone")?.toString()?.takeIf { it.isNotBlank() && it != "null" && it != "0" }
            val phone = when {
                rawPhone == null -> known?.phoneText ?: ""
                rawPhone.startsWith("+") -> rawPhone
                rawPhone.all { it.isDigit() } -> "+$rawPhone"
                else -> rawPhone
            }
            val photo = raw.optString("photoUrl", "").ifEmpty { raw.optString("baseUrl", "") }.ifEmpty { known?.avatarUrl ?: "" }
            return MaxSharedContact(id, name, phone, photo.ifEmpty { null })
        }
    }
}

/**
 * Вложение сообщения. Поля сверены с Komet (lib/models/attachment.dart): у фото ссылка
 * в baseUrl или url, у файла — fileId (ссылку на скачивание выдаёт опкод 88), у видео —
 * videoId и token (ссылку выдаёт опкод 83), у голосового — прямая ссылка в url.
 */
class MaxAttach(
    val type: String,
    val url: String?,
    val width: Int,
    val height: Int,
    val fileId: Long,
    val name: String?,
    val size: Long,
    val duration: Long,
    val videoId: Long,
    val token: String?,
    /** id голосового для запроса расшифровки (202), только AUDIO. */
    val audioId: Long,
    /** осциллограмма MAX: 80 байт 0..120, как в Komet `wave`. */
    val wave: IntArray,
    val raw: JSONObject,
) {
    val isPhoto get() = type == "PHOTO"
    val isVideo get() = type == "VIDEO"
    val isAudio get() = type == "AUDIO"
    val isFile get() = type == "FILE"
    val isSticker get() = type == "STICKER"

    companion object {
        fun parse(a: JSONObject): MaxAttach {
            val type = a.optString("_type", "").uppercase()
            val url = when (type) {
                "PHOTO" -> a.optString("baseUrl", "").ifEmpty { a.optString("url", "") }
                "AUDIO" -> a.optString("url", "").ifEmpty { a.optString("baseUrl", "") }
                "STICKER" -> a.optString("url", "").ifEmpty { a.optString("baseUrl", "") }
                "VIDEO" -> a.optString("thumbnail", "").ifEmpty { a.optString("baseUrl", "") }
                "FILE" -> a.optJSONObject("preview")?.optString("baseUrl", "") ?: ""
                else -> a.optString("baseUrl", "")
            }.ifEmpty { null }
            return MaxAttach(
                type = type,
                url = url,
                width = a.optInt("width", 0),
                height = a.optInt("height", 0),
                fileId = a.optLong("fileId", 0L),
                name = a.optString("name", "").ifEmpty { null },
                size = a.optLong("size", 0L),
                duration = a.optLong("duration", 0L),
                videoId = a.optLong("videoId", 0L),
                token = a.optString("token", "").ifEmpty { a.optString("videoToken", "") }.ifEmpty { null },
                audioId = a.optLong("audioId", 0L),
                wave = parseWave(a.opt("wave")),
                raw = a,
            )
        }

        /**
         * MAX присылает `wave` массивом байтов 0..120 (как в Komet attachment.dart),
         * иногда строкой тех же кодов или обёрткой `{"$bin": base64}`.
         */
        private fun parseWave(raw: Any?): IntArray = when (raw) {
            is JSONArray -> IntArray(raw.length()) { i ->
                val v = raw.optInt(i, 0)
                (if (v < 0) v + 256 else v).coerceIn(0, 255)
            }
            is String -> if (raw.isEmpty()) IntArray(0) else IntArray(raw.length) { raw[it].code.coerceIn(0, 255) }
            is JSONObject -> {
                val b64 = raw.optString("\$bin", "")
                if (b64.isEmpty()) IntArray(0) else try {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
                } catch (e: Throwable) {
                    IntArray(0)
                }
            }
            else -> IntArray(0)
        }
    }
}

/** Цитата ответа: на какое сообщение отвечают и что в нём было. */
class MaxReply(val messageId: Long, val senderId: Long, val text: String)

/** Сообщение MAX: текст, вложения, ответ или пересылка, служебные события. */
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
    val attaches: List<MaxAttach> = emptyList(),
    val reply: MaxReply? = null,
    /** Переслано: имя исходного автора или канала. */
    val forwardFrom: String? = null,
    val forwardSenderId: Long = 0L,
    val edited: Boolean = false,
) {
    /** Локальное, ещё не подтверждённое сервером. */
    @Volatile
    var pending: Boolean = false

    @Volatile
    var failed: Boolean = false

    /** Реакции: счётчики и моя. Меняются пушем 155 и ответом на свою реакцию. */
    @Volatile
    var reactions: MaxReactions? = null

    val isOutgoing: Boolean
        get() = senderId != 0L && senderId == MaxStore.accountId

    /** Что показать в превью чата или в цитате. */
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

    /** Копия с новым текстом — для локальной правки. */
    fun withText(newText: String): MaxMessage =
        MaxMessage(id, cid, senderId, newText, time, attachLabel, isService, attaches, reply, forwardFrom, forwardSenderId, true)
            .also { it.reactions = reactions }

    companion object {
        fun parse(json: JSONObject?): MaxMessage? {
            if (json == null) return null
            // id приходит то числом, то строкой.
            val id = json.optString("id", "").toLongOrNull() ?: json.optLong("id", 0L)
            val link = json.optJSONObject("link")
            val linkType = link?.optString("type", "")?.uppercase() ?: ""
            // У пересланного содержимое лежит в link.message, а не в самом сообщении.
            val content = if (linkType == "FORWARD") link?.optJSONObject("message") ?: json else json
            val attachArray = content.optJSONArray("attaches")
            val attaches = ArrayList<MaxAttach>()
            if (attachArray != null) {
                for (i in 0 until attachArray.length()) {
                    val a = attachArray.optJSONObject(i) ?: continue
                    if (a.optString("_type", "").uppercase() == "INLINE_KEYBOARD") continue
                    attaches.add(MaxAttach.parse(a))
                }
            }
            val first = attaches.firstOrNull()
            var reply: MaxReply? = null
            if (linkType == "REPLY") {
                val m = link?.optJSONObject("message")
                val mid = m?.optString("id", "")?.toLongOrNull() ?: link?.optLong("messageId", 0L) ?: 0L
                reply = MaxReply(mid, m?.optLong("sender", 0L) ?: 0L, m?.let { parse(it)?.displayText } ?: "Сообщение")
            }
            var forwardFrom: String? = null
            var forwardSender = 0L
            if (linkType == "FORWARD") {
                forwardSender = content.optLong("sender", 0L)
                forwardFrom = link?.optString("chatName", "")?.ifEmpty { null }
            }
            val parsed = MaxMessage(
                id = id,
                cid = json.optLong("cid", 0L),
                senderId = json.optLong("sender", 0L),
                text = localizeServerKey(content.optString("text", "")),
                time = json.optLong("time", 0L),
                attachLabel = if (first != null) attachLabel(first.type, first.raw) else null,
                isService = first?.type == "CONTROL",
                attaches = attaches,
                reply = reply,
                forwardFrom = forwardFrom,
                forwardSenderId = forwardSender,
                edited = json.optString("status", "").uppercase() == "EDITED",
            )
            parsed.reactions = MaxReactions.parse(json.optJSONObject("reactionInfo"))
            return parsed
        }

        /** Сервер иногда шлёт ключ перевода вместо текста (приветствие «Избранного»). */
        private fun localizeServerKey(text: String): String = when (text) {
            "welcome.saved.dialog.message" -> "Сохраняйте здесь сообщения, фото и файлы — они будут доступны на всех устройствах"
            else -> text
        }

        fun parseList(array: JSONArray?): List<MaxMessage> {
            if (array == null) return emptyList()
            val out = ArrayList<MaxMessage>(array.length())
            for (i in 0 until array.length()) parse(array.optJSONObject(i))?.let { out.add(it) }
            out.sortWith(compareBy({ it.time }, { it.id }))
            return out
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
            "CONTACT" -> MaxSharedContact.parse(attach).name.let { if (it == "Контакт") it else "Контакт: $it" }
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

/**
 * Реакции на сообщение, как у Komet (reaction_info.dart): `counters [{reaction, count}]`,
 * `yourReaction`, `totalCount`.
 */
class MaxReactions(val counters: List<Pair<String, Int>>, val mine: String?) {
    val isEmpty: Boolean
        get() = counters.isEmpty()

    /** Копия после моей реакции [emoji] (null — снять), пока сервер не прислал свою. */
    fun withMine(emoji: String?): MaxReactions? {
        val map = LinkedHashMap<String, Int>()
        counters.forEach { (r, c) -> map[r] = c }
        if (mine != null) map[mine] = (map[mine] ?: 1) - 1
        if (emoji != null) map[emoji] = (map[emoji] ?: 0) + 1
        val list = map.filter { it.value > 0 }.map { it.key to it.value }
        return if (list.isEmpty()) null else MaxReactions(list, emoji)
    }

    companion object {
        val QUICK = listOf("👍", "❤️", "🔥", "🤣", "😭", "😍", "👎", "🙏")

        fun parse(json: JSONObject?, keepMine: String? = null): MaxReactions? {
            if (json == null) return null
            val raw = json.optJSONArray("counters") ?: return null
            val list = ArrayList<Pair<String, Int>>()
            for (i in 0 until raw.length()) {
                val c = raw.optJSONObject(i) ?: continue
                val r = c.optString("reaction", "")
                if (r.isNotEmpty()) list.add(r to c.optInt("count", 0))
            }
            if (list.isEmpty()) return null
            val mine = json.optString("yourReaction", "").ifEmpty { keepMine }
            return MaxReactions(list, mine)
        }
    }
}

/**
 * Присутствие людей: в сети ли и когда были. status 1 — в сети, seen — секунды эпохи.
 * Сверено с Komet (chat_parsing.dart, info_cache.dart).
 */
object MaxPresence {
    class Entry(val online: Boolean, val seen: Long)

    private val known = ConcurrentHashMap<Long, Entry>()

    fun get(id: Long): Entry? = known[id]

    fun put(id: Long, json: JSONObject?) {
        if (json == null || id == 0L) return
        known[id] = Entry(json.optInt("status", 0) == 1, json.optLong("seen", 0L))
    }

    /** Словарь «id -> присутствие» из ответа на вход или на опкод 35. */
    fun putAll(map: JSONObject?) {
        if (map == null) return
        map.keys().forEach { k -> k.toLongOrNull()?.let { put(it, map.optJSONObject(k)) } }
    }

    fun clear() = known.clear()
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
    /** Отметки прочтения участников: id -> время последнего прочитанного. */
    val readMarks: MutableMap<Long, Long> = ConcurrentHashMap(),
    /** Закреплённое сообщение (`pinnedMessage`), как у Komet. */
    @Volatile var pinned: MaxMessage? = null,
    val ownerId: Long = 0L,
    val participantsCount: Int = 0,
    /** Публичная ссылка канала или группы (без «https://max.ru/»), если есть. */
    val link: String? = null,
    /** Описание группы или канала. */
    val description: String? = null,
) {
    val isGroup: Boolean
        get() = type == "CHAT" || type == "GROUP"

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

    /** Прочитал ли собеседник (или кто-то в группе) сообщение с этим временем. */
    fun isReadByOthers(time: Long): Boolean {
        val me = MaxStore.accountId
        return readMarks.any { (id, mark) -> id != me && mark >= time }
    }

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
                readMarks = readMarks(json.opt("participants")),
                pinned = MaxMessage.parse(json.optJSONObject("pinnedMessage")),
                ownerId = json.optLong("owner", 0L),
                participantsCount = json.optInt("participantsCount", 0),
                link = json.optString("link", "").ifEmpty { null },
                description = json.optString("description", "").trim().ifEmpty { null },
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

        private fun readMarks(raw: Any?): MutableMap<Long, Long> {
            val out = ConcurrentHashMap<Long, Long>()
            if (raw is JSONObject) raw.keys().forEach { k -> k.toLongOrNull()?.let { out[it] = raw.optLong(k, 0L) } }
            return out
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
