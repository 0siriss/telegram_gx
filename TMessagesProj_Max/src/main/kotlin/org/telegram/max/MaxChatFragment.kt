package org.telegram.max

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SizeNotifierFrameLayout
import java.io.ByteArrayOutputStream
import java.util.Calendar

/**
 * Переписка в чате MAX в духе экрана чата Telegram: шапка с аватаркой и статусом, обои
 * темы, пузыри со временем и отметками прочтения, ответы и пересылки, фото, видео,
 * файлы и голосовые, меню действий по долгому нажатию, отправка фото и файлов.
 *
 * [contactId] — собеседник нового диалога, которого ещё нет в списке чатов.
 */
class MaxChatFragment(private val chatId: Long, private val contactId: Long = 0L) :
    BaseFragment(), MaxClient.Listener {

    /** Сообщения по времени, от старых к новым. */
    private val messages = ArrayList<MaxMessage>()

    /** Строки списка: разделители дат и сообщения вперемешку. */
    private val rows = ArrayList<Any>()

    private lateinit var listView: RecyclerListView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var input: EditText
    private lateinit var sendButton: ImageView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var avatarView: BackupImageView
    private lateinit var emptyView: TextView
    private lateinit var replyBar: LinearLayout
    private lateinit var replyTitle: TextView
    private lateinit var replyText: TextView
    private lateinit var recordBar: LinearLayout
    private lateinit var recordTime: TextView
    private lateinit var attachButton: ImageView
    private lateinit var header: View
    private lateinit var pinBar: FrameLayout
    private lateinit var pinText: TextView
    private lateinit var inputPanel: View
    private lateinit var searchBar: LinearLayout
    private lateinit var searchCounter: TextView
    private lateinit var goDown: FrameLayout
    private val adapter = Adapter()

    /** Найденные сообщения (от новых к старым) и текущее из них. */
    private var searchResults: List<MaxMessage> = emptyList()
    private var searchIndex = 0

    /** Подсвеченное после перехода сообщение — как вспышка в Telegram. */
    private var highlightId = 0L

    private var loading = false
    private var reachedStart = false

    /** На что отвечаем (или что правим) — показывается плашкой над полем ввода. */
    private var replyTo: MaxMessage? = null
    private var editing: MaxMessage? = null

    private val chat: MaxChat
        get() = MaxClient.chatById(chatId)
            ?: (if (contactId != 0L) MaxChat.dialogWith(contactId) else MaxChat.dialogWith(chatId xor MaxStore.accountId))

    private val headerTicker = Runnable { updateHeader() }

    private lateinit var searchItem: ActionBarMenuItem
    private var muteItem: org.telegram.ui.ActionBar.ActionBarMenuSubItem? = null
    private var joinBar: View? = null

    private fun updateMuteItem() {
        val muted = MaxClient.isMuted(chatId)
        muteItem?.setTextAndIcon(if (muted) "Включить звук" else "Без звука", if (muted) org.telegram.messenger.R.drawable.msg_unmute else org.telegram.messenger.R.drawable.msg_mute)
    }

    private fun confirmClearHistory() {
        val context = parentActivity ?: return
        val builder = AlertDialog.Builder(context).setTitle("Очистить историю")
        if (chat.isDialog && !chat.isSaved) {
            builder.setItems(arrayOf<CharSequence>("Очистить у всех", "Очистить только у меня")) { _, which -> clearHistory(which == 0) }
        } else {
            builder.setMessage("Удалить все сообщения в этом чате у вас?")
            builder.setPositiveButton("Очистить") { _, _ -> clearHistory(false) }
            builder.setNegativeButton("Отмена", null)
        }
        showDialog(builder.create())
    }

    private fun clearHistory(forAll: Boolean) {
        MaxClient.clearHistory(chatId, forAll) { e ->
            AndroidUtilities.runOnUIThread {
                if (e != null) toast("Не получилось: " + MaxLog.describe(e))
                else {
                    messages.clear()
                    rebuild(scrollToEnd = true)
                }
            }
        }
    }

    private fun confirmDeleteChat() {
        val context = parentActivity ?: return
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Удалить чат")
                .setItems(arrayOf<CharSequence>("Удалить у всех", "Удалить только у меня")) { _, which ->
                    MaxClient.deleteChat(chatId, which == 0) { e ->
                        AndroidUtilities.runOnUIThread { if (e != null) toast("Не получилось: " + MaxLog.describe(e)) else finishFragment() }
                    }
                }
                .create()
        )
    }

    /** Канал или группа не из списка (найдены поиском): вместо поля ввода — «Подписаться». */
    private fun createJoinBar(context: Context): View {
        val button = TextView(context)
        button.text = if (chat.isChannel) "Подписаться" else "Вступить"
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
        button.typeface = AndroidUtilities.bold()
        button.gravity = Gravity.CENTER
        button.setTextColor(Theme.getColor(Theme.key_chat_fieldOverlayText))
        button.background = MaxGlass.chatBottom(button, 23f)
        button.setOnClickListener {
            val link = chat.link ?: return@setOnClickListener toast("У этого чата нет публичной ссылки")
            MaxClient.joinByLink(link) { joined, e ->
                AndroidUtilities.runOnUIThread {
                    if (joined == null) toast("Не получилось: " + MaxLog.describe(e))
                    else {
                        joinBar?.visibility = View.GONE
                        inputPanel.visibility = View.VISIBLE
                    }
                }
            }
        }
        return button
    }
    private lateinit var emojiButton: ImageView
    private lateinit var emojiHolder: FrameLayout
    private var emojiView: org.telegram.ui.Components.EmojiView? = null
    private var emojiShown = false

    private fun toggleEmoji() = showEmoji(!emojiShown, openKeyboard = emojiShown)

    /** Панель эмодзи Telegram (EmojiView) вместо клавиатуры и обратно. */
    private fun showEmoji(show: Boolean, openKeyboard: Boolean) {
        val context = parentActivity ?: return
        emojiShown = show
        if (show) {
            if (emojiView == null) {
                val view = org.telegram.ui.Components.EmojiView(null, false, false, false, context, false, null, null, true, null, false)
                view.setDelegate(object : org.telegram.ui.Components.EmojiView.EmojiViewDelegate {
                    override fun onBackspace(): Boolean {
                        input.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
                        return true
                    }

                    override fun onEmojiSelected(emoji: String) {
                        var at = input.selectionEnd
                        if (at < 0) at = 0
                        val span = org.telegram.messenger.Emoji.replaceEmoji(emoji, input.paint.fontMetricsInt, false)
                        input.text.insert(at, span)
                        val end = at + span.length
                        input.setSelection(end, end)
                    }
                })
                emojiHolder.addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
                emojiView = view
            }
            AndroidUtilities.hideKeyboard(input)
            emojiHolder.visibility = View.VISIBLE
            emojiButton.setImageResource(org.telegram.messenger.R.drawable.input_keyboard)
        } else {
            emojiHolder.visibility = View.GONE
            emojiButton.setImageResource(org.telegram.messenger.R.drawable.input_smile)
            if (openKeyboard) {
                input.requestFocus()
                AndroidUtilities.showKeyboard(input)
            }
        }
    }

    override fun onBackPressed(invoked: Boolean): Boolean {
        if (emojiShown) {
            if (invoked) showEmoji(false, openKeyboard = false)
            return false
        }
        return super.onBackPressed(invoked)
    }
    private var sendGlass: android.graphics.drawable.Drawable? = null
    private var wallpaperDrawable: android.graphics.drawable.Drawable? = null
    private lateinit var bottomStack: LinearLayout

    /** Высота зоны шапки: строка состояния + панель. */
    private fun headerHeight(): Int =
        ActionBar.getCurrentActionBarHeight() + (if (actionBar.getOccupyStatusBar()) AndroidUtilities.statusBarHeight else 0)

    /** Отступы ленты под плавающие шапку, закреп и нижние плашки (кнопка «вниз» не в счёт). */
    private fun updateListInsets() {
        if (!::listView.isInitialized || !::bottomStack.isInitialized) return
        val pin = if (::pinBar.isInitialized && pinBar.visibility == View.VISIBLE) AndroidUtilities.dp(56f) else 0
        val goDownSpace = if (::goDown.isInitialized && goDown.visibility != View.GONE) AndroidUtilities.dp(54f) else 0
        val top = headerHeight() + pin + AndroidUtilities.dp(4f)
        val bottom = bottomStack.height - goDownSpace + AndroidUtilities.dp(4f)
        if (listView.paddingTop != top || listView.paddingBottom != bottom) {
            val stick = isAtBottom()
            listView.setPadding(0, top, 0, bottom)
            if (stick && rows.isNotEmpty()) layoutManager.scrollToPositionWithOffset(rows.size - 1, -100000)
        }
    }

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_INFO -> openProfile()
                    MENU_LEAVE -> confirmLeave()
                    MENU_MUTE -> MaxClient.setMuted(chatId, !MaxClient.isMuted(chatId)) { e ->
                        if (e != null) toast("Не получилось: " + MaxLog.describe(e))
                        else AndroidUtilities.runOnUIThread { updateMuteItem() }
                    }
                    MENU_CLEAR -> confirmClearHistory()
                    MENU_DELETE_CHAT -> confirmDeleteChat()
                    MENU_OPEN_SEARCH -> {
                        searchItem.visibility = View.VISIBLE
                        searchItem.openSearch(true)
                    }
                }
            }
        })
        val menu = actionBar.createMenu()
        val search = menu.addItem(MENU_SEARCH, org.telegram.messenger.R.drawable.outline_header_search)
        search.setIsSearchField(true)
            .setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onSearchExpand() {
                    header.visibility = View.GONE
                }

                override fun onSearchCollapse() {
                    header.visibility = View.VISIBLE
                    searchItem.visibility = View.GONE
                    closeSearch()
                }

                override fun onSearchPressed(editText: EditText) {
                    runSearch(editText.text.toString().trim())
                }
            })
        search.setSearchFieldHint("Поиск")
        search.visibility = View.GONE
        searchItem = search
        // Как в Telegram: в шапке только круглое «⋮», поиск — пунктом меню.
        val more = menu.addItem(MENU_MORE, org.telegram.messenger.R.drawable.ic_ab_other)
        more.addSubItem(MENU_OPEN_SEARCH, org.telegram.messenger.R.drawable.msg_search, "Поиск")
        muteItem = more.addSubItem(MENU_MUTE, org.telegram.messenger.R.drawable.msg_mute, "Без звука")
        more.addSubItem(MENU_CLEAR, org.telegram.messenger.R.drawable.msg_clear, "Очистить историю")
        if (chat.isDialog) more.addSubItem(MENU_DELETE_CHAT, org.telegram.messenger.R.drawable.msg_delete, "Удалить чат")
        if (!chat.isSaved) {
            more.addSubItem(MENU_INFO, org.telegram.messenger.R.drawable.msg_info, if (chat.isDialog) "Профиль" else "Информация")
            if (!chat.isDialog) more.addSubItem(MENU_LEAVE, org.telegram.messenger.R.drawable.msg_leave, if (chat.isChannel) "Покинуть канал" else "Покинуть группу")
        }
        val wallpaper = Theme.getCachedWallpaper()
        val root = ChatBackground(context)
        wallpaperDrawable = wallpaper
        root.setBackgroundImage(wallpaper, Theme.isWallpaperMotion())
        // Стекло шапки рисует размытые обои, поэтому источник подключаем до создания плашек.
        MaxGlass.attach(root, wallpaper)
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            MaxGlass.updateWallpaper(wallpaper, v.width, v.height)
        }

        // Шапка ставится в зону самой панели, ниже строки состояния: иначе имя рисуется
        // под часами и значками и кажется пропавшим.
        val headerParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ActionBar.getCurrentActionBarHeight(), Gravity.TOP or Gravity.START)
        headerParams.topMargin = if (actionBar.occupyStatusBar) AndroidUtilities.statusBarHeight else 0
        // Внутри стеклянной плашки заголовка: слева круг «назад», справа круг «⋮».
        headerParams.leftMargin = AndroidUtilities.dp(66f)
        headerParams.rightMargin = AndroidUtilities.dp(74f)
        header = createHeader(context)
        actionBar.addView(header, headerParams)
        MaxGlass.setupChatHeader(actionBar)
        actionBar.setAddToContainer(false)

        // Как в Telegram 13: лента на весь экран, шапка, закреп и поле ввода плавают над ней.
        val listHolder = FrameLayout(context)
        root.addView(listHolder, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        listView = RecyclerListView(context)
        layoutManager = LinearLayoutManager(context)
        layoutManager.stackFromEnd = true
        listView.layoutManager = layoutManager
        listView.adapter = adapter
        listView.clipToPadding = false
        listView.setPadding(0, headerHeight() + AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(70f))
        listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (layoutManager.findFirstVisibleItemPosition() <= 5) loadOlder()
                updateGoDown()
            }
        })
        listView.setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
            val message = rows.getOrNull(position) as? MaxMessage ?: return@OnItemClickListener
            openAttach(message)
        })
        listView.setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { view, position ->
            val message = rows.getOrNull(position) as? MaxMessage ?: return@OnItemLongClickListener false
            showActions(message, view)
            true
        })
        listView.addOnItemTouchListener(SwipeToReply(context))
        listHolder.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        emptyView = TextView(context)
        emptyView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
        emptyView.setTextColor(Theme.getColor(Theme.key_chat_serviceText))
        emptyView.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(14f), Theme.getColor(Theme.key_chat_serviceBackground))
        emptyView.setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(8f), AndroidUtilities.dp(14f), AndroidUtilities.dp(8f))
        emptyView.gravity = Gravity.CENTER
        emptyView.text = "Загружаем сообщения…"
        listHolder.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER, 24f, 0f, 24f, 0f))

        val pinParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 52f, Gravity.TOP, 8f, 0f, 8f, 0f)
        pinParams.topMargin = headerHeight() + AndroidUtilities.dp(2f)
        root.addView(createPinBar(context), pinParams)

        // Низ: кнопка «вниз», плашка ответа, строка ввода (или панель поиска).
        bottomStack = LinearLayout(context)
        bottomStack.orientation = LinearLayout.VERTICAL
        bottomStack.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f), AndroidUtilities.dp(8f))
        bottomStack.addView(createGoDown(context), LayoutHelper.createLinear(46, 46, Gravity.END, 0, 0, 0, 8))
        bottomStack.addView(createReplyBar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 6f))
        inputPanel = createInput(context)
        bottomStack.addView(inputPanel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        bottomStack.addView(createSearchBar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46))
        if (MaxClient.chats.none { it.id == chatId } && !chat.isDialog) {
            val bar = createJoinBar(context)
            joinBar = bar
            inputPanel.visibility = View.GONE
            bottomStack.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46))
        } else if (chat.isChannel && chat.ownerId != MaxStore.accountId) {
            // В канал пишет только владелец: поле ввода не показываем, как в Telegram.
            inputPanel.visibility = View.GONE
        }
        emojiHolder = FrameLayout(context)
        emojiHolder.visibility = View.GONE
        bottomStack.addView(emojiHolder, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 280, 0f, 6f, 0f, 0f))
        bottomStack.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateListInsets() }
        root.addView(bottomStack, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM, 0f, 0f, 0f, 0f))

        root.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP, 0f, 0f, 0f, 0f))

        fragmentView = root
        updateHeader()
        updateMuteItem()
        loadHistory()
        if (chat.isDialog && !chat.isSaved) MaxClient.refreshPresence(listOf(chat.peerId))
        MaxAudio.onChanged = { AndroidUtilities.runOnUIThread { adapter.notifyDataSetChanged() } }
        return root
    }

    /** Обои без сдвига: в Telegram этот слой лежит под шапкой, у нас — ниже неё. */
    private class ChatBackground(context: Context) : SizeNotifierFrameLayout(context) {
        init {
            setOccupyStatusBar(false)
        }

        override fun isActionBarVisible(): Boolean = false
    }

    // ---- шапка ----

    private fun createHeader(context: Context): View {
        val header = FrameLayout(context)
        avatarView = BackupImageView(context)
        avatarView.setRoundRadius(AndroidUtilities.dp(19f))
        header.addView(avatarView, LayoutHelper.createFrame(38, 38f, Gravity.START or Gravity.CENTER_VERTICAL, 0f, 0f, 0f, 0f))

        val texts = LinearLayout(context)
        texts.orientation = LinearLayout.VERTICAL
        titleView = TextView(context)
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
        titleView.typeface = AndroidUtilities.bold()
        titleView.setTextColor(Theme.getColor(Theme.key_actionBarDefaultTitle))
        titleView.isSingleLine = true
        titleView.ellipsize = TextUtils.TruncateAt.END
        texts.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        subtitleView = TextView(context)
        subtitleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
        subtitleView.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubtitle))
        subtitleView.isSingleLine = true
        subtitleView.ellipsize = TextUtils.TruncateAt.END
        texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        header.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START or Gravity.CENTER_VERTICAL, 48f, 0f, 0f, 0f))

        header.setOnClickListener { openProfile() }
        return header
    }

    private fun openProfile() {
        val current = chat
        if (current.isSaved) return
        if (current.isDialog) presentFragment(MaxProfileFragment(chatId, current.peerId))
        else presentFragment(MaxProfileFragment(chatId, 0L))
    }

    private fun updateHeader() {
        if (!::titleView.isInitialized) return
        val current = chat
        val name = current.title
        titleView.text = MaxBubbles.emojify(name, titleView)
        MaxImages.bindAvatar(avatarView, current.avatarKey, name, current.avatarUrl, 38)
        val subtitle = MaxText.chatSubtitle(current)
        subtitleView.text = subtitle
        subtitleView.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        val typingNow = MaxClient.typingIn(chatId) != null
        subtitleView.setTextColor(Theme.getColor(if (typingNow || subtitle == "в сети") Theme.key_actionBarDefaultSubtitle else Theme.key_actionBarDefaultSubtitle))
        AndroidUtilities.cancelRunOnUIThread(headerTicker)
        if (typingNow) AndroidUtilities.runOnUIThread(headerTicker, 6_000L)
        updatePinBar()
    }

    // ---- закреплённое сообщение: плашка под шапкой, как в Telegram ----

    private fun createPinBar(context: Context): View {
        pinBar = FrameLayout(context)
        pinBar.visibility = View.GONE
        val line = View(context)
        line.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(1f), Theme.getColor(Theme.key_chat_topPanelLine))
        pinBar.addView(line, LayoutHelper.createFrame(2, 32f, Gravity.START or Gravity.CENTER_VERTICAL, 12f, 0f, 0f, 0f))
        val texts = LinearLayout(context)
        texts.orientation = LinearLayout.VERTICAL
        val title = TextView(context)
        title.text = "Закреплённое сообщение"
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
        title.typeface = AndroidUtilities.bold()
        title.setTextColor(Theme.getColor(Theme.key_chat_topPanelTitle))
        title.isSingleLine = true
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        pinText = TextView(context)
        pinText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
        pinText.setTextColor(Theme.getColor(Theme.key_chat_topPanelMessage))
        pinText.isSingleLine = true
        pinText.ellipsize = TextUtils.TruncateAt.END
        texts.addView(pinText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        pinBar.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER_VERTICAL, 22f, 0f, 48f, 0f))
        val close = ImageView(context)
        close.setImageResource(org.telegram.messenger.R.drawable.msg_unpin)
        close.scaleType = ImageView.ScaleType.CENTER
        close.colorFilter = android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_chat_topPanelClose), android.graphics.PorterDuff.Mode.SRC_IN)
        close.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1)
        close.setOnClickListener { confirmUnpin() }
        pinBar.addView(close, LayoutHelper.createFrame(48, 48f, Gravity.END or Gravity.CENTER_VERTICAL, 0f, 0f, 0f, 0f))
        pinBar.background = MaxGlass.chatTop(pinBar, 18f)
        pinBar.setOnClickListener { chat.pinned?.let { jumpTo(it.id, it.time) } }
        return pinBar
    }

    private fun updatePinBar() {
        if (!::pinBar.isInitialized) return
        val pinned = chat.pinned
        if (pinned == null || pinned.id == 0L) {
            if (pinBar.visibility != View.GONE) {
                pinBar.visibility = View.GONE
                updateListInsets()
            }
            return
        }
        if (pinBar.visibility != View.VISIBLE) {
            pinBar.visibility = View.VISIBLE
            updateListInsets()
        }
        pinText.text = MaxBubbles.emojify(pinned.displayText.replace('\n', ' '), pinText)
    }

    private fun confirmUnpin() {
        val context = parentActivity ?: return
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Открепить сообщение")
                .setMessage("Открепить сообщение для всех участников чата?")
                .setPositiveButton("Открепить") { _, _ ->
                    MaxClient.pinMessage(chatId, 0L, false) { error ->
                        if (error != null) toast("Не удалось открепить: " + MaxLog.describe(error))
                        else AndroidUtilities.runOnUIThread { updatePinBar() }
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    private fun pin(message: MaxMessage) {
        MaxClient.pinMessage(chatId, message.id, true) { error ->
            if (error != null) toast("Не удалось закрепить: " + MaxLog.describe(error))
            else AndroidUtilities.runOnUIThread {
                if (chat.pinned?.id != message.id) chat.pinned = message
                updatePinBar()
            }
        }
    }

    private fun confirmLeave() {
        val context = parentActivity ?: return
        val what = if (chat.isChannel) "канал" else "группу"
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Покинуть $what")
                .setMessage("Вы уверены, что хотите покинуть «${chat.title}»?")
                .setPositiveButton("Покинуть") { _, _ ->
                    MaxClient.leaveChat(chatId) { error ->
                        AndroidUtilities.runOnUIThread {
                            if (error != null) toast("Не удалось выйти: " + MaxLog.describe(error)) else finishFragment()
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    // ---- кнопка «вниз», как в Telegram ----

    private fun createGoDown(context: Context): View {
        goDown = FrameLayout(context)
        goDown.background = MaxGlass.chatBottom(goDown, 23f)
        val icon = ImageView(context)
        icon.setImageResource(org.telegram.messenger.R.drawable.pagedown)
        icon.scaleType = ImageView.ScaleType.CENTER
        icon.colorFilter = android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons), android.graphics.PorterDuff.Mode.SRC_IN)
        goDown.addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        goDown.visibility = View.INVISIBLE
        goDown.setOnClickListener {
            if (rows.isNotEmpty()) listView.smoothScrollToPosition(rows.size - 1)
        }
        return goDown
    }

    private fun updateGoDown() {
        if (!::goDown.isInitialized) return
        val far = rows.isNotEmpty() && layoutManager.findLastVisibleItemPosition() < rows.size - 4
        val wanted = if (far) View.VISIBLE else View.INVISIBLE
        if (goDown.visibility != wanted) goDown.visibility = wanted
    }

    // ---- поиск по чату: поле в шапке, внизу «N из M» со стрелками, как в Telegram ----

    private fun createSearchBar(context: Context): View {
        searchBar = LinearLayout(context)
        searchBar.orientation = LinearLayout.HORIZONTAL
        searchBar.gravity = Gravity.CENTER_VERTICAL
        searchBar.background = MaxGlass.chatBottom(searchBar, 23f)
        searchBar.visibility = View.GONE
        searchCounter = TextView(context)
        searchCounter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
        searchCounter.typeface = AndroidUtilities.bold()
        searchCounter.setTextColor(Theme.getColor(Theme.key_chat_searchPanelText))
        searchBar.addView(searchCounter, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 16, 0, 0, 0))
        fun arrow(res: Int, step: Int): ImageView {
            val view = ImageView(context)
            view.setImageResource(res)
            view.scaleType = ImageView.ScaleType.CENTER
            view.colorFilter = android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_chat_searchPanelIcons), android.graphics.PorterDuff.Mode.SRC_IN)
            view.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1)
            view.setOnClickListener { stepSearch(step) }
            return view
        }
        searchBar.addView(arrow(org.telegram.messenger.R.drawable.msg_go_up, 1), LayoutHelper.createLinear(48, 48))
        searchBar.addView(arrow(org.telegram.messenger.R.drawable.msg_go_down, -1), LayoutHelper.createLinear(48, 48))
        return searchBar
    }

    private fun runSearch(query: String) {
        if (query.isEmpty()) return
        AndroidUtilities.hideKeyboard(fragmentView)
        searchBar.visibility = View.VISIBLE
        inputPanel.visibility = View.GONE
        replyBar.visibility = View.GONE
        searchCounter.text = "Ищем…"
        MaxClient.searchMessages(chatId, query) { list, error ->
            AndroidUtilities.runOnUIThread {
                if (searchBar.visibility != View.VISIBLE) return@runOnUIThread
                if (error != null || list == null) {
                    searchCounter.text = "Не удалось найти"
                    return@runOnUIThread
                }
                searchResults = list
                searchIndex = 0
                if (list.isEmpty()) searchCounter.text = "Ничего не найдено" else showSearchResult()
            }
        }
    }

    private fun stepSearch(step: Int) {
        if (searchResults.isEmpty()) return
        searchIndex = (searchIndex + step).coerceIn(0, searchResults.size - 1)
        showSearchResult()
    }

    private fun showSearchResult() {
        val found = searchResults.getOrNull(searchIndex) ?: return
        searchCounter.text = "${searchIndex + 1} из ${searchResults.size}"
        jumpTo(found.id, found.time)
    }

    private fun closeSearch() {
        if (!::searchBar.isInitialized) return
        searchResults = emptyList()
        searchBar.visibility = View.GONE
        inputPanel.visibility = View.VISIBLE
        if (replyTo != null || editing != null) replyBar.visibility = View.VISIBLE
    }

    /**
     * Переход к сообщению: если оно уже в списке — прокрутка и вспышка, иначе догружаем
     * историю вверх, пока не дойдём до его времени (не больше 20 страниц).
     */
    private fun jumpTo(messageId: Long, time: Long, attempts: Int = 20) {
        val index = rows.indexOfFirst { it is MaxMessage && it.id == messageId }
        if (index >= 0) {
            highlightId = messageId
            layoutManager.scrollToPositionWithOffset(index, listView.height / 3)
            adapter.notifyItemChanged(index)
            AndroidUtilities.runOnUIThread({
                highlightId = 0L
                val now = rows.indexOfFirst { it is MaxMessage && it.id == messageId }
                if (now >= 0) adapter.notifyItemChanged(now)
            }, 1_500L)
            return
        }
        if (reachedStart || attempts <= 0 || messages.isEmpty() || loading) {
            if (loading && attempts > 0) AndroidUtilities.runOnUIThread({ jumpTo(messageId, time, attempts - 1) }, 300L)
            else toast("Сообщение не найдено в истории")
            return
        }
        loading = true
        MaxClient.loadHistory(chatId, messages.first().time, PAGE) { list, _ ->
            AndroidUtilities.runOnUIThread {
                loading = false
                if (list == null) return@runOnUIThread
                val before = messages.size
                merge(list)
                if (messages.size == before) reachedStart = true
                rebuild(scrollToEnd = false)
                jumpTo(messageId, time, attempts - 1)
            }
        }
    }

    // ---- поле ввода ----

    private fun createReplyBar(context: Context): View {
        replyBar = LinearLayout(context)
        replyBar.orientation = LinearLayout.HORIZONTAL
        replyBar.background = MaxGlass.chatBottom(replyBar, 18f)
        replyBar.setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(6f), AndroidUtilities.dp(4f), AndroidUtilities.dp(6f))
        replyBar.visibility = View.GONE

        val line = View(context)
        line.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(1f), Theme.getColor(Theme.key_chat_replyPanelLine))
        replyBar.addView(line, LayoutHelper.createLinear(2, 34, Gravity.CENTER_VERTICAL))

        val texts = LinearLayout(context)
        texts.orientation = LinearLayout.VERTICAL
        replyTitle = TextView(context)
        replyTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
        replyTitle.typeface = AndroidUtilities.bold()
        replyTitle.setTextColor(Theme.getColor(Theme.key_chat_replyPanelName))
        replyTitle.isSingleLine = true
        texts.addView(replyTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        replyText = TextView(context)
        replyText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
        replyText.setTextColor(Theme.getColor(Theme.key_chat_messagePanelText))
        replyText.isSingleLine = true
        replyText.ellipsize = TextUtils.TruncateAt.END
        texts.addView(replyText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        replyBar.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 8, 0, 0, 0))

        val close = ImageView(context)
        close.setImageResource(org.telegram.messenger.R.drawable.ic_close_white)
        close.scaleType = ImageView.ScaleType.CENTER
        close.colorFilter = android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_chat_replyPanelClose), android.graphics.PorterDuff.Mode.SRC_IN)
        close.setOnClickListener { clearReply() }
        replyBar.addView(close, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL))
        return replyBar
    }

    private fun createInput(context: Context): View {
        val panel = LinearLayout(context)
        panel.orientation = LinearLayout.HORIZONTAL
        panel.gravity = Gravity.BOTTOM

        val attach = ImageView(context)
        attachButton = attach
        attach.setImageResource(org.telegram.messenger.R.drawable.input_attach)
        attach.scaleType = ImageView.ScaleType.CENTER
        attach.colorFilter = android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons), android.graphics.PorterDuff.Mode.SRC_IN)
        attach.background = MaxGlass.chatBottom(attach, 23f)
        attach.setOnClickListener { showAttachMenu() }
        panel.addView(attach, LayoutHelper.createLinear(46, 46, Gravity.BOTTOM))

        // Поле ввода — скруглённая стеклянная плашка, как в Telegram 13.
        val pill = FrameLayout(context)
        pill.background = MaxGlass.chatBottom(pill, 23f)
        pill.minimumHeight = AndroidUtilities.dp(46f)
        panel.addView(pill, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.BOTTOM, 6, 0, 6, 0))

        input = EditText(context)
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f)
        input.setTextColor(Theme.getColor(Theme.key_chat_messagePanelText))
        input.setHintTextColor(Theme.getColor(Theme.key_chat_messagePanelHint))
        input.hint = "Сообщение"
        input.background = null
        input.maxLines = 6
        input.minHeight = AndroidUtilities.dp(46f)
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17f)
        input.setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(11f), AndroidUtilities.dp(12f), AndroidUtilities.dp(11f))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSendButton()
                if (!s.isNullOrBlank() && editing == null) MaxClient.sendTyping(chatId)
            }
        })
        pill.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER_VERTICAL, 34f, 0f, 0f, 0f))

        // Кнопка эмодзи слева в поле, как в Telegram: открывает его же панель EmojiView.
        emojiButton = ImageView(context)
        emojiButton.setImageResource(org.telegram.messenger.R.drawable.input_smile)
        emojiButton.scaleType = ImageView.ScaleType.CENTER
        emojiButton.colorFilter = android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons), android.graphics.PorterDuff.Mode.SRC_IN)
        emojiButton.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1)
        emojiButton.setOnClickListener { toggleEmoji() }
        pill.addView(emojiButton, LayoutHelper.createFrame(44, 46f, Gravity.START or Gravity.BOTTOM, 2f, 0f, 0f, 0f))
        input.setOnClickListener { if (emojiShown) showEmoji(false, openKeyboard = true) }

        // Полоса записи голосового: занимает место поля ввода, пока идёт запись.
        recordBar = LinearLayout(context)
        recordBar.orientation = LinearLayout.HORIZONTAL
        recordBar.gravity = Gravity.CENTER_VERTICAL
        recordBar.visibility = View.GONE
        recordTime = TextView(context)
        recordTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
        recordTime.setTextColor(Theme.getColor(Theme.key_chat_messagePanelText))
        recordBar.addView(recordTime, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 16, 0, 0, 0))
        val cancel = TextView(context)
        cancel.text = "Отмена"
        cancel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
        cancel.typeface = AndroidUtilities.bold()
        cancel.setTextColor(Theme.getColor(Theme.key_text_RedRegular))
        cancel.setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f))
        cancel.setOnClickListener { stopVoice(send = false) }
        recordBar.addView(cancel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        pill.addView(recordBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 46f, Gravity.CENTER_VERTICAL, 0f, 0f, 0f, 0f))

        sendButton = ImageView(context)
        sendButton.setImageResource(org.telegram.messenger.R.drawable.attach_send)
        sendButton.scaleType = ImageView.ScaleType.CENTER
        sendButton.colorFilter = android.graphics.PorterDuffColorFilter(
            Theme.getColor(Theme.key_chat_messagePanelSend), android.graphics.PorterDuff.Mode.SRC_IN
        )
        sendGlass = MaxGlass.chatBottom(sendButton, 23f)
        sendButton.background = sendGlass
        sendButton.setOnClickListener { onSendButton() }
        panel.addView(sendButton, LayoutHelper.createLinear(46, 46, Gravity.BOTTOM))
        updateSendButton()
        return panel
    }

    /** Пустое поле — кнопка микрофона, как в Telegram; с текстом или во время записи — «отправить». */
    private fun updateSendButton() {
        if (!::sendButton.isInitialized) return
        val has = input.text.toString().isNotBlank()
        val voice = !has && editing == null && !MaxRecorder.isRecording
        sendButton.setImageResource(if (voice) org.telegram.messenger.R.drawable.input_mic else org.telegram.messenger.R.drawable.attach_send)
        // Микрофон — на стеклянном круге, «отправить» — белая стрелка на круге цвета темы.
        sendButton.background = if (voice) sendGlass else Theme.createCircleDrawable(AndroidUtilities.dp(46f), Theme.getColor(Theme.key_chat_messagePanelSend))
        sendButton.colorFilter = android.graphics.PorterDuffColorFilter(
            if (voice) Theme.getColor(Theme.key_chat_messagePanelIcons) else 0xffffffff.toInt(),
            android.graphics.PorterDuff.Mode.SRC_IN
        )
        sendButton.alpha = 1f
        sendButton.isEnabled = true
    }

    private val recordTicker = object : Runnable {
        override fun run() {
            if (!MaxRecorder.isRecording) return
            recordTime.text = "● Запись  " + MaxMedia.durationText(MaxRecorder.elapsedMs)
            AndroidUtilities.runOnUIThread(this, 250L)
        }
    }

    private fun onSendButton() {
        when {
            MaxRecorder.isRecording -> stopVoice(send = true)
            input.text.toString().isNotBlank() || editing != null -> onSend()
            else -> startVoice()
        }
    }

    private fun startVoice() {
        val activity = parentActivity ?: return
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!MaxRecorder.start(activity)) {
            toast("Не удалось начать запись")
            return
        }
        input.visibility = View.GONE
        recordBar.visibility = View.VISIBLE
        updateSendButton()
        AndroidUtilities.runOnUIThread(recordTicker)
    }

    private fun stopVoice(send: Boolean) {
        AndroidUtilities.cancelRunOnUIThread(recordTicker)
        if (::recordBar.isInitialized) {
            recordBar.visibility = View.GONE
            input.visibility = View.VISIBLE
        }
        MaxRecorder.stop(send) { result ->
            AndroidUtilities.runOnUIThread { updateSendButton() }
            if (result == null) {
                if (send) toast("Слишком короткое голосовое")
                return@stop
            }
            AndroidUtilities.runOnUIThread { sendRecordedVoice(result) }
        }
        updateSendButton()
    }

    private fun sendRecordedVoice(result: MaxRecorder.Result) {
        val local = MaxMessage(0L, -System.currentTimeMillis(), MaxStore.accountId, "", System.currentTimeMillis(), "Голосовое сообщение", false)
        local.pending = true
        merge(listOf(local))
        rebuild(scrollToEnd = true)
        Thread {
            val bytes = try {
                result.file.readBytes()
            } catch (e: Throwable) {
                replaceLocal(local, null, e)
                return@Thread
            } finally {
                result.file.delete()
            }
            MaxClient.sendVoice(chatId, bytes, result.durationMs, result.wave) { sent, error -> replaceLocal(local, sent, error) }
        }.start()
    }

    override fun onRequestPermissionsResultFragment(requestCode: Int, permissions: Array<String>?, grantResults: IntArray?) {
        if (requestCode == REQ_LOCATION) {
            if (grantResults?.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) sendMyLocation()
            else toast("Без доступа к геолокации отправить её нельзя")
            return
        }
        if (requestCode != REQ_MIC) return
        if (grantResults != null && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startVoice()
        } else {
            toast("Без доступа к микрофону голосовые не записать")
        }
    }

    private fun startReply(message: MaxMessage) {
        editing = null
        replyTo = message
        replyTitle.text = "Ответ " + (MaxContacts.get(message.senderId)?.name ?: "")
        replyText.text = message.displayText
        replyBar.visibility = View.VISIBLE
        input.requestFocus()
        AndroidUtilities.showKeyboard(input)
    }

    private fun startEdit(message: MaxMessage) {
        replyTo = null
        editing = message
        replyTitle.text = "Редактирование"
        replyText.text = message.text
        replyBar.visibility = View.VISIBLE
        input.setText(message.text)
        input.setSelection(input.text.length)
        input.requestFocus()
        AndroidUtilities.showKeyboard(input)
    }

    private fun clearReply() {
        val wasEditing = editing != null
        replyTo = null
        editing = null
        replyBar.visibility = View.GONE
        if (wasEditing) input.setText("")
    }

    // ---- жизненный цикл ----

    override fun onFragmentCreate(): Boolean {
        MaxClient.addListener(this)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        MaxGlass.detach()
        MaxClient.removeListener(this)
        MaxClient.subscribeChat(chatId, false)
        if (MaxClient.openChatId == chatId) MaxClient.openChatId = null
        AndroidUtilities.cancelRunOnUIThread(headerTicker)
        MaxAudio.stop()
        MaxAudio.onChanged = null
        AndroidUtilities.cancelRunOnUIThread(recordTicker)
        if (MaxRecorder.isRecording) MaxRecorder.stop(false) {}
        super.onFragmentDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (chatId in MaxClient.leftChats) {
            finishFragment()
            return
        }
        MaxClient.openChatId = chatId
        MaxClient.subscribeChat(chatId, true)
        MaxNotifier.clear(chatId)
        markRead()
        updateHeader()
    }

    override fun onPause() {
        super.onPause()
        if (MaxClient.openChatId == chatId) MaxClient.openChatId = null
    }

    // ---- история ----

    /** Первая порция истории. Грузим из createView: ответ может прийти раньше вёрстки. */
    private fun loadHistory() {
        // Сначала кеш — переписка видна сразу, потом приходит свежая с сервера.
        val cached = MaxClient.cachedHistory(chatId)
        if (cached.isNotEmpty()) {
            merge(cached)
            rebuild(scrollToEnd = true)
        }
        loading = true
        MaxClient.loadHistory(chatId, null, PAGE) { list, error ->
            AndroidUtilities.runOnUIThread {
                loading = false
                if (error != null || list == null) {
                    // Нового диалога на сервере ещё нет — это не ошибка, просто пусто.
                    reachedStart = true
                    showEmpty(if (contactId != 0L) "Напишите первое сообщение" else "Не удалось загрузить: " + MaxLog.describe(error))
                    return@runOnUIThread
                }
                if (list.size < PAGE) reachedStart = true
                merge(list)
                rebuild(scrollToEnd = true)
                markRead()
            }
        }
    }

    private fun loadOlder() {
        if (loading || reachedStart || messages.isEmpty()) return
        loading = true
        val oldest = messages.first().time
        MaxClient.loadHistory(chatId, oldest, PAGE) { list, _ ->
            AndroidUtilities.runOnUIThread {
                loading = false
                if (list == null) return@runOnUIThread
                val before = messages.size
                merge(list)
                if (messages.size == before) reachedStart = true
                rebuild(scrollToEnd = false)
            }
        }
    }

    /** Добавляет сообщения без дублей и держит порядок по времени. */
    private fun merge(list: List<MaxMessage>) {
        for (m in list) {
            if (m.isOutgoing && m.id < 0) MaxBubbles.rotateOutGradient(wallpaperDrawable)
            val index = messages.indexOfFirst { it.sameAs(m) }
            if (index >= 0) messages[index] = m else messages.add(m)
        }
        messages.sortWith(compareBy({ it.time }, { it.id }))
    }

    private fun rebuild(scrollToEnd: Boolean) {
        if (!::listView.isInitialized) return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val firstRow = rows.getOrNull(firstVisible)
        val offset = listView.getChildAt(0)?.top ?: 0
        rows.clear()
        var lastDay = -1
        val calendar = Calendar.getInstance()
        for (m in messages) {
            calendar.timeInMillis = m.time
            val day = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
            if (day != lastDay) {
                rows.add(DayHeader(m.time))
                lastDay = day
            }
            rows.add(m)
        }
        adapter.notifyDataSetChanged()
        if (messages.isEmpty()) {
            showEmpty(if (contactId != 0L || reachedStart) "Здесь пока нет сообщений" else "Загружаем сообщения…")
        } else {
            emptyView.visibility = View.GONE
        }
        updateHeader()
        if (scrollToEnd) {
            if (rows.isNotEmpty()) layoutManager.scrollToPositionWithOffset(rows.size - 1, -100000)
        } else if (firstRow != null) {
            // Догрузка сверху не должна дёргать то, что человек сейчас читает.
            val newIndex = rows.indexOf(firstRow)
            if (newIndex >= 0) layoutManager.scrollToPositionWithOffset(newIndex, offset)
        }
    }

    private fun showEmpty(text: String) {
        if (!::emptyView.isInitialized) return
        emptyView.text = text
        emptyView.visibility = View.VISIBLE
    }

    private fun isAtBottom(): Boolean =
        !::layoutManager.isInitialized || layoutManager.findLastVisibleItemPosition() >= rows.size - 2

    private fun markRead() {
        val last = messages.lastOrNull { !it.isOutgoing && it.id != 0L } ?: return
        MaxClient.markRead(chatId, last)
    }

    // ---- события клиента ----

    override fun onNewMessage(chatId: Long, message: MaxMessage) {
        if (chatId != this.chatId) return
        AndroidUtilities.runOnUIThread {
            val stick = isAtBottom()
            merge(listOf(message))
            rebuild(scrollToEnd = stick || message.isOutgoing)
            if (!isPaused()) markRead()
        }
    }

    override fun onMessagesDeleted(chatId: Long, ids: List<Long>) {
        if (chatId != this.chatId) return
        AndroidUtilities.runOnUIThread {
            if (messages.removeAll { it.id in ids }) rebuild(scrollToEnd = false)
        }
    }

    override fun onTyping(chatId: Long, userId: Long) {
        if (chatId == this.chatId) AndroidUtilities.runOnUIThread { updateHeader() }
    }

    override fun onPresenceChanged(userId: Long) {
        if (userId == chat.peerId) AndroidUtilities.runOnUIThread { updateHeader() }
    }

    override fun onReadMarkChanged(chatId: Long) {
        if (chatId == this.chatId) AndroidUtilities.runOnUIThread { adapter.notifyDataSetChanged() }
    }

    override fun onChatsChanged() {
        AndroidUtilities.runOnUIThread { updateHeader() }
    }

    override fun onContactsChanged() {
        AndroidUtilities.runOnUIThread {
            updateHeader()
            adapter.notifyDataSetChanged()
        }
    }

    // ---- отправка ----

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val edit = editing
        if (edit != null) {
            clearReply()
            input.setText("")
            val index = messages.indexOfFirst { it === edit }
            if (index >= 0) messages[index] = edit.withText(text)
            rebuild(scrollToEnd = false)
            MaxClient.editMessage(chatId, edit.id, text) { error ->
                if (error != null) toast("Не удалось изменить: " + MaxLog.describe(error))
            }
            return
        }
        val reply = replyTo
        clearReply()
        input.setText("")
        val local = MaxMessage(
            0L, -System.currentTimeMillis(), MaxStore.accountId, text, System.currentTimeMillis(), null, false,
            reply = reply?.let { MaxReply(it.id, it.senderId, it.displayText) },
        )
        local.pending = true
        merge(listOf(local))
        rebuild(scrollToEnd = true)
        MaxClient.sendMessage(chatId, local, reply?.id) { sent, error -> replaceLocal(local, sent, error) }
    }

    /** Подменяет локальное сообщение подтверждённым или помечает его неотправленным. */
    private fun replaceLocal(local: MaxMessage, sent: MaxMessage?, error: Throwable?) {
        AndroidUtilities.runOnUIThread {
            if (error != null || sent == null) {
                local.pending = false
                local.failed = true
                adapter.notifyDataSetChanged()
                toast("Не отправлено: " + MaxLog.describe(error))
                return@runOnUIThread
            }
            messages.remove(local)
            merge(listOf(sent))
            rebuild(scrollToEnd = isAtBottom())
        }
    }

    private fun toast(text: String) {
        AndroidUtilities.runOnUIThread {
            val context = parentActivity ?: return@runOnUIThread
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- вложения: отправка ----

    /** Меню скрепки — всплывающее, как меню в Telegram. */
    private fun showAttachMenu() {
        ItemOptions.makeOptions(this, attachButton)
            .add(org.telegram.messenger.R.drawable.msg_photos, "Фото или видео") { pick(REQ_PHOTO) }
            .add(org.telegram.messenger.R.drawable.msg_video, "Видео") { pick(REQ_VIDEO) }
            .add(org.telegram.messenger.R.drawable.msg_view_file, "Файл") { pick(REQ_FILE) }
            .add(org.telegram.messenger.R.drawable.filled_poll_add_24, "Опрос") { askPoll() }
            .add(org.telegram.messenger.R.drawable.msg_location, "Геопозиция") { sendMyLocation() }
            .add(org.telegram.messenger.R.drawable.msg_contact, "Контакт") { pickContactToSend() }
            .show()
    }

    /** Новый опрос — как «Опрос» в скрепке Telegram: вопрос, варианты, флажки. */
    private fun askPoll() {
        val context = parentActivity ?: return
        fun field(hint: String) = EditText(context).apply {
            this.hint = hint
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
        }
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(AndroidUtilities.dp(24f), 0, AndroidUtilities.dp(24f), 0)
        val question = field("Вопрос")
        box.addView(question)
        val options = (1..4).map { field("Вариант $it").also { box.addView(it) } }
        val multiple = android.widget.CheckBox(context).apply { text = "Несколько ответов"; setTextColor(Theme.getColor(Theme.key_dialogTextBlack)) }
        val anonymous = android.widget.CheckBox(context).apply { text = "Анонимное голосование"; isChecked = true; setTextColor(Theme.getColor(Theme.key_dialogTextBlack)) }
        box.addView(multiple)
        box.addView(anonymous)
        val scroll = android.widget.ScrollView(context)
        scroll.addView(box)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Новый опрос")
                .setView(scroll)
                .setPositiveButton("Создать") { _, _ ->
                    val title = question.text.toString().trim()
                    val answers = options.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
                    if (title.isEmpty() || answers.size < 2) {
                        toast("Нужен вопрос и хотя бы два варианта")
                        return@setPositiveButton
                    }
                    MaxClient.sendPoll(chatId, title, answers, multiple.isChecked, anonymous.isChecked) { _, e ->
                        if (e != null) toast("Опрос не отправлен: " + MaxLog.describe(e))
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    /** Своё местоположение: последнее известное системе, при необходимости — разовый запрос. */
    private fun sendMyLocation() {
        val activity = parentActivity ?: return
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val known = try {
            listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
                .mapNotNull { if (lm.isProviderEnabled(it)) lm.getLastKnownLocation(it) else null }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
        val send: (android.location.Location) -> Unit = { loc ->
            MaxClient.sendLocation(chatId, loc.latitude, loc.longitude) { _, e -> if (e != null) toast("Не отправлено: " + MaxLog.describe(e)) }
        }
        if (known != null && System.currentTimeMillis() - known.time < 5 * 60_000L) {
            send(known)
            return
        }
        toast("Определяем местоположение…")
        try {
            val provider = if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) android.location.LocationManager.GPS_PROVIDER else android.location.LocationManager.NETWORK_PROVIDER
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) = send(location)
                @Deprecated("") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }, android.os.Looper.getMainLooper())
        } catch (e: Throwable) {
            if (known != null) send(known) else toast("Местоположение недоступно — включите геолокацию")
        }
    }

    private fun pickContactToSend() {
        presentFragment(MaxContactPickerFragment("Отправить контакт", emptySet()) { ids ->
            ids.forEach { id -> MaxClient.sendContact(chatId, id) { _, e -> if (e != null) toast("Не отправлено: " + MaxLog.describe(e)) } }
        })
    }

    override fun onPollChanged(chatId: Long, messageId: Long) {
        if (chatId == this.chatId) AndroidUtilities.runOnUIThread { refreshMessage(messageId) }
    }

    private fun pick(request: Int) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        when (request) {
            REQ_PHOTO -> {
                intent.type = "image/*"
                intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
            REQ_VIDEO -> intent.type = "video/*"
            else -> intent.type = "*/*"
        }
        try {
            startActivityForResult(Intent.createChooser(intent, null), request)
        } catch (e: Throwable) {
            toast("Нет приложения для выбора файла")
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val mime = try { parentActivity?.contentResolver?.getType(uri) } catch (e: Throwable) { null } ?: ""
        when {
            requestCode == REQ_VIDEO || (requestCode == REQ_PHOTO && mime.startsWith("video/")) -> sendPickedVideo(uri)
            requestCode == REQ_PHOTO -> sendPickedPhoto(uri)
            requestCode == REQ_FILE -> sendPickedFile(uri)
        }
    }

    private fun sendPickedPhoto(uri: Uri) {
        val context = parentActivity ?: return
        val local = MaxMessage(0L, -System.currentTimeMillis(), MaxStore.accountId, "", System.currentTimeMillis(), "Фото", false)
        local.pending = true
        merge(listOf(local))
        rebuild(scrollToEnd = true)
        Thread {
            val bytes = try {
                readPhoto(context, uri)
            } catch (e: Throwable) {
                replaceLocal(local, null, e)
                return@Thread
            }
            MaxClient.sendPhoto(chatId, bytes, "") { sent, error -> replaceLocal(local, sent, error) }
        }.start()
    }

    private fun sendPickedVideo(uri: Uri) {
        val context = parentActivity ?: return
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst() && s >= 0) size = c.getLong(s)
            }
        } catch (ignored: Throwable) {
        }
        if (size > MAX_VIDEO) {
            toast("Видео больше 200 МБ пока не отправить")
            return
        }
        val local = MaxMessage(0L, -System.currentTimeMillis(), MaxStore.accountId, "", System.currentTimeMillis(), "Видео", false)
        local.pending = true
        merge(listOf(local))
        rebuild(scrollToEnd = true)
        Thread {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("видео не открылось")
            } catch (e: Throwable) {
                replaceLocal(local, null, e)
                return@Thread
            }
            MaxClient.sendVideo(chatId, bytes, "") { sent, error -> replaceLocal(local, sent, error) }
        }.start()
    }

    /** Фото ужимаем до 2560 по большей стороне и JPEG 87 — как делают клиенты MAX. */
    private fun readPhoto(context: Context, uri: Uri): ByteArray {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("файл не открылся")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return raw
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 87, out)
        return out.toByteArray()
    }

    private fun sendPickedFile(uri: Uri) {
        val context = parentActivity ?: return
        var name = "file"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n) ?: name
                    if (s >= 0) size = c.getLong(s)
                }
            }
        } catch (ignored: Throwable) {
        }
        if (size > MAX_FILE) {
            toast("Файл больше 100 МБ пока не отправить")
            return
        }
        val local = MaxMessage(0L, -System.currentTimeMillis(), MaxStore.accountId, "", System.currentTimeMillis(), name, false)
        local.pending = true
        merge(listOf(local))
        rebuild(scrollToEnd = true)
        Thread {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("файл не открылся")
            } catch (e: Throwable) {
                replaceLocal(local, null, e)
                return@Thread
            }
            MaxClient.sendFile(chatId, bytes, name) { sent, error -> replaceLocal(local, sent, error) }
        }.start()
    }

    // ---- вложения: открытие ----

    private fun openAttach(message: MaxMessage) {
        val context = parentActivity ?: return
        val attach = message.attaches.firstOrNull() ?: return
        when {
            attach.isPhoto -> attach.url?.let { MaxMedia.showPhoto(context, it) }
            attach.isVideo -> {
                toast("Открываем видео…")
                MaxClient.videoUrl(chatId, message.id, attach.videoId, attach.token ?: "") { url, error ->
                    AndroidUtilities.runOnUIThread {
                        if (url != null) MaxMedia.openExternal(context, url, "video/*")
                        else toast("Видео недоступно: " + MaxLog.describe(error))
                    }
                }
            }
            attach.isAudio -> attach.url?.let { url ->
                MaxAudio.toggle(message.id, url) { e -> toast("Не воспроизводится: " + MaxLog.describe(e)) }
            }
            attach.isFile -> {
                toast("Готовим файл…")
                MaxClient.fileUrl(chatId, message.id, attach.fileId) { url, error ->
                    AndroidUtilities.runOnUIThread {
                        if (url != null) MaxMedia.download(context, url, attach.name ?: "file")
                        else toast("Файл недоступен: " + MaxLog.describe(error))
                    }
                }
            }
            attach.type == "LOCATION" -> {
                val lat = attach.raw.optDouble("latitude", 0.0)
                val lon = attach.raw.optDouble("longitude", 0.0)
                MaxMedia.openExternal(context, "geo:$lat,$lon?q=$lat,$lon")
            }
            attach.type == "CONTACT" -> {
                val shared = MaxSharedContact.parse(attach.raw)
                when {
                    shared.id != 0L -> presentFragment(MaxProfileFragment(0L, shared.id))
                    shared.phone.isNotEmpty() -> findShared(shared)
                }
            }
            attach.type == "SHARE" -> attach.raw.optString("url", "").takeIf { it.isNotEmpty() }?.let {
                MaxMedia.openExternal(context, it)
            }
        }
    }

    /** Присланный контакт без id MAX: ищем его номер (опкод 46), как «Найти по номеру». */
    private fun findShared(shared: MaxSharedContact) {
        MaxClient.findByPhone(shared.phone) { found, error ->
            AndroidUtilities.runOnUIThread {
                when {
                    found != null -> presentFragment(MaxProfileFragment(0L, found.id))
                    error != null -> toast("Не нашли: " + MaxLog.describe(error))
                    else -> toast("Этого номера нет в MAX")
                }
            }
        }
    }

    /** «Добавить» на карточке контакта: опкод 34 ADD с именем из карточки. */
    private fun addSharedContact(shared: MaxSharedContact) {
        val parts = shared.name.split(" ", limit = 2)
        MaxClient.updateContact(shared.id, "ADD", parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }) { error ->
            AndroidUtilities.runOnUIThread {
                if (error != null) toast("Не удалось добавить: " + MaxLog.describe(error))
                else {
                    toast("Контакт добавлен")
                    MaxClient.loadContacts { AndroidUtilities.runOnUIThread { adapter.notifyDataSetChanged() } }
                }
            }
        }
    }

    /**
     * Ответ свайпом влево, как в Telegram: пузырь уезжает за пальцем, справа проявляется
     * круглая кнопка «ответить»; отпустили дальше порога — ответ на это сообщение.
     */
    private inner class SwipeToReply(context: Context) : RecyclerView.OnItemTouchListener {
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private val threshold = AndroidUtilities.dp(64f).toFloat()
        private val maxShift = AndroidUtilities.dp(96f).toFloat()
        private var startX = 0f
        private var startY = 0f
        private var cell: MessageCell? = null
        private var message: MaxMessage? = null
        private var swiping = false
        private var armed = false

        override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = e.x
                    startY = e.y
                    swiping = false
                    armed = false
                    val view = rv.findChildViewUnder(e.x, e.y) as? MessageCell
                    val row = view?.let { rows.getOrNull(rv.getChildAdapterPosition(it)) } as? MaxMessage
                    if (view != null && row != null && canSwipeReply(row)) {
                        cell = view
                        message = row
                    } else {
                        cell = null
                        message = null
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> if (!swiping && cell != null) {
                    val dx = e.x - startX
                    val dy = e.y - startY
                    if (dx < -slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        swiping = true
                        startX = e.x
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                        listView.cancelClickRunnables(true)
                    } else if (Math.abs(dy) > slop) {
                        cell = null
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> cell = null
            }
            return swiping
        }

        override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {
            val target = cell ?: return
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    val shift = (startX - e.x).coerceIn(0f, maxShift)
                    target.setSwipe(shift, threshold)
                    val nowArmed = shift >= threshold
                    if (nowArmed && !armed) {
                        try {
                            target.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        } catch (ignored: Throwable) {
                        }
                    }
                    armed = nowArmed
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    target.releaseSwipe()
                    val replyTo = message
                    if (armed && e.actionMasked == android.view.MotionEvent.ACTION_UP && replyTo != null) startReply(replyTo)
                    cell = null
                    message = null
                    swiping = false
                    armed = false
                }
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }

    /** Отвечать можно на настоящее сообщение и только там, где есть поле ввода. */
    private fun canSwipeReply(message: MaxMessage): Boolean =
        message.id != 0L && !message.isService && !message.pending && joinBar?.visibility != View.VISIBLE &&
            searchBar.visibility != View.VISIBLE

    // ---- меню действий ----

    /**
     * Меню по долгому нажатию — всплывающее меню Telegram с иконками, сверху строка
     * быстрых реакций.
     */
    private fun showActions(message: MaxMessage, anchor: View) {
        val context = parentActivity ?: return
        if (message.isService || message.pending) return
        val sent = message.id != 0L
        val canEdit = message.isOutgoing && message.text.isNotEmpty() && message.forwardFrom == null && message.forwardSenderId == 0L
        val options = ItemOptions.makeOptions(this, anchor)
        if (sent) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(4f), AndroidUtilities.dp(6f), AndroidUtilities.dp(4f))
            for (emoji in MaxReactions.QUICK) {
                val chip = TextView(context)
                chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 22f)
                chip.gravity = Gravity.CENTER
                chip.text = MaxBubbles.emojify(emoji, chip)
                val mine = message.reactions?.mine == emoji
                chip.background = if (mine) {
                    Theme.createRoundRectDrawable(AndroidUtilities.dp(18f), Theme.getColor(Theme.key_listSelector))
                } else {
                    Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1)
                }
                chip.setOnClickListener {
                    options.dismiss()
                    toggleReaction(message, emoji)
                }
                row.addView(chip, LayoutHelper.createLinear(38, 38))
            }
            val scroll = android.widget.HorizontalScrollView(context)
            scroll.isHorizontalScrollBarEnabled = false
            scroll.addView(row)
            options.addView(scroll)
            options.addGap()
        }
        options
            .addIf(sent, org.telegram.messenger.R.drawable.menu_reply, "Ответить") { startReply(message) }
            .addIf(message.text.isNotEmpty(), org.telegram.messenger.R.drawable.msg_copy, "Копировать") {
                AndroidUtilities.addToClipboard(message.text)
                toast("Текст скопирован")
            }
            .addIf(sent, org.telegram.messenger.R.drawable.msg_forward, "Переслать") { chooseForwardTarget(message) }
            .addIf(sent && !chat.isSaved && chat.pinned?.id != message.id, org.telegram.messenger.R.drawable.msg_pin, "Закрепить") { pin(message) }
            .addIf(sent && chat.pinned?.id == message.id, org.telegram.messenger.R.drawable.msg_unpin, "Открепить") { confirmUnpin() }
            .addIf(sent && canEdit, org.telegram.messenger.R.drawable.msg_edit, "Изменить") { startEdit(message) }
            .addIf(message.failed, org.telegram.messenger.R.drawable.msg_retry, "Повторить") {
                messages.remove(message)
                input.setText(message.text)
                onSend()
            }
            .addIf(sent, org.telegram.messenger.R.drawable.msg_delete, "Удалить", true) { confirmDelete(message) }
            .setGravity(if (message.isOutgoing) Gravity.RIGHT else Gravity.LEFT)
            .show()
    }

    /** Моя реакция: та же — снять, другая — поставить. Показываем сразу, сервер подтверждает. */
    private fun toggleReaction(message: MaxMessage, emoji: String) {
        val current = message.reactions
        val next = if (current?.mine == emoji) null else emoji
        message.reactions = current?.withMine(next) ?: next?.let { MaxReactions(listOf(it to 1), it) }
        refreshMessage(message.id)
        MaxClient.setReaction(chatId, message.id, next) { _, error ->
            if (error != null) {
                message.reactions = current
                AndroidUtilities.runOnUIThread { refreshMessage(message.id) }
                toast("Реакция не поставлена: " + MaxLog.describe(error))
            }
        }
    }

    private fun vote(message: MaxMessage, poll: MaxPoll, answerIds: List<Int>) {
        MaxClient.vote(chatId, message.id, poll, answerIds) { e -> if (e != null) toast("Голос не принят: " + MaxLog.describe(e)) }
    }

    private fun refreshMessage(messageId: Long) {
        val index = rows.indexOfFirst { it is MaxMessage && it.id == messageId }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    override fun onReactionsChanged(chatId: Long, messageId: Long, reactions: MaxReactions?) {
        if (chatId != this.chatId) return
        AndroidUtilities.runOnUIThread {
            val message = messages.firstOrNull { it.id == messageId } ?: return@runOnUIThread
            // Пуш не знает, какая реакция моя: сохраняем свою, если её счётчик ещё жив.
            val oldMine = message.reactions?.mine
            message.reactions = when {
                reactions == null -> null
                reactions.mine == null && oldMine != null && reactions.counters.any { it.first == oldMine && it.second > 0 } ->
                    MaxReactions(reactions.counters, oldMine)
                else -> reactions
            }
            refreshMessage(messageId)
        }
    }

    private fun confirmDelete(message: MaxMessage) {
        val context = parentActivity ?: return
        val builder = AlertDialog.Builder(context).setTitle("Удалить сообщение?")
        if (message.isOutgoing && !chat.isSaved) {
            builder.setItems(arrayOf<CharSequence>("Удалить у всех", "Удалить только у меня")) { _, which ->
                delete(message, forEveryone = which == 0)
            }
        } else {
            builder.setMessage("Сообщение пропадёт только у вас.")
            builder.setPositiveButton("Удалить") { _, _ -> delete(message, false) }
            builder.setNegativeButton("Отмена", null)
        }
        showDialog(builder.create())
    }

    private fun delete(message: MaxMessage, forEveryone: Boolean) {
        MaxClient.deleteMessages(chatId, listOf(message.id), forEveryone) { error ->
            if (error != null) toast("Не удалось удалить: " + MaxLog.describe(error))
        }
    }

    /** Пересылка — выбор чата отдельным экраном, как «Переслать…» в Telegram. */
    private fun chooseForwardTarget(message: MaxMessage) {
        presentFragment(MaxDialogsFragment(pick = { target ->
            MaxClient.forwardMessage(target.id, chatId, message.id) { error ->
                toast(if (error == null) "Переслано: ${target.title}" else "Не переслано: " + MaxLog.describe(error))
            }
        }))
    }

    // ---- список ----

    private class DayHeader(val time: Long)

    private inner class Adapter : RecyclerListView.SelectionAdapter() {

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = holder.itemViewType == TYPE_MESSAGE

        override fun getItemViewType(position: Int): Int = when (val row = rows[position]) {
            is DayHeader -> TYPE_SERVICE
            is MaxMessage -> if (row.isService) TYPE_SERVICE else TYPE_MESSAGE
            else -> TYPE_SERVICE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = if (viewType == TYPE_MESSAGE) MessageCell(parent.context) else ServiceCell(parent.context)
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is DayHeader -> (holder.itemView as ServiceCell).bind(LocaleController.formatDateChat(row.time / 1000))
                is MaxMessage -> if (row.isService) {
                    (holder.itemView as ServiceCell).bind(serviceText(row))
                } else {
                    // Подряд идущие сообщения одного автора склеиваются, как в Telegram:
                    // имя — только у первого, хвостик — только у последнего.
                    val prev = rows.getOrNull(position - 1) as? MaxMessage
                    val next = rows.getOrNull(position + 1) as? MaxMessage
                    val samePrev = prev != null && !prev.isService && prev.senderId == row.senderId
                    val sameNext = next != null && !next.isService && next.senderId == row.senderId
                    (holder.itemView as MessageCell).bind(
                        row,
                        showName = !chat.isDialog && !chat.isChannel && !row.isOutgoing && !samePrev,
                        topNear = samePrev,
                        bottomNear = sameNext,
                    )
                }
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    private fun serviceText(message: MaxMessage): String {
        val author = MaxContacts.get(message.senderId)?.name
        return if (author != null) "$author: ${message.displayText}" else message.displayText
    }

    /** Плашка по центру: дата или служебное событие, как в Telegram. */
    private class ServiceCell(context: Context) : FrameLayout(context) {
        private val text = TextView(context)

        init {
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
            text.typeface = AndroidUtilities.bold()
            text.setTextColor(Theme.getColor(Theme.key_chat_serviceText))
            text.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(12f), Theme.getColor(Theme.key_chat_serviceBackground))
            text.setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(4f), AndroidUtilities.dp(10f), AndroidUtilities.dp(4f))
            text.gravity = Gravity.CENTER
            addView(text, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER, 32f, 6f, 32f, 6f))
        }

        fun bind(value: String) {
            text.text = value
        }
    }

    /**
     * Сообщение в оформлении Telegram: пузырь `MessageDrawable` с хвостиком у последнего
     * в серии, пересылка, автор, цитата ответа, медиа или файл, текст с временем в конце
     * последней строки, галочки и часики. Сообщение только из 1–3 эмодзи — крупно и без
     * пузыря, как в Telegram. Входящие слева, исходящие справа.
     */
    private inner class MessageCell(context: Context) : FrameLayout(context) {

        private val bubble = LinearLayout(context)
        private val forward = TextView(context)
        private val author = TextView(context)
        private val quote = LinearLayout(context)
        private val quoteName = TextView(context)
        private val quoteText = TextView(context)
        private val media = FrameLayout(context)
        private val image = BackupImageView(context)
        private val mediaBadge = TextView(context)
        private val mediaMeta = MaxBubbles.Meta(context)
        private val fileRow = LinearLayout(context)
        private val fileIcon = TextView(context)
        private val fileName = TextView(context)
        private val fileSize = TextView(context)
        private val textBox = FrameLayout(context)
        private val body = TextView(context)
        private val meta = MaxBubbles.Meta(context)
        private val reactionsRow = LinearLayout(context)
        private val reactionsBox = FrameLayout(context)
        private val reactionsMeta = MaxBubbles.Meta(context)
        private val pollBox = LinearLayout(context)
        private val contactBox = LinearLayout(context)
        private val contactAvatar = BackupImageView(context)
        private val contactName = TextView(context)
        private val contactPhone = TextView(context)
        private val contactButtons = LinearLayout(context)
        private val swipeIcon = ImageView(context)
        private var swipeAnimator: android.animation.ValueAnimator? = null
        private val inBubble = MaxBubbles.Bubble(false, false)
        private val outBubble = MaxBubbles.Bubble(true, false)
        private val inMediaBubble = MaxBubbles.Bubble(false, true)
        private val outMediaBubble = MaxBubbles.Bubble(true, true)

        init {
            bubble.orientation = LinearLayout.VERTICAL

            forward.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
            forward.isSingleLine = true
            forward.ellipsize = TextUtils.TruncateAt.END
            bubble.addView(forward, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 2f))

            author.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
            author.typeface = AndroidUtilities.bold()
            author.isSingleLine = true
            author.ellipsize = TextUtils.TruncateAt.END
            bubble.addView(author, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 1f))

            quote.orientation = LinearLayout.VERTICAL
            quote.setPadding(AndroidUtilities.dp(11f), AndroidUtilities.dp(5f), AndroidUtilities.dp(10f), AndroidUtilities.dp(6f))
            quoteName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
            quoteName.typeface = AndroidUtilities.bold()
            quoteName.isSingleLine = true
            quote.addView(quoteName, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            quoteText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
            quoteText.isSingleLine = true
            quoteText.ellipsize = TextUtils.TruncateAt.END
            quote.addView(quoteText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 1f, 0f, 0f))
            // Цитата во всю ширину пузыря, как в Telegram.
            bubble.addView(quote, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 3f, 0f, 5f))

            image.setRoundRadius(AndroidUtilities.dp(12f))
            media.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
            mediaBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
            mediaBadge.setTextColor(0xffffffff.toInt())
            mediaBadge.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(10f), 0x88000000.toInt())
            mediaBadge.setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(3f), AndroidUtilities.dp(8f), AndroidUtilities.dp(3f))
            media.addView(mediaBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER, 0f, 0f, 0f, 0f))
            media.addView(mediaMeta, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM or Gravity.END, 0f, 0f, 6f, 6f))
            bubble.addView(media, LayoutHelper.createLinear(200, 150, 0f, 0f, 0f, 0f))

            fileRow.orientation = LinearLayout.HORIZONTAL
            fileRow.gravity = Gravity.CENTER_VERTICAL
            fileIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f)
            fileIcon.gravity = Gravity.CENTER
            fileIcon.setTextColor(0xffffffff.toInt())
            fileRow.addView(fileIcon, LayoutHelper.createLinear(44, 44))
            val fileTexts = LinearLayout(context)
            fileTexts.orientation = LinearLayout.VERTICAL
            fileName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
            fileName.typeface = AndroidUtilities.bold()
            fileName.maxLines = 2
            fileName.ellipsize = TextUtils.TruncateAt.MIDDLE
            fileTexts.addView(fileName, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            fileSize.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
            fileTexts.addView(fileSize, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            fileRow.addView(fileTexts, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10f, 0f, 4f, 0f))
            bubble.addView(fileRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 2f))

            // Контакт как в Telegram: аватарка, имя, номер; ниже кнопки «Написать» / «Добавить».
            contactBox.orientation = LinearLayout.VERTICAL
            val contactRow = LinearLayout(context)
            contactRow.orientation = LinearLayout.HORIZONTAL
            contactRow.gravity = Gravity.CENTER_VERTICAL
            contactAvatar.setRoundRadius(AndroidUtilities.dp(22f))
            contactRow.addView(contactAvatar, LayoutHelper.createLinear(44, 44))
            val contactTexts = LinearLayout(context)
            contactTexts.orientation = LinearLayout.VERTICAL
            contactName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
            contactName.typeface = AndroidUtilities.bold()
            contactName.isSingleLine = true
            contactName.ellipsize = TextUtils.TruncateAt.END
            contactTexts.addView(contactName, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            contactPhone.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
            contactPhone.isSingleLine = true
            contactTexts.addView(contactPhone, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 1f, 0f, 0f))
            contactRow.addView(contactTexts, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10f, 0f, 8f, 0f))
            contactBox.addView(contactRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
            contactButtons.orientation = LinearLayout.HORIZONTAL
            contactBox.addView(contactButtons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0f, 6f, 0f, 0f))
            bubble.addView(contactBox, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 2f))

            body.setLineSpacing(0f, 1.05f)
            textBox.addView(body, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat()))
            textBox.addView(meta, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM or Gravity.END, 0f, 0f, 0f, 0f))
            bubble.addView(textBox, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

            pollBox.orientation = LinearLayout.VERTICAL
            bubble.addView(pollBox, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 4f))

            // Реакции как в Telegram: под текстом, время — справа в той же строке.
            reactionsRow.orientation = LinearLayout.HORIZONTAL
            reactionsRow.gravity = Gravity.CENTER_VERTICAL
            reactionsBox.addView(reactionsRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START or Gravity.CENTER_VERTICAL, 0f, 0f, 0f, 0f))
            reactionsBox.addView(reactionsMeta, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.END or Gravity.BOTTOM, 0f, 0f, 0f, 0f))
            bubble.addView(reactionsBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f))

            addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START, 0f, 0f, 0f, 0f))

            // Кнопка «ответить» при свайпе — как в Telegram: круг цвета служебных плашек.
            swipeIcon.setImageResource(org.telegram.messenger.R.drawable.filled_button_reply)
            swipeIcon.scaleType = ImageView.ScaleType.CENTER
            swipeIcon.colorFilter = android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_chat_serviceIcon), android.graphics.PorterDuff.Mode.SRC_IN)
            swipeIcon.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(16f), Theme.getColor(Theme.key_chat_serviceBackground))
            swipeIcon.alpha = 0f
            addView(swipeIcon, LayoutHelper.createFrame(32, 32f, Gravity.END or Gravity.CENTER_VERTICAL, 0f, 0f, 12f, 0f))
        }

        fun setSwipe(shift: Float, threshold: Float) {
            swipeAnimator?.cancel()
            applySwipe(shift, threshold)
        }

        private fun applySwipe(shift: Float, threshold: Float) {
            bubble.translationX = -shift
            val progress = (shift / threshold).coerceIn(0f, 1f)
            swipeIcon.alpha = progress
            swipeIcon.scaleX = 0.5f + 0.5f * progress
            swipeIcon.scaleY = 0.5f + 0.5f * progress
            swipeIcon.translationX = (1f - progress) * AndroidUtilities.dp(24f)
        }

        fun releaseSwipe() {
            val from = -bubble.translationX
            if (from <= 0f) return
            val threshold = AndroidUtilities.dp(64f).toFloat()
            val animator = android.animation.ValueAnimator.ofFloat(from, 0f)
            animator.duration = 180
            animator.addUpdateListener { applySwipe(it.animatedValue as Float, threshold) }
            swipeAnimator = animator
            animator.start()
        }

        fun bind(message: MaxMessage, showName: Boolean, topNear: Boolean, bottomNear: Boolean) {
            swipeAnimator?.cancel()
            applySwipe(0f, 1f)
            val out = message.isOutgoing
            val textColor = Theme.getColor(if (out) Theme.key_chat_messageTextOut else Theme.key_chat_messageTextIn)
            val accent = Theme.getColor(if (out) Theme.key_chat_outReplyNameText else Theme.key_chat_inReplyNameText)
            val secondary = Theme.getColor(if (out) Theme.key_chat_outTimeText else Theme.key_chat_inTimeText)
            val maxWidth = minOf(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) * 78 / 100
            val status = when {
                !out -> MaxBubbles.Status.NONE
                message.failed -> MaxBubbles.Status.FAILED
                message.pending -> MaxBubbles.Status.CLOCK
                chat.isReadByOthers(message.time) -> MaxBubbles.Status.READ
                else -> MaxBubbles.Status.SENT
            }
            val attach = message.attaches.firstOrNull()
            val visual = attach != null && (attach.isPhoto || attach.isVideo || attach.isSticker)
            val bigEmoji = if (attach == null && message.reply == null && message.forwardFrom == null && message.forwardSenderId == 0L) {
                MaxBubbles.bigEmojiCount(message.text)
            } else 0
            // Фото/видео без подписи и без шапки — пузырь по краю картинки, время поверх неё.
            val mediaOnly = visual && message.text.isEmpty() && !showName && message.reply == null &&
                message.forwardFrom == null && message.forwardSenderId == 0L
            val sticker = attach?.isSticker == true && message.text.isEmpty()

            // Пересылка
            val fwdName = message.forwardFrom ?: message.forwardSenderId.takeIf { it != 0L }?.let { MaxContacts.get(it)?.name }
            if (fwdName != null || message.forwardSenderId != 0L) {
                forward.visibility = VISIBLE
                forward.text = "Переслано от " + (fwdName ?: "пользователя")
                forward.setTextColor(accent)
            } else {
                forward.visibility = GONE
            }

            // Автор в группе
            if (showName) {
                author.visibility = VISIBLE
                author.text = MaxContacts.get(message.senderId)?.name ?: "Участник"
                author.setTextColor(Theme.getColor(NAME_COLORS[(Math.abs(message.senderId) % NAME_COLORS.size).toInt()]))
            } else {
                author.visibility = GONE
            }

            // Цитата ответа
            val reply = message.reply
            if (reply != null) {
                quote.visibility = VISIBLE
                quote.background = QuoteDrawable(Theme.getColor(if (out) Theme.key_chat_outReplyLine else Theme.key_chat_inReplyLine))
                quoteName.text = MaxContacts.get(reply.senderId)?.name ?: "Сообщение"
                quoteName.setTextColor(accent)
                quoteText.text = MaxBubbles.emojify(reply.text, quoteText)
                quoteText.setTextColor(textColor)
                quoteText.maxWidth = maxWidth - AndroidUtilities.dp(48f)
            } else {
                quote.visibility = GONE
            }

            // Медиа и файлы
            media.visibility = GONE
            fileRow.visibility = GONE
            contactBox.visibility = GONE
            mediaMeta.visibility = GONE
            var bodyText = message.text
            when {
                attach != null && attach.type == "CONTACT" -> bindContact(attach, textColor, accent, secondary, maxWidth)
                visual && attach != null -> {
                    media.visibility = VISIBLE
                    val (w, h) = mediaSize(attach, maxWidth - AndroidUtilities.dp(if (mediaOnly) 14f else 28f))
                    val params = media.layoutParams as LinearLayout.LayoutParams
                    params.width = w
                    params.height = h
                    params.bottomMargin = if (mediaOnly) 0 else AndroidUtilities.dp(4f)
                    media.layoutParams = params
                    image.setRoundRadius(AndroidUtilities.dp(if (sticker) 0f else 12f))
                    MaxImages.bindImage(image, attach.url, maxOf(w, h), if (sticker) 0 else Theme.getColor(Theme.key_chat_serviceBackground))
                    if (attach.isVideo) {
                        mediaBadge.visibility = VISIBLE
                        mediaBadge.text = "▶  " + MaxMedia.durationText(attach.duration)
                    } else {
                        mediaBadge.visibility = GONE
                    }
                    if (mediaOnly) {
                        mediaMeta.visibility = VISIBLE
                        mediaMeta.bind(message.time, message.edited, status, MaxBubbles.Mode.MEDIA)
                    }
                }
                attach != null && (attach.isFile || attach.isAudio) -> {
                    fileRow.visibility = VISIBLE
                    val playing = attach.isAudio && MaxAudio.playingId == message.id && message.id != 0L
                    fileIcon.text = if (attach.isAudio) (if (playing) "❚❚" else "▶") else "⬇"
                    fileIcon.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(22f), accent)
                    fileName.text = if (attach.isAudio) "Голосовое сообщение" else (attach.name ?: "Файл")
                    fileName.setTextColor(textColor)
                    fileName.maxWidth = maxWidth - AndroidUtilities.dp(90f)
                    fileSize.text = if (attach.isAudio) MaxMedia.durationText(attach.duration) else MaxMedia.sizeText(attach.size)
                    fileSize.setTextColor(secondary)
                }
                attach != null && bodyText.isEmpty() -> bodyText = message.attachLabel ?: ""
                attach != null && attach.type == "POLL" -> bodyText = message.text
                attach != null && attach.type in setOf("LOCATION", "CALL", "SHARE") ->
                    bodyText = (message.attachLabel ?: "") + "\n" + bodyText
            }
            if (attach == null && message.pending && message.attachLabel != null) {
                fileRow.visibility = VISIBLE
                fileIcon.text = "⇡"
                fileIcon.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(22f), accent)
                fileName.text = message.attachLabel
                fileName.setTextColor(textColor)
                fileSize.text = "загружается…"
                fileSize.setTextColor(secondary)
                bodyText = ""
            }

            // Текст и время. У фото без подписи время уже стоит поверх картинки. Если есть
            // реакции — время переезжает в их строку справа, как в Telegram.
            val hasReactions = message.id != 0L && message.reactions?.isEmpty == false && bigEmoji == 0 && !sticker
            val metaInReactions = hasReactions && !mediaOnly
            textBox.visibility = if (mediaOnly || (metaInReactions && bodyText.isEmpty())) GONE else VISIBLE
            meta.visibility = if (metaInReactions) GONE else VISIBLE
            meta.bind(message.time, message.edited, status, if (bigEmoji > 0) MaxBubbles.Mode.MEDIA else if (out) MaxBubbles.Mode.OUT else MaxBubbles.Mode.IN)
            body.setTextColor(textColor)
            body.maxWidth = maxWidth
            if (bigEmoji > 0) {
                body.visibility = VISIBLE
                body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, MaxBubbles.bigEmojiSize(bigEmoji))
                body.text = MaxBubbles.emojify(bodyText, body)
                body.setPadding(0, 0, 0, AndroidUtilities.dp(22f))
            } else if (bodyText.isEmpty()) {
                body.visibility = GONE
            } else {
                body.visibility = VISIBLE
                body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, SharedConfig.fontSize.toFloat())
                body.setPadding(0, 0, 0, 0)
                body.text = if (metaInReactions) MaxBubbles.emojify(bodyText, body)
                else MaxBubbles.withTimeRoom(MaxBubbles.emojify(bodyText, body), meta.measureWidth() + AndroidUtilities.dp(6f))
            }

            // Пузырь Telegram; у крупных эмодзи и стикеров пузыря нет.
            val noBubble = bigEmoji > 0 || sticker
            if (noBubble) {
                bubble.background = null
                bubble.setPadding(0, 0, 0, 0)
            } else {
                val drawable = if (mediaOnly) (if (out) outMediaBubble else inMediaBubble) else (if (out) outBubble else inBubble)
                drawable.topNear = topNear
                drawable.bottomNear = bottomNear
                bubble.background = drawable
                MaxBubbles.applyPadding(bubble, out, mediaOnly)
            }
            bindPoll(message, out, maxWidth)
            bindReactions(message, out, hasReactions)
            if (metaInReactions) {
                reactionsMeta.visibility = VISIBLE
                reactionsMeta.bind(message.time, message.edited, status, if (out) MaxBubbles.Mode.OUT else MaxBubbles.Mode.IN)
                (reactionsRow.layoutParams as LayoutParams).rightMargin = reactionsMeta.measureWidth() + AndroidUtilities.dp(10f)
            } else {
                reactionsMeta.visibility = GONE
                (reactionsRow.layoutParams as LayoutParams).rightMargin = 0
            }
            setBackgroundColor(if (message.id != 0L && message.id == highlightId) Theme.getColor(Theme.key_chat_selectedBackground) else 0)

            val params = bubble.layoutParams as LayoutParams
            params.gravity = if (out) Gravity.END else Gravity.START
            params.leftMargin = AndroidUtilities.dp(if (out) 48f else 4f)
            params.rightMargin = AndroidUtilities.dp(if (out) 4f else 48f)
            params.topMargin = AndroidUtilities.dp(if (topNear) 1f else 4f)
            params.bottomMargin = AndroidUtilities.dp(if (bottomNear) 1f else 4f)
            bubble.layoutParams = params
        }

        private fun bindContact(attach: MaxAttach, textColor: Int, accent: Int, secondary: Int, maxWidth: Int) {
            val shared = MaxSharedContact.parse(attach.raw)
            if (shared.id != 0L && !MaxContacts.has(shared.id)) MaxClient.resolveMissing(listOf(shared.id))
            contactBox.visibility = VISIBLE
            contactName.text = MaxBubbles.emojify(shared.name, contactName)
            contactName.setTextColor(accent)
            contactName.maxWidth = maxWidth - AndroidUtilities.dp(90f)
            contactPhone.text = shared.phone.ifEmpty { if (shared.id != 0L) "в MAX" else "" }
            contactPhone.setTextColor(textColor)
            MaxImages.bindAvatar(contactAvatar, if (shared.id != 0L) shared.id else shared.name.hashCode().toLong(), shared.name, shared.photoUrl, 44)
            contactButtons.removeAllViews()
            val me = MaxStore.accountId
            val buttons = ArrayList<Pair<String, () -> Unit>>()
            when {
                shared.id != 0L && shared.id != me -> {
                    buttons.add("Написать" to { presentFragment(MaxChatFragment(MaxClient.chatForContact(shared.id).id, shared.id)) })
                    if (MaxClient.contacts.none { it.id == shared.id }) buttons.add("Добавить" to { addSharedContact(shared) })
                }
                shared.id == 0L && shared.phone.isNotEmpty() -> buttons.add("Найти в MAX" to { findShared(shared) })
            }
            contactButtons.visibility = if (buttons.isEmpty()) GONE else VISIBLE
            buttons.forEachIndexed { i, (title, action) ->
                val button = TextView(context)
                button.text = title
                button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
                button.typeface = AndroidUtilities.bold()
                button.gravity = Gravity.CENTER
                button.setTextColor(accent)
                button.isSingleLine = true
                button.background = Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8f), (accent and 0x00ffffff) or 0x1a000000, (accent and 0x00ffffff) or 0x33000000)
                button.setPadding(AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(12f), 0)
                button.setOnClickListener { action() }
                contactButtons.addView(button, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, if (i == 0) 0 else 6, 0, 0, 0))
            }
            // Кнопки делят ширину поровну; чтобы им было где уместиться — минимальная ширина карточки.
            contactBox.minimumWidth = AndroidUtilities.dp(if (buttons.size > 1) 240f else 200f)
            contactPhone.setTextColor(if (shared.phone.isEmpty()) secondary else textColor)
        }

        /**
         * Опрос как в Telegram: вопрос, «Анонимный опрос», варианты с кружками; после
         * голоса — проценты и полоски, свой ответ с галочкой; внизу число голосов.
         */
        private fun bindPoll(message: MaxMessage, out: Boolean, maxWidth: Int) {
            pollBox.removeAllViews()
            val attach = message.attaches.firstOrNull()
            if (attach?.type != "POLL") {
                pollBox.visibility = GONE
                return
            }
            pollBox.visibility = VISIBLE
            val poll = MaxClient.polls[message.id] ?: MaxPoll.parse(attach.raw)?.also {
                if (message.id != 0L) MaxClient.loadPoll(chatId, message.id, it.pollId)
            } ?: return
            val textColor = Theme.getColor(if (out) Theme.key_chat_messageTextOut else Theme.key_chat_messageTextIn)
            val accent = Theme.getColor(if (out) Theme.key_chat_outAudioSeekbarFill else Theme.key_chat_inAudioSeekbarFill)
            val secondary = Theme.getColor(if (out) Theme.key_chat_outTimeText else Theme.key_chat_inTimeText)
            val width = (maxWidth * 0.85f).toInt()
            fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
                this.text = MaxBubbles.emojify(value, this)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size)
                setTextColor(color)
                if (bold) typeface = AndroidUtilities.bold()
            }
            pollBox.addView(text(poll.title, 16f, textColor, true), LayoutHelper.createLinear(width, LayoutHelper.WRAP_CONTENT))
            pollBox.addView(text(if (poll.isAnonymous) "Анонимный опрос" else "Опрос", 13f, secondary), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 6f))
            val showResults = poll.voted || message.isOutgoing && poll.total > 0
            val chosen = HashSet<Int>()
            var voteButton: TextView? = null
            for (answer in poll.answers) {
                val row = LinearLayout(context)
                row.orientation = LinearLayout.VERTICAL
                row.setPadding(0, AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(6f))
                val line = LinearLayout(context)
                line.orientation = LinearLayout.HORIZONTAL
                line.gravity = Gravity.CENTER_VERTICAL
                if (showResults) {
                    val percent = if (poll.total > 0) Math.round(answer.votes * 100f / poll.total) else 0
                    line.addView(text("$percent%", 14f, textColor, true), LayoutHelper.createLinear(44, LayoutHelper.WRAP_CONTENT))
                    line.addView(text(answer.text + if (answer.mine) "  ✓" else "", 15f, textColor), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
                    row.addView(line, LayoutHelper.createLinear(width, LayoutHelper.WRAP_CONTENT))
                    val bar = View(context)
                    bar.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(2f), accent)
                    val barWidth = maxOf(AndroidUtilities.dp(4f), ((width - AndroidUtilities.dp(44f)) * (percent / 100f)).toInt())
                    row.addView(bar, LayoutHelper.createLinear(0, 4, 0f, 4f, 0f, 0f).also {
                        it.width = barWidth
                        it.leftMargin = AndroidUtilities.dp(44f)
                    })
                } else {
                    val circle = View(context)
                    circle.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setStroke(AndroidUtilities.dp(1.5f), secondary)
                    }
                    line.addView(circle, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 0, 0, 12, 0))
                    line.addView(text(answer.text, 15f, textColor), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
                    row.addView(line, LayoutHelper.createLinear(width, LayoutHelper.WRAP_CONTENT))
                    row.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2)
                    row.setOnClickListener {
                        if (!poll.isMultiple) {
                            vote(message, poll, listOf(answer.id))
                            return@setOnClickListener
                        }
                        if (!chosen.add(answer.id)) chosen.remove(answer.id)
                        (circle.background as android.graphics.drawable.GradientDrawable).setColor(if (answer.id in chosen) accent else 0)
                        voteButton?.visibility = if (chosen.isEmpty()) GONE else VISIBLE
                    }
                }
                pollBox.addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            }
            if (!showResults && poll.isMultiple) {
                val button = text("Голосовать", 15f, accent, true)
                button.gravity = Gravity.CENTER
                button.setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(4f))
                button.visibility = GONE
                button.setOnClickListener { vote(message, poll, chosen.toList()) }
                pollBox.addView(button, LayoutHelper.createLinear(width, LayoutHelper.WRAP_CONTENT))
                voteButton = button
            }
            val votes = poll.total
            val word = when {
                votes % 10 == 1 && votes % 100 != 11 -> "голос"
                votes % 10 in 2..4 && votes % 100 !in 12..14 -> "голоса"
                else -> "голосов"
            }
            pollBox.addView(text(if (votes == 0) "Пока нет голосов" else "$votes $word", 13f, secondary), LayoutHelper.createLinear(width, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))
        }

        /** Реакции под текстом — «пилюли» с эмодзи и числом, моя закрашена, как в Telegram. */
        private fun bindReactions(message: MaxMessage, out: Boolean, show: Boolean) {
            val r = message.reactions
            reactionsRow.removeAllViews()
            if (!show || r == null) {
                reactionsBox.visibility = GONE
                return
            }
            reactionsBox.visibility = VISIBLE
            val base = Theme.getColor(if (out) Theme.key_chat_outReactionButtonBackground else Theme.key_chat_inReactionButtonBackground)
            for ((emoji, count) in r.counters.take(6)) {
                val mine = r.mine == emoji
                val chip = TextView(context)
                chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
                chip.typeface = AndroidUtilities.bold()
                chip.gravity = Gravity.CENTER_VERTICAL
                chip.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(10f), 0)
                chip.text = MaxBubbles.emojify("$emoji ${if (count > 0) count else 1}", chip)
                chip.setTextColor(Theme.getColor(
                    if (mine) (if (out) Theme.key_chat_outReactionButtonTextSelected else Theme.key_chat_inReactionButtonTextSelected)
                    else (if (out) Theme.key_chat_outReactionButtonText else Theme.key_chat_inReactionButtonText)
                ))
                chip.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(14f), if (mine) base else (base and 0x00ffffff) or 0x26000000)
                chip.setOnClickListener { toggleReaction(message, emoji) }
                reactionsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 26, 0f, 0f, 6f, 0f))
            }
        }

        /** Размер превью по пропорциям вложения: не шире пузыря, не выше 320dp. */
        private fun mediaSize(attach: MaxAttach, maxW: Int): Pair<Int, Int> {
            val limitH = AndroidUtilities.dp(320f)
            if (attach.isSticker) return AndroidUtilities.dp(160f) to AndroidUtilities.dp(160f)
            if (attach.width <= 0 || attach.height <= 0) return maxW to (maxW * 3 / 4)
            var w = maxW
            var h = (w.toLong() * attach.height / attach.width).toInt()
            if (h > limitH) {
                h = limitH
                w = (h.toLong() * attach.width / attach.height).toInt()
            }
            return w.coerceAtLeast(AndroidUtilities.dp(120f)) to h.coerceAtLeast(AndroidUtilities.dp(80f))
        }
    }

    /**
     * Фон цитаты ответа как в Telegram: скруглённая плашка цвета линии с прозрачностью
     * и сплошная полоса 3dp слева.
     */
    private class QuoteDrawable(private val color: Int) : android.graphics.drawable.Drawable() {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val path = android.graphics.Path()
        private val rect = android.graphics.RectF()

        init {
            fill.color = (color and 0x00ffffff) or 0x1f000000
            line.color = color
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val r = AndroidUtilities.dp(5f).toFloat()
            rect.set(bounds)
            path.reset()
            path.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(rect, fill)
            canvas.drawRect(rect.left, rect.top, rect.left + AndroidUtilities.dp(3f), rect.bottom, line)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val PAGE = 50
        private const val TYPE_MESSAGE = 0
        private const val TYPE_SERVICE = 1
        private const val REQ_PHOTO = 7101
        private const val REQ_FILE = 7102
        private const val REQ_MIC = 7103
        private const val REQ_VIDEO = 7104
        private const val REQ_LOCATION = 7105
        private const val MAX_VIDEO = 200L * 1024 * 1024
        private const val MENU_SEARCH = 1
        private const val MENU_MORE = 2
        private const val MENU_INFO = 3
        private const val MENU_LEAVE = 4
        private const val MENU_OPEN_SEARCH = 5
        private const val MENU_MUTE = 6
        private const val MENU_CLEAR = 7
        private const val MENU_DELETE_CHAT = 8
        private const val MAX_FILE = 100L * 1024 * 1024

        private val NAME_COLORS = intArrayOf(
            Theme.key_avatar_nameInMessageRed,
            Theme.key_avatar_nameInMessageOrange,
            Theme.key_avatar_nameInMessageViolet,
            Theme.key_avatar_nameInMessageGreen,
            Theme.key_avatar_nameInMessageCyan,
            Theme.key_avatar_nameInMessageBlue,
        )
    }
}
