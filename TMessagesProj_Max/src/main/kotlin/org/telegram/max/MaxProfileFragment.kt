package org.telegram.max

import android.content.Context
import org.telegram.ui.ActionBar.AlertDialog
import android.widget.FrameLayout
import android.widget.EditText
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
        actionBar.setTitle("")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        listView = RecyclerListView(context)
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        // Шапка профиля — одним цветом с панелью сверху, без тени, как в Telegram.
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_avatar_backgroundActionBarBlue))
        actionBar.setItemsColor(Theme.getColor(Theme.key_profile_title), false)
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_avatar_actionBarSelectorBlue), false)
        actionBar.setTitleColor(Theme.getColor(Theme.key_profile_title))
        actionBar.setCastShadows(false)
        listView.layoutManager = LinearLayoutManager(context)
        listView.adapter = adapter
        listView.setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { _, position ->
            val row = rows.getOrNull(position) as? Row.Member ?: return@OnItemLongClickListener false
            val current = chat
            if (current == null || !current.isGroup || row.contact.id == MaxStore.accountId) return@OnItemLongClickListener false
            removeMember(row.contact)
            true
        })
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
        reloadMembers()
        return listView
    }

    private fun reloadMembers() {
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
    }

    // ---- управление группой ----

    private fun addMembers() {
        presentFragment(MaxContactPickerFragment("Добавить участников", members.map { it.id }.toSet()) { ids ->
            MaxClient.updateMembers(chatId, ids, add = true) { error ->
                AndroidUtilities.runOnUIThread {
                    if (error != null) toast("Не удалось добавить: " + MaxLog.describe(error)) else reloadMembers()
                }
            }
        })
    }

    private fun removeMember(contact: MaxContact) {
        val context = parentActivity ?: return
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Удалить из группы")
                .setMessage("Удалить ${contact.name} из группы?")
                .setPositiveButton("Удалить") { _, _ ->
                    MaxClient.updateMembers(chatId, listOf(contact.id), add = false) { error ->
                        AndroidUtilities.runOnUIThread {
                            if (error != null) toast("Не удалось удалить: " + MaxLog.describe(error)) else reloadMembers()
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    private fun rename() {
        val context = parentActivity ?: return
        val input = EditText(context)
        input.setText(chat?.title ?: "")
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f)
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
        val wrap = FrameLayout(context)
        wrap.setPadding(AndroidUtilities.dp(24f), 0, AndroidUtilities.dp(24f), 0)
        wrap.addView(input)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Название группы")
                .setView(wrap)
                .setPositiveButton("Сохранить") { _, _ ->
                    val title = input.text.toString().trim()
                    if (title.isEmpty()) return@setPositiveButton
                    MaxClient.renameChat(chatId, title) { error ->
                        AndroidUtilities.runOnUIThread {
                            if (error != null) toast("Не удалось переименовать: " + MaxLog.describe(error)) else rebuild()
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    private fun leave() {
        val context = parentActivity ?: return
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Покинуть группу")
                .setMessage("Вы уверены, что хотите покинуть «${chat?.title ?: "группу"}»?")
                .setPositiveButton("Покинуть") { _, _ ->
                    MaxClient.leaveChat(chatId) { error ->
                        AndroidUtilities.runOnUIThread {
                            if (error != null) toast("Не удалось выйти: " + MaxLog.describe(error))
                            else {
                                // Переписка покинутой группы закроется сама при возврате к ней.
                                finishFragment()
                            }
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    private fun toast(text: String) {
        val context = parentActivity ?: return
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
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
        fun copy(value: String, what: String) {
            AndroidUtilities.addToClipboard(value)
            if (context != null) Toast.makeText(context, "$what скопирован", Toast.LENGTH_SHORT).show()
        }
        if (userId != 0L) {
            val person = MaxContacts.get(userId)
            val name = person?.name ?: "Пользователь $userId"
            val actions = ArrayList<HeaderAction>()
            if (userId != MaxStore.accountId) {
                actions.add(HeaderAction(org.telegram.messenger.R.drawable.filled_profile_message_24, "Сообщение") {
                    val target = MaxClient.chatForContact(userId)
                    presentFragment(MaxChatFragment(target.id, userId), chatId != 0L)
                })
            }
            val phone = person?.phoneText.orEmpty()
            if (phone.isNotEmpty()) actions.add(HeaderAction(org.telegram.messenger.R.drawable.filled_profile_call_24, "Номер") { copy(phone, "Номер") })
            rows.add(Row.Header(userId, name, person?.avatarUrl, MaxText.presence(userId), actions))
            // Как в Telegram: телефон с подписью «Мобильный», ниже идентификатор.
            if (phone.isNotEmpty()) rows.add(Row.Info(phone, "Мобильный") { copy(phone, "Номер") })
            rows.add(Row.Info(userId.toString(), "ID в MAX") { copy(userId.toString(), "ID") })
            rows.add(Row.Shadow)
        } else {
            val current = chat
            val title = current?.title ?: "Чат"
            val subtitle = current?.let { if (members.isNotEmpty()) MaxText.members(members.size) else MaxText.chatSubtitle(it) } ?: ""
            val actions = ArrayList<HeaderAction>()
            if (current != null && current.isGroup) {
                actions.add(HeaderAction(org.telegram.messenger.R.drawable.filled_profile_member_24, "Добавить") { addMembers() })
                actions.add(HeaderAction(org.telegram.messenger.R.drawable.filled_profile_edit_24, "Название") { rename() })
                actions.add(HeaderAction(org.telegram.messenger.R.drawable.leave, "Выйти") { leave() })
            }
            rows.add(Row.Header(current?.avatarKey ?: chatId, title, current?.avatarUrl, subtitle, actions))
            rows.add(Row.Info(chatId.toString(), "ID чата") { copy(chatId.toString(), "ID") })
            rows.add(Row.Shadow)
            if (current != null && current.isGroup || members.isNotEmpty()) {
                rows.add(Row.Section(if (members.isNotEmpty()) MaxText.members(members.size) else "Участники"))
                if (current != null && current.isGroup) {
                    rows.add(Row.Action(org.telegram.messenger.R.drawable.msg_contact_add, "Добавить участников") { addMembers() })
                }
                members.forEach { rows.add(Row.Member(it)) }
                rows.add(Row.Shadow)
            }
            if (current != null && current.isGroup) {
                rows.add(Row.Action(org.telegram.messenger.R.drawable.msg_leave, "Покинуть группу", red = true) { leave() })
                rows.add(Row.Shadow)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private class HeaderAction(val icon: Int, val title: String, val onClick: () -> Unit)

    private sealed class Row {
        class Header(val key: Long, val name: String, val avatar: String?, val status: String, val actions: List<HeaderAction>) : Row()
        class Info(val value: String, val label: String?, val onClick: (() -> Unit)? = null) : Row()
        class Action(val icon: Int, val title: String, val red: Boolean = false, val onClick: () -> Unit) : Row()
        class Section(val title: String) : Row()
        class Member(val contact: MaxContact) : Row()
        object Shadow : Row()
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean {
            val row = rows.getOrNull(holder.adapterPosition)
            return row is Row.Info || row is Row.Member || row is Row.Action
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> 0
            is Row.Info -> 1
            is Row.Section -> 2
            is Row.Member -> 3
            is Row.Action -> 4
            Row.Shadow -> 5
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            val white = Theme.getColor(Theme.key_windowBackgroundWhite)
            val view: View = when (viewType) {
                0 -> HeaderCell(context)
                1 -> org.telegram.ui.Cells.TextDetailCell(context).also { it.setBackgroundColor(white) }
                2 -> org.telegram.ui.Cells.HeaderCell(context).also { it.setBackgroundColor(white) }
                3 -> MaxContactCell(context).also { it.setBackgroundColor(white) }
                4 -> org.telegram.ui.Cells.TextCell(context).also { it.setBackgroundColor(white) }
                else -> org.telegram.ui.Cells.ShadowSectionCell(context)
            }
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val next = rows.getOrNull(position + 1)
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as HeaderCell).bind(row)
                is Row.Info -> (holder.itemView as org.telegram.ui.Cells.TextDetailCell)
                    .setTextAndValue(row.value, row.label ?: "", next is Row.Info)
                is Row.Section -> (holder.itemView as org.telegram.ui.Cells.HeaderCell).setText(row.title)
                is Row.Action -> {
                    val cell = holder.itemView as org.telegram.ui.Cells.TextCell
                    cell.setTextAndIcon(row.title, row.icon, next is Row.Member || next is Row.Action)
                    if (row.red) cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular)
                    else cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton)
                }
                is Row.Member -> {
                    val cell = holder.itemView as MaxContactCell
                    cell.resetColors()
                    cell.needDivider = next is Row.Member
                    cell.bind(row.contact)
                }
                Row.Shadow -> {}
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    /**
     * Шапка профиля как в Telegram 13: цветной фон шапки, крупная аватарка, белые имя и
     * статус, ниже плитки действий (полупрозрачные, белые иконки ProfileActionsView).
     */
    private class HeaderCell(context: Context) : LinearLayout(context) {
        private val avatar = BackupImageView(context)
        private val name = TextView(context)
        private val status = TextView(context)
        private val buttons = LinearLayout(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Theme.getColor(Theme.key_avatar_backgroundActionBarBlue))
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(4f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f))
            avatar.setRoundRadius(AndroidUtilities.dp(45f))
            addView(avatar, LayoutHelper.createLinear(90, 90))
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20f)
            name.typeface = AndroidUtilities.bold()
            name.gravity = Gravity.CENTER
            name.maxLines = 2
            name.ellipsize = TextUtils.TruncateAt.END
            name.setTextColor(Theme.getColor(Theme.key_profile_title))
            addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 12f, 24f, 0f))
            status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
            status.gravity = Gravity.CENTER
            status.setTextColor(Theme.getColor(Theme.key_avatar_subtitleInProfileBlue))
            addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 2f, 24f, 0f))
            buttons.orientation = HORIZONTAL
            addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 0f, 16f, 0f, 0f))
        }

        fun bind(row: Row.Header) {
            name.text = MaxBubbles.emojify(row.name, name)
            status.text = row.status
            MaxImages.bindAvatar(avatar, row.key, row.name, row.avatar, 90)
            buttons.removeAllViews()
            buttons.visibility = if (row.actions.isEmpty()) GONE else VISIBLE
            row.actions.forEachIndexed { i, action ->
                val tile = LinearLayout(context)
                tile.orientation = VERTICAL
                tile.gravity = Gravity.CENTER
                tile.background = Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12f), 0x28000000, 0x40000000)
                val icon = android.widget.ImageView(context)
                icon.setImageResource(action.icon)
                icon.colorFilter = android.graphics.PorterDuffColorFilter(0xffffffff.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                tile.addView(icon, LayoutHelper.createLinear(24, 24))
                val label = TextView(context)
                label.text = action.title
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f)
                label.typeface = AndroidUtilities.bold()
                label.setTextColor(0xffffffff.toInt())
                label.isSingleLine = true
                tile.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))
                tile.setOnClickListener { action.onClick() }
                buttons.addView(tile, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, if (i == 0) 0 else 8, 0, 0, 0))
            }
        }
    }
}
