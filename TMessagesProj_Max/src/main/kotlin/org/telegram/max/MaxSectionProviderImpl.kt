package org.telegram.max

import org.telegram.messenger.MaxSection
import org.telegram.ui.ActionBar.BaseFragment

/** Реализация точки входа: подставляется из модуля приложения при старте. */
class MaxSectionProviderImpl : MaxSection.Provider {

    override fun isAvailable(): Boolean = MaxNative.available

    override fun createFragment(): BaseFragment =
        if (MaxClient.isLoggedIn) MaxDialogsFragment() else MaxLoginFragment()

    override fun createTabFragment(): BaseFragment = MaxDialogsFragment(inTab = true)

    override fun createChatFragment(chatId: Long): BaseFragment? {
        if (!MaxClient.isLoggedIn) return null
        // После перезапуска процесса уведомление ещё может висеть: поднимаем кеш и связь.
        MaxClient.restoreFromCache()
        MaxClient.start { }
        return MaxChatFragment(chatId)
    }

    override fun getUnreadCount(): Int = if (MaxClient.isLoggedIn) MaxClient.chats.sumOf { it.unread } else 0
}
