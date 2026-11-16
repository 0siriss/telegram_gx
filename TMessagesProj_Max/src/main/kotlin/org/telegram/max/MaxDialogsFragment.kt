package org.telegram.max

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView

/** Список чатов MAX. Своя модель и свой список, телеграмных диалогов не касается. */
class MaxDialogsFragment : BaseFragment(), MaxClient.Listener {

    private lateinit var listView: RecyclerListView
    private lateinit var empty: TextView
    private var adapter: Adapter? = null

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle("MAX")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_RELOAD -> reload()
                    MENU_LOGOUT -> {
                        MaxClient.logout()
                        finishFragment()
                    }
                }
            }
        })
        val menu = actionBar.createMenu()
        val more = menu.addItem(0, org.telegram.messenger.R.drawable.ic_ab_other)
        more.addSubItem(MENU_RELOAD, 0, "Обновить")
        more.addSubItem(MENU_LOGOUT, 0, "Выйти из MAX")

        val root = FrameLayout(context)
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))

        listView = RecyclerListView(context)
        listView.setLayoutManager(LinearLayoutManager(context))
        adapter = Adapter()
        listView.setAdapter(adapter)
        listView.setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
            val chat = MaxClient.chats.getOrNull(position) ?: return@OnItemClickListener
            presentFragment(MaxChatFragment(chat.id, chat.title))
        })
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL))

        empty = TextView(context)
        empty.textSize = 15f
        empty.gravity = Gravity.CENTER
        empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        empty.text = "Загружаем чаты…"
        root.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        fragmentView = root
        loadChats()
        return root
    }

    override fun onFragmentCreate(): Boolean {
        MaxClient.addListener(this)
        return super.onFragmentCreate()
    }

    /**
     * Загрузку начинаем из createView, а не из onFragmentCreate: там поля экрана ещё не
     * созданы, и ответ, пришедший раньше вёрстки, ронял бы фрагмент.
     */
    private fun loadChats() {
        if (MaxClient.chats.isNotEmpty()) {
            updateUi()
            return
        }
        MaxClient.start { error ->
            AndroidUtilities.runOnUIThread {
                if (error != null) showEmpty("Не удалось подключиться: " + MaxLog.describe(error))
                else updateUi()
            }
        }
    }

    override fun onFragmentDestroy() {
        MaxClient.removeListener(this)
        super.onFragmentDestroy()
    }

    override fun onChatsChanged() {
        AndroidUtilities.runOnUIThread { updateUi() }
    }

    override fun onNewMessage(chatId: Long, message: MaxMessage) {
        MaxClient.reloadChats { }
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
        adapter?.notifyDataSetChanged()
        if (MaxClient.chats.isEmpty()) {
            showEmpty("Пока нет чатов")
        } else {
            empty.visibility = View.GONE
        }
    }

    private fun showEmpty(text: String) {
        if (!::empty.isInitialized) return
        empty.text = text
        empty.visibility = View.VISIBLE
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return RecyclerListView.Holder(ChatCell(parent.context))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val chat = MaxClient.chats.getOrNull(position) ?: return
            (holder.itemView as ChatCell).bind(chat)
        }

        override fun getItemCount(): Int = MaxClient.chats.size
    }

    /** Ячейка чата: заголовок, последнее сообщение, время и счётчик непрочитанных. */
    private class ChatCell(context: Context) : LinearLayout(context) {

        private val title = TextView(context)
        private val subtitle = TextView(context)
        private val time = TextView(context)
        private val counter = TextView(context)

        init {
            orientation = HORIZONTAL
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(10f), AndroidUtilities.dp(16f), AndroidUtilities.dp(10f))
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))

            val texts = LinearLayout(context)
            texts.orientation = VERTICAL

            title.textSize = 16f
            title.typeface = AndroidUtilities.bold()
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            title.maxLines = 1
            texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

            subtitle.textSize = 14f
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            subtitle.maxLines = 1
            texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))

            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 0, 0))

            val side = LinearLayout(context)
            side.orientation = VERTICAL
            side.gravity = Gravity.END

            time.textSize = 12f
            time.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            side.addView(time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

            counter.textSize = 12f
            counter.typeface = AndroidUtilities.bold()
            counter.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            counter.gravity = Gravity.CENTER
            counter.setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(1f), AndroidUtilities.dp(6f), AndroidUtilities.dp(1f))
            counter.background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(9f),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButton)
            )
            side.addView(counter, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))

            addView(side, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 0f))
        }

        fun bind(chat: MaxChat) {
            title.text = chat.title
            subtitle.text = if (chat.lastMessage.isEmpty()) "Нет сообщений" else chat.lastMessage
            time.text = if (chat.lastTime > 0) LocaleController.stringForMessageListDate(chat.lastTime / 1000) else ""
            if (chat.unread > 0) {
                counter.visibility = VISIBLE
                counter.text = chat.unread.toString()
            } else {
                counter.visibility = GONE
            }
        }
    }

    companion object {
        private const val MENU_RELOAD = 1
        private const val MENU_LOGOUT = 2
    }
}
