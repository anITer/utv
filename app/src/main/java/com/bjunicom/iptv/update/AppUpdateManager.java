package com.bjunicom.iptv.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用版本检测与后台自动下载安装模块
 *
 * 检查地址：
 * - GitHub Releases 页面: https://github.com/anITer/utv/releases
 * - GitHub Releases API: https://api.github.com/repos/anITer/utv/releases
 *
 * 交互流程：
 * 1. 启动或手动触发时在后台检查是否有高于当前版本的新版本 Release 及 APK 资产；
 * 2. 检测到新版本后，Toast 提示用户「检测到新版本正在下载中...」并在后台异步下载 APK；
 * 3. 下载完成后弹窗提示用户是否安装，用户点击同意后调起系统安装器完成安装。
 */
public class AppUpdateManager {
    private static final String TAG = "AppUpdateManager";

    public static final String RELEASES_WEB_URL = "https://github.com/anITer/utv/releases";
    public static final String RELEASES_API_URL = "https://api.github.com/repos/anITer/utv/releases";
    public static final String CURRENT_VERSION_NAME = "1.1.0";
    public static final int CURRENT_VERSION_CODE = 110;
    public static final int REQUEST_CODE_INSTALL_PERMISSION = 9201;

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 30000;

    private static volatile AppUpdateManager instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "uTV-UpdateChecker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean checkingOrDownloading = new AtomicBoolean(false);
    private boolean autoCheckedInSession = false;
    private WeakReference<Activity> currentActivityRef = new WeakReference<>(null);
    private File pendingInstallApkFile;

    public static class ReleaseInfo {
        public final String tagName;
        public final String versionName;
        public final String releaseTitle;
        public final String releaseNotes;
        public final String apkDownloadUrl;
        public final String apkFileName;
        public final long apkSizeBytes;

        public ReleaseInfo(String tagName,
                           String versionName,
                           String releaseTitle,
                           String releaseNotes,
                           String apkDownloadUrl,
                           String apkFileName,
                           long apkSizeBytes) {
            this.tagName = tagName != null ? tagName : "";
            this.versionName = versionName != null ? versionName : "";
            this.releaseTitle = releaseTitle != null ? releaseTitle : "";
            this.releaseNotes = releaseNotes != null ? releaseNotes : "";
            this.apkDownloadUrl = apkDownloadUrl != null ? apkDownloadUrl : "";
            this.apkFileName = apkFileName != null ? apkFileName : "utv-update.apk";
            this.apkSizeBytes = apkSizeBytes;
        }
    }

    public static AppUpdateManager getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (AppUpdateManager.class) {
                if (instance == null) {
                    instance = new AppUpdateManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private AppUpdateManager(Context appContext) {
        this.appContext = appContext;
    }

    public void attachActivity(@Nullable Activity activity) {
        this.currentActivityRef = new WeakReference<>(activity);
    }

    /**
     * 应用启动时自动静默检查一次更新（仅当发现新版本时才提示并后台下载）
     */
    public void checkForUpdatesOnStartup(@NonNull Activity activity) {
        attachActivity(activity);
        if (autoCheckedInSession) {
            return;
        }
        autoCheckedInSession = true;
        checkForUpdates(activity, false);
    }

    /**
     * 检查新版本
     *
     * @param activity   当前前台 Activity，用于展示安装确认弹窗
     * @param userManual 是否为用户主动点击触发（主动触发时若已是最新版或失败会给出 Toast 反馈）
     */
    public void checkForUpdates(@NonNull Activity activity, boolean userManual) {
        attachActivity(activity);
        if (!checkingOrDownloading.compareAndSet(false, true)) {
            if (userManual) {
                Toast.makeText(appContext, "正在检查或下载新版本中，请稍候...", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (userManual) {
            Toast.makeText(appContext, "正在检查新版本...", Toast.LENGTH_SHORT).show();
        }

        final String currentVersion = getCurrentVersionName(appContext);
        executor.execute(() -> {
            try {
                ReleaseInfo latest = fetchLatestRelease();
                if (latest == null || TextUtils.isEmpty(latest.versionName)) {
                    checkingOrDownloading.set(false);
                    if (userManual) {
                        postToast("未获取到版本发布信息，当前版本: v" + currentVersion, Toast.LENGTH_SHORT);
                    }
                    return;
                }

                if (!isNewerVersion(latest.versionName, currentVersion)) {
                    checkingOrDownloading.set(false);
                    if (userManual) {
                        postToast("当前已是最新版本 (v" + currentVersion + ")", Toast.LENGTH_SHORT);
                    }
                    return;
                }

                if (TextUtils.isEmpty(latest.apkDownloadUrl)) {
                    checkingOrDownloading.set(false);
                    if (userManual) {
                        postToast("检测到新版本 v" + latest.versionName + "，但未找到可下载的 APK 安装包", Toast.LENGTH_LONG);
                    }
                    return;
                }

                // 检测到新版本，Toast 提示用户正在后台下载中
                postToast("检测到新版本 v" + latest.versionName + "，正在后台下载中...", Toast.LENGTH_LONG);

                File downloadedApk = downloadApkInBackground(latest);
                checkingOrDownloading.set(false);

                if (downloadedApk != null && downloadedApk.exists() && downloadedApk.length() > 0) {
                    mainHandler.post(() -> promptUserToInstall(latest, downloadedApk));
                } else {
                    postToast("新版本 v" + latest.versionName + " 下载失败，请稍后重试", Toast.LENGTH_LONG);
                }
            } catch (Throwable t) {
                checkingOrDownloading.set(false);
                Log.w(TAG, "Check or download update failed: " + t.getMessage());
                if (userManual) {
                    postToast("检查更新失败: " + t.getMessage(), Toast.LENGTH_SHORT);
                }
            }
        });
    }

    /**
     * 优先通过 GitHub Releases API 获取最新 Release 信息，若 API 受限则回退解析 https://github.com/anITer/utv/releases 页面
     */
    @Nullable
    public ReleaseInfo fetchLatestRelease() {
        try {
            String json = httpGetText(RELEASES_API_URL, "application/vnd.github+json");
            if (!TextUtils.isEmpty(json)) {
                ReleaseInfo info = parseReleasesApiJson(json);
                if (info != null) {
                    return info;
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "GitHub API fetch fallback to HTML page: " + t.getMessage());
        }

        try {
            String html = httpGetText(RELEASES_WEB_URL, "text/html,application/xhtml+xml");
            if (!TextUtils.isEmpty(html)) {
                return parseReleasesHtml(html);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Releases web page fetch failed: " + t.getMessage());
        }
        return null;
    }

    @Nullable
    public static ReleaseInfo parseReleasesApiJson(@Nullable String jsonText) {
        if (TextUtils.isEmpty(jsonText)) {
            return null;
        }
        try {
            String trimmed = jsonText.trim();
            JSONArray releases;
            if (trimmed.startsWith("[")) {
                releases = new JSONArray(trimmed);
            } else if (trimmed.startsWith("{")) {
                releases = new JSONArray();
                releases.put(new JSONObject(trimmed));
            } else {
                return null;
            }

            ReleaseInfo bestCandidate = null;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject rel = releases.optJSONObject(i);
                if (rel == null || rel.optBoolean("draft", false)) {
                    continue;
                }
                String tagName = rel.optString("tag_name", "").trim();
                String name = rel.optString("name", "").trim();
                String body = rel.optString("body", "").trim();
                String version = normalizeVersionString(!TextUtils.isEmpty(tagName) ? tagName : name);
                if (TextUtils.isEmpty(version)) {
                    continue;
                }

                String apkUrl = "";
                String apkName = "utv-" + version + ".apk";
                long apkSize = 0L;

                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject asset = assets.optJSONObject(j);
                        if (asset == null) {
                            continue;
                        }
                        String assetName = asset.optString("name", "");
                        String downloadUrl = asset.optString("browser_download_url", "");
                        if (assetName.toLowerCase(Locale.US).endsWith(".apk")
                                || downloadUrl.toLowerCase(Locale.US).endsWith(".apk")) {
                            apkUrl = downloadUrl;
                            if (!TextUtils.isEmpty(assetName)) {
                                apkName = assetName;
                            }
                            apkSize = asset.optLong("size", 0L);
                            break;
                        }
                    }
                }

                ReleaseInfo candidate = new ReleaseInfo(
                        tagName,
                        version,
                        !TextUtils.isEmpty(name) ? name : tagName,
                        body,
                        apkUrl,
                        apkName,
                        apkSize
                );
                if (bestCandidate == null || isNewerVersion(candidate.versionName, bestCandidate.versionName)) {
                    bestCandidate = candidate;
                }
            }
            return bestCandidate;
        } catch (Throwable t) {
            Log.w(TAG, "parseReleasesApiJson error: " + t.getMessage());
            return null;
        }
    }

    @Nullable
    public static ReleaseInfo parseReleasesHtml(@Nullable String html) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        // 1. 匹配直接的 APK 下载链接: /anITer/utv/releases/download/<tag>/<file>.apk
        Pattern apkDownloadPattern = Pattern.compile(
                "(?:https://github\\.com)?(/anITer/utv/releases/download/([^/\"'\\s<>]+)/([^/\"'\\s<>]+\\.apk))",
                Pattern.CASE_INSENSITIVE);
        Matcher apkMatcher = apkDownloadPattern.matcher(html);
        if (apkMatcher.find()) {
            String path = apkMatcher.group(1);
            String tag = Uri.decode(apkMatcher.group(2));
            String fileName = Uri.decode(apkMatcher.group(3));
            String version = normalizeVersionString(tag);
            if (TextUtils.isEmpty(version)) {
                version = normalizeVersionString(fileName);
            }
            String fullUrl = path.startsWith("http") ? path : ("https://github.com" + path);
            return new ReleaseInfo(tag, version, tag, "", fullUrl, fileName, 0L);
        }

        // 2. 匹配 Release tag 链接: /anITer/utv/releases/tag/<tag>
        Pattern tagPattern = Pattern.compile(
                "/anITer/utv/releases/tag/([^/\"'\\s<>?#]+)",
                Pattern.CASE_INSENSITIVE);
        Matcher tagMatcher = tagPattern.matcher(html);
        if (tagMatcher.find()) {
            String tag = Uri.decode(tagMatcher.group(1));
            String version = normalizeVersionString(tag);
            if (!TextUtils.isEmpty(version)) {
                return new ReleaseInfo(tag, version, tag, "", "", "utv-" + version + ".apk", 0L);
            }
        }
        return null;
    }

    /**
     * 提取规范化版本号（如 "v1.2.3-beta" -> "1.2.3"）
     */
    @NonNull
    public static String normalizeVersionString(@Nullable String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)+)").matcher(raw.trim());
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher singleNum = Pattern.compile("v?(\\d+)").matcher(raw.trim());
        if (singleNum.find()) {
            return singleNum.group(1);
        }
        return "";
    }

    /**
     * 比较远程版本是否高于本地当前版本
     */
    public static boolean isNewerVersion(@Nullable String remoteVersion, @Nullable String currentVersion) {
        String cleanRemote = normalizeVersionString(remoteVersion);
        String cleanCurrent = normalizeVersionString(currentVersion);
        if (TextUtils.isEmpty(cleanRemote)) {
            return false;
        }
        if (TextUtils.isEmpty(cleanCurrent)) {
            return true;
        }

        String[] remoteParts = cleanRemote.split("\\.");
        String[] currentParts = cleanCurrent.split("\\.");
        int maxLen = Math.max(remoteParts.length, currentParts.length);
        for (int i = 0; i < maxLen; i++) {
            int r = i < remoteParts.length ? parsePartSafe(remoteParts[i]) : 0;
            int c = i < currentParts.length ? parsePartSafe(currentParts[i]) : 0;
            if (r > c) {
                return true;
            } else if (r < c) {
                return false;
            }
        }
        return false;
    }

    private static int parsePartSafe(String part) {
        try {
            return Integer.parseInt(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @NonNull
    public static String getCurrentVersionName(@NonNull Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            if (pi != null && !TextUtils.isEmpty(pi.versionName)) {
                return pi.versionName;
            }
        } catch (Throwable ignored) {
        }
        return CURRENT_VERSION_NAME;
    }

    @Nullable
    private String httpGetText(String urlStr, String acceptHeader) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "uTV-Android-Updater/1.0");
            if (!TextUtils.isEmpty(acceptHeader)) {
                conn.setRequestProperty("Accept", acceptHeader);
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (InputStream is = conn.getInputStream();
                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    return new String(bos.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Nullable
    private File downloadApkInBackground(@NonNull ReleaseInfo releaseInfo) {
        File updateDir = new File(appContext.getCacheDir(), "updates");
        if (!updateDir.exists() && !updateDir.mkdirs()) {
            updateDir = new File(appContext.getFilesDir(), "updates");
            //noinspection ResultOfMethodCallIgnored
            updateDir.mkdirs();
        }

        String safeFileName = releaseInfo.apkFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!safeFileName.toLowerCase(Locale.US).endsWith(".apk")) {
            safeFileName = safeFileName + ".apk";
        }
        File targetFile = new File(updateDir, safeFileName);
        File tempFile = new File(updateDir, safeFileName + ".part");

        if (targetFile.exists() && releaseInfo.apkSizeBytes > 0 && targetFile.length() == releaseInfo.apkSizeBytes) {
            return targetFile;
        }

        HttpURLConnection conn = null;
        try {
            String currentUrl = releaseInfo.apkDownloadUrl;
            int redirectCount = 0;
            while (redirectCount < 6) {
                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "uTV-Android-Updater/1.0");
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307
                        || code == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (TextUtils.isEmpty(location)) {
                        return null;
                    }
                    currentUrl = location;
                    redirectCount++;
                    continue;
                }
                if (code < 200 || code >= 300) {
                    return null;
                }
                break;
            }

            if (conn == null) {
                return null;
            }

            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tempFile, false)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.flush();
            }

            if (tempFile.length() <= 0) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
                return null;
            }
            if (targetFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.delete();
            }
            if (!tempFile.renameTo(targetFile)) {
                return tempFile;
            }
            return targetFile;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to download APK: " + t.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 下载完成后弹窗询问用户是否立即安装，经用户同意后启动安装
     */
    private void promptUserToInstall(@NonNull ReleaseInfo releaseInfo, @NonNull File apkFile) {
        Activity activity = currentActivityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Toast.makeText(appContext,
                    "新版本 v" + releaseInfo.versionName + " 下载完成，请重新打开应用完成安装",
                    Toast.LENGTH_LONG).show();
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("新版本 v").append(releaseInfo.versionName).append(" 已在后台下载完成。");
        if (!TextUtils.isEmpty(releaseInfo.releaseNotes)) {
            msg.append("\n\n更新内容：\n").append(releaseInfo.releaseNotes);
        }
        msg.append("\n\n是否立即安装更新？");

        new MaterialAlertDialogBuilder(activity)
                .setTitle("新版本下载完成")
                .setMessage(msg.toString())
                .setCancelable(true)
                .setPositiveButton("立即安装", (dialog, which) -> installApk(activity, apkFile))
                .setNegativeButton("稍后", null)
                .show();
    }

    /**
     * 用户同意后启动系统 APK 安装流程（兼容 Android 7.0 FileProvider 与 Android 8.0+ 未知来源权限）
     */
    public void installApk(@NonNull Activity activity, @NonNull File apkFile) {
        if (!apkFile.exists()) {
            Toast.makeText(activity, "安装包文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean canInstall = activity.getPackageManager().canRequestPackageInstalls();
            if (!canInstall) {
                pendingInstallApkFile = apkFile;
                Toast.makeText(activity, "请授予「安装未知应用」权限以完成更新", Toast.LENGTH_LONG).show();
                try {
                    Intent permIntent = new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivityForResult(permIntent, REQUEST_CODE_INSTALL_PERMISSION);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }

        launchSystemPackageInstaller(activity, apkFile);
    }

    public void handleActivityResult(@NonNull Activity activity, int requestCode) {
        if (requestCode == REQUEST_CODE_INSTALL_PERMISSION && pendingInstallApkFile != null) {
            File fileToInstall = pendingInstallApkFile;
            pendingInstallApkFile = null;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || activity.getPackageManager().canRequestPackageInstalls()) {
                launchSystemPackageInstaller(activity, fileToInstall);
            } else {
                Toast.makeText(activity, "未授予安装权限，无法安装更新", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void launchSystemPackageInstaller(@NonNull Context context, @NonNull File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch APK installer: " + t.getMessage());
            Toast.makeText(context, "无法启动安装程序: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void postToast(String message, int duration) {
        mainHandler.post(() -> Toast.makeText(appContext, message, duration).show());
    }
}
