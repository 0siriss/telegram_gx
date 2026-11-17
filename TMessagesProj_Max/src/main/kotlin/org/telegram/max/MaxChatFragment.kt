package org.telegram.max

import android.content.Context
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
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SizeNotifierFrameLayout
import java.util.Calendar

/**
 * Переписка в чате MAX в духе экрана чата Telegram: шапка с аватаркой, обои темы,
 * пузыри со временем и статусом, разделители дат, догрузка истории вверх. Вложения
 * пока показываются подписью («Фото», «Файл»), сами не открываются.
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
    private val adapter = Adapter()

    private var loading = false
    private var reachedStart = false

    private val chat: MaxChat
        get() = MaxClient.chatById(chatId)
            ?: (if (contactId != 0L) MaxChat.dialogWith(contactId) else MaxChat.dialogWith(chatId xor MaxStore.accountId))

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })
        actionBar.addView(createHeader(context), LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.START or Gravity.TOP, 56f, 0f, 16f, 0f
        ))

        val root = SizeNotifierFrameLayout(context)
        root.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion())

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        val listHolder = FrameLayout(context)
        column.addView(listHolder, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        listView = RecyclerListView(context)
        layoutManager = LinearLayoutManager(context)
        layoutManager.stackFromEnd = true
        listView.layoutManager = layoutManager
        listView.adapter = adapter
        listView.clipToPadding = false
        listView.setPadding(0, AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f))
        listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (layoutManager.findFirstVisibleItemPosition() <= 5) loadOlder()
            }
        })
        listView.setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { _, position ->
            val message = rows.getOrNull(position) as? MaxMessage ?: return@OnItemLongClickListener false
            AndroidUtilities.addToClipboard(message.displayText)
            Toast.makeText(context, "Текст скопирован", Toast.LENGTH_SHORT).show()
            true
        })
        listHolder.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        emptyView = TextView(context)
        emptyView.textSize = 14f
        emptyView.setTextColor(Theme.getColor(Theme.key_chat_serviceText))
        emptyView.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(14f), Theme.getColor(Theme.key_chat_serviceBackground))
        emptyView.setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(8f), AndroidUtilities.dp(14f), AndroidUtilities.dp(8f))
        emptyView.text = "Загружаем сообщения…"
        listHolder.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER, 0f, 0f, 0f, 0f))

        column.addView(createInput(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        fragmentView = root
        updateHeader()
        loadHistory()
        return root
    }

    private fun createHeader(context: Context): View {
        val header = FrameLayout(context)
        avatarView = BackupImageView(context)
        avatarView.setRoundRadius(AndroidUtilities.dp(21f))
        header.addView(avatarView, LayoutHelper.createFrame(42, 42f, Gravity.START or Gravity.CENTER_VERTICAL, 0f, 0f, 0f, 0f))

        titleView = TextView(context)
        titleView.textSize = 18f
        titleView.typeface = AndroidUtilities.bold()
        titleView.setTextColor(Theme.getColor(Theme.key_actionBarDefaultTitle))
        titleView.isSingleLine = true
        titleView.ellipsize = TextUtils.TruncateAt.END
        header.addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START or Gravity.TOP, 54f, 8f, 0f, 0f))

        subtitleView = TextView(context)
        subtitleView.textSize = 14f
        subtitleView.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubtitle))
        subtitleView.isSingleLine = true
        subtitleView.ellipsize = TextUtils.TruncateAt.END
        header.addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START or Gravity.TOP, 54f, 31f, 0f, 0f))
        return header
    }

    private fun updateHeader() {
        if (!::titleView.isInitialized) return
        val current = chat
        val name = current.title
        titleView.text = name
        MaxImages.bindAvatar(avatarView, current.avatarKey, name, current.avatarUrl, 42)
        subtitleView.text = when {
            current.isSaved -> ""
            current.isDialog -> MaxContacts.get(current.peerId)?.phoneText?.ifEmpty { null } ?: "в MAX"
            current.isChannel -> "канал"
            current.participants.isNotEmpty() -> participantsText(current.participants.size)
            else -> "группа"
        }
        subtitleView.visibility = if (subtitleView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun participantsText(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        val word = when {
            mod10 == 1 && mod100 != 11 -> "участник"
            mod10 in 2..4 && mod100 !in 12..14 -> "участника"
            else -> "участников"
        }
        return "$count $word"
    }

    private fun createInput(context: Context): View {
        val panel = LinearLayout(context)
        panel.orientation = LinearLayout.HORIZONTAL
        panel.setBackgroundColor(Theme.getColor(Theme.key_chat_messagePanelBackground))
        panel.setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(4f), AndroidUtilities.dp(4f), AndroidUtilities.dp(4f))

        input = EditText(context)
        input.textSize = 18f
        input.setTextColor(Theme.getColor(Theme.key_chat_messagePanelText))
        input.setHintTextColor(Theme.getColor(Theme.key_chat_messagePanelHint))
        input.hint = "Сообщение"
        input.background = null
        input.maxLines = 6
        input.minHeight = AndroidUtilities.dp(44f)
        input.setPadding(0, AndroidUtilities.dp(10f), 0, AndroidUtilities.dp(10f))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSendButton()
            }
        })
        panel.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        sendButton = ImageView(context)
        sendButton.setImageResource(org.telegram.messenger.R.drawable.attach_send)
        sendButton.scaleType = ImageView.ScaleType.CENTER
        sendButton.colorFilter = android.graphics.PorterDuffColorFilter(
            Theme.getColor(Theme.key_chat_messagePanelSend), android.graphics.PorterDuff.Mode.SRC_IN
        )
        sendButton.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1)
        sendButton.setOnClickListener { onSend() }
        panel.addView(sendButton, LayoutHelper.createLinear(48, 48, Gravity.BOTTOM))
        updateSendButton()
        return panel
    }

    private fun updateSendButton() {
        if (!::sendButton.isInitialized) return
        val has = input.text.toString().isNotBlank()
        sendButton.alpha = if (has) 1f else 0.4f
        sendButton.isEnabled = has
    }

    override fun onFragmentCreate(): Boolean {
        MaxClient.addListener(this)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        MaxClient.removeListener(this)
        if (MaxClient.openChatId == chatId) MaxClient.openChatId = null
        super.onFragmentDestroy()
    }

    override fun onResume() {
        super.onResume()
        MaxClient.openChatId = chatId
        markRead()
    }

    override fun onPause() {
        super.onPause()
        if (MaxClient.openChatId == chatId) MaxClient.openChatId = null
    }

    /** Первая порция истории. Грузим из createView: ответ может прийти раньше вёрстки. */
    private fun loadHistory() {
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

    override fun onNewMessage(chatId: Long, message: MaxMessage) {
        if (chatId != this.chatId) return
        AndroidUtilities.runOnUIThread {
            val stick = isAtBottom()
            merge(listOf(message))
            rebuild(scrollToEnd = stick || message.isOutgoing)
            if (!isPaused()) markRead()
        }
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

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        val local = MaxMessage(0L, -System.currentTimeMillis(), MaxStore.accountId, text, System.currentTimeMillis(), null, false)
        local.pending = true
        merge(listOf(local))
        rebuild(scrollToEnd = true)
        MaxClient.sendMessage(chatId, local) { sent, error ->
            AndroidUtilities.runOnUIThread {
                val index = messages.indexOfFirst { it === local }
                if (error != null || sent == null) {
                    local.pending = false
                    local.failed = true
                    adapter.notifyDataSetChanged()
                    Toast.makeText(parentActivity, "Не отправлено: " + MaxLog.describe(error), Toast.LENGTH_SHORT).show()
                    return@runOnUIThread
                }
                if (index >= 0) messages.removeAt(index)
                merge(listOf(sent))
                rebuild(scrollToEnd = isAtBottom())
            }
        }
    }

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
                    val prev = rows.getOrNull(position - 1) as? MaxMessage
                    val sameAuthor = prev != null && !prev.isService && prev.senderId == row.senderId
                    (holder.itemView as MessageCell).bind(row, showName = !chat.isDialog && !chat.isChannel && !row.isOutgoing && !sameAuthor)
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
            text.textSize = 14f
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

    /** Пузырь: входящие слева, исходящие справа, имя автора в группах, время и статус внизу. */
    private inner class MessageCell(context: Context) : FrameLayout(context) {

        private val bubble = LinearLayout(context)
        private val author = TextView(context)
        private val body = TextView(context)
        private val meta = TextView(context)

        init {
            bubble.orientation = LinearLayout.VERTICAL
            bubble.setPadding(AndroidUtilities.dp(11f), AndroidUtilities.dp(6f), AndroidUtilities.dp(11f), AndroidUtilities.dp(5f))

            author.textSize = 14f
            author.typeface = AndroidUtilities.bold()
            author.isSingleLine = true
            author.ellipsize = TextUtils.TruncateAt.END
            bubble.addView(author, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

            body.textSize = 16f
            bubble.addView(body, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

            meta.textSize = 12f
            bubble.addView(meta, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END, 0, 1, 0, 0))

            addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START, 8f, 2f, 8f, 2f))
        }

        fun bind(message: MaxMessage, showName: Boolean) {
            val out = message.isOutgoing
            body.text = message.displayText
            body.setTextColor(Theme.getColor(if (out) Theme.key_chat_messageTextOut else Theme.key_chat_messageTextIn))
            if (message.attachLabel != null && message.text.isEmpty()) {
                body.setTypeface(null, android.graphics.Typeface.ITALIC)
            } else {
                body.setTypeface(null, android.graphics.Typeface.NORMAL)
            }

            if (showName) {
                author.visibility = VISIBLE
                author.text = MaxContacts.get(message.senderId)?.name ?: "Участник"
                author.setTextColor(Theme.getColor(NAME_COLORS[(Math.abs(message.senderId) % NAME_COLORS.size).toInt()]))
            } else {
                author.visibility = GONE
            }

            val time = LocaleController.getInstance().formatterDay.format(message.time)
            val status = when {
                !out -> ""
                message.failed -> "  !"
                message.pending -> "  ◷"
                else -> "  ✓"
            }
            meta.text = time + status
            meta.setTextColor(Theme.getColor(if (out) Theme.key_chat_outTimeText else Theme.key_chat_inTimeText))

            bubble.background = Theme.createRoundRectDrawable(
                AndroidUtilities.dp(16f),
                Theme.getColor(if (out) Theme.key_chat_outBubble else Theme.key_chat_inBubble)
            )
            val params = bubble.layoutParams as LayoutParams
            params.gravity = if (out) Gravity.END else Gravity.START
            params.leftMargin = AndroidUtilities.dp(if (out) 56f else 8f)
            params.rightMargin = AndroidUtilities.dp(if (out) 8f else 56f)
            bubble.layoutParams = params
            body.maxWidth = (AndroidUtilities.displaySize.x * 0.78f).toInt()
        }
    }

    companion object {
        private const val PAGE = 50
        private const val TYPE_MESSAGE = 0
        private const val TYPE_SERVICE = 1

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
