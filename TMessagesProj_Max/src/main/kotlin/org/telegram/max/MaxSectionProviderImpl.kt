package org.telegram.max

import org.telegram.messenger.MaxSection
import org.telegram.ui.ActionBar.BaseFragment

/** Реализация точки входа: подставляется из модуля приложения при старте. */
class MaxSectionProviderImpl : MaxSection.Provider {

    override fun isAvailable(): Boolean = MaxNative.available

    override fun createFragment(): BaseFragment =
        if (MaxClient.isLoggedIn) MaxDialogsFragment() else MaxLoginFragment()

    override fun getUnreadCount(): Int = MaxClient.chats.sumOf { it.unread }
}
