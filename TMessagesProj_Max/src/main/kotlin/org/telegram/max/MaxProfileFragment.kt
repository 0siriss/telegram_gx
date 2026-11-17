package org.telegram.max

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView

/**
 * Профиль в MAX: собеседник личного диалога или группа/канал. У человека — аватарка,
 * статус, телефон (нажатие копирует), «Написать». У группы — участники.
 */
class MaxProfileFragment(private val chatId: Long, private val userId: Long) :
    BaseFragment(), MaxClient.Listener {

    private val rows = ArrayList<Row>()
    private val adapter = Adapter()
    private var members: List<MaxContact> = emptyList()
    private lateinit var listView: RecyclerListView

    private val chat: MaxChat?
        get() = if (chatId != 0L || userId == 0L) MaxClient.chatById(chatId) else null

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(if (userId != 0L) "Профиль" else "Информация")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        listView = RecyclerListView(context)
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        listView.layoutManager = LinearLayoutManager(context)
        listView.adapter = adapter
        listView.setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
            when (val row = rows.getOrNull(position)) {
                is Row.Info -> row.onClick?.invoke()
                is Row.Member -> if (row.contact.id != MaxStore.accountId) {
                    presentFragment(MaxProfileFragment(0L, row.contact.id))
                }
                else -> {}
            }
        })
        fragmentView = listView
        rebuild()
        if (userId != 0L) MaxClient.refreshPresence(listOf(userId))
        val current = chat
        if (current != null && !current.isDialog) {
            MaxClient.loadMembers(current.id) { list, _ ->
                AndroidUtilities.runOnUIThread {
                    if (list != null) {
                        members = list
                        rebuild()
                    }
                }
            }
        }
        return listView
    }

    override fun onFragmentCreate(): Boolean {
        MaxClient.addListener(this)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        MaxClient.removeListener(this)
        super.onFragmentDestroy()
    }

    override fun onPresenceChanged(userId: Long) {
        AndroidUtilities.runOnUIThread { rebuild() }
    }

    override fun onContactsChanged() {
        AndroidUtilities.runOnUIThread { rebuild() }
    }

    private fun rebuild() {
        if (!::listView.isInitialized) return
        rows.clear()
        val context = parentActivity
        if (userId != 0L) {
            val person = MaxContacts.get(userId)
            val name = person?.name ?: "Пользователь $userId"
            rows.add(Row.Header(userId, name, person?.avatarUrl, MaxText.presence(userId)))
            val phone = person?.phoneText.orEmpty()
            if (phone.isNotEmpty()) {
                rows.add(Row.Info(phone, "Телефон") {
                    AndroidUtilities.addToClipboard(phone)
                    if (context != null) Toast.makeText(context, "Номер скопирован", Toast.LENGTH_SHORT).show()
                })
            }
            rows.add(Row.Info(userId.toString(), "ID в MAX") {
                AndroidUtilities.addToClipboard(userId.toString())
                if (context != null) Toast.makeText(context, "ID скопирован", Toast.LENGTH_SHORT).show()
            })
            if (userId != MaxStore.accountId) {
                rows.add(Row.Info("Написать сообщение", null, accent = true) {
                    val target = MaxClient.chatForContact(userId)
                    presentFragment(MaxChatFragment(target.id, userId), chatId != 0L)
                })
            }
        } else {
            val current = chat
            val title = current?.title ?: "Чат"
            val subtitle = current?.let { if (members.isNotEmpty()) MaxText.members(members.size) else MaxText.chatSubtitle(it) } ?: ""
            rows.add(Row.Header(current?.avatarKey ?: chatId, title, current?.avatarUrl, subtitle))
            if (members.isNotEmpty()) {
                rows.add(Row.Section("Участники"))
                members.forEach { rows.add(Row.Member(it)) }
            }
        }
        adapter.notifyDataSetChanged()
    }

    private sealed class Row {
        class Header(val key: Long, val name: String, val avatar: String?, val status: String) : Row()
        class Info(val value: String, val label: String?, val accent: Boolean = false, val onClick: (() -> Unit)? = null) : Row()
        class Section(val title: String) : Row()
        class Member(val contact: MaxContact) : Row()
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean {
            val row = rows.getOrNull(holder.adapterPosition)
            return row is Row.Info || row is Row.Member
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> 0
            is Row.Info -> 1
            is Row.Section -> 2
            is Row.Member -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                0 -> HeaderCell(parent.context)
                3 -> MaxContactCell(parent.context)
                else -> TextRowCell(parent.context)
            }
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as HeaderCell).bind(row)
                is Row.Info -> (holder.itemView as TextRowCell).bind(row.value, row.label, row.accent)
                is Row.Section -> (holder.itemView as TextRowCell).bindSection(row.title)
                is Row.Member -> {
                    val cell = holder.itemView as MaxContactCell
                    cell.resetColors()
                    cell.bind(row.contact)
                }
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    /** Шапка профиля: большая аватарка, имя, статус. */
    private class HeaderCell(context: Context) : LinearLayout(context) {
        private val avatar = BackupImageView(context)
        private val name = TextView(context)
        private val status = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, AndroidUtilities.dp(20f), 0, AndroidUtilities.dp(16f))
            avatar.setRoundRadius(AndroidUtilities.dp(45f))
            addView(avatar, LayoutHelper.createLinear(90, 90))
            name.textSize = 20f
            name.typeface = AndroidUtilities.bold()
            name.gravity = Gravity.CENTER
            name.maxLines = 2
            name.ellipsize = TextUtils.TruncateAt.END
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 12f, 24f, 0f))
            status.textSize = 14f
            status.gravity = Gravity.CENTER
            status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 4f, 24f, 0f))
        }

        fun bind(row: Row.Header) {
            name.text = row.name
            status.text = row.status
            MaxImages.bindAvatar(avatar, row.key, row.name, row.avatar, 90)
        }
    }

    /** Строка «значение + подпись» или заголовок раздела. */
    private class TextRowCell(context: Context) : LinearLayout(context) {
        private val value = TextView(context)
        private val label = TextView(context)

        init {
            orientation = VERTICAL
            setPadding(AndroidUtilities.dp(21f), AndroidUtilities.dp(10f), AndroidUtilities.dp(21f), AndroidUtilities.dp(10f))
            value.textSize = 16f
            addView(value, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            label.textSize = 13f
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        }

        fun bind(text: String, hint: String?, accent: Boolean) {
            value.text = text
            value.typeface = null
            value.setTextColor(Theme.getColor(if (accent) Theme.key_windowBackgroundWhiteBlueText2 else Theme.key_windowBackgroundWhiteBlackText))
            label.text = hint ?: ""
            label.visibility = if (hint == null) GONE else VISIBLE
        }

        fun bindSection(title: String) {
            value.text = title
            value.typeface = AndroidUtilities.bold()
            value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
            label.visibility = GONE
        }
    }
}
