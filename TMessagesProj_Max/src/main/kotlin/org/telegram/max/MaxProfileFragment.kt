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

    /** Сырой объект контакта из опкода 32: описание, ссылка, регистрация, страна. */
    private var info: org.json.JSONObject? = null

    /** Свежие сведения о группе или канале из опкода 48. */
    private var chatRaw: org.json.JSONObject? = null

    /** Фото профиля человека (опкод 39), первым — текущее. */
    private var photos: List<String> = emptyList()

    /** Последние фото и видео переписки (опкод 51). */
    private var media: List<MaxMessage> = emptyList()
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
        loadDetails()
        return listView
    }

    /** Подробности как в Komet: сведения о человеке или чате, фото профиля, медиа переписки. */
    private fun loadDetails() {
        if (userId != 0L) {
            MaxClient.contactInfo(userId) { raw, _ ->
                AndroidUtilities.runOnUIThread {
                    if (raw != null) {
                        info = raw
                        rebuild()
                    }
                }
            }
            MaxClient.contactPhotos(userId) { urls, _ ->
                AndroidUtilities.runOnUIThread {
                    if (urls.isNotEmpty()) {
                        photos = urls
                        rebuild()
                    }
                }
            }
        } else if (chatId != 0L) {
            MaxClient.chatInfo(chatId) { raw, _ ->
                AndroidUtilities.runOnUIThread {
                    if (raw != null) {
                        chatRaw = raw
                        rebuild()
                    }
                }
            }
        }
        val mediaChat = if (userId != 0L) MaxClient.chatForContact(userId) else chat
        val anchor = mediaChat?.lastMessage?.id ?: 0L
        if (mediaChat != null && anchor != 0L) {
            MaxClient.chatMedia(mediaChat.id, anchor, 30) { list, _ ->
                AndroidUtilities.runOnUIThread {
                    if (list.isNotEmpty()) {
                        media = list
                        rebuild()
                    }
                }
            }
        }
    }

    /** Аватарка на весь экран; у человека листаются все его фото профиля. */
    private fun openAvatar(current: String?) {
        val activity = parentActivity ?: return
        val list = if (photos.isNotEmpty()) photos else listOfNotNull(current)
        if (list.isEmpty()) return
        MaxMedia.showPhotos(activity, list, 0)
    }

    private fun openMedia(message: MaxMessage) {
        val activity = parentActivity ?: return
        val attach = message.attaches.firstOrNull { it.isPhoto || it.isVideo } ?: return
        if (attach.isPhoto) {
            val urls = media.mapNotNull { m -> m.attaches.firstOrNull { it.isPhoto }?.url }
            val start = urls.indexOf(attach.url).coerceAtLeast(0)
            MaxMedia.showPhotos(activity, urls, start)
        } else {
            val mediaChatId = if (userId != 0L) MaxClient.chatForContact(userId).id else chatId
            toast("Открываем видео…")
            MaxClient.videoUrl(mediaChatId, message.id, attach.videoId, attach.token ?: "") { url, error ->
                AndroidUtilities.runOnUIThread {
                    if (url != null) MaxMedia.openExternal(activity, url, "video/*")
                    else toast("Видео недоступно: " + MaxLog.describe(error))
                }
            }
        }
    }

    private fun dateText(ms: Long): String =
        java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("ru")).format(java.util.Date(ms))

    private fun linkText(link: String): String =
        if (link.startsWith("http")) link else "https://max.ru/" + link.trimStart('@', '/')

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

    /** Добавить в контакты или переименовать: опкод 34 ADD / UPDATE (Komet addContact, updateContact). */
    private fun editContact(inBook: Boolean) {
        val context = parentActivity ?: return
        val person = MaxContacts.get(userId)
        fun field(hint: String, value: String) = EditText(context).apply {
            this.hint = hint
            setText(value)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
        }
        val first = field("Имя", person?.firstName ?: "")
        val last = field("Фамилия", person?.lastName ?: "")
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(AndroidUtilities.dp(24f), 0, AndroidUtilities.dp(24f), 0)
        box.addView(first)
        box.addView(last)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle(if (inBook) "Изменить контакт" else "Добавить в контакты")
                .setView(box)
                .setPositiveButton("Сохранить") { _, _ ->
                    MaxClient.updateContact(userId, if (inBook) "UPDATE" else "ADD", first.text.toString().trim(), last.text.toString().trim()) { e ->
                        AndroidUtilities.runOnUIThread { if (e != null) toast("Не получилось: " + MaxLog.describe(e)) else rebuild() }
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    /** Ответы сервера приходят в фоне — показываем всегда на главном потоке. */
    private fun toast(text: String) {
        AndroidUtilities.runOnUIThread {
            val context = parentActivity ?: return@runOnUIThread
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
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
            val dialogId = MaxClient.chatForContact(userId).id
            if (userId != MaxStore.accountId) {
                val muted = MaxClient.isMuted(dialogId)
                actions.add(HeaderAction(
                    if (muted) org.telegram.messenger.R.drawable.filled_profile_unmute_24 else org.telegram.messenger.R.drawable.filled_profile_mute_24,
                    if (muted) "Включить" else "Без звука",
                ) {
                    MaxClient.setMuted(dialogId, !muted) { e -> AndroidUtilities.runOnUIThread { if (e != null) toast("Не получилось: " + MaxLog.describe(e)) else rebuild() } }
                })
            }
            if (phone.isNotEmpty()) actions.add(HeaderAction(org.telegram.messenger.R.drawable.filled_profile_call_24, "Номер") { copy(phone, "Номер") })
            rows.add(Row.Header(userId, name, photos.firstOrNull() ?: person?.avatarUrl, MaxText.presence(userId), actions))
            // Как в Telegram: телефон «Мобильный», ссылка, «О себе»; дальше сведения как в Komet.
            val raw = info
            if (phone.isNotEmpty()) rows.add(Row.Info(phone, "Мобильный") { copy(phone, "Номер") })
            raw?.optString("link", "")?.takeIf { it.isNotEmpty() }?.let { link ->
                val full = linkText(link)
                rows.add(Row.Info(full, "Ссылка") { copy(full, "Адрес") })
            }
            val bio = raw?.optString("description", "")?.trim()?.ifEmpty { raw.optString("about", "").trim() }.orEmpty()
            if (bio.isNotEmpty()) rows.add(Row.Info(bio, "О себе", multiline = true) { copy(bio, "Текст") })
            raw?.optLong("registrationTime", 0L)?.takeIf { it > 0 }?.let { rows.add(Row.Info(dateText(it), "В MAX с")) }
            raw?.optString("country", "")?.takeIf { it.isNotEmpty() }?.let { rows.add(Row.Info(it, "Страна")) }
            when (raw?.optInt("gender", 0)) {
                1 -> rows.add(Row.Info("Мужской", "Пол"))
                2 -> rows.add(Row.Info("Женский", "Пол"))
            }
            rows.add(Row.Info(userId.toString(), "ID в MAX") { copy(userId.toString(), "ID") })
            rows.add(Row.Shadow)
            addMediaRows()
            if (userId != MaxStore.accountId) {
                val inBook = MaxClient.contacts.any { it.id == userId }
                rows.add(Row.Action(org.telegram.messenger.R.drawable.msg_edit, if (inBook) "Изменить контакт" else "Добавить в контакты") { editContact(inBook) })
                if (inBook) rows.add(Row.Action(org.telegram.messenger.R.drawable.msg_delete, "Удалить контакт", red = true) {
                    MaxClient.updateContact(userId, "REMOVE") { e -> AndroidUtilities.runOnUIThread { if (e != null) toast("Не получилось: " + MaxLog.describe(e)) else rebuild() } }
                })
                rows.add(Row.Action(org.telegram.messenger.R.drawable.msg_block, "Заблокировать", red = true) {
                    MaxClient.setBlocked(userId, true) { e -> toast(if (e == null) "Пользователь заблокирован" else "Не получилось: " + MaxLog.describe(e)) }
                })
                rows.add(Row.Shadow)
            }
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
            val description = chatRaw?.optString("description", "")?.trim()?.ifEmpty { null } ?: current?.description
            if (!description.isNullOrEmpty()) rows.add(Row.Info(description, "Описание", multiline = true) { copy(description, "Текст") })
            val link = chatRaw?.optString("link", "")?.ifEmpty { null } ?: current?.link
            if (!link.isNullOrEmpty()) {
                val full = linkText(link)
                rows.add(Row.Info(full, "Ссылка") { copy(full, "Адрес") })
            }
            rows.add(Row.Info(chatId.toString(), "ID чата") { copy(chatId.toString(), "ID") })
            rows.add(Row.Shadow)
            addMediaRows()
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

    /** Раздел «Медиа» как в Telegram: последние фото и видео сеткой по три. */
    private fun addMediaRows() {
        if (media.isEmpty()) return
        rows.add(Row.Section("Медиа"))
        media.take(MEDIA_LIMIT).chunked(3).forEach { rows.add(Row.MediaRow(it)) }
        rows.add(Row.Shadow)
    }

    private class HeaderAction(val icon: Int, val title: String, val onClick: () -> Unit)

    private sealed class Row {
        class Header(val key: Long, val name: String, val avatar: String?, val status: String, val actions: List<HeaderAction>) : Row()
        class Info(val value: String, val label: String?, val multiline: Boolean = false, val onClick: (() -> Unit)? = null) : Row()
        class MediaRow(val items: List<MaxMessage>) : Row()
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
            is Row.Info -> if ((rows[position] as Row.Info).multiline) 7 else 1
            is Row.Section -> 2
            is Row.Member -> 3
            is Row.Action -> 4
            Row.Shadow -> 5
            is Row.MediaRow -> 6
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
                6 -> MediaRowCell(context).also { it.setBackgroundColor(white) }
                7 -> org.telegram.ui.Cells.TextDetailCell(context, null, true, false).also { it.setBackgroundColor(white) }
                else -> org.telegram.ui.Cells.ShadowSectionCell(context)
            }
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val next = rows.getOrNull(position + 1)
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as HeaderCell).bind(row) { openAvatar(row.avatar) }
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
                is Row.MediaRow -> (holder.itemView as MediaRowCell).bind(row.items)
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

        fun bind(row: Row.Header, onAvatar: () -> Unit) {
            avatar.setOnClickListener { onAvatar() }
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

    /** Строка сетки медиа: три квадратные превью, у видео — длительность. */
    private inner class MediaRowCell(context: Context) : LinearLayout(context) {
        init {
            orientation = HORIZONTAL
            setPadding(AndroidUtilities.dp(2f), 0, AndroidUtilities.dp(2f), 0)
        }

        fun bind(items: List<MaxMessage>) {
            removeAllViews()
            val side = (AndroidUtilities.displaySize.x - AndroidUtilities.dp(4f)) / 3
            for (i in 0 until 3) {
                val message = items.getOrNull(i)
                val tile = FrameLayout(context)
                if (message != null) {
                    val attach = message.attaches.first { it.isPhoto || it.isVideo }
                    val image = BackupImageView(context)
                    tile.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
                    MaxImages.bindImage(image, attach.url, side, Theme.getColor(Theme.key_windowBackgroundGray))
                    if (attach.isVideo) {
                        val badge = TextView(context)
                        badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f)
                        badge.setTextColor(0xffffffff.toInt())
                        badge.text = "▶ " + MaxMedia.durationText(attach.duration)
                        badge.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(8f), 0x88000000.toInt())
                        badge.setPadding(AndroidUtilities.dp(5f), AndroidUtilities.dp(1f), AndroidUtilities.dp(5f), AndroidUtilities.dp(1f))
                        tile.addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM or Gravity.START, 4f, 0f, 0f, 4f))
                    }
                    tile.setOnClickListener { openMedia(message) }
                }
                val params = LinearLayout.LayoutParams(side, side)
                params.setMargins(AndroidUtilities.dp(1f), AndroidUtilities.dp(1f), AndroidUtilities.dp(1f), AndroidUtilities.dp(1f))
                addView(tile, params)
            }
        }
    }

    companion object {
        private const val MEDIA_LIMIT = 30
    }
}
