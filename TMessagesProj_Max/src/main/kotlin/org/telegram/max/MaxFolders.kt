package org.telegram.max

import org.json.JSONArray
import org.json.JSONObject

/**
 * Папки чатов MAX, сверено с Komet (backend/models/chat_folder.dart и
 * backend/modules/folders.dart): `include` — явно добавленные чаты, `filters` — типы
 * (числами или именами), правила совпадения как в `chatMatchesFolder`.
 */
class MaxFolder(
    val id: String,
    val title: String,
    val emoji: String?,
    val include: Set<Long>,
    val filters: Set<Int>,
) {
    /** «Все чаты» уже есть первой вкладкой — такую папку не дублируем. */
    val isAllChats: Boolean
        get() = id == "all.chat.folder" || title.trim().lowercase() in setOf("все", "все чаты", "all", "all chats")

    fun matches(chat: MaxChat): Boolean {
        if (chat.id !in include) {
            val types = filters.filter { it in CHAT_TYPES }
            if (types.isEmpty() || types.none { matchesType(it, chat) }) return false
        }
        val roles = filters.filter { it == OWNER || it == ADMIN }
        if (roles.isNotEmpty() && chat.ownerId != MaxStore.accountId) return false
        for (f in filters) {
            when (f) {
                UNREAD -> if (chat.unread <= 0) return false
                READ -> if (chat.unread > 0) return false
            }
        }
        return true
    }

    private fun matchesType(filter: Int, chat: MaxChat): Boolean {
        val me = MaxStore.accountId
        val peer = if (chat.isDialog) chat.peerId else 0L
        val isSelf = chat.isSaved || (peer != 0L && peer == me)
        val isContact = chat.isDialog && !isSelf && MaxClient.contacts.any { it.id == peer }
        return when (filter) {
            CHANNEL -> chat.isChannel
            CHAT -> chat.isGroup
            DIALOG -> chat.isDialog
            CONTACT -> chat.isDialog && isContact
            NOT_CONTACT -> chat.isDialog && !isSelf && !isContact
            else -> false
        }
    }

    companion object {
        const val UNREAD = 0
        const val READ = 1
        const val CHANNEL = 2
        const val CHAT = 3
        const val DIALOG = 4
        const val OWNER = 5
        const val ADMIN = 6
        const val CONTACT = 8
        const val NOT_CONTACT = 9
        const val BOT = 10
        const val ORG = 13
        private val CHAT_TYPES = setOf(CONTACT, NOT_CONTACT, CHAT, CHANNEL, BOT, DIALOG, ORG)
        private val NAMES = mapOf(
            "UNREAD" to UNREAD, "READ" to READ, "CHANNEL" to CHANNEL, "CHAT" to CHAT, "GROUP" to CHAT,
            "DIALOG" to DIALOG, "OWNER" to OWNER, "ADMIN" to ADMIN, "MUTED" to 7, "CONTACT" to CONTACT,
            "NOT_CONTACT" to NOT_CONTACT, "BOT" to BOT, "NOT_MUTED" to 11, "MARKED_UNREAD" to 12, "ORG" to ORG,
        )

        private fun code(raw: Any?): Int? = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: NAMES[raw]
            else -> null
        }

        fun parse(json: JSONObject?): MaxFolder? {
            if (json == null) return null
            val id = json.optString("id", "")
            if (id.isEmpty()) return null
            val include = HashSet<Long>()
            json.optJSONArray("include")?.let { a -> for (i in 0 until a.length()) a.optString(i, "").toLongOrNull()?.let { include.add(it) } }
            val filters = HashSet<Int>()
            json.optJSONArray("filters")?.let { a -> for (i in 0 until a.length()) code(a.opt(i))?.let { filters.add(it) } }
            return MaxFolder(id, json.optString("title", ""), json.optString("emoji", "").ifEmpty { null }, include, filters)
        }

        /** Папки в порядке `foldersOrder`; неизвестные порядку — в конце. */
        fun parseList(folders: JSONArray?, order: JSONArray?): List<MaxFolder> {
            if (folders == null) return emptyList()
            val list = (0 until folders.length()).mapNotNull { parse(folders.optJSONObject(it)) }
            val pos = HashMap<String, Int>()
            if (order != null) for (i in 0 until order.length()) pos.putIfAbsent(order.optString(i, ""), i)
            return list.sortedBy { pos[it.id] ?: Int.MAX_VALUE }
        }
    }
}
