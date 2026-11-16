package org.telegram.max

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView

/** Переписка в чате MAX. В первой версии только текст: вложения не отправляются и не показываются. */
class MaxChatFragment(private val chatId: Long, private val chatTitle: String) :
    BaseFragment(), MaxClient.Listener {

    private val messages = ArrayList<MaxMessage>()

    private lateinit var listView: RecyclerListView
    private lateinit var input: EditText
    private var adapter: Adapter? = null

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(chatTitle)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))

        listView = RecyclerListView(context)
        val layoutManager = LinearLayoutManager(context)
        layoutManager.stackFromEnd = true
        listView.setLayoutManager(layoutManager)
        adapter = Adapter()
        listView.setAdapter(adapter)
        root.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f, 0, 0, 0, 0))

        val bottom = LinearLayout(context)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        bottom.setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(8f), AndroidUtilities.dp(12f), AndroidUtilities.dp(8f))

        input = EditText(context)
        input.textSize = 16f
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
        input.hint = "Сообщение"
        input.setBackgroundColor(0)
        input.maxLines = 4
        bottom.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 0, 0))

        val send = ImageView(context)
        send.setImageResource(org.telegram.messenger.R.drawable.attach_send)
        send.colorFilter = android.graphics.PorterDuffColorFilter(
            Theme.getColor(Theme.key_chat_messagePanelSend), android.graphics.PorterDuff.Mode.SRC_IN
        )
        send.setOnClickListener { onSend() }
        bottom.addView(send, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 8, 0, 0, 0))

        root.addView(bottom, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        fragmentView = root
        loadHistory()
        return root
    }

    override fun onFragmentCreate(): Boolean {
        MaxClient.addListener(this)
        return super.onFragmentCreate()
    }

    /** Историю грузим из createView: ответ может прийти раньше, чем будет создана вёрстка. */
    private fun loadHistory() {
        MaxClient.loadHistory(chatId, 50) { list, error ->
            AndroidUtilities.runOnUIThread {
                if (error != null || list == null) {
                    MaxLog.e("история не загрузилась", error)
                    return@runOnUIThread
                }
                messages.clear()
                messages.addAll(list)
                adapter?.notifyDataSetChanged()
                scrollToBottom()
            }
        }
    }

    override fun onFragmentDestroy() {
        MaxClient.removeListener(this)
        super.onFragmentDestroy()
    }

    override fun onNewMessage(chatId: Long, message: MaxMessage) {
        if (chatId != this.chatId) return
        AndroidUtilities.runOnUIThread {
            messages.add(message)
            adapter?.notifyDataSetChanged()
            scrollToBottom()
        }
    }

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        val local = MaxMessage("local-" + System.currentTimeMillis(), MaxStore.accountId, text, System.currentTimeMillis())
        messages.add(local)
        adapter?.notifyDataSetChanged()
        scrollToBottom()
        MaxClient.sendMessage(chatId, text) { error ->
            if (error != null) {
                AndroidUtilities.runOnUIThread {
                    MaxLog.e("сообщение не отправилось", error)
                }
            }
        }
    }

    private fun scrollToBottom() {
        // Пуш может прийти раньше вёрстки: слушателя мы ставим в onFragmentCreate.
        if (!::listView.isInitialized) return
        if (messages.isNotEmpty()) {
            listView.scrollToPosition(messages.size - 1)
        }
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = false

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return RecyclerListView.Holder(MessageCell(parent.context))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val message = messages.getOrNull(position) ?: return
            (holder.itemView as MessageCell).bind(message)
        }

        override fun getItemCount(): Int = messages.size
    }

    /** Пузырь сообщения: входящие слева, исходящие справа. */
    private class MessageCell(context: Context) : FrameLayout(context) {

        private val bubble = TextView(context)

        init {
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(3f), AndroidUtilities.dp(12f), AndroidUtilities.dp(3f))
            bubble.textSize = 16f
            bubble.setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(8f), AndroidUtilities.dp(12f), AndroidUtilities.dp(8f))
            addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START))
        }

        fun bind(message: MaxMessage) {
            bubble.text = message.text
            val params = bubble.layoutParams as LayoutParams
            if (message.isOutgoing) {
                params.gravity = Gravity.END
                bubble.setTextColor(Theme.getColor(Theme.key_chat_messageTextOut))
                bubble.background = Theme.createSimpleSelectorRoundRectDrawable(
                    AndroidUtilities.dp(14f),
                    Theme.getColor(Theme.key_chat_outBubble),
                    Theme.getColor(Theme.key_chat_outBubble)
                )
            } else {
                params.gravity = Gravity.START
                bubble.setTextColor(Theme.getColor(Theme.key_chat_messageTextIn))
                bubble.background = Theme.createSimpleSelectorRoundRectDrawable(
                    AndroidUtilities.dp(14f),
                    Theme.getColor(Theme.key_chat_inBubble),
                    Theme.getColor(Theme.key_chat_inBubble)
                )
            }
            bubble.layoutParams = params
        }
    }
}
