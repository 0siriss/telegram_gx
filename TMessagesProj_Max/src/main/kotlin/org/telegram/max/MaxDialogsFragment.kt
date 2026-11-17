package org.telegram.max

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.FragmentFloatingButton
import org.telegram.ui.Components.FragmentSearchField
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.DialogsActivity
import org.telegram.ui.Components.RecyclerListView

/**
 * Главный экран MAX: вкладки «Чаты» и «Контакты», поиск по обеим. Свои модели и свои
 * списки — телеграмных диалогов не касается.
 */
class MaxDialogsFragment(
    private val inTab: Boolean = false,
    /** Режим выбора чата (пересылка): нажатие отдаёт чат и закрывает экран. */
    private val pick: ((MaxChat) -> Unit)? = null,
) : BaseFragment(), MaxClient.Listener {

    private var root: FrameLayout? = null
    private var mainContent: View? = null
    private var loginStub: View? = null
    private var fab: FragmentFloatingButton? = null
    private var moreItem: ActionBarMenuItem? = null
    private var started = false

    private lateinit var chatsList: RecyclerListView
    private lateinit var contactsList: RecyclerListView
    private lateinit var empty: TextView
    private var searchItem: ActionBarMenuItem? = null
    private var storiesStack: LinearLayout? = null
    private lateinit var searchField: FragmentSearchField
    private lateinit var filterTabs: FilterTabsView

    private val chatsAdapter = ChatsAdapter()
    private val contactsAdapter = ContactsAdapter()

    private var tab = TAB_CHATS

    /** Открытая папка (индекс в MaxClient.folders) или -1 — «Все чаты». */
    private var folderIndex = -1
    private var shownFolders: List<String> = emptyList()
    private var query = ""
    private var chatsLoaded = false

    override fun createView(context: Context): View {
        if (!inTab) actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        // В режиме выбора чата — обычный заголовок; на вкладке — своя шапка как у Telegram.
        actionBar.setTitle(if (pick != null) "Переслать…" else "")
        // Шапка как у вкладок Telegram 13: цвет фона списка, тёмный заголовок, без тени.
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        actionBar.setTitleColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        actionBar.setItemsColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false)
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_listSelector), false)
        actionBar.setCastShadows(false)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_RELOAD -> reload()
                    MENU_ACCOUNT -> presentFragment(MaxAccountFragment())
                    MENU_NEW_CONTACT -> askNewContact()
                    MENU_JOIN_LINK -> askLink()
                    MENU_FIND_PHONE -> askPhone()
                    MENU_LOGOUT -> confirmLogout()
                    MENU_SEARCH -> toggleSearch()
                    MENU_ADD_STORY -> addStory()
                }
            }
        })
        val menu = actionBar.createMenu()
        // Как в шапке Telegram: значок поиска и «⋮».
        searchItem = menu.addItem(MENU_SEARCH, org.telegram.messenger.R.drawable.outline_header_search)
        val more = menu.addItem(MENU_MORE, org.telegram.messenger.R.drawable.ic_ab_other)
        if (pick == null) more.addSubItem(MENU_ADD_STORY, org.telegram.messenger.R.drawable.msg_camera, "Добавить историю")
        more.addSubItem(MENU_ACCOUNT, org.telegram.messenger.R.drawable.msg_openprofile, "Мой аккаунт MAX")
        more.addSubItem(MENU_NEW_CONTACT, org.telegram.messenger.R.drawable.msg_contact_add, "Новый контакт")
        more.addSubItem(MENU_JOIN_LINK, org.telegram.messenger.R.drawable.msg_link, "Вступить по ссылке")
        more.addSubItem(MENU_FIND_PHONE, org.telegram.messenger.R.drawable.msg_search, "Найти по номеру")
        more.addSubItem(MENU_RELOAD, org.telegram.messenger.R.drawable.msg_retry, "Обновить")
        more.addSubItem(MENU_LOGOUT, org.telegram.messenger.R.drawable.msg_leave, "Выйти из MAX")
        moreItem = more
        if (pick == null) {
            val headerParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ActionBar.getCurrentActionBarHeight(), Gravity.TOP or Gravity.START)
            headerParams.topMargin = if (actionBar.getOccupyStatusBar()) AndroidUtilities.statusBarHeight else 0
            headerParams.leftMargin = AndroidUtilities.dp(if (inTab) 14f else 64f)
            headerParams.rightMargin = AndroidUtilities.dp(104f)
            actionBar.addView(createTitleRow(context), headerParams)
        }

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        val root = column

        // Шапка списка, как у вкладок Telegram 13: истории, поле поиска, переключатель.

        searchField = FragmentSearchField(context, null)
        searchField.setupBlurredBackground(MaxGlass.topPanel(searchField, 20f))
        searchField.setHintText("Поиск", false)
        searchField.editText.hint = "Поиск"
        searchField.editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                query = e?.toString()?.trim() ?: ""
                updateUi()
            }
        })
        searchField.setCloseButtonOnClickListener { toggleSearch() }
        searchField.setCloseButtonVisible(true)
        searchField.setPadding(AndroidUtilities.dp(4f), AndroidUtilities.dp(4f), AndroidUtilities.dp(4f), AndroidUtilities.dp(4f))
        // «Активная шапка» как в Telegram: развёрнутые истории и поле поиска спрятаны и
        // появляются, если потянуть список вниз; при прокрутке вверх снова прячутся.
        val header = LinearLayout(context)
        header.orientation = LinearLayout.VERTICAL
        header.visibility = View.GONE
        val bar = MaxStoriesBar(context, { preview -> presentFragment(MaxStoryViewerFragment(preview)) }, { addStory() })
        storiesBar = bar
        header.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 98))
        header.addView(searchField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 6f, 0f, 6f, 0f))
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        root.layoutTransition = android.animation.LayoutTransition().apply { setDuration(220) }
        expandHeader = header

        filterTabs = FilterTabsView(context, null)
        filterTabs.setBlurredBackground(MaxGlass.topPanel(filterTabs, 18f).setPadding(AndroidUtilities.dp(6.666f)))
        filterTabs.setPadding(0, AndroidUtilities.dp(7f), 0, AndroidUtilities.dp(7f))
        filterTabs.setDelegate(object : FilterTabsView.FilterTabsViewDelegate {
            override fun onPageSelected(tab: FilterTabsView.Tab, forward: Boolean) = selectTab(tab.id)
            override fun onPageScrolled(progress: Float) {}
            override fun onSamePageSelected() {
                (if (tab == TAB_CHATS) chatsList else contactsList).smoothScrollToPosition(0)
            }
            override fun getTabCounter(tabId: Int): Int = when {
                tabId == TAB_CHATS -> MaxClient.chats.sumOf { it.unread }
                tabId >= TAB_FOLDER -> MaxClient.folders.getOrNull(tabId - TAB_FOLDER)
                    ?.let { f -> MaxClient.chats.filter { f.matches(it) }.sumOf { it.unread } } ?: 0
                else -> 0
            }
            override fun didSelectTab(tabView: FilterTabsView.TabView, selected: Boolean): Boolean = false
            override fun isTabMenuVisible(): Boolean = false
            override fun onDeletePressed(id: Int) {}
            override fun onPageReorder(fromId: Int, toId: Int) {}
            override fun canPerformActions(): Boolean = true
        })
        fillTabs()
        // Первая вкладка выбирается сама при добавлении. selectFirstTab() здесь звать нельзя:
        // он сразу дёргает делегат, а списков ещё нет — так падала v2.4.14.
        filterTabs.finishAddingTabs(false)
        if (pick != null) filterTabs.visibility = View.GONE
        root.addView(filterTabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 4f, 0f, 4f, 0f))

        val content = FrameLayout(context)
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        chatsList = RecyclerListView(context)
        chatsList.layoutManager = LinearLayoutManager(context)
        chatsList.adapter = chatsAdapter
        chatsList.setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { view, position ->
            val chat = chatsAdapter.items.getOrNull(position) as? MaxChat ?: return@OnItemLongClickListener false
            if (pick != null || MaxClient.chats.none { it.id == chat.id }) return@OnItemLongClickListener false
            showChatActions(chat, view)
            true
        })
        chatsList.setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
            val chat = chatsAdapter.items.getOrNull(position) as? MaxChat ?: return@OnItemClickListener
            if (pick != null) {
                finishFragment()
                pick.invoke(chat)
                return@OnItemClickListener
            }
            presentFragment(MaxChatFragment(chat.id))
        })
        content.addView(chatsList, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        attachPullToReveal(chatsList)
        contactsList = RecyclerListView(context)
        contactsList.layoutManager = LinearLayoutManager(context)
        contactsList.adapter = contactsAdapter
        contactsList.setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { view, position ->
            val contact = contactsAdapter.items.getOrNull(position - 1) ?: return@OnItemLongClickListener false
            showContactActions(contact, view)
            true
        })
        attachPullToReveal(contactsList)
        contactsList.visibility = View.GONE
        contactsList.setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
            if (position == 0) {
                askPhone()
                return@OnItemClickListener
            }
            val contact = contactsAdapter.items.getOrNull(position - 1) ?: return@OnItemClickListener
            openContact(contact)
        })
        content.addView(contactsList, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        empty = TextView(context)
        empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
        empty.gravity = Gravity.CENTER
        empty.setPadding(AndroidUtilities.dp(32f), 0, AndroidUtilities.dp(32f), 0)
        empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        empty.text = "Загружаем чаты…"
        content.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER, 0f, 0f, 0f, 0f))

        // Вёрстку собираем сами: шапка поверх, под ней колонка, внизу кнопка «новое сообщение».
        // Во вкладке главного экрана ActionBarLayout шапку не раскладывает, поэтому так в обоих режимах.
        actionBar.setAddToContainer(false)
        val frame = FrameLayout(context)
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        val top = ActionBar.getCurrentActionBarHeight() + (if (actionBar.getOccupyStatusBar()) AndroidUtilities.statusBarHeight else 0)
        val columnParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat())
        columnParams.topMargin = top
        frame.addView(column, columnParams)
        mainContent = column

        val bottom = if (inTab) AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS.toFloat()) + AndroidUtilities.navigationBarHeight else 0
        for (list in arrayOf(chatsList, contactsList)) {
            list.clipToPadding = false
            list.setPadding(0, 0, 0, bottom + AndroidUtilities.dp(80f))
        }

        val button = FragmentFloatingButton(context, null)
        button.setImageResource(org.telegram.messenger.R.drawable.filled_fab_compose_32)
        button.contentDescription = "Новое сообщение"
        button.setOnClickListener { newMessage() }
        val fabParams = FragmentFloatingButton.createDefaultLayoutParams()
        fabParams.bottomMargin += bottom
        frame.addView(button, fabParams)
        fab = button

        loginStub = createLoginStub(context, top, bottom)
        frame.addView(loginStub, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        frame.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))
        this.root = frame

        fragmentView = frame
        selectTab(TAB_CHATS)
        checkLoginState()
        return frame
    }

    /** Вход не выполнен: вкладка приглашает войти, сам раздел ничего не грузит. */
    private fun createLoginStub(context: Context, top: Int, bottom: Int): View {
        val stub = LinearLayout(context)
        stub.orientation = LinearLayout.VERTICAL
        stub.gravity = Gravity.CENTER
        stub.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        stub.setPadding(AndroidUtilities.dp(32f), top, AndroidUtilities.dp(32f), bottom)
        stub.isClickable = true

        val icon = android.widget.ImageView(context)
        icon.setImageResource(org.telegram.messenger.R.drawable.grambas_tab_max)
        icon.setColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton))
        stub.addView(icon, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20))

        val title = TextView(context)
        title.text = "MAX в Telegram"
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20f)
        title.typeface = AndroidUtilities.bold()
        title.gravity = Gravity.CENTER
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        stub.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10))

        val text = TextView(context)
        text.text = "Переписывайтесь в MAX прямо отсюда: чаты, контакты, фото и голосовые. " +
            "Это отдельный аккаунт, Telegram он не затрагивает.\n\n" +
            "Вход неофициальный, поэтому аккаунт MAX могут заблокировать."
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
        text.gravity = Gravity.CENTER
        text.setLineSpacing(AndroidUtilities.dp(2f).toFloat(), 1f)
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        stub.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 28))

        val button = TextView(context)
        button.text = "Войти в MAX"
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
        button.typeface = AndroidUtilities.bold()
        button.gravity = Gravity.CENTER
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
        button.background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 8f)
        button.setOnClickListener { presentFragment(MaxLoginFragment(fromTab = inTab)) }
        stub.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL))
        return stub
    }

    /** Показывает либо приглашение войти, либо чаты; при первом показе чатов поднимает связь. */
    private fun checkLoginState() {
        val loggedIn = MaxClient.isLoggedIn
        loginStub?.visibility = if (loggedIn) View.GONE else View.VISIBLE
        fab?.visibility = if (loggedIn && pick == null) View.VISIBLE else View.GONE
        moreItem?.visibility = if (loggedIn && pick == null) View.VISIBLE else View.GONE
        searchItem?.visibility = if (loggedIn) View.VISIBLE else View.GONE
        if (loggedIn && !started) {
            started = true
            chatsLoaded = false
            loadChats()
        } else if (!loggedIn) {
            started = false
        }
    }

    /** Карандаш, как в Telegram: новая группа, поиск по номеру или контакты. */
    private fun newMessage() {
        val anchor = fab ?: return
        ItemOptions.makeOptions(this, anchor)
            .add(org.telegram.messenger.R.drawable.msg_groups_create, "Новая группа") { newGroup() }
            .add(org.telegram.messenger.R.drawable.msg_user_search, "Найти по номеру") { askPhone() }
            .add(org.telegram.messenger.R.drawable.msg_contacts, "Контакты") { selectTab(TAB_CONTACTS) }
            .show()
    }

    private fun newGroup() {
        presentFragment(MaxContactPickerFragment("Новая группа", emptySet()) { ids ->
            askGroupTitle { title ->
                MaxClient.createGroup(title, ids) { chat, error ->
                    AndroidUtilities.runOnUIThread {
                        when {
                            chat != null -> presentFragment(MaxChatFragment(chat.id))
                            else -> showDialog(
                                AlertDialog.Builder(parentActivity ?: return@runOnUIThread)
                                    .setTitle("MAX")
                                    .setMessage("Группа не создана: " + MaxLog.describe(error))
                                    .setPositiveButton("ОК", null)
                                    .create()
                            )
                        }
                    }
                }
            }
        })
    }

    private fun askGroupTitle(onTitle: (String) -> Unit) {
        val context = parentActivity ?: return
        val input = EditText(context)
        input.hint = "Название группы"
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f)
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
        val wrap = FrameLayout(context)
        wrap.setPadding(AndroidUtilities.dp(24f), 0, AndroidUtilities.dp(24f), 0)
        wrap.addView(input)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Новая группа")
                .setView(wrap)
                .setPositiveButton("Создать") { _, _ ->
                    val title = input.text.toString().trim()
                    if (title.isNotEmpty()) onTitle(title)
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
        AndroidUtilities.runOnUIThread({ input.requestFocus(); AndroidUtilities.showKeyboard(input) }, 200)
    }

    /**
     * Вкладки как папки Telegram: «Все чаты», папки MAX с сервера (как у Komet), затем
     * «Контакты». id: 0 — все, 1 — контакты, 10+i — папка i.
     */
    private fun fillTabs() {
        filterTabs.addTab(TAB_CHATS, TAB_CHATS, "Все чаты", true, true, false)
        MaxClient.folders.forEachIndexed { i, folder ->
            val title = if (folder.emoji != null) "${folder.emoji} ${folder.title}" else folder.title
            filterTabs.addTab(TAB_FOLDER + i, TAB_FOLDER + i, title, true, false, false)
        }
        filterTabs.addTab(TAB_CONTACTS, TAB_CONTACTS, "Контакты", true, false, false)
        shownFolders = MaxClient.folders.map { it.id }
    }

    /** Папки пришли или поменялись — пересобираем вкладки, сохраняя выбранную. */
    private fun refreshTabs() {
        if (!::filterTabs.isInitialized || pick != null) return
        if (MaxClient.folders.map { it.id } == shownFolders) return
        val current = filterTabs.getCurrentTabId()
        filterTabs.removeTabs()
        fillTabs()
        filterTabs.finishAddingTabs(false)
        if (current >= 0 && !filterTabs.selectTabWithStableId(current)) {
            filterTabs.selectTabWithStableId(TAB_CHATS)
            selectTab(TAB_CHATS)
        }
        filterTabs.invalidate()
    }

    private fun tabPosition(id: Int): Int = when {
        id == TAB_CHATS -> 0
        id >= TAB_FOLDER -> 1 + (id - TAB_FOLDER)
        else -> 1 + MaxClient.folders.size
    }

    private fun selectTab(which: Int) {
        if (!::chatsList.isInitialized || !::contactsList.isInitialized) return
        // Переключение из кода (карандаш → «Контакты»): двигаем и полоску переключателя.
        // scrollToTab сам вызовет делегат, тот вернётся сюда уже с совпавшей вкладкой.
        val position = tabPosition(which)
        if (::filterTabs.isInitialized && filterTabs.getCurrentTabId() != which && position < filterTabs.getTabsCount()) {
            filterTabs.scrollToTab(filterTabs.getTab(position), position)
            return
        }
        folderIndex = if (which >= TAB_FOLDER) which - TAB_FOLDER else -1
        tab = if (which == TAB_CONTACTS) TAB_CONTACTS else TAB_CHATS
        val which = tab
        chatsList.visibility = if (which == TAB_CHATS) View.VISIBLE else View.GONE
        contactsList.visibility = if (which == TAB_CONTACTS) View.VISIBLE else View.GONE
        if (which == TAB_CONTACTS && MaxClient.contacts.isEmpty()) {
            MaxClient.loadContacts { error ->
                AndroidUtilities.runOnUIThread {
                    if (error != null) showEmpty("Не удалось загрузить контакты: " + MaxLog.describe(error))
                    else updateUi()
                }
            }
        }
        updateUi()
    }

    override fun isLightStatusBar(): Boolean =
        Theme.getColor(Theme.key_windowBackgroundWhite).let {
            (0.299 * android.graphics.Color.red(it) + 0.587 * android.graphics.Color.green(it) + 0.114 * android.graphics.Color.blue(it)) > 180
        }

    override fun onFragmentCreate(): Boolean {
        MaxClient.addListener(this)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        MaxClient.removeListener(this)
        AndroidUtilities.cancelRunOnUIThread(typingTicker)
        super.onFragmentDestroy()
    }

    override fun onResume() {
        super.onResume()
        checkLoginState()
        updateUi()
    }

    /** Загрузку начинаем из createView: ответ может прийти раньше, чем готова вёрстка. */
    private fun loadChats() {
        MaxClient.restoreFromCache()
        if (MaxClient.chats.isNotEmpty()) {
            // Список из кеша уже на экране; соединение и свежий вход — в фоне.
            chatsLoaded = true
            updateUi()
            MaxClient.start { }
            return
        }
        MaxClient.start { error ->
            AndroidUtilities.runOnUIThread {
                chatsLoaded = true
                if (error != null) showEmpty("Не удалось подключиться: " + MaxLog.describe(error))
                else updateUi()
            }
        }
    }

    override fun onChatsChanged() {
        AndroidUtilities.runOnUIThread {
            refreshTabs()
            updateUi()
            if (::filterTabs.isInitialized) filterTabs.notifyTabCounterChanged(TAB_CHATS)
        }
    }

    override fun onStoriesChanged() {
        AndroidUtilities.runOnUIThread {
            updateStoriesStack()
            storiesBar?.update()
            if (headerExpanded) storiesStack?.visibility = View.GONE
        }
    }

    /** Шапка вкладки: кружки историй внахлёст и заголовок, как «Telegram» на главном экране. */
    private fun createTitleRow(context: Context): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val stack = LinearLayout(context)
        stack.orientation = LinearLayout.HORIZONTAL
        stack.setOnClickListener { openStories() }
        row.addView(stack, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER_VERTICAL, 0, 0, 10, 0))
        storiesStack = stack
        val title = TextView(context)
        title.text = "MAX"
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20f)
        title.typeface = AndroidUtilities.bold()
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        row.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        updateStoriesStack()
        return row
    }

    /** До трёх колец внахлёст: непросмотренные первыми, как в шапке Telegram. */
    private fun updateStoriesStack() {
        val stack = storiesStack ?: return
        val context = stack.context
        stack.removeAllViews()
        val shown = MaxStoriesStore.previews.sortedBy { !it.hasUnread }.take(3)
        stack.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        shown.forEachIndexed { i, preview ->
            val frame = FrameLayout(context)
            val ring = MaxStoriesBar.Ring(context)
            ring.mode = if (preview.hasUnread) MaxStoriesBar.Ring.UNREAD else MaxStoriesBar.Ring.READ
            frame.addView(ring, LayoutHelper.createFrame(40, 40f))
            val avatar = org.telegram.ui.Components.BackupImageView(context)
            avatar.setRoundRadius(AndroidUtilities.dp(16f))
            MaxImages.bindAvatar(avatar, preview.owner.id, preview.title, preview.avatarUrl, 32)
            frame.addView(avatar, LayoutHelper.createFrame(32, 32f, Gravity.CENTER, 0f, 0f, 0f, 0f))
            stack.addView(frame, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, if (i == 0) 0 else -14, 0, 0, 0))
            frame.translationZ = (3 - i).toFloat()
        }
    }

    private fun openStories() {
        val first = MaxStoriesStore.previews.firstOrNull { it.hasUnread } ?: MaxStoriesStore.previews.firstOrNull() ?: return
        presentFragment(MaxStoryViewerFragment(first))
    }

    private fun toggleSearch() {
        if (!::searchField.isInitialized) return
        if (headerExpanded && searchField.editText.hasFocus()) {
            searchField.editText.setText("")
            AndroidUtilities.hideKeyboard(searchField.editText)
            searchField.editText.clearFocus()
            setHeaderExpanded(false)
        } else {
            setHeaderExpanded(true)
            searchField.editText.requestFocus()
            AndroidUtilities.showKeyboard(searchField.editText)
        }
    }

    private var expandHeader: View? = null
    private var storiesBar: MaxStoriesBar? = null
    private var headerExpanded = false

    private fun setHeaderExpanded(expanded: Boolean) {
        if (headerExpanded == expanded || pick != null) return
        headerExpanded = expanded
        // Истории — только у чатов и только если они есть; поиск — всегда.
        storiesBar?.visibility = if (tab == TAB_CHATS && MaxStoriesStore.previews.isNotEmpty()) View.VISIBLE else View.GONE
        expandHeader?.visibility = if (expanded) View.VISIBLE else View.GONE
        // Развёрнутые истории заменяют кружки у заголовка, как в Telegram.
        storiesStack?.visibility = if (expanded || MaxStoriesStore.previews.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Потянули список вниз, когда он уже в самом верху, — раскрываем шапку. */
    private fun attachPullToReveal(list: RecyclerListView) {
        var downY = 0f
        list.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> downY = event.y
                android.view.MotionEvent.ACTION_MOVE ->
                    if (!headerExpanded && !list.canScrollVertically(-1) && event.y - downY > AndroidUtilities.dp(36f)) {
                        setHeaderExpanded(true)
                    }
            }
            false
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > AndroidUtilities.dp(6f) && headerExpanded && query.isEmpty() && !searchField.editText.hasFocus()) {
                    setHeaderExpanded(false)
                }
            }
        })
    }

    /** Своя история: фото из галереи, как «Добавить историю» у Komet. */
    private fun addStory() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        try {
            startActivityForResult(android.content.Intent.createChooser(intent, null), REQ_STORY)
        } catch (e: Throwable) {
            toast("Нет приложения для выбора фото")
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode != REQ_STORY || resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val context = parentActivity ?: return
        toast("Публикуем историю…")
        Thread {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("фото не открылось")
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                val bytes = if (bitmap == null) raw else java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 87, it) }.toByteArray()
                MaxClient.publishPhotoStory(bytes) { error ->
                    toast(if (error == null) "История опубликована" else "История не опубликована: " + MaxLog.describe(error))
                }
            } catch (e: Throwable) {
                toast("История не опубликована: " + MaxLog.describe(e))
            }
        }.start()
    }

    private fun toast(text: String) {
        AndroidUtilities.runOnUIThread {
            val context = parentActivity ?: return@runOnUIThread
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onContactsChanged() {
        AndroidUtilities.runOnUIThread { updateUi() }
    }

    /** «Печатает…» в превью гаснет само: через 6 секунд перерисовываем список. */
    private val typingTicker = Runnable { updateUi() }

    override fun onTyping(chatId: Long, userId: Long) {
        AndroidUtilities.runOnUIThread {
            updateUi()
            AndroidUtilities.cancelRunOnUIThread(typingTicker)
            AndroidUtilities.runOnUIThread(typingTicker, 6_500L)
        }
    }

    override fun onReadMarkChanged(chatId: Long) {
        AndroidUtilities.runOnUIThread { updateUi() }
    }

    private var globalResults: List<MaxChat> = emptyList()
    private var globalQuery = ""
    private val globalSearch = Runnable {
        val q = globalQuery
        MaxClient.searchPublic(q) { list, _ ->
            AndroidUtilities.runOnUIThread {
                if (q != globalQuery) return@runOnUIThread
                globalResults = list ?: emptyList()
                updateUi()
            }
        }
    }

    /** Глобальный поиск — через 400 мс после последней буквы, от 3 символов. */
    private fun scheduleGlobalSearch(q: String) {
        if (q == globalQuery) return
        globalQuery = q
        globalResults = emptyList()
        AndroidUtilities.cancelRunOnUIThread(globalSearch)
        if (q.length >= 3 && pick == null) AndroidUtilities.runOnUIThread(globalSearch, 400)
    }

    /** Поле ввода для диалогов: подпись и текст, как в формах Telegram. */
    private fun dialogInput(context: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS): EditText {
        val input = EditText(context)
        input.inputType = type
        input.hint = hint
        input.setText(value)
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f)
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
        return input
    }

    private fun formOf(context: Context, vararg fields: EditText): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(AndroidUtilities.dp(24f), 0, AndroidUtilities.dp(24f), 0)
        fields.forEach { box.addView(it, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)) }
        return box
    }

    /** Новый контакт по номеру, как «Добавить контакт» у Komet (опкод 41). */
    private fun askNewContact() {
        val context = parentActivity ?: return
        val first = dialogInput(context, "Имя")
        val last = dialogInput(context, "Фамилия (необязательно)")
        val phone = dialogInput(context, "Номер телефона", "+7", InputType.TYPE_CLASS_PHONE)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Новый контакт")
                .setView(formOf(context, first, last, phone))
                .setPositiveButton("Добавить") { _, _ ->
                    MaxClient.addContactByPhone(phone.text.toString(), first.text.toString().trim(), last.text.toString().trim()) { contact, e ->
                        if (contact != null) toast("${contact.name} добавлен в контакты")
                        else toast("Не добавлен: " + MaxLog.describe(e))
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
        AndroidUtilities.runOnUIThread({ first.requestFocus(); AndroidUtilities.showKeyboard(first) }, 200)
    }

    /** Вступить в группу или канал по ссылке max.ru (опкоды 89 и 57, как у Komet). */
    private fun askLink() {
        val context = parentActivity ?: return
        val link = dialogInput(context, "https://max.ru/…", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Вступить по ссылке")
                .setView(formOf(context, link))
                .setPositiveButton("Открыть") { _, _ -> openLink(link.text.toString().trim()) }
                .setNegativeButton("Отмена", null)
                .create()
        )
        AndroidUtilities.runOnUIThread({ link.requestFocus(); AndroidUtilities.showKeyboard(link) }, 200)
    }

    private fun openLink(link: String) {
        if (link.isEmpty()) return
        MaxClient.resolveLink(link) { chat, user, e ->
            AndroidUtilities.runOnUIThread {
                val context = parentActivity ?: return@runOnUIThread
                when {
                    user != null -> openContact(user)
                    chat != null && MaxClient.chats.any { it.id == chat.id } -> presentFragment(MaxChatFragment(chat.id))
                    chat != null -> showDialog(
                        AlertDialog.Builder(context)
                            .setTitle(chat.title)
                            .setMessage(if (chat.isChannel) "Подписаться на канал?" else "Вступить в группу?")
                            .setPositiveButton(if (chat.isChannel) "Подписаться" else "Вступить") { _, _ ->
                                MaxClient.joinByLink(link) { joined, err ->
                                    AndroidUtilities.runOnUIThread {
                                        if (joined != null) presentFragment(MaxChatFragment(joined.id))
                                        else toast("Не получилось: " + MaxLog.describe(err))
                                    }
                                }
                            }
                            .setNeutralButton("Посмотреть") { _, _ -> presentFragment(MaxChatFragment(chat.id)) }
                            .setNegativeButton("Отмена", null)
                            .create()
                    )
                    else -> toast("Ссылка не открылась: " + MaxLog.describe(e))
                }
            }
        }
    }

    /** Долгое нажатие на контакт: переименовать, удалить, заблокировать (опкод 34). */
    private fun showContactActions(contact: MaxContact, anchor: View) {
        ItemOptions.makeOptions(this, anchor)
            .add(org.telegram.messenger.R.drawable.msg_edit, "Изменить имя") { renameContact(contact) }
            .add(org.telegram.messenger.R.drawable.msg_block, "Заблокировать") {
                MaxClient.setBlocked(contact.id, true) { e -> toast(if (e == null) "${contact.name} заблокирован" else "Не получилось: " + MaxLog.describe(e)) }
            }
            .add(org.telegram.messenger.R.drawable.msg_delete, "Удалить контакт", true) {
                MaxClient.updateContact(contact.id, "REMOVE") { e -> if (e != null) toast("Не получилось: " + MaxLog.describe(e)) }
            }
            .show()
    }

    private fun renameContact(contact: MaxContact) {
        val context = parentActivity ?: return
        val first = dialogInput(context, "Имя", contact.firstName)
        val last = dialogInput(context, "Фамилия", contact.lastName)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Изменить контакт")
                .setView(formOf(context, first, last))
                .setPositiveButton("Сохранить") { _, _ ->
                    MaxClient.updateContact(contact.id, "UPDATE", first.text.toString().trim(), last.text.toString().trim()) { e ->
                        if (e != null) toast("Не получилось: " + MaxLog.describe(e))
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    /** Долгое нажатие на чат — меню Telegram: прочитать, звук, очистить, удалить. */
    private fun showChatActions(chat: MaxChat, anchor: View) {
        val muted = MaxClient.isMuted(chat.id)
        ItemOptions.makeOptions(this, anchor)
            .addIf(chat.unread > 0, org.telegram.messenger.R.drawable.msg_markread, "Отметить прочитанным") { MaxClient.markChatRead(chat.id) }
            .add(if (muted) org.telegram.messenger.R.drawable.msg_unmute else org.telegram.messenger.R.drawable.msg_mute, if (muted) "Включить звук" else "Без звука") {
                MaxClient.setMuted(chat.id, !muted) { e -> if (e != null) toast("Не получилось: " + MaxLog.describe(e)) }
            }
            .add(org.telegram.messenger.R.drawable.msg_clear, "Очистить историю") { confirmClear(chat) }
            .add(org.telegram.messenger.R.drawable.msg_delete, if (chat.isDialog) "Удалить чат" else "Удалить и покинуть", true) { confirmDeleteChat(chat) }
            .show()
    }

    private fun confirmClear(chat: MaxChat) {
        val context = parentActivity ?: return
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Очистить историю")
                .setMessage("Удалить все сообщения в чате «${chat.title}» у вас?")
                .setPositiveButton("Очистить") { _, _ ->
                    MaxClient.clearHistory(chat.id, false) { e -> if (e != null) toast("Не получилось: " + MaxLog.describe(e)) }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    private fun confirmDeleteChat(chat: MaxChat) {
        val context = parentActivity ?: return
        showDialog(
            AlertDialog.Builder(context)
                .setTitle(if (chat.isDialog) "Удалить чат" else "Покинуть и удалить")
                .setMessage("Чат «${chat.title}» пропадёт из списка.")
                .setPositiveButton("Удалить") { _, _ ->
                    val done: (Throwable?) -> Unit = { e -> if (e != null) toast("Не получилось: " + MaxLog.describe(e)) }
                    if (chat.isDialog || chat.isSaved) MaxClient.deleteChat(chat.id, false, done) else MaxClient.leaveChat(chat.id, done)
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    private fun reload() {
        showEmpty("Обновляем…")
        MaxClient.reloadChats { error ->
            AndroidUtilities.runOnUIThread {
                if (error != null) showEmpty("Не удалось обновить: " + MaxLog.describe(error))
                else updateUi()
            }
        }
    }

    private fun updateUi() {
        if (!::empty.isInitialized) return
        val q = query.lowercase()
        val folder = MaxClient.folders.getOrNull(folderIndex)
        val local = MaxClient.chats.filter {
            (folder == null || folder.matches(it)) && (q.isEmpty() || it.title.lowercase().contains(q))
        }
        // При поиске — ниже своих чатов «Глобальный поиск»: люди и каналы MAX, как у Komet.
        val global = if (q.isEmpty() || pick != null) emptyList() else globalResults.filter { g -> local.none { it.id == g.id } }
        chatsAdapter.items = if (global.isEmpty()) local else local + GLOBAL_HEADER + global
        scheduleGlobalSearch(q)
        contactsAdapter.items = MaxClient.contacts.filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.phoneText.contains(q)
        }
        chatsAdapter.notifyDataSetChanged()
        contactsAdapter.notifyDataSetChanged()
        val text = when {
            tab == TAB_CHATS && !chatsLoaded -> "Загружаем чаты…"
            tab == TAB_CHATS && chatsAdapter.items.isEmpty() -> if (q.isEmpty()) "Пока нет чатов" else "Ищем…"
            tab == TAB_CONTACTS && contactsAdapter.items.isEmpty() && q.isNotEmpty() -> "Ничего не нашлось"
            else -> null
        }
        if (text == null) empty.visibility = View.GONE else showEmpty(text)
    }

    private fun showEmpty(text: String) {
        if (!::empty.isInitialized) return
        empty.text = text
        empty.visibility = View.VISIBLE
    }

    private fun openContact(contact: MaxContact) {
        val chat = MaxClient.chatForContact(contact.id)
        presentFragment(MaxChatFragment(chat.id, contact.id))
    }

    /** Поиск человека по номеру: находим и сразу открываем переписку. */
    private fun askPhone() {
        val context = parentActivity ?: return
        val input = EditText(context)
        input.inputType = InputType.TYPE_CLASS_PHONE
        input.hint = "+7 999 000 00 00"
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f)
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
        val wrap = FrameLayout(context)
        wrap.setPadding(AndroidUtilities.dp(24f), 0, AndroidUtilities.dp(24f), 0)
        wrap.addView(input)
        val dialog = AlertDialog.Builder(context)
            .setTitle("Найти по номеру")
            .setView(wrap)
            .setPositiveButton("Найти") { _, _ -> findPhone(input.text.toString()) }
            .setNegativeButton("Отмена", null)
            .create()
        showDialog(dialog)
        AndroidUtilities.runOnUIThread({ input.requestFocus(); AndroidUtilities.showKeyboard(input) }, 200)
    }

    private fun findPhone(phone: String) {
        if (phone.filter { it.isDigit() }.length < 5) return
        MaxClient.findByPhone(phone) { contact, error ->
            AndroidUtilities.runOnUIThread {
                val context = parentActivity ?: return@runOnUIThread
                if (contact == null) {
                    showDialog(
                        AlertDialog.Builder(context)
                            .setTitle("MAX")
                            .setMessage(if (error != null) MaxLog.describe(error) else "Пользователь с таким номером не найден")
                            .setPositiveButton("ОК", null)
                            .create()
                    )
                    return@runOnUIThread
                }
                openContact(contact)
            }
        }
    }

    private fun confirmLogout() {
        val context = parentActivity ?: return
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Выйти из MAX")
                .setMessage("Аккаунт MAX будет отключён на этом устройстве. Telegram это не затронет.")
                .setPositiveButton("Выйти") { _, _ ->
                    MaxClient.logout()
                    if (inTab) checkLoginState() else finishFragment()
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    private inner class ChatsAdapter : RecyclerListView.SelectionAdapter() {
        var items: List<Any> = emptyList()

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = holder.itemViewType == 0

        override fun getItemViewType(position: Int): Int = if (items[position] is MaxChat) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val header = org.telegram.ui.Cells.GraySectionCell(parent.context)
                header.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                return RecyclerListView.Holder(header)
            }
            val cell = MaxChatCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(cell)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items.getOrNull(position) ?: return
            if (item !is MaxChat) {
                (holder.itemView as org.telegram.ui.Cells.GraySectionCell).setText(item.toString())
                return
            }
            val cell = holder.itemView as MaxChatCell
            cell.needDivider = items.getOrNull(position + 1) is MaxChat
            cell.bind(item)
        }

        override fun getItemCount(): Int = items.size
    }

    /** Первая строка — «Найти по номеру», дальше адресная книга. */
    private inner class ContactsAdapter : RecyclerListView.SelectionAdapter() {
        var items: List<MaxContact> = emptyList()

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = MaxContactCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(cell)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val cell = holder.itemView as MaxContactCell
            if (position == 0) {
                cell.needDivider = true
                cell.bindAction("Найти по номеру", "Написать тому, кого нет в контактах")
                return
            }
            val contact = items.getOrNull(position - 1) ?: return
            cell.resetColors()
            cell.needDivider = position != items.size
            cell.bind(contact)
        }

        override fun getItemCount(): Int = items.size + 1
    }

    companion object {
        private const val TAB_CHATS = 0
        private const val TAB_CONTACTS = 1
        private const val TAB_FOLDER = 10

        private const val REQ_STORY = 7201
        private const val GLOBAL_HEADER = "Глобальный поиск"
        private const val MENU_SEARCH = 6
        private const val MENU_ADD_STORY = 7
        private const val MENU_MORE = 2
        private const val MENU_RELOAD = 3
        private const val MENU_LOGOUT = 4
        private const val MENU_FIND_PHONE = 5
        private const val MENU_ACCOUNT = 8
        private const val MENU_NEW_CONTACT = 9
        private const val MENU_JOIN_LINK = 10
    }
}
