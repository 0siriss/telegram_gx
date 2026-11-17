package org.telegram.max

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.FragmentFloatingButton
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView

/**
 * Выбор нескольких людей из адресной книги MAX — для новой группы и для добавления
 * участников. Отметки как в Telegram при создании группы, готово — круглой кнопкой.
 */
class MaxContactPickerFragment(
    private val title: String,
    private val exclude: Set<Long>,
    private val onDone: (List<Long>) -> Unit,
) : BaseFragment() {

    private val selected = LinkedHashSet<Long>()
    private var query = ""
    private val adapter = Adapter()
    private var doneButton: FragmentFloatingButton? = null

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(title)
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })
        actionBar.createMenu().addItem(1, org.telegram.messenger.R.drawable.outline_header_search)
            .setIsSearchField(true)
            .setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onTextChanged(editText: EditText) {
                    query = editText.text.toString().trim().lowercase()
                    adapter.update()
                }

                override fun onSearchCollapse() {
                    query = ""
                    adapter.update()
                }
            })

        val root = FrameLayout(context)
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        val list = RecyclerListView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.clipToPadding = false
        list.setPadding(0, 0, 0, AndroidUtilities.dp(88f))
        list.setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
            val contact = adapter.items.getOrNull(position) ?: return@OnItemClickListener
            if (!selected.add(contact.id)) selected.remove(contact.id)
            adapter.notifyItemChanged(position)
            updateTitle()
        })
        root.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        val done = FragmentFloatingButton(context, null)
        done.setImageResource(org.telegram.messenger.R.drawable.ic_ab_done)
        done.contentDescription = "Готово"
        done.setOnClickListener {
            if (selected.isEmpty()) return@setOnClickListener
            finishFragment()
            onDone(selected.toList())
        }
        root.addView(done, FragmentFloatingButton.createDefaultLayoutParams())
        doneButton = done

        fragmentView = root
        if (MaxClient.contacts.isEmpty()) MaxClient.loadContacts { AndroidUtilities.runOnUIThread { adapter.update() } }
        adapter.update()
        updateTitle()
        return root
    }

    private fun updateTitle() {
        actionBar.setSubtitle(if (selected.isEmpty()) "Выберите участников" else "Выбрано: ${selected.size}")
        doneButton?.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {
        var items: List<MaxContact> = emptyList()

        fun update() {
            items = MaxClient.contacts.filter {
                it.id !in exclude && (query.isEmpty() || it.name.lowercase().contains(query) || it.phoneText.contains(query))
            }
            notifyDataSetChanged()
        }

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val frame = FrameLayout(parent.context)
            val cell = MaxContactCell(parent.context)
            frame.addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))
            val check = ImageView(parent.context)
            check.setImageResource(org.telegram.messenger.R.drawable.checkbig)
            check.scaleType = ImageView.ScaleType.CENTER
            check.colorFilter = android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_checkboxCheck), android.graphics.PorterDuff.Mode.SRC_IN)
            check.background = Theme.createCircleDrawable(AndroidUtilities.dp(24f), Theme.getColor(Theme.key_checkbox))
            frame.addView(check, LayoutHelper.createFrame(24, 24f, Gravity.END or Gravity.CENTER_VERTICAL, 0f, 0f, 20f, 0f))
            frame.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(frame)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val contact = items.getOrNull(position) ?: return
            val frame = holder.itemView as FrameLayout
            val cell = frame.getChildAt(0) as MaxContactCell
            cell.resetColors()
            cell.needDivider = position != items.size - 1
            cell.bind(contact)
            frame.getChildAt(1).visibility = if (contact.id in selected) View.VISIBLE else View.GONE
        }

        override fun getItemCount(): Int = items.size
    }
}
