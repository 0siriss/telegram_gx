package org.telegram.max

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.MaxSection
import ru.kolibri.Auth
import ru.kolibri.Config
import ru.kolibri.Kolibri
import ru.kolibri.Session
import ru.kolibri.SessionState
import java.io.File

/**
 * Сессия MAX поверх ядра kolibri. Живёт отдельно от MTProto: свой сокет, свой токен,
 * своё состояние. Публичные методы не suspend — результат приходит в колбэк на потоке
 * ввода-вывода, UI сам перекладывает его в главный поток.
 *
 * Соединение устроено как у Komet (lib/backend/api.dart): автопереподключение ядра
 * выключено, на каждое соединение — новая сессия, сразу после рукопожатия вход по
 * токену, раз в несколько секунд проверка живости. С автопереподключением ядра сессия
 * после обрыва поднималась без входа, и сервер на любой запрос отвечал «Недопустимое
 * состояние сессии».
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

    // Опкоды, сверены с Komet (lib/core/protocol/opcode_map.dart).
    private const val OP_CONTACTS_GET = 8
    private const val OP_AUTH_REQUEST = 17
    private const val OP_AUTH = 18
    private const val OP_LOGIN = 19
    private const val OP_AUTH_CONFIRM = 23
    private const val OP_CONTACT_INFO = 32
    private const val OP_CONTACT_PRESENCE = 35
    private const val OP_CONTACT_INFO_BY_PHONE = 46
    private const val OP_CHAT_INFO = 48
    private const val OP_CHAT_HISTORY = 49
    private const val OP_CHAT_MARK = 50
    private const val OP_CHAT_MEMBERS = 59
    private const val OP_PUBLIC_SEARCH = 60
    private const val OP_MSG_SEND = 64
    private const val OP_MSG_TYPING = 65
    private const val OP_MSG_DELETE = 66
    private const val OP_MSG_EDIT = 67
    private const val OP_PHOTO_UPLOAD = 80
    private const val OP_VIDEO_UPLOAD = 82
    private const val OP_VIDEO_PLAY = 83
    private const val OP_FILE_UPLOAD = 87
    private const val OP_FILE_DOWNLOAD = 88
    private const val OP_AUTH_CHECK_PASSWORD = 115

    private const val PUSH_MESSAGE = 128
    private const val PUSH_TYPING = 129
    private const val PUSH_MARK = 130
    private const val PUSH_PRESENCE = 132
    private const val PUSH_MSG_DELETE = 142

    @Volatile
    private var session: Session? = null

    /** Выполнен ли вход в текущей сессии. После переподключения — снова false. */
    @Volatile
    private var authorized = false

    private var callsSeed: Long = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null
    private var livenessJob: Job? = null
    private val connectLock = Any()

    @Volatile
    var chats: List<MaxChat> = emptyList()
        private set

    /** Адресная книга MAX (опкод 8), по имени. */
    @Volatile
    var contacts: List<MaxContact> = emptyList()
        private set

    /** Открытый сейчас чат: входящие в него не увеличивают счётчик и не дают уведомлений. */
    @Volatile
    var openChatId: Long? = null

    /** Кто и когда печатал: chatId -> (userId, время в мс). */
    private val typing = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, Long>>()

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

    // ---- слушатели ----

    private val listeners = mutableListOf<Listener>()

    interface Listener {
        fun onChatsChanged() {}
        fun onContactsChanged() {}
        /** Новое или изменённое сообщение (у изменённого тот же id). */
        fun onNewMessage(chatId: Long, message: MaxMessage) {}
        fun onMessagesDeleted(chatId: Long, ids: List<Long>) {}
        fun onTyping(chatId: Long, userId: Long) {}
        fun onPresenceChanged(userId: Long) {}
        fun onReadMarkChanged(chatId: Long) {}
        fun onStateChanged() {}
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) { if (!listeners.contains(listener)) listeners.add(listener) }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    /** Список чатов изменился: экраны раздела и счётчик вкладки на главном экране. */
    private fun chatsChanged() {
        forEachListener { it.onChatsChanged() }
        MaxSection.notifyUnreadChanged()
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

    /** Фоновая задача клиента; ошибки ловятся внутри [body]. */
    private fun io(body: () -> Unit) {
        scope.launch {
            try {
                body()
            } catch (e: Throwable) {
                MaxLog.e("фоновая задача упала", e)
            }
        }
    }

    // ---- соединение ----

    private fun healthy(s: Session?): Boolean = s != null && s.state == SessionState.ONLINE

    /**
     * Готовая к работе сессия: соединение есть, рукопожатие сделано, вход выполнен (если
     * есть токен). Под блокировкой: два экрана могут позвать запрос одновременно.
     */
    private fun ensureOnline() {
        synchronized(connectLock) {
            configureCore()
            if (!healthy(session)) openFresh()
            val token = MaxStore.token
            if (!authorized && token != null) loginRaw(token)
        }
        startLiveness()
    }

    /** Новая сессия с нуля: старую закрываем, хосты пробуем по очереди. */
    private fun openFresh() {
        dropSessionLocked()
        var last: Throwable? = null
        for (host in HOSTS) {
            val candidate = openSession(host)
            try {
                note("хост: $host")
                handshake(candidate)
                session = candidate
                startPushLoop(candidate)
                return
            } catch (e: Throwable) {
                last = e
                MaxLog.e("хост $host не ответил", e)
                retire(candidate)
            }
        }
        throw last ?: IllegalStateException("не удалось подключиться")
    }

    private fun dropSessionLocked() {
        authorized = false
        pushJob?.cancel()
        pushJob = null
        session?.let { retire(it) }
        session = null
    }

    /** Отключённые, но ещё не освобождённые сессии: держим ссылку, пока их не закроем. */
    private val retired = java.util.Collections.synchronizedSet(HashSet<Session>())

    /**
     * Сессию сначала только отключаем, а освобождаем через 30 секунд. Освобождение
     * удаляет нативный объект, и если в этот момент другой поток ещё внутри него (цикл
     * пушей ждёт следующий пуш до секунды, запрос ждёт ответа), Rust обращается к
     * удалённой памяти и падает весь процесс — так «Обновить» и ронял приложение.
     * Ссылку держим в [retired], иначе её раньше времени освободит сборщик мусора.
     */
    private fun retire(s: Session) {
        try {
            s.disconnect()
        } catch (ignored: Throwable) {
        }
        retired.add(s)
        scope.launch {
            delay(30_000L)
            retired.remove(s)
            try {
                s.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun dropSession() {
        synchronized(connectLock) { dropSessionLocked() }
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
            // Как у Komet: переподключаемся сами, чтобы после рукопожатия сразу войти.
            autoReconnect = false,
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
    }

    /**
     * Проверка живости, как у Komet: соединение рвётся молча (фон, смена сети), и без
     * неё экран показывал бы старые данные, пока первый же запрос не упадёт.
     */
    private fun startLiveness() {
        if (livenessJob?.isActive == true) return
        livenessJob = scope.launch {
            var backoff = 2_000L
            while (isActive) {
                delay(3_000L)
                if (MaxStore.token == null || healthy(session)) {
                    backoff = 2_000L
                    continue
                }
                try {
                    ensureOnline()
                    backoff = 2_000L
                } catch (e: Throwable) {
                    MaxLog.e("переподключение не удалось", e)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
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

    private fun rawRequest(opcode: Int, payload: JSONObject): String {
        val live = session ?: throw IllegalStateException("нет соединения с MAX")
        return live.blocking.requestJson(opcode, payload.toString())
    }

    /**
     * Запрос с готовой сессией. Если сессия оказалась мёртвой или сервер ответил, что
     * она не в том состоянии, — переподключаемся, входим заново и повторяем один раз.
     */
    private fun request(opcode: Int, payload: JSONObject): String {
        ensureOnline()
        return try {
            rawRequest(opcode, payload)
        } catch (e: Throwable) {
            if (!isSessionProblem(e)) throw e
            MaxLog.e("сессия потеряна, переподключаемся", e)
            dropSession()
            ensureOnline()
            rawRequest(opcode, payload)
        }
    }

    private fun isSessionProblem(e: Throwable): Boolean {
        if (!healthy(session)) return true
        val text = (e.message ?: "").lowercase()
        return text.contains("состояние сессии") || text.contains("session state") ||
            text.contains("not connected") || text.contains("disconnected") || text.contains("closed")
    }

    /**
     * Сбрасывает соединение и входит заново: так «Обновить» получает свежий список чатов.
     * Повторный вход в той же сессии сервер не принимает.
     */
    fun reloadChats(onDone: (Throwable?) -> Unit) {
        io {
            try {
                dropSession()
                ensureOnline()
                onDone(null)
            } catch (e: Throwable) {
                MaxLog.e("reloadChats failed", e)
                onDone(e)
            }
        }
    }

    @Volatile
    private var restored = false

    /**
     * Чаты и люди из кеша — чтобы список появился сразу, ещё до соединения. Вызывается
     * один раз за процесс; свежий вход потом заменит всё серверными данными.
     */
    fun restoreFromCache() {
        if (restored || MaxStore.token == null) return
        restored = true
        try {
            MaxContacts.putAll(MaxContact.parseList(MaxCache.loadPeople()))
            val book = MaxContact.parseList(MaxCache.loadBook())
            MaxContacts.putAll(book)
            if (contacts.isEmpty()) contacts = book.filter { it.id != MaxStore.accountId }.sortedBy { it.name.lowercase() }
            if (chats.isEmpty()) chats = MaxChat.parseList(MaxCache.loadChats())
            if (chats.isNotEmpty()) {
                chatsChanged()
                forEachListener { it.onContactsChanged() }
            }
        } catch (e: Throwable) {
            MaxLog.e("кеш MAX не восстановлен", e)
        }
    }

    /** Первая страница истории из кеша: экран показывает её, пока грузится свежая. */
    fun cachedHistory(chatId: Long): List<MaxMessage> = try {
        MaxMessage.parseList(MaxCache.loadHistory(chatId))
    } catch (e: Throwable) {
        emptyList()
    }

    /**
     * Подключение и вход по сохранённому токену. Экраны зовут при открытии: если всё
     * уже готово, ничего не делает.
     */
    fun start(onDone: (Throwable?) -> Unit) {
        io {
            try {
                ensureOnline()
                onDone(null)
            } catch (e: Throwable) {
                MaxLog.e("start failed", e)
                onDone(e)
            }
        }
    }

    /** Запрос кода подтверждения на номер. Возвращает временный токен. */
    fun requestCode(phone: String, onResult: (String?, Throwable?) -> Unit) {
        scope.launch {
            try {
                ensureOnline()
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
        scope.launch {
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
                synchronized(connectLock) { loginRaw(loginToken) }
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
        scope.launch {
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
                synchronized(connectLock) { loginRaw(loginToken) }
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
        scope.launch {
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
                synchronized(connectLock) { loginRaw(token) }
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
        MaxCache.clear()
        MaxContacts.clear()
        MaxPresence.clear()
        chats = emptyList()
        contacts = emptyList()
        livenessJob?.cancel()
        livenessJob = null
        dropSession()
        chatsChanged()
    }

    /**
     * Вход по токену в текущей сессии. В ответе сразу чаты, профиль, люди из этих чатов
     * и их присутствие. Адресную книгу и недостающие имена дозапрашиваем следом.
     * Звать под [connectLock].
     */
    private fun loginRaw(token: String) {
        val payload = JSONObject()
            .put("token", token)
            .put("interactive", true)
            .put("presenceSync", -1)
            .put("chatsSync", -1)
            // Как у Komet: бинарное поле 0x0b 0x32, ядро понимает {"$bin": base64}.
            .put("exp", JSONObject().put("chatsCountGroups", JSONObject().put("\$bin", "CzI=")))
        fingerprint()?.let { payload.put("chatCacheFingerprint", it) }
        val answer = JSONObject(rawRequest(OP_LOGIN, payload))
        authorized = true
        answer.optString("token", "").takeIf { it.isNotEmpty() }?.let { MaxStore.token = it }
        extractAccountId(answer)?.let { MaxStore.accountId = it }
        answer.optJSONObject("profile")?.let { profile ->
            MaxContact.parse(profile.optJSONObject("contact") ?: profile)?.let { MaxContacts.put(it) }
        }
        MaxContacts.putAll(MaxContact.parseList(answer.optJSONArray("contacts")))
        MaxPresence.putAll(answer.optJSONObject("presence"))
        chats = MaxChat.parseList(answer.optJSONArray("chats"))
        MaxCache.saveChats(answer.optJSONArray("chats"))
        MaxCache.savePeople(answer.optJSONArray("contacts"))
        MaxLog.i("вход выполнен, чатов: " + chats.size)
        chatsChanged()
        resolveMissing(chats.map { it.peerId }.filter { it != 0L })
        io { fetchContacts() }
    }

    // ---- люди ----

    /** Адресная книга: опкод 8, как у Komet (ContactsModule.syncFromServer). */
    private fun fetchContacts() {
        val answer = JSONObject(request(OP_CONTACTS_GET, JSONObject().put("contactsSync", 0)))
        val list = MaxContact.parseList(answer.optJSONArray("contacts"))
        MaxContacts.putAll(list)
        MaxPresence.putAll(answer.optJSONObject("presence"))
        MaxCache.saveBook(answer.optJSONArray("contacts"))
        contacts = list.filter { it.id != MaxStore.accountId }.sortedBy { it.name.lowercase() }
        forEachListener { it.onContactsChanged() }
        chatsChanged()
    }

    fun loadContacts(onDone: (Throwable?) -> Unit) {
        io {
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
        io {
            missing.chunked(100).forEach { part ->
                val payload = JSONObject().put("contactIds", JSONArray(part))
                val answer = JSONObject(request(OP_CONTACT_INFO, payload))
                MaxContacts.putAll(MaxContact.parseList(answer.optJSONArray("contacts")))
            }
            chatsChanged()
            forEachListener { it.onContactsChanged() }
        }
    }

    /** Присутствие людей (опкод 35): «в сети» или когда были. */
    fun refreshPresence(ids: Collection<Long>) {
        val list = ids.filter { it != 0L }.distinct()
        if (list.isEmpty()) return
        io {
            val answer = JSONObject(request(OP_CONTACT_PRESENCE, JSONObject().put("contactIds", JSONArray(list))))
            MaxPresence.putAll(answer.optJSONObject("presence"))
            list.forEach { id -> forEachListener { it.onPresenceChanged(id) } }
        }
    }

    /** Поиск человека по точному номеру телефона. */
    fun findByPhone(phone: String, onResult: (MaxContact?, Throwable?) -> Unit) {
        io {
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

    /** Участники группы (опкод 59), как у Komet: тип MEMBER, постранично. */
    fun loadMembers(chatId: Long, onResult: (List<MaxContact>?, Throwable?) -> Unit) {
        io {
            try {
                val payload = JSONObject()
                    .put("type", "MEMBER")
                    .put("marker", 0)
                    .put("chatId", chatId)
                    .put("count", 100)
                val answer = JSONObject(request(OP_CHAT_MEMBERS, payload))
                val out = ArrayList<MaxContact>()
                val members = answer.optJSONArray("members")
                if (members != null) {
                    for (i in 0 until members.length()) {
                        val m = members.optJSONObject(i) ?: continue
                        val contact = MaxContact.parse(m.optJSONObject("contact")) ?: continue
                        MaxContacts.put(contact)
                        MaxPresence.put(contact.id, m.optJSONObject("presence"))
                        out.add(contact)
                    }
                }
                onResult(out, null)
            } catch (e: Throwable) {
                MaxLog.e("loadMembers failed", e)
                onResult(null, e)
            }
        }
    }

    /** Глобальный поиск по имени: публичные чаты и каналы. */
    fun searchPublic(query: String, onResult: (List<MaxChat>?, Throwable?) -> Unit) {
        io {
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

    // ---- чаты ----

    /** Чат с человеком: существующий диалог или пустой новый (id = мой XOR его). */
    fun chatForContact(contactId: Long): MaxChat {
        val me = MaxStore.accountId
        return chats.firstOrNull { it.isDialog && (it.peerId == contactId || it.id == (me xor contactId)) }
            ?: MaxChat.dialogWith(contactId)
    }

    fun chatById(chatId: Long): MaxChat? = chats.firstOrNull { it.id == chatId }

    /** Кто печатает в чате прямо сейчас (последние 6 секунд), или null. */
    fun typingIn(chatId: Long): Long? {
        val entry = typing[chatId] ?: return null
        return if (System.currentTimeMillis() - entry.second < 6_000L) entry.first else null
    }

    /** Отметка прочитанным, как у Komet: опкод 50, READ_MESSAGE, mark — время сообщения. */
    fun markRead(chatId: Long, message: MaxMessage) {
        if (message.id == 0L) return
        chatById(chatId)?.let {
            if (it.unread != 0) {
                it.unread = 0
                chatsChanged()
            }
        }
        io {
            val payload = JSONObject()
                .put("type", "READ_MESSAGE")
                .put("chatId", chatId)
                .put("messageId", message.id)
                .put("mark", message.time)
            request(OP_CHAT_MARK, payload)
        }
    }

    /** Незнакомый чат: дозапрашиваем его опкодом 48 и ставим наверх. */
    private fun fetchChat(chatId: Long) {
        val answer = JSONObject(request(OP_CHAT_INFO, JSONObject().put("chatIds", JSONArray(listOf(chatId)))))
        val fresh = MaxChat.parseList(answer.optJSONArray("chats")).firstOrNull() ?: return
        chats = (listOf(fresh) + chats.filter { it.id != chatId }).sortedByDescending { it.sortTime }
        chatsChanged()
        resolveMissing(listOf(fresh.peerId))
    }

    // ---- сообщения ----

    /**
     * История: [before] — время самого старого показанного сообщения, null — с конца.
     * Сервер отдаёт сообщения до этого времени включительно, дубли отсекает экран.
     */
    fun loadHistory(chatId: Long, before: Long?, count: Int, onResult: (List<MaxMessage>?, Throwable?) -> Unit) {
        io {
            try {
                val payload = JSONObject()
                    .put("chatId", chatId)
                    .put("from", before ?: (System.currentTimeMillis() + 86_400_000L))
                    .put("forward", 0)
                    .put("backward", count)
                    .put("getMessages", true)
                val answer = JSONObject(request(OP_CHAT_HISTORY, payload))
                val list = MaxMessage.parseList(answer.optJSONArray("messages"))
                if (before == null) MaxCache.saveHistory(chatId, answer.optJSONArray("messages"))
                resolveMissing(list.flatMap { listOf(it.senderId, it.forwardSenderId, it.reply?.senderId ?: 0L) })
                onResult(list, null)
            } catch (e: Throwable) {
                MaxLog.e("loadHistory failed", e)
                onResult(null, e)
            }
        }
    }

    /**
     * Отправка текста, при [replyTo] — ответом. [local] — уже показанное сообщение с тем
     * же cid; в ответ приходит подтверждённое сервером, экран заменяет им локальное.
     */
    fun sendMessage(chatId: Long, local: MaxMessage, replyTo: Long?, onResult: (MaxMessage?, Throwable?) -> Unit) {
        io {
            try {
                val message = JSONObject()
                    .put("cid", local.cid)
                    .put("text", local.text)
                    .put("elements", JSONArray())
                    .put("attaches", JSONArray())
                if (replyTo != null && replyTo != 0L) {
                    message.put("link", JSONObject().put("type", "REPLY").put("chatId", chatId).put("messageId", replyTo))
                }
                onResult(send(chatId, message), null)
            } catch (e: Throwable) {
                MaxLog.e("sendMessage failed", e)
                onResult(null, e)
            }
        }
    }

    /**
     * Опкод 64. Вложение после загрузки сервер обрабатывает не сразу и отвечает ошибкой
     * «…not.ready» — как Komet, повторяем раз в секунду.
     */
    private fun send(chatId: Long, message: JSONObject): MaxMessage? {
        val payload = JSONObject().put("chatId", chatId).put("message", message).put("notify", true)
        var attempt = 0
        while (true) {
            try {
                val answer = JSONObject(request(OP_MSG_SEND, payload))
                val sent = MaxMessage.parse(answer.optJSONObject("message"))
                if (sent != null) onSent(chatId, sent)
                return sent
            } catch (e: Throwable) {
                val notReady = (e.message ?: "").contains("not.ready")
                if (!notReady || ++attempt >= 30) throw e
                Thread.sleep(1_000L)
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
        chatsChanged()
    }

    /** Правка своего сообщения (опкод 67), как у Komet. */
    fun editMessage(chatId: Long, messageId: Long, text: String, onResult: (Throwable?) -> Unit) {
        io {
            try {
                val payload = JSONObject()
                    .put("messageId", messageId)
                    .put("chatId", chatId)
                    .put("elements", JSONArray())
                    .put("text", text)
                request(OP_MSG_EDIT, payload)
                onResult(null)
            } catch (e: Throwable) {
                MaxLog.e("editMessage failed", e)
                onResult(e)
            }
        }
    }

    /** Удаление (опкод 66): у себя или у всех. */
    fun deleteMessages(chatId: Long, ids: List<Long>, forEveryone: Boolean, onResult: (Throwable?) -> Unit) {
        io {
            try {
                val payload = JSONObject()
                    .put("messageIds", JSONArray(ids))
                    .put("chatId", chatId)
                    .put("forMe", !forEveryone)
                    .put("itemType", "REGULAR")
                request(OP_MSG_DELETE, payload)
                forEachListener { it.onMessagesDeleted(chatId, ids) }
                onResult(null)
            } catch (e: Throwable) {
                MaxLog.e("deleteMessages failed", e)
                onResult(e)
            }
        }
    }

    /** Пересылка (опкод 64 со ссылкой FORWARD), как у Komet. */
    fun forwardMessage(targetChatId: Long, sourceChatId: Long, messageId: Long, onResult: (Throwable?) -> Unit) {
        io {
            try {
                val message = JSONObject()
                    .put("isLive", false)
                    .put("detectShare", false)
                    .put("elements", JSONArray())
                    .put("attaches", JSONArray())
                    .put("cid", -System.currentTimeMillis())
                    .put("link", JSONObject().put("type", "FORWARD").put("chatId", sourceChatId).put("messageId", messageId))
                send(targetChatId, message)
                onResult(null)
            } catch (e: Throwable) {
                MaxLog.e("forwardMessage failed", e)
                onResult(e)
            }
        }
    }

    private var lastTypingSent = 0L

    /** «Печатает…» для собеседника (опкод 65), не чаще раза в 4 секунды. */
    fun sendTyping(chatId: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 4_000L || !healthy(session)) return
        lastTypingSent = now
        io { request(OP_MSG_TYPING, JSONObject().put("chatId", chatId).put("type", "TEXT")) }
    }

    // ---- медиа ----

    /**
     * Фото: адрес загрузки (опкод 80), загрузка через ядро, токен из ответа CDN, затем
     * сообщение с вложением PHOTO. Схема сверена с Komet (file_uploader.dart).
     */
    fun sendPhoto(chatId: Long, data: ByteArray, caption: String, onResult: (MaxMessage?, Throwable?) -> Unit) {
        io {
            try {
                val urlAnswer = JSONObject(request(OP_PHOTO_UPLOAD, JSONObject().put("count", 1)))
                val url = urlAnswer.optString("url", "").ifEmpty { throw IllegalStateException("сервер не выдал адрес загрузки") }
                val live = session ?: throw IllegalStateException("нет соединения с MAX")
                val result = live.blocking.uploadPhoto(url, data, "photo.jpg")
                if (result.status != 200) throw IllegalStateException("фото не загрузилось: ${result.status}")
                val token = photoToken(String(result.body, Charsets.UTF_8))
                    ?: throw IllegalStateException("сервер не вернул токен фото")
                val message = JSONObject()
                    .put("cid", -System.currentTimeMillis())
                    .put("attaches", JSONArray().put(JSONObject().put("_type", "PHOTO").put("photoToken", token)))
                if (caption.isNotEmpty()) message.put("text", caption)
                onResult(send(chatId, message), null)
            } catch (e: Throwable) {
                MaxLog.e("sendPhoto failed", e)
                onResult(null, e)
            }
        }
    }

    private fun photoToken(body: String): String? = try {
        val json = JSONObject(body)
        var found: String? = null
        json.optJSONObject("photos")?.let { photos ->
            photos.keys().forEach { k ->
                val t = photos.optJSONObject(k)?.optString("token", "") ?: ""
                if (found == null && t.isNotEmpty()) found = t
            }
        }
        found ?: json.optString("photoToken", "").ifEmpty { null }
    } catch (e: Throwable) {
        MaxLog.e("ответ CDN на фото не разобран: $body", e)
        null
    }

    /** Файл: адрес загрузки (опкод 87 → url, fileId, token), загрузка, сообщение FILE. */
    fun sendFile(chatId: Long, data: ByteArray, name: String, onResult: (MaxMessage?, Throwable?) -> Unit) {
        io {
            try {
                val answer = JSONObject(request(OP_FILE_UPLOAD, JSONObject().put("count", 1)))
                val info = answer.optJSONArray("info")?.optJSONObject(0)
                    ?: throw IllegalStateException("сервер не выдал адрес загрузки")
                val url = info.optString("url", "")
                val fileId = info.optLong("fileId", 0L)
                val token = info.optString("token", "")
                val live = session ?: throw IllegalStateException("нет соединения с MAX")
                val result = live.blocking.uploadFile(url, data, name)
                if (result.status != 200) throw IllegalStateException("файл не загрузился: ${result.status}")
                val attach = JSONObject().put("_type", "FILE")
                if (token.isNotEmpty()) attach.put("token", token) else attach.put("fileId", fileId)
                val message = JSONObject()
                    .put("cid", System.currentTimeMillis())
                    .put("attaches", JSONArray().put(attach))
                onResult(send(chatId, message), null)
            } catch (e: Throwable) {
                MaxLog.e("sendFile failed", e)
                onResult(null, e)
            }
        }
    }

    /**
     * Голосовое: адрес загрузки — опкод 82 с uploaderType 1, type 2 (как у Komet
     * requestAudioUploadUrl), загрузка Ogg/Opus, затем вложение AUDIO с token, duration
     * в миллисекундах и волной из 80 значений.
     */
    fun sendVoice(chatId: Long, data: ByteArray, durationMs: Long, wave: ByteArray, onResult: (MaxMessage?, Throwable?) -> Unit) {
        io {
            try {
                val answer = JSONObject(request(OP_VIDEO_UPLOAD, JSONObject().put("uploaderType", 1).put("type", 2).put("count", 1)))
                val info = answer.optJSONArray("info")?.optJSONObject(0)
                    ?: throw IllegalStateException("сервер не выдал адрес загрузки")
                val url = info.optString("url", "")
                val token = info.optString("token", "")
                if (url.isEmpty() || token.isEmpty()) throw IllegalStateException("сервер не выдал адрес загрузки")
                val live = session ?: throw IllegalStateException("нет соединения с MAX")
                val result = live.blocking.uploadFile(url, data, "voice.ogg")
                if (result.status != 200) throw IllegalStateException("голосовое не загрузилось: ${result.status}")
                val attach = JSONObject()
                    .put("duration", durationMs)
                    .put("_type", "AUDIO")
                    .put("wave", JSONObject().put("\$bin", Base64.encodeToString(wave, Base64.NO_WRAP)))
                    .put("token", token)
                val message = JSONObject()
                    .put("isLive", false)
                    .put("detectShare", false)
                    .put("elements", JSONArray())
                    .put("cid", -System.currentTimeMillis())
                    .put("attaches", JSONArray().put(attach))
                onResult(send(chatId, message), null)
            } catch (e: Throwable) {
                MaxLog.e("sendVoice failed", e)
                onResult(null, e)
            }
        }
    }

    /** Ссылка на скачивание файла (опкод 88 с fileId), как у Komet getFileUrl. */
    fun fileUrl(chatId: Long, messageId: Long, fileId: Long, onResult: (String?, Throwable?) -> Unit) {
        io {
            try {
                val payload = JSONObject()
                    .put("fileId", fileId)
                    .put("chatId", chatId)
                    .put("messageId", messageId)
                    .put("itemType", "REGULAR")
                val answer = JSONObject(request(OP_FILE_DOWNLOAD, payload))
                onResult(answer.optString("url", "").ifEmpty { null }, null)
            } catch (e: Throwable) {
                MaxLog.e("fileUrl failed", e)
                onResult(null, e)
            }
        }
    }

    /** Ссылка на видео (опкод 83): в ответе качества, берём лучшее. */
    fun videoUrl(chatId: Long, messageId: Long, videoId: Long, token: String, onResult: (String?, Throwable?) -> Unit) {
        io {
            try {
                val payload = JSONObject()
                    .put("messageId", messageId)
                    .put("chatId", chatId)
                    .put("token", token)
                    .put("videoId", videoId)
                val answer = JSONObject(request(OP_VIDEO_PLAY, payload))
                var best: String? = null
                answer.keys().forEach { k ->
                    val v = answer.opt(k)
                    if (v is String && v.startsWith("http")) best = v
                }
                onResult(best, null)
            } catch (e: Throwable) {
                MaxLog.e("videoUrl failed", e)
                onResult(null, e)
            }
        }
    }

    // ---- пуши ----

    /**
     * Входящее сообщение (или правка — у неё тот же id): обновляет превью и счётчик чата,
     * поднимает его наверх, раздаёт экранам и, если чат не открыт, показывает уведомление.
     */
    private fun onIncoming(chatId: Long, message: MaxMessage) {
        val existing = chatById(chatId)
        val isNew = existing?.lastMessage?.sameAs(message) != true && !message.edited
        if (existing == null) {
            io { fetchChat(chatId) }
        } else {
            existing.lastMessage = message
            if (isNew && !message.isOutgoing && openChatId != chatId) existing.unread += 1
            chats = (listOf(existing) + chats.filter { it.id != chatId })
            chatsChanged()
        }
        typing.remove(chatId)
        resolveMissing(listOf(message.senderId, message.forwardSenderId))
        forEachListener { it.onNewMessage(chatId, message) }
        if (isNew && !message.isOutgoing && openChatId != chatId) MaxNotifier.show(chatId, message)
    }

    private fun startPushLoop(live: Session) {
        pushJob?.cancel()
        pushJob = scope.launch {
            try {
                live.pushes().collect { push -> handlePush(push.opcode, push.json) }
            } catch (e: Throwable) {
                MaxLog.e("цикл пушей остановлен", e)
            }
        }
    }

    private fun handlePush(opcode: Int, json: String) {
        try {
            val body = JSONObject(json)
            val chatId = body.optLong("chatId", 0L)
            when (opcode) {
                PUSH_MESSAGE -> MaxMessage.parse(body.optJSONObject("message"))?.let { onIncoming(chatId, it) }
                PUSH_TYPING -> {
                    val userId = body.optLong("userId", 0L)
                    if (userId != 0L && userId != MaxStore.accountId) {
                        typing[chatId] = userId to System.currentTimeMillis()
                        forEachListener { it.onTyping(chatId, userId) }
                    }
                }
                PUSH_MARK -> {
                    val userId = body.optLong("userId", 0L)
                    val mark = body.optLong("mark", 0L)
                    if (body.optBoolean("setAsUnread", false) || userId == 0L) return
                    chatById(chatId)?.let { chat ->
                        chat.readMarks[userId] = mark
                        if (userId == MaxStore.accountId) chat.unread = 0
                    }
                    forEachListener { it.onReadMarkChanged(chatId) }
                    chatsChanged()
                }
                PUSH_PRESENCE -> {
                    val userId = body.optLong("userId", 0L)
                    MaxPresence.put(userId, body.optJSONObject("presence"))
                    forEachListener { it.onPresenceChanged(userId) }
                }
                PUSH_MSG_DELETE -> {
                    val id = if (chatId != 0L) chatId else body.optJSONObject("chat")?.optLong("id", 0L) ?: 0L
                    val raw = body.optJSONArray("messageIds") ?: return
                    val ids = (0 until raw.length()).mapNotNull { raw.optString(it, "").toLongOrNull() }
                    forEachListener { it.onMessagesDeleted(id, ids) }
                }
            }
        } catch (e: Throwable) {
            MaxLog.e("пуш $opcode не разобран", e)
        }
    }

    // ---- служебное ----

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
