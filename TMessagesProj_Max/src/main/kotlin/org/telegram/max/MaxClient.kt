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

    /**
     * Параметры клиента сверены с Komet, который у пользователя работает. Пара «версия +
     * хеши отпечатка» должна быть согласованной: хеши dex и so меняются от версии к версии.
     * Когда сервер отвечает «Приложение устарело», брать свежую пару из Komet
     * (lib/core/storage/spoofing_service.dart и lib/core/protocol/chat_cache_fingerprint.dart),
     * а не поднимать версию отдельно — с чужими хешами код молча не приходит.
     */
    private val HOSTS = listOf("api2.oneme.ru", "api.oneme.ru")
    private const val APP_VERSION = "26.23.2"
    private const val BUILD_NUMBER = 6779L
    private val SIGNATURE_DIGEST = hex("1684414033eb263e2c615f8b7df5ed8793850a07656304997fbf07e9e21e1e93")
    private val DEX_DIGEST = hex("38cff46f392dc1734c308be011c2f0d8da152a390b41063dbb2c913e3032f4b3")
    private val SO_DIGEST = hex("634ecc42b246784d975f180b4fecf903df235cdf0476da47163a85630eb1a6a8")

    /** Случайный на каждый запуск процесса, как у официального клиента. */
    private val CLIENT_SESSION_ID = java.security.SecureRandom().nextInt(0x7FFFFFFF).toLong() + 1

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private const val OP_CONTACTS_GET = 8
    private const val OP_CONTACT_INFO = 32
    private const val OP_CHAT_INFO = 48
    private const val OP_CHAT_MARK = 50
    private const val OP_AUTH_REQUEST = 17
    private const val OP_AUTH = 18
    private const val OP_LOGIN = 19
    private const val OP_AUTH_CONFIRM = 23
    private const val OP_AUTH_CHECK_PASSWORD = 115
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

    /** Адресная книга MAX (опкод 8), по имени. */
    @Volatile
    var contacts: List<MaxContact> = emptyList()
        private set

    /** Открытый сейчас чат: входящие в него не увеличивают счётчик непрочитанных. */
    @Volatile
    var openChatId: Long? = null

    /**
     * Последние ответы сервера как есть. Нужны на экране входа: когда код не приходит,
     * понять причину можно только по тому, что реально ответил сервер, а лог приложения
     * с телефона не достать.
     */
    @Volatile
    var diagnostics: String = ""
        private set

    /** Как нас видит сам сервер MAX: страна и признак VPN из рукопожатия. */
    @Volatile
    var serverLocation: String? = null
        private set

    @Volatile
    var serverSeesVpn: Boolean? = null
        private set

    private fun note(line: String) {
        diagnostics = if (diagnostics.isEmpty()) line else diagnostics + "\n" + line
        MaxLog.i(line)
    }

    /** Слушатели изменений списка чатов и входящих сообщений. */
    private val listeners = mutableListOf<Listener>()

    interface Listener {
        fun onChatsChanged() {}
        fun onContactsChanged() {}
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
            val live = session
            if (live != null && live.state == SessionState.ONLINE) return
            if (live != null) {
                handshake(live)
                return
            }
            // Хосты пробуем по очереди: первый отвечает не всегда, а раздел без
            // соединения бесполезен.
            var last: Throwable? = null
            for (host in HOSTS) {
                val candidate = openSession(host)
                // Ставим сессию до рукопожатия: цикл пушей стартует внутри него и
                // берёт её отсюда.
                session = candidate
                try {
                    note("хост: $host")
                    handshake(candidate)
                    return
                } catch (e: Throwable) {
                    last = e
                    session = null
                    MaxLog.e("хост $host не ответил", e)
                    try {
                        candidate.close()
                    } catch (ignored: Throwable) {
                    }
                }
            }
            throw last ?: IllegalStateException("не удалось подключиться")
        }
    }

    private fun openSession(host: String): Session = Session.open(
        Config(
            host = host,
            appVersion = APP_VERSION,
            buildNumber = BUILD_NUMBER,
            deviceId = MaxStore.deviceId,
            instanceId = MaxStore.instanceId,
            clientSessionId = CLIENT_SESSION_ID,
            deviceName = (android.os.Build.MANUFACTURER ?: "") + " " + (android.os.Build.MODEL ?: ""),
            osVersion = "Android " + android.os.Build.VERSION.RELEASE,
            timezone = java.util.TimeZone.getDefault().id,
            arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            pingIntervalSeconds = 10,
        )
    )

    /**
     * Рукопожатие. `callsSeed` из ответа обязателен: без него не посчитать отпечаток
     * клиента, а запрос кода без отпечатка сервер принимает молча и кода не присылает.
     */
    private fun handshake(live: Session) {
        val answer = live.blocking.connect()
        note("рукопожатие: $answer")
        val handshake = JSONObject(answer)
        serverLocation = handshake.optString("location", "").ifEmpty { null }
        serverSeesVpn = if (handshake.has("isVpn")) handshake.optBoolean("isVpn") else null
        callsSeed = handshake.optLong("callsSeed", 0L)
        if (callsSeed == 0L) {
            throw IllegalStateException("сервер не прислал callsSeed, вход невозможен")
        }
        forEachListener { it.onStateChanged() }
        startPushLoop()
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
                connectIfNeeded()
                // Отпечаток обязателен: без него сервер отвечает успехом, но кода не шлёт.
                val mode = fingerprint()
                    ?: throw IllegalStateException("не удалось подтвердить клиент")
                val payload = JSONObject()
                    .put("phone", normalizePhone(phone))
                    .put("type", "START_AUTH")
                    .put("language", "ru")
                    .put("mode", mode)
                val raw = request(OP_AUTH_REQUEST, payload)
                note("запрос кода: $raw")
                val answer = JSONObject(raw)
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
                val raw = request(OP_AUTH, payload)
                val answer = JSONObject(raw)
                // Только имена полей: в ответе может быть постоянный токен, а панель
                // диагностики видна на экране и попадает на скриншоты.
                note("проверка кода, поля: " + answer.keys().asSequence().joinToString(", "))
                // Порядок как у Komet: сначала облачный пароль, потом регистрация, потом вход.
                val challenge = answer.optJSONObject("passwordChallenge")
                if (challenge != null) {
                    val trackId = challenge.optString("trackId", "")
                    if (trackId.isEmpty()) {
                        onResult(IllegalStateException("сервер просит пароль, но не прислал trackId"))
                        return@launch
                    }
                    pendingTrackId = trackId
                    onResult(PasswordRequired(challenge.optString("hint", "").ifEmpty { null }))
                    return@launch
                }
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
                extractAccountId(answer)?.let { MaxStore.accountId = it }
                login(loginToken)
                onResult(null)
            } catch (e: Throwable) {
                MaxLog.e("verifyCode failed", e)
                onResult(e)
            }
        }
    }

    /** trackId облачного пароля: живёт между вводом кода и вводом пароля. */
    @Volatile
    private var pendingTrackId: String? = null

    /**
     * Вход с облачным паролем (двухфакторный). После кода сервер вместо токена входа
     * присылает passwordChallenge с trackId; пароль уходит опкодом 115, в ответ приходит
     * обычный токен входа. Схема сверена с Komet (AccountModule.checkPassword).
     */
    fun checkPassword(password: String, onResult: (Throwable?) -> Unit) {
        val trackId = pendingTrackId
        if (trackId == null) {
            onResult(IllegalStateException("сессия входа устарела, запросите код заново"))
            return
        }
        ensureScope().launch {
            try {
                val payload = JSONObject()
                    .put("trackId", trackId)
                    .put("password", password)
                val answer = JSONObject(request(OP_AUTH_CHECK_PASSWORD, payload))
                if (answer.has("error") && !answer.isNull("error")) {
                    onResult(WrongPassword())
                    return@launch
                }
                val loginToken = answer.optJSONObject("tokenAttrs")
                    ?.optJSONObject("LOGIN")?.optString("token", "")
                if (loginToken.isNullOrEmpty()) {
                    onResult(IllegalStateException("сервер не вернул токен входа"))
                    return@launch
                }
                pendingTrackId = null
                MaxStore.token = loginToken
                extractAccountId(answer)?.let { MaxStore.accountId = it }
                login(loginToken)
                onResult(null)
            } catch (e: Throwable) {
                MaxLog.e("checkPassword failed", e)
                onResult(e)
            }
        }
    }

    /** Номер аккаунта из ответа: profile.contact.id, иначе profile.id — как у Komet. */
    private fun extractAccountId(answer: JSONObject): Long? {
        val profile = answer.optJSONObject("profile") ?: return null
        val contactId = profile.optJSONObject("contact")?.optLong("id", 0L) ?: 0L
        if (contactId != 0L) return contactId
        val profileId = profile.optLong("id", 0L)
        return if (profileId != 0L) profileId else null
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
        pendingTrackId = null
        MaxStore.clear()
        MaxContacts.clear()
        chats = emptyList()
        contacts = emptyList()
        stop()
        forEachListener { it.onChatsChanged() }
    }

    /**
     * Вход по сохранённому токену: в ответе сразу чаты, профиль и люди из этих чатов.
     * Адресную книгу и недостающие имена дозапрашиваем следом, не задерживая список.
     */
    private fun login(token: String) {
        val payload = JSONObject()
            .put("token", token)
            .put("interactive", true)
            .put("presenceSync", -1)
            .put("chatsSync", -1)
            // Как у Komet: бинарное поле 0x0b 0x32, ядро понимает {"$bin": base64}.
            .put("exp", JSONObject().put("chatsCountGroups", JSONObject().put("\$bin", "CzI=")))
        fingerprint()?.let { payload.put("chatCacheFingerprint", it) }
        val answer = JSONObject(request(OP_LOGIN, payload))
        answer.optString("token", "").takeIf { it.isNotEmpty() }?.let { MaxStore.token = it }
        extractAccountId(answer)?.let { MaxStore.accountId = it }
        answer.optJSONObject("profile")?.let { profile ->
            MaxContact.parse(profile.optJSONObject("contact") ?: profile)?.let { MaxContacts.put(it) }
        }
        MaxContacts.putAll(MaxContact.parseList(answer.optJSONArray("contacts")))
        chats = MaxChat.parseList(answer.optJSONArray("chats"))
        MaxLog.i("вход выполнен, чатов: " + chats.size)
        forEachListener { it.onChatsChanged() }
        resolveMissing(chats.map { it.peerId }.filter { it != 0L })
        ensureScope().launch {
            try {
                fetchContacts()
            } catch (e: Throwable) {
                MaxLog.e("контакты не загрузились", e)
            }
        }
    }

    /** Адресная книга: опкод 8, как у Komet (ContactsModule.syncFromServer). */
    private fun fetchContacts() {
        val answer = JSONObject(request(OP_CONTACTS_GET, JSONObject().put("contactsSync", 0)))
        val list = MaxContact.parseList(answer.optJSONArray("contacts"))
        MaxContacts.putAll(list)
        contacts = list.filter { it.id != MaxStore.accountId }.sortedBy { it.name.lowercase() }
        forEachListener { it.onContactsChanged() }
        forEachListener { it.onChatsChanged() }
    }

    fun loadContacts(onDone: (Throwable?) -> Unit) {
        ensureScope().launch {
            try {
                fetchContacts()
                onDone(null)
            } catch (e: Throwable) {
                MaxLog.e("loadContacts failed", e)
                onDone(e)
            }
        }
    }

    /**
     * Дозапрос людей, которых ещё не знаем (собеседники диалогов, авторы в группах):
     * опкод 32 с contactIds. Без него у диалогов не было бы имён.
     */
    fun resolveMissing(ids: Collection<Long>) {
        val missing = ids.filter { it != 0L && !MaxContacts.has(it) }.distinct()
        if (missing.isEmpty()) return
        ensureScope().launch {
            try {
                missing.chunked(100).forEach { part ->
                    val payload = JSONObject().put("contactIds", JSONArray(part))
                    val answer = JSONObject(request(OP_CONTACT_INFO, payload))
                    MaxContacts.putAll(MaxContact.parseList(answer.optJSONArray("contacts")))
                }
                forEachListener { it.onChatsChanged() }
                forEachListener { it.onContactsChanged() }
            } catch (e: Throwable) {
                MaxLog.e("resolveMissing failed", e)
            }
        }
    }

    /** Чат с человеком: существующий диалог или пустой новый (id = мой XOR его). */
    fun chatForContact(contactId: Long): MaxChat {
        val me = MaxStore.accountId
        return chats.firstOrNull { it.isDialog && (it.peerId == contactId || it.id == (me xor contactId)) }
            ?: MaxChat.dialogWith(contactId)
    }

    fun chatById(chatId: Long): MaxChat? = chats.firstOrNull { it.id == chatId }

    /** Отметка прочитанным, как у Komet: опкод 50, READ_MESSAGE, mark — время сообщения. */
    fun markRead(chatId: Long, message: MaxMessage) {
        if (message.id == 0L) return
        chatById(chatId)?.let {
            if (it.unread != 0) {
                it.unread = 0
                forEachListener { l -> l.onChatsChanged() }
            }
        }
        ensureScope().launch {
            try {
                val payload = JSONObject()
                    .put("type", "READ_MESSAGE")
                    .put("chatId", chatId)
                    .put("messageId", message.id)
                    .put("mark", message.time)
                request(OP_CHAT_MARK, payload)
            } catch (e: Throwable) {
                MaxLog.e("markRead failed", e)
            }
        }
    }

    /** Новый чат из пуша: дозапрашиваем его опкодом 48 и ставим наверх. */
    private fun fetchChat(chatId: Long) {
        try {
            val answer = JSONObject(request(OP_CHAT_INFO, JSONObject().put("chatIds", JSONArray(listOf(chatId)))))
            val fresh = MaxChat.parseList(answer.optJSONArray("chats")).firstOrNull() ?: return
            chats = (listOf(fresh) + chats.filter { it.id != chatId }).sortedByDescending { it.sortTime }
            forEachListener { it.onChatsChanged() }
            resolveMissing(listOf(fresh.peerId))
        } catch (e: Throwable) {
            MaxLog.e("fetchChat failed", e)
        }
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

    /**
     * История: [before] — время самого старого показанного сообщения, null — с конца.
     * Сервер отдаёт сообщения до этого времени включительно, дубли отсекает экран.
     */
    fun loadHistory(chatId: Long, before: Long?, count: Int, onResult: (List<MaxMessage>?, Throwable?) -> Unit) {
        ensureScope().launch {
            try {
                val payload = JSONObject()
                    .put("chatId", chatId)
                    .put("from", before ?: (System.currentTimeMillis() + 86_400_000L))
                    .put("forward", 0)
                    .put("backward", count)
                    .put("getMessages", true)
                val answer = JSONObject(request(OP_CHAT_HISTORY, payload))
                val list = MaxMessage.parseList(answer.optJSONArray("messages"))
                resolveMissing(list.map { it.senderId })
                onResult(list, null)
            } catch (e: Throwable) {
                MaxLog.e("loadHistory failed", e)
                onResult(null, e)
            }
        }
    }

    /**
     * Отправка текста. [local] — уже показанное на экране сообщение с тем же cid: в ответ
     * приходит подтверждённое сервером, его и отдаём, чтобы экран заменил локальное.
     */
    fun sendMessage(chatId: Long, local: MaxMessage, onResult: (MaxMessage?, Throwable?) -> Unit) {
        ensureScope().launch {
            try {
                val message = JSONObject()
                    .put("cid", local.cid)
                    .put("text", local.text)
                    .put("elements", JSONArray())
                    .put("attaches", JSONArray())
                val payload = JSONObject()
                    .put("chatId", chatId)
                    .put("message", message)
                    .put("notify", true)
                val answer = JSONObject(request(OP_MSG_SEND, payload))
                val sent = MaxMessage.parse(answer.optJSONObject("message"))
                if (sent != null) onSent(chatId, sent)
                onResult(sent, null)
            } catch (e: Throwable) {
                MaxLog.e("sendMessage failed", e)
                onResult(null, e)
            }
        }
    }

    /** Своё отправленное сообщение поднимает чат наверх, как входящее. */
    private fun onSent(chatId: Long, message: MaxMessage) {
        val existing = chatById(chatId)
        if (existing == null) {
            fetchChat(chatId)
            return
        }
        existing.lastMessage = message
        chats = (listOf(existing) + chats.filter { it.id != chatId })
        forEachListener { it.onChatsChanged() }
    }

    /** Поиск человека по точному номеру телефона. */
    fun findByPhone(phone: String, onResult: (MaxContact?, Throwable?) -> Unit) {
        ensureScope().launch {
            try {
                val payload = JSONObject().put("phone", normalizePhone(phone))
                val answer = JSONObject(request(OP_CONTACT_INFO_BY_PHONE, payload))
                val found = MaxContact.parse(answer.optJSONObject("contact"))
                found?.let { MaxContacts.put(it) }
                onResult(found, null)
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

    /**
     * Входящее сообщение: обновляет превью и счётчик чата, поднимает его наверх и
     * раздаёт экранам. Незнакомый чат дозапрашивается, незнакомый автор — тоже.
     */
    private fun onIncoming(chatId: Long, message: MaxMessage) {
        val existing = chatById(chatId)
        if (existing == null) {
            ensureScope().launch { fetchChat(chatId) }
        } else {
            existing.lastMessage = message
            if (!message.isOutgoing && openChatId != chatId) existing.unread += 1
            chats = (listOf(existing) + chats.filter { it.id != chatId })
            forEachListener { it.onChatsChanged() }
        }
        resolveMissing(listOf(message.senderId))
        forEachListener { it.onNewMessage(chatId, message) }
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
                        if (message != null) onIncoming(chatId, message)
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
            val bytes = Auth.fingerprint(callsSeed, MaxStore.deviceId, SIGNATURE_DIGEST, DEX_DIGEST, SO_DIGEST)
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

/** На аккаунте облачный пароль: после кода нужно ввести его. [hint] — подсказка владельца. */
class PasswordRequired(val hint: String?) : Exception("нужен облачный пароль")

class WrongPassword : Exception("неверный пароль")
