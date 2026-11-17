package org.telegram.max

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Свой аккаунт MAX, как профиль и настройки у Komet: имя и фото, активные сеансы с
 * завершением чужих, чёрный список, выход. Оформление — ячейки настроек Telegram.
 */
class MaxAccountFragment : BaseFragment(), MaxClient.Listener {

    private sealed class Row {
        object Header : Row()
        class Action(val icon: Int, val title: String, val red: Boolean = false, val onClick: () -> Unit) : Row()
        class Section(val title: String) : Row()
        class Info(val value: String, val label: String, val onClick: (() -> Unit)? = null) : Row()
        class Person(val contact: MaxContact) : Row()
        class Note(val text: String) : Row()
        object Shadow : Row()
    }

    private val rows = ArrayList<Row>()
    private val adapter = Adapter()
    private lateinit var listView: RecyclerListView
    private var sessions: List<MaxClient.SessionInfo>? = null
    private var blocked: List<MaxContact>? = null

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setTitle("")
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_avatar_backgroundActionBarBlue))
        actionBar.setItemsColor(Theme.getColor(Theme.key_profile_title), false)
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_avatar_actionBarSelectorBlue), false)
        actionBar.setCastShadows(false)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        listView = RecyclerListView(context)
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        listView.layoutManager = LinearLayoutManager(context)
        listView.adapter = adapter
        listView.setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
            when (val row = rows.getOrNull(position)) {
                is Row.Action -> row.onClick()
                is Row.Info -> row.onClick?.invoke()
                is Row.Person -> confirmUnblock(row.contact)
                else -> {}
            }
        })
        fragmentView = listView
        rebuild()
        load()
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

    override fun onContactsChanged() {
        AndroidUtilities.runOnUIThread { rebuild() }
    }

    private fun load() {
        MaxClient.sessions { list, _ -> AndroidUtilities.runOnUIThread { sessions = list ?: emptyList(); rebuild() } }
        MaxClient.blocked { list, _ -> AndroidUtilities.runOnUIThread { blocked = list ?: emptyList(); rebuild() } }
    }

    private fun me(): MaxContact? = MaxContacts.get(MaxStore.accountId)

    private fun rebuild() {
        if (!::listView.isInitialized) return
        rows.clear()
        rows.add(Row.Header)
        val me = me()
        me?.phoneText?.takeIf { it.isNotEmpty() }?.let { rows.add(Row.Info(it, "Номер телефона")) }
        rows.add(Row.Info(MaxStore.accountId.toString(), "ID в MAX") {
            AndroidUtilities.addToClipboard(MaxStore.accountId.toString())
            toast("ID скопирован")
        })
        rows.add(Row.Shadow)
        rows.add(Row.Action(org.telegram.messenger.R.drawable.msg_edit, "Изменить имя") { editName() })
        rows.add(Row.Action(org.telegram.messenger.R.drawable.msg_addphoto, "Сменить фото") { pickPhoto() })
        rows.add(Row.Shadow)

        rows.add(Row.Section("Активные сеансы"))
        val list = sessions
        when {
            list == null -> rows.add(Row.Note("Загружаем…"))
            else -> {
                val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
                list.forEach { s ->
                    val parts = listOf(s.info, s.location, if (s.time > 0) fmt.format(Date(s.time)) else "")
                        .filter { it.isNotEmpty() }.joinToString(" · ")
                    rows.add(Row.Info(s.client.ifEmpty { "Неизвестное устройство" } + if (s.current) " — это устройство" else "", parts))
                }
                if (list.count { !it.current } > 0) {
                    rows.add(Row.Action(org.telegram.messenger.R.drawable.msg_block, "Завершить другие сеансы", red = true) { confirmCloseSessions() })
                }
            }
        }
        rows.add(Row.Shadow)

        rows.add(Row.Section("Чёрный список"))
        val blockedList = blocked
        when {
            blockedList == null -> rows.add(Row.Note("Загружаем…"))
            blockedList.isEmpty() -> rows.add(Row.Note("Никого нет"))
            else -> blockedList.forEach { rows.add(Row.Person(it)) }
        }
        rows.add(Row.Shadow)
        rows.add(Row.Action(org.telegram.messenger.R.drawable.msg_leave, "Выйти из MAX", red = true) { confirmLogout() })
        rows.add(Row.Shadow)
        adapter.notifyDataSetChanged()
    }

    private fun input(context: Context, hint: String, value: String): EditText {
        val view = EditText(context)
        view.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        view.hint = hint
        view.setText(value)
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18f)
        view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
        view.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
        return view
    }

    /** Своё имя — опкод 16 (Komet updateProfileName). */
    private fun editName() {
        val context = parentActivity ?: return
        val me = me()
        val first = input(context, "Имя", me?.firstName ?: "")
        val last = input(context, "Фамилия", me?.lastName ?: "")
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(AndroidUtilities.dp(24f), 0, AndroidUtilities.dp(24f), 0)
        box.addView(first)
        box.addView(last)
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Ваше имя")
                .setView(box)
                .setPositiveButton("Сохранить") { _, _ ->
                    val f = first.text.toString().trim()
                    if (f.isEmpty()) return@setPositiveButton
                    MaxClient.updateProfileName(f, last.text.toString().trim()) { e ->
                        toast(if (e == null) "Имя изменено" else "Не получилось: " + MaxLog.describe(e))
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    private fun pickPhoto() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        try {
            startActivityForResult(Intent.createChooser(intent, null), REQ_PHOTO)
        } catch (e: Throwable) {
            toast("Нет приложения для выбора фото")
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PHOTO || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val context = parentActivity ?: return
        toast("Загружаем фото…")
        Thread {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("фото не открылось")
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                val bytes = if (bitmap == null) raw else java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                MaxClient.updateProfilePhoto(bytes) { e -> toast(if (e == null) "Фото обновлено" else "Не получилось: " + MaxLog.describe(e)) }
            } catch (e: Throwable) {
                toast("Не получилось: " + MaxLog.describe(e))
            }
        }.start()
    }

    private fun confirmCloseSessions() {
        val context = parentActivity ?: return
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Завершить другие сеансы")
                .setMessage("Выйти из MAX на всех устройствах, кроме этого?")
                .setPositiveButton("Завершить") { _, _ ->
                    MaxClient.closeOtherSessions { e ->
                        toast(if (e == null) "Другие сеансы завершены" else "Не получилось: " + MaxLog.describe(e))
                        if (e == null) MaxClient.sessions { list, _ -> AndroidUtilities.runOnUIThread { sessions = list; rebuild() } }
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    private fun confirmUnblock(contact: MaxContact) {
        val context = parentActivity ?: return
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Разблокировать")
                .setMessage("Разблокировать ${contact.name}?")
                .setPositiveButton("Разблокировать") { _, _ ->
                    MaxClient.setBlocked(contact.id, false) { e ->
                        AndroidUtilities.runOnUIThread {
                            if (e != null) toast("Не получилось: " + MaxLog.describe(e))
                            else {
                                blocked = blocked?.filter { it.id != contact.id }
                                rebuild()
                            }
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    private fun confirmLogout() {
        val context = parentActivity ?: return
        showDialog(
            AlertDialog.Builder(context)
                .setTitle("Выйти из MAX")
                .setMessage("Аккаунт MAX будет отключён на этом устройстве. Telegram это не затронет.")
                .setPositiveButton("Выйти") { _, _ ->
                    MaxClient.logout()
                    finishFragment()
                }
                .setNegativeButton("Отмена", null)
                .create()
        )
    }

    private fun toast(text: String) {
        AndroidUtilities.runOnUIThread {
            val context = parentActivity ?: return@runOnUIThread
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean {
            val row = rows.getOrNull(holder.adapterPosition)
            return row is Row.Action || row is Row.Person || (row is Row.Info && row.onClick != null)
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            Row.Header -> 0
            is Row.Action -> 1
            is Row.Section -> 2
            is Row.Info -> 3
            is Row.Person -> 4
            is Row.Note -> 5
            Row.Shadow -> 6
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            val white = Theme.getColor(Theme.key_windowBackgroundWhite)
            val view: View = when (viewType) {
                0 -> HeaderView(context)
                1 -> org.telegram.ui.Cells.TextCell(context).also { it.setBackgroundColor(white) }
                2 -> org.telegram.ui.Cells.HeaderCell(context).also { it.setBackgroundColor(white) }
                3 -> org.telegram.ui.Cells.TextDetailCell(context).also { it.setBackgroundColor(white) }
                4 -> MaxContactCell(context).also { it.setBackgroundColor(white) }
                5 -> org.telegram.ui.Cells.TextInfoPrivacyCell(context)
                else -> org.telegram.ui.Cells.ShadowSectionCell(context)
            }
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val next = rows.getOrNull(position + 1)
            when (val row = rows[position]) {
                Row.Header -> (holder.itemView as HeaderView).bind()
                is Row.Action -> {
                    val cell = holder.itemView as org.telegram.ui.Cells.TextCell
                    cell.setTextAndIcon(row.title, row.icon, next is Row.Action)
                    if (row.red) cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular)
                    else cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText)
                }
                is Row.Section -> (holder.itemView as org.telegram.ui.Cells.HeaderCell).setText(row.title)
                is Row.Info -> (holder.itemView as org.telegram.ui.Cells.TextDetailCell)
                    .setTextAndValue(row.value, row.label, next is Row.Info || next is Row.Action)
                is Row.Person -> {
                    val cell = holder.itemView as MaxContactCell
                    cell.resetColors()
                    cell.needDivider = next is Row.Person
                    cell.bind(row.contact)
                }
                is Row.Note -> (holder.itemView as org.telegram.ui.Cells.TextInfoPrivacyCell).setText(row.text)
                Row.Shadow -> {}
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    /** Шапка: своя аватарка (нажатие — сменить), имя и «в сети», как профиль Telegram. */
    private inner class HeaderView(context: Context) : LinearLayout(context) {
        private val avatar = BackupImageView(context)
        private val name = TextView(context)
        private val status = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Theme.getColor(Theme.key_avatar_backgroundActionBarBlue))
            setPadding(0, AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(20f))
            avatar.setRoundRadius(AndroidUtilities.dp(45f))
            avatar.setOnClickListener { pickPhoto() }
            addView(avatar, LayoutHelper.createLinear(90, 90))
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20f)
            name.typeface = AndroidUtilities.bold()
            name.gravity = Gravity.CENTER
            name.setTextColor(Theme.getColor(Theme.key_profile_title))
            addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 12f, 24f, 0f))
            status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
            status.gravity = Gravity.CENTER
            status.setTextColor(Theme.getColor(Theme.key_avatar_subtitleInProfileBlue))
            status.text = "в сети"
            addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 2f, 24f, 0f))
        }

        fun bind() {
            val me = me()
            val title = me?.name ?: "Мой аккаунт"
            name.text = MaxBubbles.emojify(title, name)
            MaxImages.bindAvatar(avatar, MaxStore.accountId, title, me?.avatarUrl, 90)
        }
    }

    companion object {
        private const val REQ_PHOTO = 7301
    }
}
