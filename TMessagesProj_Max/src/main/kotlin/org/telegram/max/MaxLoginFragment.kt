package org.telegram.max

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/**
 * Вход в MAX: номер телефона, затем код из сообщения. Регистрации нет — номер должен
 * быть уже зарегистрирован в MAX.
 */
class MaxLoginFragment : BaseFragment() {

    private lateinit var input: EditText
    private lateinit var hint: TextView
    private lateinit var button: TextView
    private lateinit var status: TextView

    private var tempToken: String? = null
    private var busy = false

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle("MAX")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) finishFragment()
            }
        })

        val root = FrameLayout(context)
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(AndroidUtilities.dp(24f), AndroidUtilities.dp(24f), AndroidUtilities.dp(24f), AndroidUtilities.dp(24f))

        hint = TextView(context)
        hint.textSize = 15f
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        hint.text = "Введите номер телефона, на который зарегистрирован MAX"
        column.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        input = EditText(context)
        input.textSize = 18f
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
        input.hint = "+7 999 000 00 00"
        input.inputType = InputType.TYPE_CLASS_PHONE
        input.setBackgroundColor(0)
        column.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 16f, 0f, 0f))

        button = TextView(context)
        button.textSize = 15f
        button.typeface = AndroidUtilities.bold()
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
        button.gravity = Gravity.CENTER
        button.text = "Получить код"
        button.setPadding(0, AndroidUtilities.dp(14f), 0, AndroidUtilities.dp(14f))
        button.background = Theme.createSimpleSelectorRoundRectDrawable(
            AndroidUtilities.dp(8f),
            Theme.getColor(Theme.key_featuredStickers_addButton),
            Theme.getColor(Theme.key_featuredStickers_addButtonPressed)
        )
        button.setOnClickListener { onButtonClick() }
        column.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 24f, 0f, 0f))

        status = TextView(context)
        status.textSize = 14f
        status.setTextColor(Theme.getColor(Theme.key_text_RedRegular))
        column.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 16f, 0f, 0f))

        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP))
        fragmentView = root
        return root
    }

    private fun onButtonClick() {
        if (busy) return
        val value = input.text.toString().trim()
        if (value.isEmpty()) return
        busy = true
        status.text = ""
        button.text = "Подождите…"

        val pending = tempToken
        if (pending == null) {
            MaxClient.requestCode(value) { token, error ->
                AndroidUtilities.runOnUIThread {
                    busy = false
                    if (token == null) {
                        fail(error)
                        return@runOnUIThread
                    }
                    tempToken = token
                    hint.text = "Код отправлен. Введите его, чтобы войти"
                    input.setText("")
                    input.inputType = InputType.TYPE_CLASS_NUMBER
                    input.hint = "Код из сообщения"
                    button.text = "Войти"
                }
            }
        } else {
            MaxClient.verifyCode(value, pending) { error ->
                AndroidUtilities.runOnUIThread {
                    busy = false
                    if (error != null) {
                        fail(error)
                        button.text = "Войти"
                        return@runOnUIThread
                    }
                    presentFragment(MaxDialogsFragment(), true)
                }
            }
        }
    }

    private fun fail(error: Throwable?) {
        status.text = error?.message ?: "Не получилось, попробуйте ещё раз"
        button.text = if (tempToken == null) "Получить код" else "Войти"
    }
}
