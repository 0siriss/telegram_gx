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
import org.telegram.ui.Components.FragmentFloatingButton
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
    private var searchItem: ActionBarMenuItem? = null
    private var moreItem: ActionBarMenuItem? = null
    private var started = false

    private lateinit var chatsList: RecyclerListView
    private lateinit var contactsList: RecyclerListView
    private lateinit var empty: TextView
    private lateinit var tabChats: TextView
    private lateinit var tabContacts: TextView
    private lateinit var tabLine: View

    private val chatsAdapter = ChatsAdapter()
    private val contactsAdapter = ContactsAdapter()

    private var tab = TAB_CHATS
    private var query = ""
    private var chatsLoaded = false

    override fun createView(context: Context): View {
        if (!inTab) actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(if (pick != null) "Переслать…" else "MAX")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_RELOAD -> reload()
                    MENU_FIND_PHONE -> askPhone()
                    MENU_LOGOUT -> confirmLogout()
                }
            }
        })
        val menu = actionBar.createMenu()
        val search = menu.addItem(MENU_SEARCH, org.telegram.messenger.R.drawable.outline_header_search)
        search.setIsSearchField(true)
            .setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onTextChanged(editText: EditText) {
                    query = editText.text.toString().trim()
                    updateUi()
                }

                override fun onSearchCollapse() {
                    query = ""
                    updateUi()
                }
            })
        search.setSearchFieldHint("Поиск")
        searchItem = search
        val more = menu.addItem(MENU_MORE, org.telegram.messenger.R.drawable.ic_ab_other)
        more.addSubItem(MENU_FIND_PHONE, org.telegram.messenger.R.drawable.msg_search, "Найти по номеру")
        more.addSubItem(MENU_RELOAD, org.telegram.messenger.R.drawable.msg_retry, "Обновить")
        more.addSubItem(MENU_LOGOUT, org.telegram.messenger.R.drawable.msg_leave, "Выйти из MAX")
        moreItem = more

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        val root = column

        val tabsBar = createTabs(context)
        if (pick != null) tabsBar.visibility = View.GONE
        root.addView(tabsBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44))

        val content = FrameLayout(context)
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        chatsList = RecyclerListView(context)
        chatsList.layoutManager = LinearLayoutManager(context)
        chatsList.adapter = chatsAdapter
        chatsList.setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
            val chat = chatsAdapter.items.getOrNull(position) ?: return@OnItemClickListener
            if (pick != null) {
                finishFragment()
                pick.invoke(chat)
                return@OnItemClickListener
            }
            presentFragment(MaxChatFragment(chat.id))
        })
        content.addView(chatsList, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        contactsList = RecyclerListView(context)
        contactsList.layoutManager = LinearLayoutManager(context)
        contactsList.adapter = contactsAdapter
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
        empty.textSize = 15f
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
        title.textSize = 20f
        title.typeface = AndroidUtilities.bold()
        title.gravity = Gravity.CENTER
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        stub.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10))

        val text = TextView(context)
        text.text = "Переписывайтесь в MAX прямо отсюда: чаты, контакты, фото и голосовые. " +
            "Это отдельный аккаунт, Telegram он не затрагивает.\n\n" +
            "Вход неофициальный, поэтому аккаунт MAX могут заблокировать."
        text.textSize = 15f
        text.gravity = Gravity.CENTER
        text.setLineSpacing(AndroidUtilities.dp(2f).toFloat(), 1f)
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        stub.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 28))

        val button = TextView(context)
        button.text = "Войти в MAX"
        button.textSize = 15f
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
        searchItem?.visibility = if (loggedIn) View.VISIBLE else View.GONE
        moreItem?.visibility = if (loggedIn && pick == null) View.VISIBLE else View.GONE
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
        input.textSize = 18f
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

    private fun createTabs(context: Context): View {
        val bar = FrameLayout(context)
        bar.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault))
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        tabChats = tabText(context, "Чаты") { selectTab(TAB_CHATS) }
        tabContacts = tabText(context, "Контакты") { selectTab(TAB_CONTACTS) }
        row.addView(tabChats, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f))
        row.addView(tabContacts, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f))
        bar.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        tabLine = View(context)
        tabLine.setBackgroundColor(Theme.getColor(Theme.key_actionBarTabLine))
        bar.addView(tabLine, LayoutHelper.createFrame(0, 3f, Gravity.BOTTOM or Gravity.START, 0f, 0f, 0f, 0f))
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeTabLine() }
        return bar
    }

    private fun tabText(context: Context, text: String, onClick: () -> Unit): TextView {
        val view = TextView(context)
        view.text = text
        view.textSize = 15f
        view.typeface = AndroidUtilities.bold()
        view.gravity = Gravity.CENTER
        view.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_actionBarDefaultSelector), 2)
        view.setOnClickListener { onClick() }
        return view
    }

    private fun placeTabLine() {
        if (!::tabLine.isInitialized) return
        val parent = tabLine.parent as? View ?: return
        val half = parent.width / 2
        val params = tabLine.layoutParams as FrameLayout.LayoutParams
        if (params.width != half || params.leftMargin != tab * half) {
            params.width = half
            params.leftMargin = tab * half
            tabLine.layoutParams = params
        }
    }

    private fun selectTab(which: Int) {
        tab = which
        tabChats.setTextColor(Theme.getColor(if (which == TAB_CHATS) Theme.key_actionBarTabActiveText else Theme.key_actionBarTabUnactiveText))
        tabContacts.setTextColor(Theme.getColor(if (which == TAB_CONTACTS) Theme.key_actionBarTabActiveText else Theme.key_actionBarTabUnactiveText))
        chatsList.visibility = if (which == TAB_CHATS) View.VISIBLE else View.GONE
        contactsList.visibility = if (which == TAB_CONTACTS) View.VISIBLE else View.GONE
        placeTabLine()
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
        AndroidUtilities.runOnUIThread { updateUi() }
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
        chatsAdapter.items = MaxClient.chats.filter { q.isEmpty() || it.title.lowercase().contains(q) }
        contactsAdapter.items = MaxClient.contacts.filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.phoneText.contains(q)
        }
        chatsAdapter.notifyDataSetChanged()
        contactsAdapter.notifyDataSetChanged()
        val text = when {
            tab == TAB_CHATS && !chatsLoaded -> "Загружаем чаты…"
            tab == TAB_CHATS && chatsAdapter.items.isEmpty() -> if (q.isEmpty()) "Пока нет чатов" else "Ничего не нашлось"
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
        input.textSize = 18f
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
        var items: List<MaxChat> = emptyList()

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = MaxChatCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(cell)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val chat = items.getOrNull(position) ?: return
            val cell = holder.itemView as MaxChatCell
            cell.needDivider = position != items.size - 1
            cell.bind(chat)
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

        private const val MENU_SEARCH = 1
        private const val MENU_MORE = 2
        private const val MENU_RELOAD = 3
        private const val MENU_LOGOUT = 4
        private const val MENU_FIND_PHONE = 5
    }
}
