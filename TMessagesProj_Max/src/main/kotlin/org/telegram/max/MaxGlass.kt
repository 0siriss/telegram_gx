package org.telegram.max

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceWrapped
import org.telegram.ui.Components.chat.ViewPositionWatcher
import org.telegram.ui.Components.chat.WallpaperBitmapProvider

/**
 * «Стеклянные» плашки Telegram 13 — те же drawable и цвета, что у ChatActivity и
 * DialogsActivity (`BlurredBackgroundProviderImpl`).
 *
 * В переписке за стеклом лежат размытые обои: источник подключается из `MaxChatFragment`
 * (`attach`), как `navbarContentSourceWallpaper` в ChatActivity. Иначе плашка рисует
 * полупрозрачный цвет поверх прозрачного источника, и сквозь шапку видно ленту.
 * Списки (вкладка MAX) остаются на цветном источнике: у них белый фон.
 */
object MaxGlass {

    private val colorFactory: BlurredBackgroundDrawableViewFactory by lazy {
        BlurredBackgroundDrawableViewFactory(BlurredBackgroundSourceColor())
    }

    private val wallpaperSource = BlurredBackgroundSourceWrapped()
    private val wallpaperProvider = WallpaperBitmapProvider()
    private val chatFactory: BlurredBackgroundDrawableViewFactory by lazy {
        BlurredBackgroundDrawableViewFactory(wallpaperSource)
    }

    private var positionWatcher: ViewPositionWatcher? = null
    private var headerActionBar: java.lang.ref.WeakReference<ActionBar>? = null
    private var lastWallpaper: Drawable? = null
    private var lastWidth = 0
    private var lastHeight = 0

    /** Шапка переписки: круг «назад», плашка заголовка, круг меню — как в ChatActivity. */
    fun setupChatHeader(actionBar: ActionBar) {
        headerActionBar = java.lang.ref.WeakReference(actionBar)
        actionBar.setupGlass(chatFactory, BlurredBackgroundProviderImpl.topPanelChatActivity(null))
    }

    /** Плашки поверх обоев сверху (закреп, панель поиска). */
    fun chatTop(view: View, radiusDp: Float): BlurredBackgroundDrawable =
        chatFactory.create(view, BlurredBackgroundProviderImpl.topPanelChatActivity(null))
            .setRadius(AndroidUtilities.dp(radiusDp).toFloat())

    /** Плашки поверх обоев снизу (поле ввода, кнопки, ответ). */
    fun chatBottom(view: View, radiusDp: Float): BlurredBackgroundDrawable =
        chatFactory.create(view, BlurredBackgroundProviderImpl.bottomPanelChatActivity(null))
            .setRadius(AndroidUtilities.dp(radiusDp).toFloat())

    /** Плашки на белом фоне списков (поиск, вкладки) — как у DialogsActivity. */
    fun topPanel(view: View, radiusDp: Float): BlurredBackgroundDrawable =
        colorFactory.create(view, BlurredBackgroundProviderImpl.topPanel(null))
            .setRadius(AndroidUtilities.dp(radiusDp).toFloat())

    /**
     * Привязывает стекло переписки к её обоям: считает размытую подложку и следит за
     * положением каждой плашки, чтобы за ней оказывался нужный кусок обоев.
     */
    fun attach(root: ViewGroup, wallpaper: Drawable?) {
        positionWatcher?.shutdown()
        val watcher = ViewPositionWatcher(root)
        positionWatcher = watcher
        chatFactory.setSourceRootView(watcher, root)
        updateWallpaper(wallpaper, root.width, root.height)
    }

    fun updateWallpaper(wallpaper: Drawable?, width: Int, height: Int) {
        if (wallpaper == null || width <= 0 || height <= 0) return
        if (wallpaper === lastWallpaper && width == lastWidth && height == lastHeight) return
        lastWallpaper = wallpaper
        lastWidth = width
        lastHeight = height
        val source = wallpaperProvider.updateSourceFromBackgroundViewDrawable(wallpaper)
        if (source is BlurredBackgroundSourceBitmap) {
            source.setParentSize(width, height, 0)
        }
        wallpaperSource.setSource(source)
        headerActionBar?.get()?.invalidate()
    }

    fun detach() {
        positionWatcher?.shutdown()
        positionWatcher = null
        headerActionBar = null
        lastWallpaper = null
        lastWidth = 0
        lastHeight = 0
    }
}
