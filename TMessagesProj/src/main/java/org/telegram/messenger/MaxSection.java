/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import android.content.Intent;

import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

/**
 * GramBas: точка входа в раздел MAX. Код MAX живёт в отдельном модуле, который этот
 * модуль не видит, поэтому реализацию регистрирует модуль приложения при старте.
 * Зависимость строго односторонняя: раздел знает про тему и компоненты, ядро про
 * раздел — только через этот интерфейс.
 */
public class MaxSection {

    /** Extra в интенте уведомления: id чата MAX, который открыть. */
    public static final String EXTRA_CHAT_ID = "grambas_max_chat_id";

    private static final String PREF_TAB = "grambas_max_tab";

    public interface Provider {
        /** Готов ли раздел работать: собрана ли нативная часть под эту архитектуру. */
        boolean isAvailable();

        /** Экран раздела: список чатов, если вход уже выполнен, иначе экран входа. */
        BaseFragment createFragment();

        /** Экран для вкладки главного экрана: без кнопки «назад», с отступом под панель вкладок. */
        BaseFragment createTabFragment();

        /** Переписка с чатом MAX, например из уведомления. Null, если вход не выполнен. */
        BaseFragment createChatFragment(long chatId);

        /** Непрочитанные для счётчика. Ноль, если вход не выполнен. */
        int getUnreadCount();
    }

    private static Provider provider;
    private static final ArrayList<Runnable> unreadListeners = new ArrayList<>();

    public static void setProvider(Provider value) {
        provider = value;
    }

    public static boolean isAvailable() {
        return provider != null && provider.isAvailable();
    }

    /** Показывать ли MAX вкладкой на главном экране. По умолчанию да. */
    public static boolean isTabEnabled() {
        return isAvailable() && MessagesController.getGlobalMainSettings().getBoolean(PREF_TAB, true);
    }

    public static void setTabEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_TAB, enabled).apply();
    }

    public static BaseFragment createFragment() {
        return provider == null ? null : provider.createFragment();
    }

    public static BaseFragment createTabFragment() {
        return provider == null ? null : provider.createTabFragment();
    }

    public static BaseFragment createChatFragment(long chatId) {
        return provider == null ? null : provider.createChatFragment(chatId);
    }

    public static int getUnreadCount() {
        return provider == null ? 0 : provider.getUnreadCount();
    }

    /** Чат MAX из интента уведомления, или 0. */
    public static long chatIdFromIntent(Intent intent) {
        return intent == null ? 0 : intent.getLongExtra(EXTRA_CHAT_ID, 0);
    }

    public static void addUnreadListener(Runnable listener) {
        if (!unreadListeners.contains(listener)) {
            unreadListeners.add(listener);
        }
    }

    public static void removeUnreadListener(Runnable listener) {
        unreadListeners.remove(listener);
    }

    /** Раздел MAX сообщает, что число непрочитанных могло измениться. Вызывать можно из любого потока. */
    public static void notifyUnreadChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (Runnable listener : new ArrayList<>(unreadListeners)) {
                listener.run();
            }
        });
    }
}
