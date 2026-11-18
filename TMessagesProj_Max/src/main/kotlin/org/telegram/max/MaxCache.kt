package org.telegram.max

import org.json.JSONArray
import org.telegram.messenger.ApplicationLoader
import java.io.File

/**
 * Локальный кеш MAX: ответы сервера как есть, в своих файлах (в `MessagesStorage`
 * Telegram ничего не пишем). Список чатов, люди и первая страница истории каждого чата
 * показываются сразу при открытии, а затем обновляются с сервера. Выход из аккаунта
 * кеш стирает.
 */
object MaxCache {

    private val dir: File?
        get() = try {
            File(ApplicationLoader.applicationContext.filesDir, "max_cache").apply { mkdirs() }
        } catch (e: Throwable) {
            null
        }

    private fun write(name: String, array: JSONArray?) {
        if (array == null) return
        try {
            val target = File(dir ?: return, name)
            val tmp = File(target.path + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(target)
        } catch (e: Throwable) {
            MaxLog.e("кеш MAX: не записан $name", e)
        }
    }

    private fun read(name: String): JSONArray? = try {
        val file = File(dir ?: return null, name)
        if (file.exists()) JSONArray(file.readText()) else null
    } catch (e: Throwable) {
        MaxLog.e("кеш MAX: не прочитан $name", e)
        null
    }

    fun saveChats(chats: JSONArray?) = write("chats.json", chats)
    fun loadChats(): JSONArray? = read("chats.json")

    fun savePeople(people: JSONArray?) = write("people.json", people)
    fun loadPeople(): JSONArray? = read("people.json")

    fun saveBook(contacts: JSONArray?) = write("book.json", contacts)
    fun loadBook(): JSONArray? = read("book.json")

    fun saveHistory(chatId: Long, messages: JSONArray?) = write("h_$chatId.json", messages)
    fun loadHistory(chatId: Long): JSONArray? = read("h_$chatId.json")

    fun clear() {
        try {
            dir?.listFiles()?.forEach { it.delete() }
            MaxImages.clearDisk()
        } catch (ignored: Throwable) {
        }
    }
}
