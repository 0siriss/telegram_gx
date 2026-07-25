package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * Consolidated settings screen for this fork's own features (GramBas), separate from
 * upstream Data/Chat/Privacy settings. Proxy settings are intentionally not included here --
 * they stay at their existing entry point.
 */
public class GramBasSettingsActivity extends BaseFragment {

    private UniversalRecyclerView listView;

    private static final int ID_ANTI_RECALL = 1;
    private static final int ID_KEEP_ALIVE = 2;
    private static final int ID_KEEP_EPHEMERAL = 3;
    private static final int ID_ALLOW_SCREENSHOTS = 4;
    private static final int ID_MUTE_SCREENSHOT_PING = 5;
    private static final int ID_VOICE_PRELOAD = 6;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("GramBas");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);

        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Приватность"));
        items.add(UItem.asCheck(ID_ANTI_RECALL, "Показывать удалённые сообщения").setChecked(MessagesController.isAntiRecallEnabledGlobally()));
        items.add(UItem.asCheck(ID_KEEP_EPHEMERAL, "Сохранять самоуничтожающиеся медиа").setChecked(MessagesController.isKeepEphemeralEnabled()));
        items.add(UItem.asCheck(ID_ALLOW_SCREENSHOTS, "Разрешать скриншоты").setChecked(MessagesController.isAllowScreenshotsEnabled()));
        items.add(UItem.asCheck(ID_MUTE_SCREENSHOT_PING, "Не сообщать о скриншоте (секретные чаты)").setChecked(MessagesController.isMuteScreenshotPingEnabled()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Медиа"));
        items.add(UItem.asCheck(ID_VOICE_PRELOAD, "Загружать входящие голосовые в фоне").setChecked(MessagesController.isVoicePreloadEnabled()));
        items.add(UItem.asShadow("Голосовые сообщения начинают загружаться сразу по получении, не дожидаясь открытия диалога."));

        items.add(UItem.asHeader("Соединение"));
        items.add(UItem.asCheck(ID_KEEP_ALIVE, "Держать соединение в фоне").setChecked(MessagesController.isKeepAliveEnabled()));
        items.add(UItem.asShadow("Настоящие push-уведомления недоступны для этой сборки (FCM не работает с самоподписанным APK). Держит соединение открытым, пока приложение свёрнуто, чтобы сообщения приходили без задержки — расходует больше заряда батареи. Рекомендуется также разрешить автозапуск и отключить оптимизацию батареи для приложения в настройках системы."));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ANTI_RECALL) {
            boolean enabled = !MessagesController.isAntiRecallEnabledGlobally();
            MessagesController.setAntiRecallEnabledGlobally(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
        } else if (item.id == ID_KEEP_EPHEMERAL) {
            boolean enabled = !MessagesController.isKeepEphemeralEnabled();
            MessagesController.setKeepEphemeralEnabled(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
        } else if (item.id == ID_ALLOW_SCREENSHOTS) {
            boolean enabled = !MessagesController.isAllowScreenshotsEnabled();
            MessagesController.setAllowScreenshotsEnabled(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
        } else if (item.id == ID_MUTE_SCREENSHOT_PING) {
            boolean enabled = !MessagesController.isMuteScreenshotPingEnabled();
            MessagesController.setMuteScreenshotPingEnabled(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
        } else if (item.id == ID_VOICE_PRELOAD) {
            boolean enabled = !MessagesController.isVoicePreloadEnabled();
            MessagesController.setVoicePreloadEnabled(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
        } else if (item.id == ID_KEEP_ALIVE) {
            boolean enabled = !MessagesController.isKeepAliveEnabled();
            MessagesController.setKeepAliveEnabled(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            if (enabled) {
                PowerManager powerManager = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
                String packageName = ApplicationLoader.applicationContext.getPackageName();
                if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + packageName));
                        if (getParentActivity() != null) {
                            getParentActivity().startActivity(intent);
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        }
    }
}
