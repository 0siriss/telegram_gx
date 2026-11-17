package org.telegram.max

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.widget.Toast
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
 * Вход в MAX: номер телефона, затем код из сообщения. Если аккаунта на номере ещё нет,
 * сервер вместо входа отдаёт токен регистрации, и экран просит имя — это третий шаг.
 */
class MaxLoginFragment : BaseFragment() {

    private lateinit var input: EditText
    private lateinit var lastNameInput: EditText
    private lateinit var hint: TextView
    private lateinit var button: TextView
    private lateinit var status: TextView
    private lateinit var diag: TextView

    private var tempToken: String? = null
    private var busy = false

    /** Шаг: номер -> код -> имя (последний только для нового номера). */
    private var step = STEP_PHONE

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
        hint.text = PHONE_HINT
        column.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        input = EditText(context)
        input.textSize = 18f
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
        input.hint = "+7 999 000 00 00"
        input.inputType = InputType.TYPE_CLASS_PHONE
        input.setBackgroundColor(0)
        column.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 16f, 0f, 0f))

        lastNameInput = EditText(context)
        lastNameInput.textSize = 18f
        lastNameInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        lastNameInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
        lastNameInput.hint = "Фамилия (необязательно)"
        lastNameInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        lastNameInput.setBackgroundColor(0)
        lastNameInput.visibility = View.GONE
        column.addView(lastNameInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 12f, 0f, 0f))

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

        // Ответ сервера как есть. Пока раздел обкатывается, это единственный способ
        // понять, почему код не дошёл: лог приложения с телефона не достать.
        diag = TextView(context)
        diag.textSize = 11f
        diag.setTextIsSelectable(true)
        diag.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        diag.setOnLongClickListener {
            AndroidUtilities.addToClipboard(diag.text.toString())
            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
            true
        }
        column.addView(diag, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 20f, 0f, 0f))

        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP))
        fragmentView = root
        connect()
        return root
    }

    /**
     * Поднимаем соединение сразу при открытии экрана: до рукопожатия сервер не примет ни
     * запрос кода, ни вход, а пользователь должен видеть, что идёт подключение.
     */
    private fun connect() {
        hint.text = "Подключаемся к MAX…"
        button.isEnabled = false
        MaxClient.start { error ->
            AndroidUtilities.runOnUIThread {
                button.isEnabled = true
                showDiagnostics()
                if (step == STEP_PHONE) {
                    hint.text = PHONE_HINT
                }
                if (error != null) {
                    status.text = "Не удалось подключиться: " + MaxLog.describe(error)
                }
            }
        }
    }

    private fun onButtonClick() {
        if (busy) return
        val value = input.text.toString().trim()
        if (value.isEmpty()) return
        busy = true
        status.text = ""
        button.text = "Подождите…"

        when (step) {
            STEP_PHONE -> MaxClient.requestCode(value) { token, error ->
                AndroidUtilities.runOnUIThread {
                    busy = false
                    if (token == null) {
                        fail(error)
                        return@runOnUIThread
                    }
                    showDiagnostics()
                    tempToken = token
                    step = STEP_CODE
                    hint.text = "Код отправлен. Он приходит сообщением, а если MAX уже\n" +
                        "стоит на этом номере — внутрь него"
                    input.setText("")
                    input.inputType = InputType.TYPE_CLASS_NUMBER
                    input.hint = "Код из сообщения"
                    button.text = "Войти"
                }
            }

            STEP_CODE -> {
                val pending = tempToken
                if (pending == null) {
                    busy = false
                    fail(IllegalStateException("потерян токен, запросите код заново"))
                    return
                }
                MaxClient.verifyCode(value, pending) { error ->
                AndroidUtilities.runOnUIThread {
                    busy = false
                    if (error is RegistrationRequired) {
                        // Номера ещё нет в MAX: остаётся представиться и завершить регистрацию.
                        step = STEP_NAME
                        hint.text = "Этого номера ещё нет в MAX. Как вас зовут?"
                        input.setText("")
                        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                        input.hint = "Имя"
                        lastNameInput.visibility = View.VISIBLE
                        button.text = "Зарегистрироваться"
                        return@runOnUIThread
                    }
                    if (error != null) {
                        fail(error)
                        return@runOnUIThread
                    }
                    presentFragment(MaxDialogsFragment(), true)
                    }
                }
            }

            else -> MaxClient.completeRegistration(value, lastNameInput.text.toString().trim()) { error ->
                AndroidUtilities.runOnUIThread {
                    busy = false
                    if (error != null) {
                        fail(error)
                        return@runOnUIThread
                    }
                    presentFragment(MaxDialogsFragment(), true)
                }
            }
        }
    }

    /** Подтягивает ответы сервера на экран после каждого шага. */
    private fun showDiagnostics() {
        if (!::diag.isInitialized) return
        diag.text = MaxClient.diagnostics
    }

    private fun fail(error: Throwable?) {
        showDiagnostics()
        status.text = if (error == null) "Не получилось, попробуйте ещё раз" else MaxLog.describe(error)
        button.text = when (step) {
            STEP_PHONE -> "Получить код"
            STEP_CODE -> "Войти"
            else -> "Зарегистрироваться"
        }
    }

    companion object {
        private const val PHONE_HINT =
            "Введите номер телефона. Если аккаунта MAX на нём нет, после кода " +
                "предложим зарегистрироваться"

        private const val STEP_PHONE = 0
        private const val STEP_CODE = 1
        private const val STEP_NAME = 2
    }
}
