package com.bjunicom.iptv.report;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.update.AppUpdateManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端设备画像与环境数据上报管理模块
 *
 * 核心能力：
 * 1. 采集设备硬件画像 (device) 与运行环境信息 (env)，构建标准 JSON 载荷上报至服务端接口；
 * 2. 支持幂等重试机制：生成唯一 report_id (如 req_8f9d2a1b4c)，遇到网络波动重推时保持同一 report_id；
 * 3. 兼容处理服务端首次创建响应 (HTTP 201 Created, is_duplicate=false) 与幂等重复响应 (HTTP 200 OK, is_duplicate=true)。
 */
public class DataReportManager {
    private static final String TAG = "DataReportManager";

    public static final String REPORT_API_URL = "http://104.223.42.150:8080/api/v1/report";
    public static final String CHANNEL_REPORT_API_URL = "http://104.223.42.150:8080/api/v1/channel";
    public static final String REPORT_TYPE_DEVICE_PROFILE_INIT = "device_profile_init";
    public static final String REPORT_TYPE_CHANNEL_WATCH = "channel_watch";
    public static final String PLATFORM_ANDROID = "Android";
    public static final String DEFAULT_CHANNEL = IptvSettings.DEFAULT_APP_CHANNEL;
    public static final long CHANNEL_REPORT_INTERVAL_MS = 10 * 60 * 1000L;

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1200L;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static volatile DataReportManager instance;

    private final Context appContext;
    private final IptvSettings settings;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "uTV-DataReporter");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean reportingInProgress = new AtomicBoolean(false);
    private final AtomicBoolean channelReportingInProgress = new AtomicBoolean(false);
    private final AtomicBoolean startupReportTriggeredInSession = new AtomicBoolean(false);
    private final AtomicBoolean periodicChannelReportScheduled = new AtomicBoolean(false);
    private volatile boolean startupReportSucceeded = false;
    private volatile String lastSucceededDateKey = "";
    private volatile String reportEndpointUrl = REPORT_API_URL;
    private volatile String channelReportEndpointUrl = CHANNEL_REPORT_API_URL;
    private volatile long channelReportIntervalMs = CHANNEL_REPORT_INTERVAL_MS;
    private volatile ReportResponse lastReportResponse;
    private volatile ReportResponse lastChannelReportResponse;

    private final Map<String, SessionChannelEntry> currentSessionChannels = new LinkedHashMap<>();
    @Nullable
    private String activeChannelKey;
    private long activeChannelStartMs = 0L;

    private final Runnable periodicChannelReportRunnable = new Runnable() {
        @Override
        public void run() {
            if (!periodicChannelReportScheduled.get()) {
                return;
            }
            flushSessionChannelsToPendingQueue(System.currentTimeMillis(), true);
            reportWatchedChannelsAsync(null);
            if (periodicChannelReportScheduled.get()) {
                mainHandler.postDelayed(this, Math.max(1000L, channelReportIntervalMs));
            }
        }
    };

    public interface ReportCallback {
        void onSuccess(@NonNull ReportResponse response);

        void onFailure(@NonNull String reportId, int httpStatusCode, @NonNull String errorMessage);
    }

    /**
     * 设备硬件与播放器画像信息 (device 节点)
     */
    public static class DeviceProfile {
        public final String deviceId;
        public final String platform;
        public final String brand;
        public final String model;
        public final String osVersion;
        public final int screenWidth;
        public final int screenHeight;
        public final double dpr;
        public final long ramMb;
        public final long diskFreeMb;
        public final boolean isEmulator;
        public final String playerType;
        public final boolean isHwDecoder;

        public DeviceProfile(@NonNull String deviceId,
                             @NonNull String platform,
                             @NonNull String brand,
                             @NonNull String model,
                             @NonNull String osVersion,
                             int screenWidth,
                             int screenHeight,
                             double dpr,
                             long ramMb,
                             long diskFreeMb,
                             boolean isEmulator,
                             @NonNull String playerType,
                             boolean isHwDecoder) {
            this.deviceId = deviceId;
            this.platform = platform;
            this.brand = brand;
            this.model = model;
            this.osVersion = osVersion;
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.dpr = dpr;
            this.ramMb = ramMb;
            this.diskFreeMb = diskFreeMb;
            this.isEmulator = isEmulator;
            this.playerType = playerType;
            this.isHwDecoder = isHwDecoder;
        }

        @NonNull
        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("device_id", deviceId);
            obj.put("platform", platform);
            obj.put("brand", brand);
            obj.put("model", model);
            obj.put("os_version", osVersion);
            obj.put("screen_width", screenWidth);
            obj.put("screen_height", screenHeight);
            obj.put("dpr", dpr);
            obj.put("ram_mb", ramMb);
            obj.put("disk_free_mb", diskFreeMb);
            obj.put("is_emulator", isEmulator);
            obj.put("player_type", playerType);
            obj.put("is_hw_decoder", isHwDecoder);
            return obj;
        }
    }

    /**
     * 应用版本与网络环境信息 (env 节点)
     */
    public static class EnvProfile {
        public final String appVersion;
        public final String buildNumber;
        public final String channel;
        public final String networkType;
        public final String carrier;
        public final String language;
        public final String timezone;
        public final String userAgent;

        public EnvProfile(@NonNull String appVersion,
                          @NonNull String buildNumber,
                          @NonNull String channel,
                          @NonNull String networkType,
                          @NonNull String carrier,
                          @NonNull String language,
                          @NonNull String timezone,
                          @NonNull String userAgent) {
            this.appVersion = appVersion;
            this.buildNumber = buildNumber;
            this.channel = channel;
            this.networkType = networkType;
            this.carrier = carrier;
            this.language = language;
            this.timezone = timezone;
            this.userAgent = userAgent;
        }

        @NonNull
        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("app_version", appVersion);
            obj.put("build_number", buildNumber);
            obj.put("channel", channel);
            obj.put("network_type", networkType);
            obj.put("carrier", carrier);
            obj.put("language", language);
            obj.put("timezone", timezone);
            obj.put("user_agent", userAgent);
            return obj;
        }
    }

    /**
     * 单个频道的观看时长统计记录 (channels 数组元素)
     */
    public static class ChannelWatchRecord {
        public final String channelName;
        public final int channelNumber;
        public final String channelUrl;
        public final String groupTitle;
        public final String ispName;
        public final String resolution;
        public final long durationMs;
        public final long durationSec;
        public final int watchCount;

        public ChannelWatchRecord(@NonNull String channelName,
                                  int channelNumber,
                                  @Nullable String channelUrl,
                                  long durationMs,
                                  int watchCount) {
            this(channelName, channelNumber, channelUrl, "", "", "",
                    durationMs, computeDurationSec(durationMs), watchCount);
        }

        public ChannelWatchRecord(@NonNull String channelName,
                                  int channelNumber,
                                  @Nullable String channelUrl,
                                  @Nullable String groupTitle,
                                  @Nullable String ispName,
                                  @Nullable String resolution,
                                  long durationMs,
                                  long durationSec,
                                  int watchCount) {
            this.channelName = channelName != null ? channelName.trim() : "";
            this.channelNumber = Math.max(0, channelNumber);
            this.channelUrl = channelUrl != null ? channelUrl.trim() : "";
            this.groupTitle = groupTitle != null ? groupTitle.trim() : "";
            this.ispName = ispName != null ? ispName.trim() : "";
            this.resolution = resolution != null ? resolution.trim() : "";
            this.durationMs = Math.max(0L, durationMs);
            this.durationSec = durationSec > 0 ? durationSec : computeDurationSec(this.durationMs);
            this.watchCount = Math.max(1, watchCount);
        }

        public static long computeDurationSec(long durationMs) {
            if (durationMs <= 0L) {
                return 0L;
            }
            return Math.max(1L, Math.round(durationMs / 1000.0));
        }

        @NonNull
        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("channel_name", channelName);
            obj.put("channel_number", channelNumber);
            if (!TextUtils.isEmpty(channelUrl)) {
                obj.put("channel_url", channelUrl);
            }
            if (!TextUtils.isEmpty(groupTitle)) {
                obj.put("group_title", groupTitle);
            }
            if (!TextUtils.isEmpty(ispName)) {
                obj.put("isp_name", ispName);
            }
            if (!TextUtils.isEmpty(resolution)) {
                obj.put("resolution", resolution);
            }
            obj.put("duration_sec", durationSec);
            obj.put("duration_ms", durationMs);
            obj.put("watch_count", watchCount);
            return obj;
        }

        @Nullable
        public static ChannelWatchRecord fromJson(@Nullable JSONObject obj) {
            if (obj == null) {
                return null;
            }
            String name = obj.optString("channel_name", "").trim();
            String url = obj.optString("channel_url", "").trim();
            if (TextUtils.isEmpty(name)) {
                name = url;
            }
            if (TextUtils.isEmpty(name)) {
                return null;
            }
            int num = obj.optInt("channel_number", 0);
            String group = obj.optString("group_title", "");
            String isp = obj.optString("isp_name", "");
            String res = obj.optString("resolution", "");
            long durMs = obj.optLong("duration_ms", 0L);
            long durSec = obj.optLong("duration_sec", 0L);
            if (durMs <= 0L && durSec > 0L) {
                durMs = durSec * 1000L;
            }
            if (durSec <= 0L && durMs > 0L) {
                durSec = computeDurationSec(durMs);
            }
            int count = obj.optInt("watch_count", 1);
            return new ChannelWatchRecord(name, num, url, group, isp, res, durMs, durSec, count);
        }

        @NonNull
        public static String toJsonArrayString(@Nullable List<ChannelWatchRecord> records) {
            if (records == null || records.isEmpty()) {
                return "";
            }
            JSONArray arr = new JSONArray();
            for (ChannelWatchRecord r : records) {
                if (r != null && !TextUtils.isEmpty(r.channelName)) {
                    try {
                        arr.put(r.toJson());
                    } catch (JSONException ignored) {
                    }
                }
            }
            return arr.length() > 0 ? arr.toString() : "";
        }

        @NonNull
        public static List<ChannelWatchRecord> parseListFromJson(@Nullable String jsonText) {
            if (TextUtils.isEmpty(jsonText)) {
                return Collections.emptyList();
            }
            List<ChannelWatchRecord> list = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(jsonText.trim());
                for (int i = 0; i < arr.length(); i++) {
                    ChannelWatchRecord rec = fromJson(arr.optJSONObject(i));
                    if (rec != null) {
                        list.add(rec);
                    }
                }
            } catch (Throwable ignored) {
            }
            return list;
        }
    }

    private static class SessionChannelEntry {
        String channelName;
        int channelNumber;
        String channelUrl;
        String groupTitle;
        String ispName;
        String resolution;
        long durationMs;
        int watchCount;

        SessionChannelEntry(@NonNull String channelName,
                            int channelNumber,
                            @Nullable String channelUrl,
                            @Nullable String groupTitle,
                            @Nullable String ispName,
                            @Nullable String resolution) {
            this.channelName = channelName;
            this.channelNumber = channelNumber;
            this.channelUrl = channelUrl != null ? channelUrl : "";
            this.groupTitle = groupTitle != null ? groupTitle : "";
            this.ispName = ispName != null ? ispName : "";
            this.resolution = resolution != null ? resolution : "";
            this.durationMs = 0L;
            this.watchCount = 0;
        }

        @NonNull
        SessionChannelEntry copyMetadataForNewWindow() {
            SessionChannelEntry next = new SessionChannelEntry(
                    channelName, channelNumber, channelUrl, groupTitle, ispName, resolution);
            next.watchCount = 1;
            return next;
        }

        @NonNull
        ChannelWatchRecord toRecord(long extraActiveMs) {
            long totalMs = Math.max(0L, durationMs + Math.max(0L, extraActiveMs));
            long totalSec = ChannelWatchRecord.computeDurationSec(totalMs);
            return new ChannelWatchRecord(
                    channelName,
                    channelNumber,
                    channelUrl,
                    groupTitle,
                    ispName,
                    resolution,
                    totalMs,
                    totalSec,
                    Math.max(1, watchCount)
            );
        }
    }

    /**
     * 频道列表及时长单独上报请求包体 (POST /api/v1/channel)
     */
    public static class ChannelReportPayload {
        public final String reportId;
        public final String reportType;
        public final long timestamp;
        @Nullable
        public final String userId;
        public final String deviceId;
        public final String appVersion;
        public final List<ChannelWatchRecord> channels;

        public ChannelReportPayload(@NonNull String reportId,
                                    @NonNull String reportType,
                                    long timestamp,
                                    @Nullable String userId,
                                    @NonNull String deviceId,
                                    @NonNull String appVersion,
                                    @NonNull List<ChannelWatchRecord> channels) {
            this.reportId = reportId;
            this.reportType = reportType;
            this.timestamp = timestamp;
            this.userId = userId;
            this.deviceId = deviceId;
            this.appVersion = appVersion;
            this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
        }

        @NonNull
        public JSONObject toJson() throws JSONException {
            JSONObject root = new JSONObject();
            root.put("report_id", reportId);
            root.put("report_type", reportType);
            root.put("timestamp", timestamp);
            root.put("user_id", userId != null ? userId : JSONObject.NULL);
            root.put("device_id", deviceId);
            root.put("app_version", appVersion);
            JSONArray chArr = new JSONArray();
            for (ChannelWatchRecord record : channels) {
                if (record != null) {
                    chArr.put(record.toJson());
                }
            }
            root.put("channels", chArr);
            return root;
        }

        @NonNull
        public String toJsonString() {
            try {
                return toJson().toString();
            } catch (JSONException e) {
                throw new IllegalStateException("Failed to serialize ChannelReportPayload to JSON", e);
            }
        }
    }

    /**
     * 完整设备画像上报请求包体 (POST /api/v1/report)
     */
    public static class ReportPayload {
        public final String reportId;
        public final String reportType;
        public final long timestamp;
        @Nullable
        public final String userId;
        public final DeviceProfile device;
        public final EnvProfile env;
        public final List<ChannelWatchRecord> channels;

        public ReportPayload(@NonNull String reportId,
                             @NonNull String reportType,
                             long timestamp,
                             @Nullable String userId,
                             @NonNull DeviceProfile device,
                             @NonNull EnvProfile env) {
            this(reportId, reportType, timestamp, userId, device, env, null);
        }

        public ReportPayload(@NonNull String reportId,
                             @NonNull String reportType,
                             long timestamp,
                             @Nullable String userId,
                             @NonNull DeviceProfile device,
                             @NonNull EnvProfile env,
                             @Nullable List<ChannelWatchRecord> channels) {
            this.reportId = reportId;
            this.reportType = reportType;
            this.timestamp = timestamp;
            this.userId = userId;
            this.device = device;
            this.env = env;
            this.channels = channels != null
                    ? Collections.unmodifiableList(new ArrayList<>(channels))
                    : Collections.emptyList();
        }

        @NonNull
        public JSONObject toJson() throws JSONException {
            JSONObject root = new JSONObject();
            root.put("report_id", reportId);
            root.put("report_type", reportType);
            root.put("timestamp", timestamp);
            root.put("user_id", userId != null ? userId : JSONObject.NULL);
            root.put("device", device.toJson());
            root.put("env", env.toJson());
            if (!channels.isEmpty()) {
                JSONArray chArr = new JSONArray();
                for (ChannelWatchRecord record : channels) {
                    if (record != null) {
                        chArr.put(record.toJson());
                    }
                }
                root.put("channels", chArr);
            }
            return root;
        }

        @NonNull
        public String toJsonString() {
            try {
                String raw = toJson().toString();
                if (device.dpr == Math.floor(device.dpr) && !Double.isInfinite(device.dpr)) {
                    String intDpr = "\"dpr\":" + (long) device.dpr;
                    String floatDpr = "\"dpr\":" + String.format(Locale.US, "%.1f", device.dpr);
                    raw = raw.replace(intDpr + ",", floatDpr + ",")
                            .replace(intDpr + "}", floatDpr + "}");
                }
                return raw;
            } catch (JSONException e) {
                throw new IllegalStateException("Failed to serialize ReportPayload to JSON", e);
            }
        }
    }

    /**
     * 服务端上报响应结果
     * - 首次上报成功: HTTP 201 Created, code=0, message="created", is_duplicate=false
     * - 重复重试上报: HTTP 200 OK, code=0, message="success", is_duplicate=true
     */
    public static class ReportResponse {
        public final int httpStatusCode;
        public final int code;
        public final String message;
        public final String reportId;
        public final String deviceId;
        public final boolean isDuplicate;
        public final int channelCount;
        public final long totalDurationSec;
        public final String createdAt;

        public ReportResponse(int httpStatusCode,
                              int code,
                              @NonNull String message,
                              @NonNull String reportId,
                              @NonNull String deviceId,
                              boolean isDuplicate,
                              @NonNull String createdAt) {
            this(httpStatusCode, code, message, reportId, deviceId, isDuplicate, 0, 0L, createdAt);
        }

        public ReportResponse(int httpStatusCode,
                              int code,
                              @NonNull String message,
                              @NonNull String reportId,
                              @NonNull String deviceId,
                              boolean isDuplicate,
                              int channelCount,
                              long totalDurationSec,
                              @NonNull String createdAt) {
            this.httpStatusCode = httpStatusCode;
            this.code = code;
            this.message = message;
            this.reportId = reportId;
            this.deviceId = deviceId;
            this.isDuplicate = isDuplicate;
            this.channelCount = channelCount;
            this.totalDurationSec = totalDurationSec;
            this.createdAt = createdAt;
        }

        public boolean isSuccess() {
            return code == 0 && (httpStatusCode == HttpURLConnection.HTTP_CREATED
                    || httpStatusCode == HttpURLConnection.HTTP_OK);
        }

        public boolean isFirstReportCreated() {
            return httpStatusCode == HttpURLConnection.HTTP_CREATED && code == 0 && !isDuplicate;
        }

        public boolean isIdempotentDuplicate() {
            return code == 0 && isDuplicate;
        }

        @Nullable
        public static ReportResponse parse(@Nullable String jsonText, int httpStatusCode) {
            if (TextUtils.isEmpty(jsonText)) {
                return null;
            }
            try {
                JSONObject root = new JSONObject(jsonText.trim());
                int code = root.optInt("code", -1);
                String message = root.optString("message", "");
                JSONObject data = root.optJSONObject("data");
                String reportId = "";
                String deviceId = "";
                boolean isDuplicate = false;
                int channelCount = 0;
                long totalDurationSec = 0L;
                String createdAt = "";
                if (data != null) {
                    reportId = data.optString("report_id", "");
                    deviceId = data.optString("device_id", "");
                    isDuplicate = data.optBoolean("is_duplicate", false);
                    channelCount = data.optInt("channel_count", data.optInt("watched_channels_count", 0));
                    totalDurationSec = data.optLong("total_duration_sec", 0L);
                    createdAt = data.optString("created_at", "");
                }
                return new ReportResponse(
                        httpStatusCode,
                        code,
                        message,
                        reportId,
                        deviceId,
                        isDuplicate,
                        channelCount,
                        totalDurationSec,
                        createdAt
                );
            } catch (Throwable t) {
                Log.w(TAG, "Failed to parse report response JSON: " + t.getMessage());
                return null;
            }
        }
    }

    public static DataReportManager getInstance(@NonNull Context context) {
        Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (instance == null || instance.appContext != appCtx) {
            synchronized (DataReportManager.class) {
                if (instance == null || instance.appContext != appCtx) {
                    instance = new DataReportManager(appCtx);
                }
            }
        }
        return instance;
    }

    private DataReportManager(@NonNull Context appContext) {
        this.appContext = appContext;
        this.settings = new IptvSettings(appContext);
        promoteCrashedSessionChannelsIfAny();
    }

    /**
     * 若上次进程异常退出残留了未归档的会话观看记录，初始化时自动归档至待上报队列
     */
    private synchronized void promoteCrashedSessionChannelsIfAny() {
        String orphanJson = settings.getActiveSessionChannelsJson();
        if (!TextUtils.isEmpty(orphanJson)) {
            List<ChannelWatchRecord> orphanRecords = ChannelWatchRecord.parseListFromJson(orphanJson);
            if (!orphanRecords.isEmpty()) {
                List<ChannelWatchRecord> merged = mergeChannelWatchRecords(getPendingWatchedChannels(), orphanRecords);
                settings.setPendingReportChannelsJson(ChannelWatchRecord.toJsonArrayString(merged));
            }
            settings.setActiveSessionChannelsJson(null);
        }
    }

    @NonNull
    public String getReportEndpointUrl() {
        return reportEndpointUrl;
    }

    public void setReportEndpointUrl(@Nullable String url) {
        this.reportEndpointUrl = normalizeReportUrl(url);
    }

    @NonNull
    public String getChannelReportEndpointUrl() {
        return channelReportEndpointUrl;
    }

    public void setChannelReportEndpointUrl(@Nullable String url) {
        this.channelReportEndpointUrl = normalizeChannelReportUrl(url);
    }

    public long getChannelReportIntervalMs() {
        return channelReportIntervalMs;
    }

    public void setChannelReportIntervalMs(long intervalMs) {
        this.channelReportIntervalMs = intervalMs > 0 ? intervalMs : CHANNEL_REPORT_INTERVAL_MS;
    }

    @Nullable
    public ReportResponse getLastReportResponse() {
        return lastReportResponse;
    }

    @Nullable
    public ReportResponse getLastChannelReportResponse() {
        return lastChannelReportResponse;
    }

    public boolean isStartupReportSucceeded() {
        return startupReportSucceeded;
    }

    public boolean isStartupReportTriggeredInSession() {
        return startupReportTriggeredInSession.get();
    }

    /**
     * 规范化上报接口地址（自动修正类似 "http://http://104.223.42.150:8080/api/v1/report" 的重复协议前缀及缺失端口）
     */
    @NonNull
    public static String normalizeReportUrl(@Nullable String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) {
            return REPORT_API_URL;
        }
        String url = rawUrl.trim();
        while (url.regionMatches(true, 0, "http://http://", 0, "http://http://".length())) {
            url = "http://" + url.substring("http://http://".length());
        }
        while (url.regionMatches(true, 0, "https://https://", 0, "https://https://".length())) {
            url = "https://" + url.substring("https://https://".length());
        }
        if (url.regionMatches(true, 0, "http://https://", 0, "http://https://".length())) {
            url = "https://" + url.substring("http://https://".length());
        }
        if (url.regionMatches(true, 0, "http://104.223.42.150/", 0, "http://104.223.42.150/".length())) {
            url = "http://104.223.42.150:8080/" + url.substring("http://104.223.42.150/".length());
        }
        return url;
    }

    /**
     * 规范化频道列表上报接口地址 (/api/v1/channel)
     */
    @NonNull
    public static String normalizeChannelReportUrl(@Nullable String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) {
            return CHANNEL_REPORT_API_URL;
        }
        return normalizeReportUrl(rawUrl);
    }

    /**
     * 生成格式为 "req_" + 10位十六进制小写字符的请求流水号（例如 "req_8f9d2a1b4c"）
     */
    @NonNull
    public static String generateReportId() {
        byte[] bytes = new byte[5];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(14);
        sb.append("req_");
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 获取或创建待上报的幂等 report_id：
     * 若上次上报因网络波动未完成确认，则继续复用同一个 report_id 以保证服务端幂等去重。
     */
    @NonNull
    public synchronized String getOrCreatePendingReportId() {
        String pending = settings.getPendingReportId();
        if (!TextUtils.isEmpty(pending)) {
            return pending;
        }
        String newId = generateReportId();
        settings.setPendingReportId(newId);
        return newId;
    }

    /**
     * 获取或创建待上报的频道列表幂等 report_id
     */
    @NonNull
    public synchronized String getOrCreatePendingChannelReportId() {
        String pending = settings.getPendingChannelReportId();
        if (!TextUtils.isEmpty(pending)) {
            return pending;
        }
        String newId = generateReportId();
        settings.setPendingChannelReportId(newId);
        return newId;
    }

    /**
     * 获取或生成持久化的设备唯一标识 (UUID 格式，如 "d4e5f6a7-b8c9-40d1-a2b3-c4d5e6f7a8b9")
     */
    @NonNull
    public synchronized String getOrCreateDeviceId() {
        String saved = settings.getSavedDeviceId();
        if (!TextUtils.isEmpty(saved)) {
            return saved;
        }
        String generated = null;
        try {
            String androidId = Settings.Secure.getString(
                    appContext.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (!TextUtils.isEmpty(androidId)
                    && !"9774d56d682e549c".equalsIgnoreCase(androidId.trim())) {
                String seed = "utv-device-" + androidId.trim() + "-" + Build.BRAND + "-" + Build.MODEL;
                generated = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
            }
        } catch (Throwable ignored) {
        }
        if (TextUtils.isEmpty(generated)) {
            generated = UUID.randomUUID().toString();
        }
        settings.setSavedDeviceId(generated);
        return generated;
    }

    /**
     * 采集当前设备的完整上报数据包 (device_profile_init)
     */
    @NonNull
    public ReportPayload buildDeviceProfileInitPayload() {
        return buildDeviceProfileInitPayload(getOrCreatePendingReportId(), System.currentTimeMillis());
    }

    @NonNull
    public ReportPayload buildDeviceProfileInitPayload(@NonNull String reportId, long timestampMs) {
        DeviceProfile device = collectDeviceProfile();
        EnvProfile env = collectEnvProfile();
        return new ReportPayload(
                reportId,
                REPORT_TYPE_DEVICE_PROFILE_INIT,
                timestampMs,
                settings.getUserId(),
                device,
                env
        );
    }

    /**
     * 构建频道观看列表上报数据包 (POST /api/v1/channel)
     */
    @Nullable
    public synchronized ChannelReportPayload buildChannelReportPayload() {
        return buildChannelReportPayload(getOrCreatePendingChannelReportId(), System.currentTimeMillis());
    }

    @Nullable
    public synchronized ChannelReportPayload buildChannelReportPayload(@NonNull String reportId, long timestampMs) {
        List<ChannelWatchRecord> pending = getPendingWatchedChannels();
        if (pending.isEmpty()) {
            return null;
        }
        return new ChannelReportPayload(
                reportId,
                REPORT_TYPE_CHANNEL_WATCH,
                timestampMs,
                settings.getUserId(),
                getOrCreateDeviceId(),
                AppUpdateManager.getCurrentVersionName(appContext),
                pending
        );
    }

    /**
     * 记录频道开始播放或切台事件（自动结算上一频道观看时长并持久化到本地，同时确保 10 分钟定时上报任务运行）
     */
    public synchronized void onChannelPlaybackStarted(@Nullable IptvChannel channel) {
        onChannelPlaybackStarted(channel, System.currentTimeMillis());
    }

    public synchronized void onChannelPlaybackStarted(@Nullable IptvChannel channel, long nowMs) {
        settleActiveChannelDurationLocked(nowMs);
        if (channel == null) {
            activeChannelKey = null;
            activeChannelStartMs = 0L;
            return;
        }
        String key = resolveChannelKey(channel);
        if (TextUtils.isEmpty(key)) {
            activeChannelKey = null;
            activeChannelStartMs = 0L;
            return;
        }
        SessionChannelEntry entry = currentSessionChannels.get(key);
        String groupTitle = !channel.getGroups().isEmpty() ? channel.getGroups().get(0) : channel.getGroupTitleRaw();
        if (entry == null) {
            entry = new SessionChannelEntry(
                    key,
                    channel.getChannelNumber(),
                    channel.getRawUrl(),
                    groupTitle,
                    channel.getIspName(),
                    channel.getResolutionTag()
            );
            currentSessionChannels.put(key, entry);
        } else {
            if (channel.getChannelNumber() > 0) {
                entry.channelNumber = channel.getChannelNumber();
            }
            if (!TextUtils.isEmpty(channel.getRawUrl())) {
                entry.channelUrl = channel.getRawUrl();
            }
            if (!TextUtils.isEmpty(groupTitle)) {
                entry.groupTitle = groupTitle;
            }
            if (!TextUtils.isEmpty(channel.getIspName())) {
                entry.ispName = channel.getIspName();
            }
            if (!TextUtils.isEmpty(channel.getResolutionTag())) {
                entry.resolution = channel.getResolutionTag();
            }
        }
        entry.watchCount++;
        activeChannelKey = key;
        activeChannelStartMs = nowMs;
        snapshotActiveSessionToPrefsLocked(nowMs);
        startPeriodicChannelReporting();
    }

    /**
     * 当播放暂停或退到后台时结算当前频道已播放时长并持久化到本地
     */
    public synchronized void onChannelPlaybackPaused() {
        onChannelPlaybackPaused(System.currentTimeMillis());
    }

    public synchronized void onChannelPlaybackPaused(long nowMs) {
        settleActiveChannelDurationLocked(nowMs);
        snapshotActiveSessionToPrefsLocked(nowMs);
    }

    /**
     * 当从后台恢复继续播放同一频道时恢复计时
     */
    public synchronized void onChannelPlaybackResumed(@Nullable IptvChannel channel) {
        onChannelPlaybackResumed(channel, System.currentTimeMillis());
    }

    public synchronized void onChannelPlaybackResumed(@Nullable IptvChannel channel, long nowMs) {
        startPeriodicChannelReporting();
        if (channel == null) {
            return;
        }
        String key = resolveChannelKey(channel);
        if (!TextUtils.isEmpty(key) && key.equals(activeChannelKey) && currentSessionChannels.containsKey(key)) {
            if (activeChannelStartMs <= 0L) {
                activeChannelStartMs = nowMs;
            }
            return;
        }
        onChannelPlaybackStarted(channel, nowMs);
    }

    /**
     * 直接累加指定频道的观看时长（毫秒）
     */
    public synchronized void recordChannelWatchDuration(@NonNull IptvChannel channel, long durationMs) {
        String key = resolveChannelKey(channel);
        if (TextUtils.isEmpty(key)) {
            return;
        }
        String groupTitle = !channel.getGroups().isEmpty() ? channel.getGroups().get(0) : channel.getGroupTitleRaw();
        SessionChannelEntry entry = currentSessionChannels.get(key);
        if (entry == null) {
            entry = new SessionChannelEntry(
                    key,
                    channel.getChannelNumber(),
                    channel.getRawUrl(),
                    groupTitle,
                    channel.getIspName(),
                    channel.getResolutionTag()
            );
            currentSessionChannels.put(key, entry);
        }
        entry.watchCount++;
        entry.durationMs += Math.max(0L, durationMs);
        snapshotActiveSessionToPrefsLocked(System.currentTimeMillis());
    }

    /**
     * 启动每 10 分钟一次的频道列表定时上报任务
     */
    public void startPeriodicChannelReporting() {
        if (periodicChannelReportScheduled.compareAndSet(false, true)) {
            mainHandler.removeCallbacks(periodicChannelReportRunnable);
            mainHandler.postDelayed(periodicChannelReportRunnable, Math.max(1000L, channelReportIntervalMs));
        }
    }

    /**
     * 停止每 10 分钟一次的频道列表定时上报任务
     */
    public void stopPeriodicChannelReporting() {
        periodicChannelReportScheduled.set(false);
        mainHandler.removeCallbacks(periodicChannelReportRunnable);
    }

    public boolean isPeriodicChannelReportScheduled() {
        return periodicChannelReportScheduled.get();
    }

    /**
     * 将当前播放会话累计的频道及时长结算并归档到本地待上报队列 (pending_report_channels)
     *
     * @param nowMs                  结算时间戳
     * @param keepActiveChannelAlive 若为 true（如 10 分钟定时上报），当前正在播放的频道从 nowMs 重新开始下一周期计时；
     *                               若为 false（如 App 退出），清空当前正在播放的频道状态
     */
    @NonNull
    public synchronized List<ChannelWatchRecord> flushSessionChannelsToPendingQueue(long nowMs,
                                                                                    boolean keepActiveChannelAlive) {
        boolean wasActivelyPlaying = !TextUtils.isEmpty(activeChannelKey) && activeChannelStartMs > 0L;
        SessionChannelEntry activeEntrySnapshot = null;
        if (wasActivelyPlaying && keepActiveChannelAlive) {
            SessionChannelEntry cur = currentSessionChannels.get(activeChannelKey);
            if (cur != null) {
                activeEntrySnapshot = cur.copyMetadataForNewWindow();
            }
        }

        settleActiveChannelDurationLocked(nowMs);

        List<ChannelWatchRecord> existingPending = getPendingWatchedChannels();
        if (!currentSessionChannels.isEmpty()) {
            List<ChannelWatchRecord> sessionRecords = buildCurrentSessionRecordsLocked(nowMs);
            List<ChannelWatchRecord> merged = mergeChannelWatchRecords(existingPending, sessionRecords);
            settings.setPendingReportChannelsJson(ChannelWatchRecord.toJsonArrayString(merged));
            // 归档新频道数据后生成新的待上报流水号，确保本批次完整上报
            settings.setPendingChannelReportId(generateReportId());
            currentSessionChannels.clear();
            existingPending = merged;
        }

        if (keepActiveChannelAlive && activeEntrySnapshot != null) {
            currentSessionChannels.put(activeEntrySnapshot.channelName, activeEntrySnapshot);
            activeChannelKey = activeEntrySnapshot.channelName;
            activeChannelStartMs = nowMs;
            snapshotActiveSessionToPrefsLocked(nowMs);
        } else {
            activeChannelKey = null;
            activeChannelStartMs = 0L;
            settings.setActiveSessionChannelsJson(null);
        }

        return existingPending;
    }

    /**
     * 获取当前播放会话中已观看的频道列表及实时累计时长
     */
    @NonNull
    public synchronized List<ChannelWatchRecord> getCurrentSessionWatchedChannels() {
        return buildCurrentSessionRecordsLocked(System.currentTimeMillis());
    }

    /**
     * 获取本地已归档、待上报至 /api/v1/channel 的频道观看时长列表
     */
    @NonNull
    public synchronized List<ChannelWatchRecord> getPendingWatchedChannels() {
        return ChannelWatchRecord.parseListFromJson(settings.getPendingReportChannelsJson());
    }

    public synchronized void clearSessionAndPendingWatchedChannels() {
        currentSessionChannels.clear();
        activeChannelKey = null;
        activeChannelStartMs = 0L;
        settings.setActiveSessionChannelsJson(null);
        settings.setPendingReportChannelsJson(null);
        settings.setPendingChannelReportId(null);
    }

    @NonNull
    private static String resolveChannelKey(@NonNull IptvChannel channel) {
        String name = channel.getDisplayName();
        if (!TextUtils.isEmpty(name)) {
            return name.trim();
        }
        String raw = channel.getRawUrl();
        return !TextUtils.isEmpty(raw) ? raw.trim() : "";
    }

    private void settleActiveChannelDurationLocked(long nowMs) {
        if (!TextUtils.isEmpty(activeChannelKey) && activeChannelStartMs > 0L) {
            SessionChannelEntry entry = currentSessionChannels.get(activeChannelKey);
            if (entry != null) {
                long delta = Math.max(0L, nowMs - activeChannelStartMs);
                entry.durationMs += delta;
            }
            activeChannelStartMs = 0L;
        }
    }

    @NonNull
    private List<ChannelWatchRecord> buildCurrentSessionRecordsLocked(long nowMs) {
        List<ChannelWatchRecord> list = new ArrayList<>(currentSessionChannels.size());
        for (Map.Entry<String, SessionChannelEntry> e : currentSessionChannels.entrySet()) {
            long extraMs = 0L;
            if (e.getKey().equals(activeChannelKey) && activeChannelStartMs > 0L) {
                extraMs = Math.max(0L, nowMs - activeChannelStartMs);
            }
            list.add(e.getValue().toRecord(extraMs));
        }
        return list;
    }

    private void snapshotActiveSessionToPrefsLocked(long nowMs) {
        List<ChannelWatchRecord> current = buildCurrentSessionRecordsLocked(nowMs);
        settings.setActiveSessionChannelsJson(ChannelWatchRecord.toJsonArrayString(current));
    }

    @NonNull
    public static List<ChannelWatchRecord> mergeChannelWatchRecords(
            @Nullable List<ChannelWatchRecord> baseList,
            @Nullable List<ChannelWatchRecord> newList) {
        Map<String, ChannelWatchRecord> merged = new LinkedHashMap<>();
        if (baseList != null) {
            for (ChannelWatchRecord r : baseList) {
                if (r != null && !TextUtils.isEmpty(r.channelName)) {
                    merged.put(r.channelName, r);
                }
            }
        }
        if (newList != null) {
            for (ChannelWatchRecord r : newList) {
                if (r == null || TextUtils.isEmpty(r.channelName)) {
                    continue;
                }
                ChannelWatchRecord existing = merged.get(r.channelName);
                if (existing == null) {
                    merged.put(r.channelName, r);
                } else {
                    long totalMs = existing.durationMs + r.durationMs;
                    long totalSec = ChannelWatchRecord.computeDurationSec(totalMs);
                    if (totalSec < existing.durationSec + r.durationSec) {
                        totalSec = existing.durationSec + r.durationSec;
                    }
                    merged.put(r.channelName, new ChannelWatchRecord(
                            r.channelName,
                            r.channelNumber > 0 ? r.channelNumber : existing.channelNumber,
                            !TextUtils.isEmpty(r.channelUrl) ? r.channelUrl : existing.channelUrl,
                            !TextUtils.isEmpty(r.groupTitle) ? r.groupTitle : existing.groupTitle,
                            !TextUtils.isEmpty(r.ispName) ? r.ispName : existing.ispName,
                            !TextUtils.isEmpty(r.resolution) ? r.resolution : existing.resolution,
                            totalMs,
                            totalSec,
                            existing.watchCount + r.watchCount
                    ));
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    @NonNull
    public DeviceProfile collectDeviceProfile() {
        String deviceId = getOrCreateDeviceId();
        String brand = !TextUtils.isEmpty(Build.BRAND) ? Build.BRAND.trim()
                : (!TextUtils.isEmpty(Build.MANUFACTURER) ? Build.MANUFACTURER.trim() : "Android");
        String model = !TextUtils.isEmpty(Build.MODEL) ? Build.MODEL.trim() : "Android TV";
        String osVersion = formatOsVersion(Build.VERSION.RELEASE);

        DisplayMetrics dm = getScreenDisplayMetrics();
        int width = dm.widthPixels > 0 ? dm.widthPixels : 1920;
        int height = dm.heightPixels > 0 ? dm.heightPixels : 1080;
        int screenWidth = Math.max(width, height);
        int screenHeight = Math.min(width, height);
        double dpr = dm.density > 0 ? Math.round(dm.density * 100.0) / 100.0 : 2.0;

        long ramMb = getTotalRamMb();
        long diskFreeMb = getDiskFreeMb();
        boolean emulator = detectIsEmulator();
        String playerType = settings.getPlayerTypeReportName();
        boolean isHwDecoder = settings.isMediaCodecEnabled();

        return new DeviceProfile(
                deviceId,
                PLATFORM_ANDROID,
                brand,
                model,
                osVersion,
                screenWidth,
                screenHeight,
                dpr,
                ramMb,
                diskFreeMb,
                emulator,
                playerType,
                isHwDecoder
        );
    }

    @NonNull
    public EnvProfile collectEnvProfile() {
        String appVersion = AppUpdateManager.getCurrentVersionName(appContext);
        String buildNumber = getCurrentBuildNumber(appContext);
        String channel = resolveAppChannel();
        String networkType = detectNetworkType();
        String carrier = detectCarrier();
        String language = detectLanguageTag();
        String timezone = detectTimezoneId();
        String userAgent = buildUserAgent();

        return new EnvProfile(
                appVersion,
                buildNumber,
                channel,
                networkType,
                carrier,
                language,
                timezone,
                userAgent
        );
    }

    /**
     * 规范化系统版本号（如 "11" -> "11.0"，"11.0" -> "11.0"）
     */
    @NonNull
    public static String formatOsVersion(@Nullable String rawRelease) {
        if (TextUtils.isEmpty(rawRelease)) {
            return "11.0";
        }
        String trimmed = rawRelease.trim();
        if (trimmed.matches("^\\d+$")) {
            return trimmed + ".0";
        }
        return trimmed;
    }

    /**
     * 构建标准 Android User-Agent 字符串
     * 格式示例: "Mozilla/5.0 (Linux; U; Android 11; MiBox4 Build/RQ3A.211001.001)"
     */
    @NonNull
    public static String buildUserAgent() {
        String release = !TextUtils.isEmpty(Build.VERSION.RELEASE) ? Build.VERSION.RELEASE.trim() : "11";
        String model = !TextUtils.isEmpty(Build.MODEL) ? Build.MODEL.trim() : "Android TV";
        String buildId = !TextUtils.isEmpty(Build.ID) ? Build.ID.trim() : "RQ3A.211001.001";
        return buildUserAgent(release, model, buildId);
    }

    @NonNull
    public static String buildUserAgent(@NonNull String androidRelease,
                                        @NonNull String model,
                                        @NonNull String buildId) {
        return String.format(Locale.US,
                "Mozilla/5.0 (Linux; U; Android %s; %s Build/%s)",
                androidRelease, model, buildId);
    }

    @NonNull
    public static String getCurrentBuildNumber(@NonNull Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            if (pi != null) {
                long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? pi.getLongVersionCode()
                        : pi.versionCode;
                if (code > 0) {
                    return String.valueOf(code);
                }
            }
        } catch (Throwable ignored) {
        }
        return String.valueOf(AppUpdateManager.CURRENT_VERSION_CODE);
    }

    @NonNull
    private String resolveAppChannel() {
        String configured = settings.getAppChannel();
        if (!TextUtils.isEmpty(configured) && !DEFAULT_CHANNEL.equals(configured)) {
            return configured;
        }
        try {
            ApplicationInfo ai = appContext.getPackageManager().getApplicationInfo(
                    appContext.getPackageName(), PackageManager.GET_META_DATA);
            if (ai != null && ai.metaData != null) {
                String metaChannel = ai.metaData.getString("APP_CHANNEL");
                if (!TextUtils.isEmpty(metaChannel)) {
                    return metaChannel.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return !TextUtils.isEmpty(configured) ? configured : DEFAULT_CHANNEL;
    }

    @NonNull
    private DisplayMetrics getScreenDisplayMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                if (display != null) {
                    display.getRealMetrics(dm);
                    if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                        return dm;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        DisplayMetrics resDm = appContext.getResources().getDisplayMetrics();
        if (resDm != null) {
            return resDm;
        }
        dm.widthPixels = 1920;
        dm.heightPixels = 1080;
        dm.density = 2.0f;
        return dm;
    }

    private long getTotalRamMb() {
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(memInfo);
                if (memInfo.totalMem > 0) {
                    return Math.round(memInfo.totalMem / (1024.0 * 1024.0));
                }
            }
        } catch (Throwable ignored) {
        }
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        return maxHeapMb > 0 ? Math.max(512L, maxHeapMb) : 2048L;
    }

    private long getDiskFreeMb() {
        try {
            File path = Environment.getDataDirectory();
            if (path == null || !path.exists()) {
                path = appContext.getFilesDir();
            }
            if (path != null) {
                StatFs stat = new StatFs(path.getAbsolutePath());
                long availableBytes = stat.getAvailableBytes();
                if (availableBytes >= 0) {
                    return availableBytes / (1024L * 1024L);
                }
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    public static boolean detectIsEmulator() {
        String fingerprint = Build.FINGERPRINT != null ? Build.FINGERPRINT.toLowerCase(Locale.US) : "";
        String model = Build.MODEL != null ? Build.MODEL.toLowerCase(Locale.US) : "";
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase(Locale.US) : "";
        String brand = Build.BRAND != null ? Build.BRAND.toLowerCase(Locale.US) : "";
        String device = Build.DEVICE != null ? Build.DEVICE.toLowerCase(Locale.US) : "";
        String product = Build.PRODUCT != null ? Build.PRODUCT.toLowerCase(Locale.US) : "";
        String hardware = Build.HARDWARE != null ? Build.HARDWARE.toLowerCase(Locale.US) : "";

        return fingerprint.startsWith("generic")
                || fingerprint.contains("vbox")
                || fingerprint.contains("test-keys") && (model.contains("sdk") || product.contains("sdk"))
                || model.contains("google_sdk")
                || model.contains("emulator")
                || model.contains("android sdk built for")
                || model.contains("sdk_gphone")
                || manufacturer.contains("genymotion")
                || (brand.startsWith("generic") && device.startsWith("generic"))
                || product.equals("google_sdk")
                || product.contains("sdk_gphone")
                || product.contains("vbox86p")
                || product.contains("emulator")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || hardware.contains("vbox86");
    }

    @NonNull
    public String detectNetworkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network activeNetwork = cm.getActiveNetwork();
                    if (activeNetwork != null) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                        if (caps != null) {
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                                return "Ethernet";
                            }
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                return "WiFi";
                            }
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                return "Cellular";
                            }
                        }
                    }
                }
                @SuppressWarnings("deprecation")
                NetworkInfo info = cm.getActiveNetworkInfo();
                if (info != null && info.isConnected()) {
                    int type = info.getType();
                    if (type == ConnectivityManager.TYPE_ETHERNET) {
                        return "Ethernet";
                    } else if (type == ConnectivityManager.TYPE_WIFI) {
                        return "WiFi";
                    } else if (type == ConnectivityManager.TYPE_MOBILE) {
                        return "Cellular";
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface nif : Collections.list(interfaces)) {
                    if (!nif.isUp() || nif.isLoopback()) {
                        continue;
                    }
                    String name = nif.getName() != null ? nif.getName().toLowerCase(Locale.US) : "";
                    if (name.startsWith("eth")) {
                        return "Ethernet";
                    } else if (name.startsWith("wlan")) {
                        return "WiFi";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "Ethernet";
    }

    @NonNull
    public String detectCarrier() {
        try {
            TelephonyManager tm = (TelephonyManager) appContext.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String operatorCode = tm.getSimOperator();
                if (TextUtils.isEmpty(operatorCode)) {
                    operatorCode = tm.getNetworkOperator();
                }
                String mappedByCode = mapCarrierByOperatorCode(operatorCode);
                if (!TextUtils.isEmpty(mappedByCode)) {
                    return mappedByCode;
                }

                String operatorName = tm.getNetworkOperatorName();
                if (TextUtils.isEmpty(operatorName)) {
                    operatorName = tm.getSimOperatorName();
                }
                String mappedByName = mapCarrierByOperatorName(operatorName);
                if (!TextUtils.isEmpty(mappedByName)) {
                    return mappedByName;
                }
            }
        } catch (Throwable ignored) {
        }
        return settings.getCarrierReportName();
    }

    @Nullable
    public static String mapCarrierByOperatorCode(@Nullable String code) {
        if (TextUtils.isEmpty(code)) {
            return null;
        }
        String trimmed = code.trim();
        switch (trimmed) {
            case "46000":
            case "46002":
            case "46004":
            case "46007":
            case "46008":
                return "China Mobile";
            case "46001":
            case "46006":
            case "46009":
                return "China Unicom";
            case "46003":
            case "46005":
            case "46011":
                return "China Telecom";
            default:
                return null;
        }
    }

    @Nullable
    public static String mapCarrierByOperatorName(@Nullable String name) {
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        String lower = name.trim().toLowerCase(Locale.US);
        if (lower.contains("telecom") || lower.contains("电信") || lower.contains("chn-ct")) {
            return "China Telecom";
        }
        if (lower.contains("unicom") || lower.contains("联通") || lower.contains("cucc")) {
            return "China Unicom";
        }
        if (lower.contains("mobile") || lower.contains("移动") || lower.contains("cmcc")) {
            return "China Mobile";
        }
        return null;
    }

    @NonNull
    public static String detectLanguageTag() {
        Locale locale = Locale.getDefault();
        if (locale == null) {
            return "zh-CN";
        }
        String tag = locale.toLanguageTag();
        if (TextUtils.isEmpty(tag) || "und".equalsIgnoreCase(tag)) {
            return "zh-CN";
        }
        if ("zh".equalsIgnoreCase(tag)) {
            return "zh-CN";
        }
        return tag;
    }

    @NonNull
    public static String detectTimezoneId() {
        TimeZone tz = TimeZone.getDefault();
        if (tz != null && !TextUtils.isEmpty(tz.getID())) {
            return tz.getID();
        }
        return "Asia/Shanghai";
    }

    @NonNull
    private static String currentDateKey() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return sdf.format(new java.util.Date());
    }

    private static boolean isRobolectricRuntime() {
        return Build.FINGERPRINT != null && "robolectric".equalsIgnoreCase(Build.FINGERPRINT.trim());
    }

    /**
     * 应用启动或恢复时在后台异步上报一次 device_profile_init（单次会话成功后不重复上报，若启动时网络未就绪则允许后续恢复重试，跨天活跃自动补报）
     */
    public void reportDeviceProfileInitOnStartup() {
        String today = currentDateKey();
        boolean firstTriggerInSession = startupReportTriggeredInSession.compareAndSet(false, true);
        if (!firstTriggerInSession && startupReportSucceeded && today.equals(lastSucceededDateKey)) {
            return;
        }
        if (!isRobolectricRuntime() || !REPORT_API_URL.equals(this.reportEndpointUrl)) {
            reportDeviceProfileInitAsync(null);
        }
        // 若本地存在上次退出未完成网络发送的待上报频道列表，启动时自动补报至 /api/v1/channel
        if (!getPendingWatchedChannels().isEmpty()
                && (!isRobolectricRuntime() || !CHANNEL_REPORT_API_URL.equals(this.channelReportEndpointUrl))) {
            reportWatchedChannelsAsync(null);
        }
    }

    /**
     * 当应用退出时：
     * 1. 停止 10 分钟周期定时任务；
     * 2. 结算本次播放会话中观看过的所有频道及时长，并持久化到本地存储；
     * 3. 立即触发一次频道列表上报 (/api/v1/channel)；
     * 4. 重置启动会话标记。
     */
    public synchronized void onAppExit() {
        onAppExit(System.currentTimeMillis());
    }

    public synchronized void onAppExit(long exitTimestampMs) {
        stopPeriodicChannelReporting();
        List<ChannelWatchRecord> pending = flushSessionChannelsToPendingQueue(exitTimestampMs, false);
        startupReportTriggeredInSession.set(false);
        startupReportSucceeded = false;
        if (!pending.isEmpty()
                && (!isRobolectricRuntime() || !CHANNEL_REPORT_API_URL.equals(this.channelReportEndpointUrl))) {
            reportWatchedChannelsAsync(null);
        }
    }

    /**
     * 异步将当前累计及待上报的频道列表上报至服务端 POST /api/v1/channel
     */
    public void reportWatchedChannelsAsync(@Nullable ReportCallback callback) {
        flushSessionChannelsToPendingQueue(System.currentTimeMillis(), true);
        if (getPendingWatchedChannels().isEmpty()) {
            return;
        }
        if (!channelReportingInProgress.compareAndSet(false, true)) {
            return;
        }
        final String targetUrl = this.channelReportEndpointUrl;
        executor.execute(() -> {
            String reportId = getOrCreatePendingChannelReportId();
            try {
                ChannelReportPayload payload = buildChannelReportPayload(reportId, System.currentTimeMillis());
                if (payload == null || payload.channels.isEmpty()) {
                    return;
                }
                ReportResponse response = sendChannelReportWithRetry(
                        targetUrl, payload, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_DELAY_MS);
                if (response != null && response.isSuccess()) {
                    if (callback != null) {
                        mainHandler.post(() -> callback.onSuccess(response));
                    }
                } else {
                    int status = response != null ? response.httpStatusCode : -1;
                    String msg = response != null && !TextUtils.isEmpty(response.message)
                            ? response.message
                            : "Channel report request failed";
                    if (callback != null) {
                        mainHandler.post(() -> callback.onFailure(reportId, status, msg));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Async channel report failed (report_id=" + reportId + "): " + t.getMessage());
                if (callback != null) {
                    String err = t.getMessage() != null ? t.getMessage() : "Unknown error";
                    mainHandler.post(() -> callback.onFailure(reportId, -1, err));
                }
            } finally {
                channelReportingInProgress.set(false);
            }
        });
    }

    /**
     * 同步发送频道列表上报请求 (POST /api/v1/channel) 并在遇到网络波动时以同一 report_id 进行幂等重试
     */
    @Nullable
    public ReportResponse sendChannelReportWithRetry(@NonNull ChannelReportPayload payload,
                                                     int maxAttempts,
                                                     long retryDelayMs) {
        return sendChannelReportWithRetry(this.channelReportEndpointUrl, payload, maxAttempts, retryDelayMs);
    }

    @Nullable
    public ReportResponse sendChannelReportWithRetry(@NonNull String targetUrl,
                                                     @NonNull ChannelReportPayload payload,
                                                     int maxAttempts,
                                                     long retryDelayMs) {
        int attempts = Math.max(1, maxAttempts);
        ReportResponse lastResp = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                lastResp = executeChannelHttpPost(targetUrl, payload);
                if (lastResp != null && lastResp.isSuccess()) {
                    synchronized (this) {
                        String currentPendingId = settings.getPendingChannelReportId();
                        if (TextUtils.isEmpty(currentPendingId) || payload.reportId.equals(currentPendingId)) {
                            settings.setPendingChannelReportId(null);
                            settings.setPendingReportChannelsJson(null);
                        }
                        settings.setLastSuccessChannelReportId(
                                !TextUtils.isEmpty(lastResp.reportId) ? lastResp.reportId : payload.reportId);
                    }
                    lastChannelReportResponse = lastResp;
                    Log.i(TAG, String.format(Locale.US,
                            "Channel report succeeded (http=%d, report_id=%s, channels=%d, is_duplicate=%b, attempt=%d/%d)",
                            lastResp.httpStatusCode, lastResp.reportId, payload.channels.size(),
                            lastResp.isDuplicate, attempt, attempts));
                    return lastResp;
                }
                if (lastResp != null
                        && lastResp.httpStatusCode >= 400
                        && lastResp.httpStatusCode < 500
                        && lastResp.httpStatusCode != HttpURLConnection.HTTP_CLIENT_TIMEOUT
                        && lastResp.httpStatusCode != 429) {
                    synchronized (this) {
                        if (payload.reportId.equals(settings.getPendingChannelReportId())) {
                            settings.setPendingChannelReportId(null);
                        }
                    }
                    break;
                }
            } catch (IOException ioEx) {
                Log.w(TAG, String.format(Locale.US,
                        "Network fluctuation during channel report (report_id=%s, attempt=%d/%d): %s",
                        payload.reportId, attempt, attempts, ioEx.getMessage()));
            }

            if (attempt < attempts && retryDelayMs > 0) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return lastResp;
    }

    public void reportDeviceProfileInitAsync(@Nullable ReportCallback callback) {
        if (!reportingInProgress.compareAndSet(false, true)) {
            return;
        }
        final String targetUrl = this.reportEndpointUrl;
        executor.execute(() -> {
            String reportId = getOrCreatePendingReportId();
            try {
                ReportPayload payload = buildDeviceProfileInitPayload(reportId, System.currentTimeMillis());
                ReportResponse response = sendReportWithRetry(targetUrl, payload, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_DELAY_MS);
                if (response != null && response.isSuccess()) {
                    startupReportSucceeded = true;
                    if (callback != null) {
                        mainHandler.post(() -> callback.onSuccess(response));
                    }
                } else {
                    int status = response != null ? response.httpStatusCode : -1;
                    String msg = response != null && !TextUtils.isEmpty(response.message)
                            ? response.message
                            : "Report request failed";
                    if (callback != null) {
                        mainHandler.post(() -> callback.onFailure(reportId, status, msg));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Async device profile report failed (report_id=" + reportId + "): " + t.getMessage());
                if (callback != null) {
                    String err = t.getMessage() != null ? t.getMessage() : "Unknown error";
                    mainHandler.post(() -> callback.onFailure(reportId, -1, err));
                }
            } finally {
                reportingInProgress.set(false);
            }
        });
    }

    /**
     * 同步发送上报请求并在遇到网络波动时以同一 report_id 进行幂等重试
     */
    @Nullable
    public ReportResponse sendReportWithRetry(@NonNull ReportPayload payload,
                                              int maxAttempts,
                                              long retryDelayMs) {
        return sendReportWithRetry(this.reportEndpointUrl, payload, maxAttempts, retryDelayMs);
    }

    @Nullable
    public ReportResponse sendReportWithRetry(@NonNull String targetUrl,
                                              @NonNull ReportPayload payload,
                                              int maxAttempts,
                                              long retryDelayMs) {
        int attempts = Math.max(1, maxAttempts);
        ReportResponse lastResp = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                lastResp = executeHttpPost(targetUrl, payload);
                if (lastResp != null && lastResp.isSuccess()) {
                    String currentPending = settings.getPendingReportId();
                    if (TextUtils.isEmpty(currentPending) || payload.reportId.equals(currentPending)) {
                        settings.setPendingReportId(null);
                    }
                    settings.setLastSuccessReportId(
                            !TextUtils.isEmpty(lastResp.reportId) ? lastResp.reportId : payload.reportId);
                    lastReportResponse = lastResp;
                    startupReportSucceeded = true;
                    lastSucceededDateKey = currentDateKey();
                    Log.i(TAG, String.format(Locale.US,
                            "Report succeeded (http=%d, report_id=%s, is_duplicate=%b, attempt=%d/%d)",
                            lastResp.httpStatusCode, lastResp.reportId, lastResp.isDuplicate, attempt, attempts));
                    return lastResp;
                }
                // 若返回 4xx 客户端格式错误（非 408/429），不再无意义重试，并清理异常的 pending_report_id
                if (lastResp != null
                        && lastResp.httpStatusCode >= 400
                        && lastResp.httpStatusCode < 500
                        && lastResp.httpStatusCode != HttpURLConnection.HTTP_CLIENT_TIMEOUT
                        && lastResp.httpStatusCode != 429) {
                    if (payload.reportId.equals(settings.getPendingReportId())) {
                        settings.setPendingReportId(null);
                    }
                    break;
                }
            } catch (IOException ioEx) {
                Log.w(TAG, String.format(Locale.US,
                        "Network fluctuation during report (report_id=%s, attempt=%d/%d): %s",
                        payload.reportId, attempt, attempts, ioEx.getMessage()));
            }

            if (attempt < attempts && retryDelayMs > 0) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return lastResp;
    }

    /**
     * 执行单次 HTTP POST JSON 上报请求 (/api/v1/report)
     */
    @Nullable
    public static ReportResponse executeHttpPost(@NonNull String targetUrl,
                                                 @NonNull ReportPayload payload) throws IOException {
        String normalizedUrl = normalizeReportUrl(targetUrl);
        String userAgent = payload.env != null ? payload.env.userAgent : null;
        return executeJsonHttpPost(normalizedUrl, payload.toJsonString(),
                payload.reportId, payload.device.deviceId, userAgent);
    }

    /**
     * 执行单次频道列表 HTTP POST JSON 上报请求 (/api/v1/channel)
     */
    @Nullable
    public static ReportResponse executeChannelHttpPost(@NonNull String targetUrl,
                                                        @NonNull ChannelReportPayload payload) throws IOException {
        String normalizedUrl = normalizeChannelReportUrl(targetUrl);
        return executeJsonHttpPost(normalizedUrl, payload.toJsonString(),
                payload.reportId, payload.deviceId, buildUserAgent());
    }

    @Nullable
    private static ReportResponse executeJsonHttpPost(@NonNull String normalizedUrl,
                                                      @NonNull String jsonBody,
                                                      @NonNull String fallbackReportId,
                                                      @NonNull String fallbackDeviceId,
                                                      @Nullable String userAgent) throws IOException {
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(normalizedUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            if (!TextUtils.isEmpty(userAgent)) {
                conn.setRequestProperty("User-Agent", userAgent);
            }
            conn.setFixedLengthStreamingMode(bodyBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int statusCode = conn.getResponseCode();
            InputStream stream = (statusCode >= 200 && statusCode < 400)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String responseText = readStreamAsString(stream);
            ReportResponse parsed = ReportResponse.parse(responseText, statusCode);
            if (parsed != null) {
                return parsed;
            }
            return new ReportResponse(statusCode, -1, responseText != null ? responseText : "",
                    fallbackReportId, fallbackDeviceId, false, "");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Nullable
    private static String readStreamAsString(@Nullable InputStream is) throws IOException {
        if (is == null) {
            return null;
        }
        try (InputStream in = is;
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
