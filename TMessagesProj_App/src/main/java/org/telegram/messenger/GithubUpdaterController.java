package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.regular.BuildConfig;
import org.telegram.ui.web.HttpGetFileTask;
import org.telegram.ui.web.HttpGetTask;

import java.io.File;

public class GithubUpdaterController {

    private static final String RELEASES_URL = "https://api.github.com/repos/0siriss/telegram_gx/releases/latest";
    private static final String ASSET_NAME = "app.apk";

    private static GithubUpdaterController instance;
    public static GithubUpdaterController getInstance() {
        if (instance == null) {
            instance = new GithubUpdaterController();
        }
        return instance;
    }

    private String version;
    private int versionCode;
    private String changelog;
    private String path;
    private long lastCheck;

    private String fileUrl;

    public GithubUpdaterController() {
        load();
    }

    private SharedPreferences getSharedPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("github_update", Activity.MODE_PRIVATE);
    }

    private void load() {
        final SharedPreferences prefs = getSharedPreferences();

        version = prefs.getString("version", null);
        versionCode = prefs.getInt("versionCode", 0);
        changelog = prefs.getString("changelog", null);
        path = prefs.getString("path", null);
        lastCheck = prefs.getLong("lastCheck", 0L);

        if (getCurrentVersionCode() >= versionCode || !TextUtils.isEmpty(path) && !new File(path).exists()) {
            version = null;
            versionCode = 0;
            path = null;
            changelog = null;
            lastCheck = 0;
            save();
        }
    }

    private void save() {
        final SharedPreferences.Editor e = getSharedPreferences().edit();
        if (TextUtils.isEmpty(version)) {
            e.remove("version");
        } else {
            e.putString("version", version);
        }
        if (TextUtils.isEmpty(changelog)) {
            e.remove("changelog");
        } else {
            e.putString("changelog", changelog);
        }
        if (versionCode == 0) {
            e.remove("versionCode");
        } else {
            e.putInt("versionCode", versionCode);
        }
        if (TextUtils.isEmpty(path)) {
            e.remove("path");
        } else {
            e.putString("path", path);
        }
        if (lastCheck == 0) {
            e.remove("lastCheck");
        } else {
            e.putLong("lastCheck", lastCheck);
        }
        e.apply();
    }

    private final static long CHECK_INTERVAL_PAUSED = 1000 * 60 * 60 * 24; // 1 day
    private final static long CHECK_INTERVAL = 1000 * 60 * 20; // 20 minutes

    private boolean firstCheck = true;
    private boolean checkingForUpdate;
    private final Runnable scheduledUpdateCheck = () -> checkForUpdate(false, null);
    public void checkForUpdate(boolean force, Runnable whenDone) {
        if (checkingForUpdate) return;

        if (firstCheck) {
            force = true;
        }
        if (!force && System.currentTimeMillis() - lastCheck < (ApplicationLoader.mainInterfacePaused ? CHECK_INTERVAL_PAUSED : CHECK_INTERVAL)) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }

        checkingForUpdate = true;
        firstCheck = false;
        new HttpGetTask(str -> AndroidUtilities.runOnUIThread(() -> {
            checkingForUpdate = false;
            try {
                final JSONObject json = new JSONObject(str);
                final String newVersion = json.getString("tag_name");
                final int newVersionCode = parseVersionCode(newVersion);
                final String newChangelog = json.optString("body", null);

                String newFileUrl = null;
                final JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        final JSONObject asset = assets.getJSONObject(i);
                        if (ASSET_NAME.equals(asset.optString("name"))) {
                            newFileUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }

                final int oldVersionCode = this.versionCode;

                if (newVersionCode > getCurrentVersionCode() && newFileUrl != null) {
                    if (newVersionCode != this.versionCode && !TextUtils.isEmpty(path)) {
                        deleteDownloadedFile();
                    }
                    version = newVersion;
                    versionCode = newVersionCode;
                    changelog = newChangelog;
                    fileUrl = newFileUrl;
                } else {
                    if (!TextUtils.isEmpty(path)) {
                        deleteDownloadedFile();
                    }
                    version = null;
                    versionCode = 0;
                    fileUrl = null;
                    changelog = null;
                }

                this.lastCheck = System.currentTimeMillis();
                save();

                if (this.versionCode != oldVersionCode) {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                }

                AndroidUtilities.cancelRunOnUIThread(this.scheduledUpdateCheck);
                AndroidUtilities.runOnUIThread(this.scheduledUpdateCheck, CHECK_INTERVAL);
                if (whenDone != null) {
                    whenDone.run();
                }
            } catch (Exception e) {
                FileLog.e("Failed to check for GitHub release update, received: " + str, e);
            }
        })).setHeader("User-Agent", "TGX-DPI-Bypass-UpdateChecker")
          .setHeader("Accept", "application/vnd.github+json")
          .execute(RELEASES_URL);
    }

    private static int parseVersionCode(String tag) {
        try {
            String cleaned = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            String[] parts = cleaned.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major * 1000 + minor;
        } catch (Exception e) {
            return 0;
        }
    }

    private void deleteDownloadedFile() {
        final File file = new File(path);
        try {
            file.delete();
        } catch (Exception e) {
            FileLog.e(e);
        }
        path = null;
    }

    public BetaUpdate getUpdate() {
        if (version == null || versionCode == 0) {
            return null;
        }
        return new BetaUpdate(version, versionCode, changelog);
    }

    private boolean downloading;
    private float downloadingProgress;
    private HttpGetFileTask downloadingTask;
    public void downloadUpdate() {
        downloadUpdate(false);
    }
    private void downloadUpdate(boolean triedGettingFileUrl) {
        if (downloading || !TextUtils.isEmpty(path)) return;

        downloading = true;
        downloadingProgress = 0.0f;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);

        if (TextUtils.isEmpty(fileUrl)) {
            if (!triedGettingFileUrl) {
                checkForUpdate(true, () -> downloadUpdate(true));
            } else {
                downloading = false;
            }
            return;
        }

        downloadingTask = new HttpGetFileTask(
            downloadedFile -> AndroidUtilities.runOnUIThread(() -> {
                if (downloadedFile != null) {
                    if (!TextUtils.isEmpty(path)) {
                        deleteDownloadedFile();
                    }
                    path = downloadedFile.getAbsolutePath();
                    save();
                    downloadingProgress = 1.0f;
                    downloading = false;
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                } else {
                    downloading = false;
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                }
            }),
            progress -> {
                downloadingProgress = progress;
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
            }
        ).setOverrideExtension("apk");
        downloadingTask.execute(fileUrl);
    }
    public void cancelDownloadingUpdate() {
        if (!downloading) return;
        if (downloadingTask != null) {
            downloadingTask.cancel(false);
        }
        downloading = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    public boolean isDownloading() {
        return downloading;
    }

    public float getDownloadingProgress() {
        return downloadingProgress;
    }

    public File getDownloadedFile() {
        if (path == null) {
            return null;
        }
        final File file = new File(path);
        if (!file.exists()) {
            path = null;
            save();
            return null;
        }
        return file;
    }

    private int getCurrentVersionCode() {
        return parseVersionCode(BuildConfig.FORK_VERSION_TAG);
    }
}
