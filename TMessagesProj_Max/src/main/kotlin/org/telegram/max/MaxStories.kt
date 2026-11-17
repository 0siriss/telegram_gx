package org.telegram.max

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Истории MAX, сверено с Komet (models/story.dart, backend/modules/stories.dart).
 * Владелец — `{ownerId, type}`: 0 человек, 1 чат, 2 канал. Кольцо — превью с числом
 * всех и просмотренных историй.
 */
class MaxStoryOwner(val id: Long, val type: Int) {
    fun toJson(): JSONObject = JSONObject().put("ownerId", id).put("type", type)

    companion object {
        fun parse(json: JSONObject?): MaxStoryOwner? {
            if (json == null) return null
            val id = json.optLong("ownerId", 0L)
            if (id == 0L) return null
            return MaxStoryOwner(id, json.optInt("type", 0))
        }
    }
}

class MaxStoryPreview(
    val owner: MaxStoryOwner,
    val updateTime: Long,
    val total: Int,
    @Volatile var read: Int,
) {
    val hasUnread: Boolean
        get() = total > read

    val isEmpty: Boolean
        get() = total <= 0

    /** Имя и аватарка владельца: человек — из контактов, чат или канал — из списка чатов. */
    val title: String
        get() = when {
            owner.id == MaxStore.accountId -> "Ваша история"
            owner.type == 0 -> MaxContacts.get(owner.id)?.name ?: "Пользователь"
            else -> MaxClient.chatById(owner.id)?.title ?: "Канал"
        }

    val avatarUrl: String?
        get() = if (owner.type == 0) MaxContacts.get(owner.id)?.avatarUrl else MaxClient.chatById(owner.id)?.avatarUrl

    companion object {
        fun parse(json: JSONObject?): MaxStoryPreview? {
            val owner = MaxStoryOwner.parse(json?.optJSONObject("owner")) ?: return null
            return MaxStoryPreview(
                owner,
                json!!.optLong("updateTime", 0L),
                json.optInt("totalCount", 0),
                json.optInt("readCount", 0),
            )
        }
    }
}

class MaxStory(
    val id: Long,
    val owner: MaxStoryOwner,
    val time: Long,
    val isVideo: Boolean,
    /** Фото — `photoUrl`/`baseUrl`/`url`; видео — `mp4Url`/`videoUrl`/`MP4_1080`/`baseUrl`. */
    val url: String?,
    val thumbnail: String?,
    val durationMs: Long,
) {
    companion object {
        fun parse(json: JSONObject?): MaxStory? {
            if (json == null) return null
            val owner = MaxStoryOwner.parse(json.optJSONObject("owner")) ?: return null
            val media = json.optJSONObject("media") ?: JSONObject()
            val type = media.optString("_type", "").uppercase()
            fun pick(vararg keys: String): String? = keys.map { media.optString(it, "") }.firstOrNull { it.isNotEmpty() }
            val video = type == "VIDEO"
            return MaxStory(
                json.optLong("id", 0L),
                owner,
                json.optLong("time", 0L),
                video,
                if (video) pick("mp4Url", "videoUrl", "MP4_1080", "MP4_720", "baseUrl") else pick("photoUrl", "baseUrl", "url"),
                media.optString("thumbnail", "").ifEmpty { null },
                media.optLong("duration", 0L),
            )
        }

        fun parseList(array: JSONArray?): List<MaxStory> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { parse(array.optJSONObject(it)) }.sortedBy { it.time }
        }
    }
}

/** Лента колец и загруженные истории владельцев. */
object MaxStoriesStore {
    /** Порядок как в ответе сервера; своя история всегда первой. */
    @Volatile
    var previews: List<MaxStoryPreview> = emptyList()

    val stories = ConcurrentHashMap<Long, List<MaxStory>>()

    fun apply(preview: MaxStoryPreview) {
        val rest = previews.filter { it.owner.id != preview.owner.id }
        previews = if (preview.isEmpty) rest else sorted(listOf(preview) + rest)
        if (preview.isEmpty) stories.remove(preview.owner.id)
    }

    fun replaceAll(list: List<MaxStoryPreview>) {
        previews = sorted(list.filter { !it.isEmpty })
    }

    /** Своя — первой, дальше непросмотренные, потом просмотренные, свежие выше. */
    private fun sorted(list: List<MaxStoryPreview>): List<MaxStoryPreview> =
        list.sortedWith(compareBy<MaxStoryPreview>({ it.owner.id != MaxStore.accountId }, { !it.hasUnread }, { -it.updateTime }))

    fun clear() {
        previews = emptyList()
        stories.clear()
    }
}
