package org.telegram.max

import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor

/**
 * «Стеклянные» плашки Telegram 13 — те же drawable и цвета, что у ChatActivity и
 * DialogsActivity (`BlurredBackgroundProviderImpl`). Источник — цветной, без размытия
 * подложки: полупрозрачная плашка с обводкой и тенью, как Telegram рисует их на
 * устройствах без размытия.
 */
object MaxGlass {

    private val factory: BlurredBackgroundDrawableViewFactory by lazy {
        BlurredBackgroundDrawableViewFactory(BlurredBackgroundSourceColor())
    }

    /** Шапка переписки: круг «назад», плашка заголовка, круг меню — как в ChatActivity. */
    fun setupChatHeader(actionBar: ActionBar) {
        actionBar.setupGlass(factory, BlurredBackgroundProviderImpl.topPanelChatActivity(null))
    }

    /** Плашки поверх обоев сверху (закреп, панель поиска). */
    fun chatTop(view: View, radiusDp: Float): BlurredBackgroundDrawable =
        factory.create(view, BlurredBackgroundProviderImpl.topPanelChatActivity(null))
            .setRadius(AndroidUtilities.dp(radiusDp).toFloat())

    /** Плашки поверх обоев снизу (поле ввода, кнопки, ответ). */
    fun chatBottom(view: View, radiusDp: Float): BlurredBackgroundDrawable =
        factory.create(view, BlurredBackgroundProviderImpl.bottomPanelChatActivity(null))
            .setRadius(AndroidUtilities.dp(radiusDp).toFloat())

    /** Плашки на белом фоне списков (поиск, вкладки) — как у DialogsActivity. */
    fun topPanel(view: View, radiusDp: Float): BlurredBackgroundDrawable =
        factory.create(view, BlurredBackgroundProviderImpl.topPanel(null))
            .setRadius(AndroidUtilities.dp(radiusDp).toFloat())
}
