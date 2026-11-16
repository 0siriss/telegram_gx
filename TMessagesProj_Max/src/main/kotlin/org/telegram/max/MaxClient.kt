package org.telegram.max

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.kolibri.Auth
import ru.kolibri.Config
import ru.kolibri.Kolibri
import ru.kolibri.Session
import ru.kolibri.SessionState
import android.util.Base64
import java.io.File

/**
 * Сессия MAX поверх ядра kolibri. Живёт отдельно от MTProto: свой сокет, свой токен,
 * своё состояние. Публичные методы не suspend — результат приходит в колбэк на том же
 * потоке ввода-вывода, UI сам перекладывает его в главный поток.
 */
object MaxClient {

    const val HOST = "api2.oneme.ru"

    private const val OP_AUTH_REQUEST = 17
    private const val OP_AUTH = 18
    private const val OP_LOGIN = 19
    private const val OP_AUTH_CONFIRM = 23
    private const val OP_CHAT_HISTORY = 49
    private const val OP_MSG_SEND = 64
    private const val OP_CONTACT_INFO_BY_PHONE = 46
    private const val OP_PUBLIC_SEARCH = 60

    /** Пуши, которые нас интересуют: новое сообщение и обновление чата. */
    private const val PUSH_NEW_MESSAGE = 128

    private var session: Session? = null
    private var callsSeed: Long = 0
    private var scope: CoroutineScope? = null
    private var pushJob: Job? = null
    private val connectLock = Any()

    @Volatile
    var chats: List<MaxChat> = emptyList()
        private set

    /** Слушатели изменений списка чатов и входящих сообщений. */
    private val listeners = mutableListOf<Listener>()

    interface Listener {
        fun onChatsChanged() {}
        fun onNewMessage(chatId: Long, message: MaxMessage) {}
        fun onStateChanged() {}
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) { if (!listeners.contains(listener)) listeners.add(listener) }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun forEachListener(body: (Listener) -> Unit) {
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach {
            try {
                body(it)
            } catch (e: Throwable) {
                MaxLog.e("listener failed", e)
            }
        }
    }

    val state: SessionState
        get() = session?.state ?: SessionState.DISCONNECTED

    val isLoggedIn: Boolean
        get() = MaxStore.token != null

    /** Загружена ли нативная библиотека ядра. Без неё раздел недоступен. */
    val isAvailable: Boolean
        get() = MaxNative.available

    private fun ensureScope(): CoroutineScope {
        var current = scope
        if (current == null) {
            current = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = current
        }
        return current
    }

    /**
     * Поднимает сокет и делает рукопожатие. Если токен уже сохранён, сразу выполняет
     * вход и забирает список чатов. Вызывать не обязательно: любой запрос сам поднимает
     * сессию, но экран может позвать это заранее, чтобы показать состояние.
     */
    fun start(onDone: (Throwable?) -> Unit) {
        ensureScope().launch {
            try {
                connectIfNeeded()
                val token = MaxStore.token
                if (token != null) {
                    login(token)
                }
                onDone(null)
            } catch (e: Throwable) {
                MaxLog.e("start failed", e)
                onDone(e)
            }
        }
    }

    /**
     * Открывает сессию и делает рукопожатие, если это ещё не сделано. Синхронно и под
     * блокировкой: два экрана могут позвать запрос одновременно, а сессия нужна одна.
     */
    private fun connectIfNeeded() {
        synchronized(connectLock) {
            configureCore()
            var live = session
            if (live == null) {
                live = Session.open(
                    Config(
                        host = HOST,
                        deviceId = MaxStore.deviceId,
                        instanceId = MaxStore.deviceId,
                        deviceName = android.os.Build.MODEL ?: "Android",
                        osVersion = "Android " + android.os.Build.VERSION.RELEASE,
                        arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
                    )
                )
                session = live
            }
            if (live.state != SessionState.ONLINE) {
                val handshake = JSONObject(live.blocking.connect())
                callsSeed = handshake.optLong("callsSeed", 0L)
                MaxLog.i("рукопожатие выполнено, callsSeed=" + callsSeed)
                forEachListener { it.onStateChanged() }
                startPushLoop()
            }
        }
    }

    private var coreConfigured = false

    /**
     * Настройки ядра, общие на весь процесс. Ставятся один раз до первого соединения.
     *
     * `api2.oneme.ru` отдаёт сертификат Минцифры, которого нет в системном хранилище
     * Android: без этого флага TLS падает с UnknownIssuer и до рукопожатия дело не
     * доходит. Ядро держит этот корень внутри себя и добавляет его только к своим
     * исходящим соединениям, системное хранилище не трогается.
     *
     * Кеш адресов на диске нужен, чтобы соединение поднималось и когда DNS в сети
     * подменён или не отвечает.
     */
    private fun configureCore() {
        if (coreConfigured) return
        try {
            Kolibri.setTrustMincifryCa(true)
            val dir = org.telegram.messenger.ApplicationLoader.applicationContext.filesDir
            Kolibri.setDnsCachePath(File(dir, "kolibri_dns").path)
            coreConfigured = true
        } catch (e: Throwable) {
            MaxLog.e("не удалось настроить ядро", e)
        }
    }

    fun stop() {
        pushJob?.cancel()
        pushJob = null
        try {
            session?.disconnect()
            session?.close()
        } catch (e: Throwable) {
            MaxLog.e("stop failed", e)
        }
        session = null
        scope?.cancel()
        scope = null
    }

    /** Запрос кода подтверждения на номер. Возвращает временный токен. */
    fun requestCode(phone: String, onResult: (String?, Throwable?) -> Unit) {
        ensureScope().launch {
            try {
                val payload = JSONObject()
                    .put("phone", normalizePhone(phone))
                    .put("type", "START_AUTH")
                    .put("language", "ru")
                fingerprint()?.let { payload.put("mode", it) }
                val answer = JSONObject(request(OP_AUTH_REQUEST, payload))
                val token = answer.optString("token", "")
                if (token.isEmpty()) {
                    onResult(null, IllegalStateException("сервер не вернул токен"))
                } else {
                    onResult(token, null)
                }
            } catch (e: Throwable) {
                MaxLog.e("requestCode failed", e)
                onResult(null, e)
            }
        }
    }

    /**
     * Проверка кода. При успехе сохраняет постоянный токен и выполняет вход.
     * Регистрация нового аккаунта не поддерживается: для неё сервер отдаёт REGISTER.
     */
    fun verifyCode(code: String, tempToken: String, onResult: (Throwable?) -> Unit) {
        ensureScope().launch {
            try {
                val payload = JSONObject()
                    .put("token", tempToken)
                    .put("verifyCode", code)
                    .put("authTokenType", "CHECK_CODE")
                val answer = JSONObject(request(OP_AUTH, payload))
                val attrs = answer.optJSONObject("tokenAttrs")
                val loginToken = attrs?.optJSONObject("LOGIN")?.optString("token", "")
                if (loginToken.isNullOrEmpty()) {
                    // Номера ещё нет в MAX: сервер вместо входа отдаёт токен регистрации,
                    // её надо завершить, прислав имя (см. completeRegistration).
                    val registerToken = attrs?.optJSONObject("REGISTER")?.optString("token", "")
                    if (!registerToken.isNullOrEmpty()) {
                        pendingRegisterToken = registerToken
                        onResult(RegistrationRequired())
                    } else {
                        onResult(IllegalStateException("сервер не вернул токен входа"))
                    }
                    return@launch
                }
                pendingRegisterToken = null
                MaxStore.token = loginToken
                answer.optJSONObject("profile")?.optLong("id", 0L)?.let {
                    if (it != 0L) MaxStore.accountId = it
                }
                login(loginToken)
                onResult(null)
            } catch (e: Throwable) {
                MaxLog.e("verifyCode failed", e)
                onResult(e)
            }
        }
    }

    /** Токен регистрации, если номер оказался новым. Живёт до завершения регистрации. */
    @Volatile
    private var pendingRegisterToken: String? = null

    val needsRegistration: Boolean
        get() = pendingRegisterToken != null

    /**
     * Завершение регистрации нового номера: сервер ждёт имя, фамилия необязательна.
     * После успеха сразу выполняется вход.
     */
    fun completeRegistration(firstName: String, lastName: String, onResult: (Throwable?) -> Unit) {
        val token = pendingRegisterToken
        if (token == null) {
            onResult(IllegalStateException("нет токена регистрации"))
            return
        }
        ensureScope().launch {
            try {
                val payload = JSONObject()
                    .put("token", token)
                    .put("tokenType", "REGISTER")
                    .put("firstName", firstName)
                if (lastName.isNotEmpty()) payload.put("lastName", lastName)
                val answer = JSONObject(request(OP_AUTH_CONFIRM, payload))
                val profile = answer.optJSONObject("profile")
                    ?: throw IllegalStateException("сервер не вернул профиль")
                val accountId = profile.optJSONObject("contact")?.optLong("id", 0L) ?: 0L
                if (accountId == 0L) throw IllegalStateException("сервер не вернул номер аккаунта")
                MaxStore.accountId = accountId
                // Токен регистрации после подтверждения становится рабочим токеном входа.
                MaxStore.token = token
                pendingRegisterToken = null
                login(token)
                onResult(null)
            } catch (e: Throwable) {
                MaxLog.e("completeRegistration failed", e)
                onResult(e)
            }
        }
    }

    fun logout() {
        pendingRegisterToken = null
        MaxStore.clear()
        chats = emptyList()
        stop()
        forEachListener { it.onChatsChanged() }
    }

    /** Вход по сохранённому токену: сервер в ответе сразу присылает чаты и контакты. */
    private fun login(token: String) {
        val payload = JSONObject()
            .put("token", token)
            .put("interactive", true)
            .put("presenceSync", -1)
            .put("chatsSync", -1)
            .put("contactsSync", 0)
        fingerprint()?.let { payload.put("chatCacheFingerprint", it) }
        val answer = JSONObject(request(OP_LOGIN, payload))
        answer.optString("token", "").takeIf { it.isNotEmpty() }?.let { MaxStore.token = it }
        val profileId = answer.optJSONObject("profile")?.optLong("id", 0L) ?: 0L
        if (profileId != 0L) MaxStore.accountId = profileId
        chats = MaxChat.parseList(answer.optJSONArray("chats"))
        MaxLog.i("вход выполнен, чатов: " + chats.size)
        forEachListener { it.onChatsChanged() }
    }

    /** Перезапрашивает список чатов по уже сохранённому токену. */
    fun reloadChats(onDone: (Throwable?) -> Unit) {
        val token = MaxStore.token
        if (token == null) {
            onDone(IllegalStateException("нет сохранённого токена"))
            return
        }
        ensureScope().launch {
            try {
                login(token)
                onDone(null)
            } catch (e: Throwable) {
                MaxLog.e("reloadChats failed", e)
                onDone(e)
            }
        }
    }

    fun loadHistory(chatId: Long, count: Int, onResult: (List<MaxMessage>?, Throwable?) -> Unit) {
        ensureScope().launch {
            try {
                val payload = JSONObject()
                    .put("chatId", chatId)
                    .put("from", System.currentTimeMillis() + 86_400_000L)
                    .put("forward", 0)
                    .put("backward", count)
                    .put("getMessages", true)
                val answer = JSONObject(request(OP_CHAT_HISTORY, payload))
                onResult(MaxMessage.parseList(answer.optJSONArray("messages")), null)
            } catch (e: Throwable) {
                MaxLog.e("loadHistory failed", e)
                onResult(null, e)
            }
        }
    }

    fun sendMessage(chatId: Long, text: String, onResult: (Throwable?) -> Unit) {
        ensureScope().launch {
            try {
                val message = JSONObject()
                    .put("cid", -System.currentTimeMillis())
                    .put("text", text)
                val payload = JSONObject()
                    .put("chatId", chatId)
                    .put("message", message)
                    .put("notify", true)
                request(OP_MSG_SEND, payload)
                onResult(null)
            } catch (e: Throwable) {
                MaxLog.e("sendMessage failed", e)
                onResult(e)
            }
        }
    }

    /** Поиск человека по точному номеру телефона. */
    fun findByPhone(phone: String, onResult: (MaxContact?, Throwable?) -> Unit) {
        ensureScope().launch {
            try {
                val payload = JSONObject().put("phone", normalizePhone(phone))
                val answer = JSONObject(request(OP_CONTACT_INFO_BY_PHONE, payload))
                onResult(MaxContact.parse(answer.optJSONObject("contact")), null)
            } catch (e: Throwable) {
                MaxLog.e("findByPhone failed", e)
                onResult(null, e)
            }
        }
    }

    /** Глобальный поиск по имени: публичные чаты и каналы. */
    fun searchPublic(query: String, onResult: (List<MaxChat>?, Throwable?) -> Unit) {
        ensureScope().launch {
            try {
                val payload = JSONObject()
                    .put("type", "ALL")
                    .put("count", 20)
                    .put("query", query)
                val answer = JSONObject(request(OP_PUBLIC_SEARCH, payload))
                onResult(MaxChat.parseList(answer.optJSONArray("chats")), null)
            } catch (e: Throwable) {
                MaxLog.e("searchPublic failed", e)
                onResult(null, e)
            }
        }
    }

    private fun startPushLoop() {
        if (pushJob != null) return
        pushJob = ensureScope().launch {
            val live = session ?: return@launch
            try {
                live.pushes().collect { push ->
                    if (push.opcode != PUSH_NEW_MESSAGE) return@collect
                    try {
                        val body = JSONObject(push.json)
                        val chatId = body.optLong("chatId", 0L)
                        val message = MaxMessage.parse(body.optJSONObject("message"))
                        if (chatId != 0L && message != null) {
                            forEachListener { it.onNewMessage(chatId, message) }
                        }
                    } catch (e: Throwable) {
                        MaxLog.e("push parse failed", e)
                    }
                }
            } catch (e: Throwable) {
                MaxLog.e("push loop stopped", e)
            }
        }
    }

    private fun request(opcode: Int, payload: JSONObject): String {
        // Соединение поднимается здесь, а не на экране: иначе первый же запрос с экрана
        // входа падал с "сессия не открыта".
        connectIfNeeded()
        val live = session ?: throw IllegalStateException("сессия не открыта")
        return live.blocking.requestJson(opcode, payload.toString())
    }

    /**
     * Отпечаток клиента, которого требует сервер при авторизации. Ядро считает его из
     * дайджестов эталонного клиента; в JSON двоичное поле помечается как $bin.
     */
    private fun fingerprint(): JSONObject? {
        if (callsSeed == 0L) {
            MaxLog.e("callsSeed неизвестен — отпечаток не отправляется")
            return null
        }
        return try {
            val bytes = Auth.fingerprint(callsSeed, MaxStore.deviceId)
            JSONObject().put("\$bin", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } catch (e: Throwable) {
            MaxLog.e("fingerprint failed", e)
            null
        }
    }

    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return "+$digits"
    }
}

/** Номер не зарегистрирован: вместо входа нужно завершить регистрацию. */
class RegistrationRequired : Exception("нужна регистрация")

/** Чат MAX: ровно то, что нужно списку и экрану переписки. */
class MaxChat(
    val id: Long,
    val title: String,
    val lastMessage: String,
    val lastTime: Long,
    val unread: Int,
) {
    companion object {
        fun parse(json: JSONObject?): MaxChat? {
            if (json == null) return null
            val id = json.optLong("id", 0L)
            if (id == 0L) return null
            val last = json.optJSONObject("lastMessage")
            return MaxChat(
                id = id,
                title = title(json),
                lastMessage = last?.optString("text", "") ?: "",
                lastTime = last?.optLong("time", 0L) ?: json.optLong("modified", 0L),
                unread = json.optInt("newMessages", 0),
            )
        }

        fun parseList(array: JSONArray?): List<MaxChat> {
            if (array == null) return emptyList()
            val out = ArrayList<MaxChat>(array.length())
            for (i in 0 until array.length()) {
                parse(array.optJSONObject(i))?.let { out.add(it) }
            }
            out.sortByDescending { it.lastTime }
            return out
        }

        /**
         * У групп заголовок лежит в title, у диалогов один на один его нет — там имя
         * собеседника приходит в участниках или в контактах.
         */
        private fun title(json: JSONObject): String {
            val direct = json.optString("title", "")
            if (direct.isNotEmpty()) return direct
            val names = json.optJSONArray("names")
            if (names != null && names.length() > 0) {
                val first = names.optJSONObject(0)
                val name = first?.optString("name", "") ?: ""
                if (name.isNotEmpty()) return name
            }
            return "Чат " + json.optLong("id", 0L)
        }
    }
}

/** Сообщение MAX. Для первой версии только текст. */
class MaxMessage(
    val id: String,
    val senderId: Long,
    val text: String,
    val time: Long,
) {
    val isOutgoing: Boolean
        get() = senderId != 0L && senderId == MaxStore.accountId

    companion object {
        fun parse(json: JSONObject?): MaxMessage? {
            if (json == null) return null
            val text = json.optString("text", "")
            val id = json.optString("id", "")
            return MaxMessage(
                id = if (id.isNotEmpty()) id else json.optLong("cid", 0L).toString(),
                senderId = json.optLong("sender", 0L),
                text = text,
                time = json.optLong("time", 0L),
            )
        }

        fun parseList(array: JSONArray?): List<MaxMessage> {
            if (array == null) return emptyList()
            val out = ArrayList<MaxMessage>(array.length())
            for (i in 0 until array.length()) {
                parse(array.optJSONObject(i))?.let { out.add(it) }
            }
            out.sortBy { it.time }
            return out
        }
    }
}

/** Найденный по номеру человек. */
class MaxContact(val id: Long, val name: String, val phone: String) {
    companion object {
        fun parse(json: JSONObject?): MaxContact? {
            if (json == null) return null
            val id = json.optLong("id", 0L)
            if (id == 0L) return null
            var name = ""
            val names = json.optJSONArray("names")
            if (names != null && names.length() > 0) {
                val entry = names.optJSONObject(0)
                val first = entry?.optString("firstName", "") ?: ""
                val last = entry?.optString("lastName", "") ?: ""
                name = (first + " " + last).trim()
            }
            return MaxContact(id, name, json.optString("phone", ""))
        }
    }
}
