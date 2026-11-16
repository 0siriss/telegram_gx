/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import org.telegram.ui.ActionBar.BaseFragment;

/**
 * GramBas: точка входа в раздел MAX. Код MAX живёт в отдельном модуле, который этот
 * модуль не видит, поэтому реализацию регистрирует модуль приложения при старте.
 * Зависимость строго односторонняя: раздел знает про тему и компоненты, ядро про
 * раздел — только через этот интерфейс.
 */
public class MaxSection {

    public interface Provider {
        /** Готов ли раздел работать: собрана ли нативная часть под эту архитектуру. */
        boolean isAvailable();

        /** Экран раздела: список чатов, если вход уже выполнен, иначе экран входа. */
        BaseFragment createFragment();

        /** Непрочитанные для счётчика. Ноль, если вход не выполнен. */
        int getUnreadCount();
    }

    private static Provider provider;

    public static void setProvider(Provider value) {
        provider = value;
    }

    public static boolean isAvailable() {
        return provider != null && provider.isAvailable();
    }

    public static BaseFragment createFragment() {
        return provider == null ? null : provider.createFragment();
    }

    public static int getUnreadCount() {
        return provider == null ? 0 : provider.getUnreadCount();
    }
}
