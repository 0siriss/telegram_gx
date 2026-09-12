package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ViewGroup;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.ui.IUpdateLayout;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.UpdateLayout;

import java.io.File;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    protected boolean isBeta() {
        return true;
    }

    private final Handler keepAliveHandler = new Handler(Looper.getMainLooper());
    private final Runnable startKeepAliveService = () -> ConnectionKeepAliveService.start(ApplicationLoader.applicationContext);

    // GramBas: a fast onPause->onResume flicker (e.g. tapping a heads-up notification the
    // instant it appears) used to start the service and stop it again within milliseconds,
    // before onStartCommand's async startForeground() call landed -- Android then kills the
    // process with ForegroundServiceDidNotStartInTimeException. Debouncing the start absorbs
    // the flicker: if onResume cancels it in time, the service never actually starts.
    private static final long KEEP_ALIVE_START_DELAY_MS = 1500;

    @Override
    public boolean onPause() {
        // GramBas: real FCM push doesn't work for this fork's signing cert -- keep the native
        // network layer alive in background instead (see ConnectionKeepAliveService).
        if (MessagesController.isKeepAliveEnabled()) {
            keepAliveHandler.postDelayed(startKeepAliveService, KEEP_ALIVE_START_DELAY_MS);
            return true;
        }
        return false;
    }

    @Override
    public void onResume() {
        keepAliveHandler.removeCallbacks(startKeepAliveService);
        ConnectionKeepAliveService.stop(ApplicationLoader.applicationContext);
    }

    @Override
    public boolean isCustomUpdate() {
        return !TextUtils.isEmpty(BuildConfig.FORK_VERSION_TAG);
    }

    @Override
    public BetaUpdate getUpdate() {
        if (!isCustomUpdate()) return null;
        return GithubUpdaterController.getInstance().getUpdate();
    }

    @Override
    public void checkUpdate(boolean force, Runnable whenDone) {
        if (!isCustomUpdate()) return;
        GithubUpdaterController.getInstance().checkForUpdate(force, whenDone);
    }

    @Override
    public void downloadUpdate() {
        if (!isCustomUpdate()) return;
        GithubUpdaterController.getInstance().downloadUpdate();
    }

    @Override
    public void cancelDownloadingUpdate() {
        if (!isCustomUpdate()) return;
        GithubUpdaterController.getInstance().cancelDownloadingUpdate();
    }

    @Override
    public boolean isDownloadingUpdate() {
        if (!isCustomUpdate()) return false;
        return GithubUpdaterController.getInstance().isDownloading();
    }

    @Override
    public float getDownloadingUpdateProgress() {
        if (!isCustomUpdate()) return 0;
        return GithubUpdaterController.getInstance().getDownloadingProgress();
    }

    @Override
    public File getDownloadedUpdateFile() {
        if (!isCustomUpdate()) return null;
        return GithubUpdaterController.getInstance().getDownloadedFile();
    }

    @Override
    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        try {
            new UpdateAppAlertDialog(context, update, account).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        if (!isCustomUpdate()) return null;
        return new UpdateLayout(activity, sideMenuContainer);
    }
}
