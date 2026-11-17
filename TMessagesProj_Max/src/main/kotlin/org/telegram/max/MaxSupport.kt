package org.telegram.max

import android.content.Context
import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import java.security.SecureRandom
import java.util.UUID

/**
 * Проверка нативной библиотеки ядра. Собирается в CI не под все архитектуры, поэтому
 * раздел должен уметь честно сказать, что он недоступен, вместо падения приложения.
 */
object MaxNative {

    val available: Boolean by lazy {
        try {
            System.loadLibrary("kolibri_kotlin")
            true
        } catch (e: Throwable) {
            MaxLog.e("нативная библиотека MAX недоступна", e)
            false
        }
    }
}

/** Логи раздела идут в общий лог приложения, чтобы их было видно в отладочных выгрузках. */
object MaxLog {
    fun i(message: String) {
        FileLog.d("MAX: $message")
    }

    fun e(message: String, e: Throwable? = null) {
        if (e != null) {
            FileLog.e("MAX: $message", e)
        } else {
            FileLog.e("MAX: $message")
        }
    }

    /**
     * Текст ошибки для экрана. У исключения может не быть сообщения — тогда показываем
     * хотя бы имя класса и причину, иначе пользователь видит пустую строку и сказать по
     * ней нечего.
     */
    fun describe(e: Throwable?): String {
        if (e == null) return "неизвестная ошибка"
        val own = e.message?.trim().orEmpty()
        val cause = e.cause?.message?.trim().orEmpty()
        val text = when {
            own.isNotEmpty() && cause.isNotEmpty() && own != cause -> "$own ($cause)"
            own.isNotEmpty() -> own
            cause.isNotEmpty() -> cause
            else -> e.javaClass.simpleName
        }
        return text
    }
}

/**
 * Состояние аккаунта MAX. Хранится отдельно от UserConfig: слоты аккаунтов Telegram
 * остаются за MTProto, раздел их не занимает.
 */
object MaxStore {

    private const val PREFS = "max_account"
    private const val KEY_TOKEN = "token"
    private const val KEY_ACCOUNT_ID = "account_id"
    // Новые ключи: прежний deviceId был UUID и уже засветился в неудачных попытках входа.
    // Старый ключ "device_id" не читаем, чтобы все установки получили свежую пару.
    private const val KEY_DEVICE_ID = "device_id_hex"
    private const val KEY_INSTANCE_ID = "instance_id"

    private val prefs: SharedPreferences
        get() = ApplicationLoader.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, value)
            editor.apply()
        }

    var accountId: Long
        get() = prefs.getLong(KEY_ACCOUNT_ID, 0L)
        set(value) {
            prefs.edit().putLong(KEY_ACCOUNT_ID, value).apply()
        }

    /**
     * Идентификатор устройства: 16 hex-символов, в формате Android ID, как у официального
     * клиента и у Komet. С UUID сервер принимал запрос кода, но код не отправлял.
     * Постоянный: участвует в отпечатке клиента.
     */
    val deviceId: String
        get() = stored(KEY_DEVICE_ID) {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

    /** Идентификатор установки — отдельный UUID, не совпадает с deviceId. */
    val instanceId: String
        get() = stored(KEY_INSTANCE_ID) { UUID.randomUUID().toString() }

    private fun stored(key: String, generate: () -> String): String {
        val existing = prefs.getString(key, null)
        if (!existing.isNullOrEmpty()) return existing
        val generated = generate()
        prefs.edit().putString(key, generated).apply()
        return generated
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_ACCOUNT_ID).apply()
    }
}
