package org.telegram.max

import android.content.Context
import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
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
}

/**
 * Состояние аккаунта MAX. Хранится отдельно от UserConfig: слоты аккаунтов Telegram
 * остаются за MTProto, раздел их не занимает.
 */
object MaxStore {

    private const val PREFS = "max_account"
    private const val KEY_TOKEN = "token"
    private const val KEY_ACCOUNT_ID = "account_id"
    private const val KEY_DEVICE_ID = "device_id"

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

    /** Идентификатор устройства держим постоянным: он участвует в отпечатке клиента. */
    val deviceId: String
        get() {
            val stored = prefs.getString(KEY_DEVICE_ID, null)
            if (stored != null) return stored
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_ACCOUNT_ID).apply()
    }
}
