package com.bjunicom.iptv.report;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.repository.ChannelRepository;

/**
 * 频道列表分享与上传管理模块
 *
 * 核心能力：
 * 1. 支持上传前调用服务端接口 (/api/v1/channel-lists/check) 检测是否存在同名及相同属性信息的频道列表；
 * 2. 确认无重名列表后，通过 multipart/form-data 将 M3U 文件流及描述信息（省、市、运营商类型、频道信息、文件名）上传至服务端 (/api/v1/channel-lists/upload)。
 */
public class ChannelListShareManager {
    private static final String TAG = "ChannelListShareManager";

    public static final String CHANNEL_LIST_CHECK_API_URL = "http://104.223.42.150:8080/api/v1/channel-lists/check";
    public static final String CHANNEL_LIST_UPLOAD_API_URL = "http://104.223.42.150:8080/api/v1/channel-lists/upload";
    public static final String CHANNEL_LIST_LIST_API_URL = "http://104.223.42.150:8080/api/v1/channel-lists";

    // 运营商类型常量
    public static final String ISP_TYPE_UNICOM = "联通";
    public static final String ISP_TYPE_MOBILE = "移动";
    public static final String ISP_TYPE_TELECOM = "电信";
    public static final String ISP_TYPE_BROADNET = "广电";
    public static final String ISP_TYPE_OTHER = "其他";

    // 频道列表类型常量（直连、经过代理后的、时移列表）
    public static final String CHANNEL_TYPE_DIRECT = "直连";
    public static final String CHANNEL_TYPE_PROXY = "经过代理后的";
    public static final String CHANNEL_TYPE_TIMESHIFT = "时移列表";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    private static volatile ChannelListShareManager instance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "uTV-ChannelListSharer");
        t.setDaemon(true);
        return t;
    });

    private volatile String checkEndpointUrl = CHANNEL_LIST_CHECK_API_URL;
    private volatile String uploadEndpointUrl = CHANNEL_LIST_UPLOAD_API_URL;
    private volatile String listEndpointUrl = CHANNEL_LIST_LIST_API_URL;
    private volatile CheckResponse lastCheckResponse;
    private volatile UploadResponse lastUploadResponse;
    private volatile ListResponse lastListResponse;

    public interface ShareCallback {
        void onDuplicateDetected(@NonNull UploadSettings settings, @NonNull CheckResponse checkResponse);

        void onUploadSuccess(@NonNull UploadSettings settings, @NonNull UploadResponse uploadResponse);

        void onFailure(@NonNull UploadSettings settings, int httpStatusCode, @NonNull String errorMessage);
    }

    public interface FetchListCallback {
        void onChannelListsFetched(@NonNull List<SharedChannelListItem> items);

        default void onFailure(int httpStatusCode, @NonNull String errorMessage) {
        }
    }

    public interface FetchDetailCallback {
        void onChannelListDetailFetched(@NonNull SharedChannelListItem item);

        default void onFailure(int httpStatusCode, @NonNull String errorMessage) {
        }
    }

    /**
     * 用户上传的频道来源列表项（与北京联通、北京移动、北京电信等内置频道来源并列展示）
     */
    public static class SharedChannelListItem {
        public final long id;
        public final String fileName;
        public final String province;
        public final String city;
        public final String ispType;
        public final String channelType;
        public final String description;
        public final int channelCount;
        public final long fileSize;
        public final String m3uContent;
        public final String createdAt;

        public SharedChannelListItem(long id,
                                     @NonNull String fileName,
                                     @NonNull String province,
                                     @NonNull String city,
                                     @NonNull String ispType,
                                     @NonNull String channelType,
                                     @Nullable String description,
                                     int channelCount,
                                     long fileSize,
                                     @Nullable String m3uContent,
                                     @Nullable String createdAt) {
            this.id = id;
            this.fileName = normalizeFileName(fileName);
            this.province = province != null ? province.trim() : "";
            this.city = city != null ? city.trim() : "";
            this.ispType = ispType != null ? ispType.trim() : "";
            this.channelType = channelType != null ? channelType.trim() : "";
            this.description = description != null ? description.trim() : "";
            this.channelCount = Math.max(0, channelCount);
            this.fileSize = Math.max(0L, fileSize);
            this.m3uContent = m3uContent != null ? m3uContent : "";
            this.createdAt = createdAt != null ? createdAt.trim() : "";
        }

        @NonNull
        public String getSourceKey() {
            return fileName + "|" + province + "|" + city + "|" + ispType + "|" + channelType;
        }

        @NonNull
        public String getRegionAndIspLabel() {
            String region = "";
            if (!TextUtils.isEmpty(province) && !TextUtils.isEmpty(city)) {
                if (province.equals(city) || city.startsWith(province)) {
                    region = city;
                } else if (province.startsWith(city)) {
                    region = province;
                } else {
                    region = province + city;
                }
            } else if (!TextUtils.isEmpty(province)) {
                region = province;
            } else if (!TextUtils.isEmpty(city)) {
                region = city;
            }
            String combined = region + ispType;
            if (!TextUtils.isEmpty(combined)) {
                return combined;
            }
            return !TextUtils.isEmpty(fileName) ? fileName : "用户上传源";
        }

        @NonNull
        public String getShortDisplayName() {
            String regionIsp = getRegionAndIspLabel();
            if (!TextUtils.isEmpty(channelType)) {
                return regionIsp + "·" + channelType;
            }
            return regionIsp;
        }

        @NonNull
        public String getDisplayName() {
            String regionIsp = getRegionAndIspLabel();
            String typeTag = !TextUtils.isEmpty(channelType) ? channelType : "用户上传";
            return String.format(Locale.US, "%s (%s · %s)", regionIsp, typeTag, fileName);
        }

        @NonNull
        public String getOptionTitle() {
            String regionIsp = getRegionAndIspLabel();
            String typeTag = !TextUtils.isEmpty(channelType) ? channelType : "自定义";
            if (channelCount > 0) {
                return String.format(Locale.US, "%s IPTV [用户上传 · %s] (%s · %d 频道)",
                        regionIsp, typeTag, fileName, channelCount);
            }
            return String.format(Locale.US, "%s IPTV [用户上传 · %s] (%s)",
                    regionIsp, typeTag, fileName);
        }

        public boolean hasM3uContent() {
            return !TextUtils.isEmpty(m3uContent) && !m3uContent.trim().isEmpty();
        }

        @NonNull
        public SharedChannelListItem withM3uContent(@Nullable String newContent, int newChannelCount) {
            return new SharedChannelListItem(
                    this.id,
                    this.fileName,
                    this.province,
                    this.city,
                    this.ispType,
                    this.channelType,
                    this.description,
                    newChannelCount > 0 ? newChannelCount : this.channelCount,
                    this.fileSize,
                    newContent,
                    this.createdAt
            );
        }

        @NonNull
        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("file_name", fileName);
                obj.put("province", province);
                obj.put("city", city);
                obj.put("isp_type", ispType);
                obj.put("channel_type", channelType);
                obj.put("description", description);
                obj.put("channel_count", channelCount);
                obj.put("file_size", fileSize);
                if (!TextUtils.isEmpty(m3uContent)) {
                    obj.put("m3u_content", m3uContent);
                }
                if (!TextUtils.isEmpty(createdAt)) {
                    obj.put("created_at", createdAt);
                }
            } catch (Throwable ignored) {
            }
            return obj;
        }

        @Nullable
        public static SharedChannelListItem fromJson(@Nullable JSONObject obj) {
            if (obj == null) {
                return null;
            }
            String fileName = obj.optString("file_name", obj.optString("name", obj.optString("filename", "")));
            if (TextUtils.isEmpty(fileName)) {
                return null;
            }
            long id = obj.optLong("id", 0L);
            String province = obj.optString("province", "");
            String city = obj.optString("city", "");
            String ispType = obj.optString("isp_type", obj.optString("isp", ""));
            String channelType = obj.optString("channel_type", obj.optString("stream_type", ""));
            String description = obj.optString("description", "");
            int channelCount = obj.optInt("channel_count", 0);
            long fileSize = obj.optLong("file_size", 0L);
            String m3uContent = obj.optString("m3u_content", obj.optString("content", ""));
            String createdAt = obj.optString("created_at", "");
            if (channelCount <= 0 && !TextUtils.isEmpty(m3uContent)) {
                channelCount = M3uParser.parseString(m3uContent).size();
            }
            return new SharedChannelListItem(
                    id,
                    fileName,
                    province,
                    city,
                    ispType,
                    channelType,
                    description,
                    channelCount,
                    fileSize,
                    m3uContent,
                    createdAt
            );
        }
    }

    /**
     * 服务端频道列表查询响应结果 (GET /api/v1/channel-lists)
     */
    public static class ListResponse {
        public final int httpStatusCode;
        public final int code;
        public final String message;
        public final List<SharedChannelListItem> items;
        public final int total;

        public ListResponse(int httpStatusCode,
                            int code,
                            @NonNull String message,
                            @NonNull List<SharedChannelListItem> items,
                            int total) {
            this.httpStatusCode = httpStatusCode;
            this.code = code;
            this.message = message;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.total = total;
        }

        public boolean isSuccess() {
            return httpStatusCode == HttpURLConnection.HTTP_OK && code == 0;
        }

        @Nullable
        public static ListResponse parse(@Nullable String jsonText, int httpStatusCode) {
            if (TextUtils.isEmpty(jsonText)) {
                return null;
            }
            try {
                JSONObject root = new JSONObject(jsonText.trim());
                int code = root.optInt("code", -1);
                String message = root.optString("message", "");
                List<SharedChannelListItem> parsedItems = new ArrayList<>();
                int total = 0;

                JSONObject dataObj = root.optJSONObject("data");
                JSONArray itemsArr = null;
                if (dataObj != null) {
                    itemsArr = dataObj.optJSONArray("items");
                    total = dataObj.optInt("total", 0);
                } else {
                    itemsArr = root.optJSONArray("data");
                }
                if (itemsArr != null) {
                    for (int i = 0; i < itemsArr.length(); i++) {
                        SharedChannelListItem item = SharedChannelListItem.fromJson(itemsArr.optJSONObject(i));
                        if (item != null) {
                            parsedItems.add(item);
                        }
                    }
                    if (total <= 0) {
                        total = parsedItems.size();
                    }
                }
                return new ListResponse(httpStatusCode, code, message, parsedItems, total);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to parse channel list response JSON: " + t.getMessage());
                return null;
            }
        }
    }

    /**
     * 频道列表上传设置信息：
     * 包含运营商信息（省、市、运营商类型）、频道信息（直连、经过代理后的、还是时移列表）以及文件名信息
     */
    public static class UploadSettings {
        public final String province;
        public final String city;
        public final String ispType;
        public final String channelType;
        public final String fileName;
        public final String description;

        public UploadSettings(@NonNull String province,
                              @NonNull String city,
                              @NonNull String ispType,
                              @NonNull String channelType,
                              @NonNull String fileName) {
            this(province, city, ispType, channelType, fileName, "");
        }

        public UploadSettings(@NonNull String province,
                              @NonNull String city,
                              @NonNull String ispType,
                              @NonNull String channelType,
                              @NonNull String fileName,
                              @Nullable String description) {
            this.province = province != null ? province.trim() : "";
            this.city = city != null ? city.trim() : "";
            this.ispType = ispType != null ? ispType.trim() : "";
            this.channelType = channelType != null ? channelType.trim() : "";
            this.fileName = normalizeFileName(fileName);
            this.description = description != null ? description.trim() : "";
        }

        @Nullable
        public String validate() {
            if (TextUtils.isEmpty(province)) {
                return "请填写运营商所在省份";
            }
            if (TextUtils.isEmpty(city)) {
                return "请填写运营商所在城市";
            }
            if (TextUtils.isEmpty(ispType)) {
                return "请选择运营商类型";
            }
            if (TextUtils.isEmpty(channelType)) {
                return "请选择频道信息类型（直连 / 经过代理后的 / 时移列表）";
            }
            if (TextUtils.isEmpty(fileName)) {
                return "请填写上传的文件名";
            }
            return null;
        }
    }

    /**
     * 服务端重名与属性检测响应结果
     */
    public static class CheckResponse {
        public final int httpStatusCode;
        public final int code;
        public final String message;
        public final boolean exists;
        public final String fileName;
        public final String province;
        public final String city;
        public final String ispType;
        public final String channelType;
        public final long existingId;

        public CheckResponse(int httpStatusCode,
                             int code,
                             @NonNull String message,
                             boolean exists,
                             @NonNull String fileName,
                             @NonNull String province,
                             @NonNull String city,
                             @NonNull String ispType,
                             @NonNull String channelType,
                             long existingId) {
            this.httpStatusCode = httpStatusCode;
            this.code = code;
            this.message = message;
            this.exists = exists;
            this.fileName = fileName;
            this.province = province;
            this.city = city;
            this.ispType = ispType;
            this.channelType = channelType;
            this.existingId = existingId;
        }

        public boolean isSuccess() {
            return httpStatusCode == HttpURLConnection.HTTP_OK && code == 0;
        }

        @Nullable
        public static CheckResponse parse(@Nullable String jsonText, int httpStatusCode) {
            if (TextUtils.isEmpty(jsonText)) {
                return null;
            }
            try {
                JSONObject root = new JSONObject(jsonText.trim());
                int code = root.optInt("code", -1);
                String message = root.optString("message", "");
                JSONObject data = root.optJSONObject("data");
                boolean exists = false;
                String fileName = "";
                String province = "";
                String city = "";
                String ispType = "";
                String channelType = "";
                long existingId = 0L;
                if (data != null) {
                    exists = data.optBoolean("exists", data.optBoolean("is_duplicate", false));
                    fileName = data.optString("file_name", "");
                    province = data.optString("province", "");
                    city = data.optString("city", "");
                    ispType = data.optString("isp_type", "");
                    channelType = data.optString("channel_type", "");
                    existingId = data.optLong("existing_id", 0L);
                }
                return new CheckResponse(
                        httpStatusCode,
                        code,
                        message,
                        exists,
                        fileName,
                        province,
                        city,
                        ispType,
                        channelType,
                        existingId
                );
            } catch (Throwable t) {
                Log.w(TAG, "Failed to parse check response JSON: " + t.getMessage());
                return null;
            }
        }
    }

    /**
     * 服务端频道列表上传响应结果
     */
    public static class UploadResponse {
        public final int httpStatusCode;
        public final int code;
        public final String message;
        public final long id;
        public final String fileName;
        public final String province;
        public final String city;
        public final String ispType;
        public final String channelType;
        public final int channelCount;
        public final long fileSize;
        public final String createdAt;

        public UploadResponse(int httpStatusCode,
                              int code,
                              @NonNull String message,
                              long id,
                              @NonNull String fileName,
                              @NonNull String province,
                              @NonNull String city,
                              @NonNull String ispType,
                              @NonNull String channelType,
                              int channelCount,
                              long fileSize,
                              @NonNull String createdAt) {
            this.httpStatusCode = httpStatusCode;
            this.code = code;
            this.message = message;
            this.id = id;
            this.fileName = fileName;
            this.province = province;
            this.city = city;
            this.ispType = ispType;
            this.channelType = channelType;
            this.channelCount = channelCount;
            this.fileSize = fileSize;
            this.createdAt = createdAt;
        }

        public boolean isSuccess() {
            return code == 0 && (httpStatusCode == HttpURLConnection.HTTP_CREATED
                    || httpStatusCode == HttpURLConnection.HTTP_OK);
        }

        public boolean isConflict() {
            return httpStatusCode == HttpURLConnection.HTTP_CONFLICT || code == 40900;
        }

        @Nullable
        public static UploadResponse parse(@Nullable String jsonText, int httpStatusCode) {
            if (TextUtils.isEmpty(jsonText)) {
                return null;
            }
            try {
                JSONObject root = new JSONObject(jsonText.trim());
                int code = root.optInt("code", -1);
                String message = root.optString("message", "");
                JSONObject data = root.optJSONObject("data");
                long id = 0L;
                String fileName = "";
                String province = "";
                String city = "";
                String ispType = "";
                String channelType = "";
                int channelCount = 0;
                long fileSize = 0L;
                String createdAt = "";
                if (data != null) {
                    id = data.optLong("id", 0L);
                    fileName = data.optString("file_name", "");
                    province = data.optString("province", "");
                    city = data.optString("city", "");
                    ispType = data.optString("isp_type", "");
                    channelType = data.optString("channel_type", "");
                    channelCount = data.optInt("channel_count", 0);
                    fileSize = data.optLong("file_size", 0L);
                    createdAt = data.optString("created_at", "");
                }
                return new UploadResponse(
                        httpStatusCode,
                        code,
                        message,
                        id,
                        fileName,
                        province,
                        city,
                        ispType,
                        channelType,
                        channelCount,
                        fileSize,
                        createdAt
                );
            } catch (Throwable t) {
                Log.w(TAG, "Failed to parse upload response JSON: " + t.getMessage());
                return null;
            }
        }
    }

    /**
     * 先检测重名再上传的完整流程结果封装
     */
    public static class ShareResult {
        @Nullable
        public final CheckResponse checkResponse;
        @Nullable
        public final UploadResponse uploadResponse;
        public final boolean duplicateDetected;
        public final boolean uploaded;
        @NonNull
        public final String errorMessage;

        public ShareResult(@Nullable CheckResponse checkResponse,
                           @Nullable UploadResponse uploadResponse,
                           boolean duplicateDetected,
                           boolean uploaded,
                           @NonNull String errorMessage) {
            this.checkResponse = checkResponse;
            this.uploadResponse = uploadResponse;
            this.duplicateDetected = duplicateDetected;
            this.uploaded = uploaded;
            this.errorMessage = errorMessage;
        }
    }

    public static ChannelListShareManager getInstance(@NonNull Context context) {
        Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (instance == null || instance.appContext != appCtx) {
            synchronized (ChannelListShareManager.class) {
                if (instance == null || instance.appContext != appCtx) {
                    instance = new ChannelListShareManager(appCtx);
                }
            }
        }
        return instance;
    }

    private ChannelListShareManager(@NonNull Context appContext) {
        this.appContext = appContext;
    }

    @NonNull
    public String getCheckEndpointUrl() {
        return checkEndpointUrl;
    }

    public void setCheckEndpointUrl(@Nullable String url) {
        this.checkEndpointUrl = TextUtils.isEmpty(url)
                ? CHANNEL_LIST_CHECK_API_URL
                : DataReportManager.normalizeReportUrl(url);
    }

    @NonNull
    public String getUploadEndpointUrl() {
        return uploadEndpointUrl;
    }

    public void setUploadEndpointUrl(@Nullable String url) {
        this.uploadEndpointUrl = TextUtils.isEmpty(url)
                ? CHANNEL_LIST_UPLOAD_API_URL
                : DataReportManager.normalizeReportUrl(url);
    }

    @NonNull
    public String getListEndpointUrl() {
        return listEndpointUrl;
    }

    public void setListEndpointUrl(@Nullable String url) {
        this.listEndpointUrl = TextUtils.isEmpty(url)
                ? CHANNEL_LIST_LIST_API_URL
                : DataReportManager.normalizeReportUrl(url);
    }

    @Nullable
    public ListResponse getLastListResponse() {
        return lastListResponse;
    }

    @Nullable
    public CheckResponse getLastCheckResponse() {
        return lastCheckResponse;
    }

    @Nullable
    public UploadResponse getLastUploadResponse() {
        return lastUploadResponse;
    }

    @NonNull
    public static String normalizeFileName(@Nullable String rawName) {
        if (TextUtils.isEmpty(rawName)) {
            return "";
        }
        String trimmed = rawName.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        if (slash >= 0 && slash < trimmed.length() - 1) {
            trimmed = trimmed.substring(slash + 1).trim();
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".m3u") && !lower.endsWith(".m3u8")) {
            trimmed = trimmed + ".m3u";
        }
        return trimmed;
    }

    /**
     * 根据当前运营商配置推断默认的运营商类型
     */
    @NonNull
    public static String inferDefaultIspType(int ispProvider) {
        switch (ispProvider) {
            case IptvSettings.ISP_BEIJING_MOBILE:
                return ISP_TYPE_MOBILE;
            case IptvSettings.ISP_BEIJING_TELECOM:
                return ISP_TYPE_TELECOM;
            case IptvSettings.ISP_BEIJING_UNICOM:
            default:
                return ISP_TYPE_UNICOM;
        }
    }

    /**
     * 根据 M3U 内容及频道列表特征推断默认的频道信息类型（直连、经过代理后的、还是时移列表）
     */
    @NonNull
    public static String inferDefaultChannelType(@Nullable String m3uContent,
                                                 @Nullable List<IptvChannel> channels,
                                                 @Nullable IptvSettings settings) {
        if (!TextUtils.isEmpty(m3uContent)) {
            String lower = m3uContent.toLowerCase(Locale.ROOT);
            if (lower.contains("catchup-source=") || lower.contains("playseek=")) {
                return CHANNEL_TYPE_TIMESHIFT;
            }
            if (lower.contains("/rtp/") || lower.contains("/udp/")) {
                return CHANNEL_TYPE_PROXY;
            }
        }
        if (channels != null) {
            for (IptvChannel ch : channels) {
                if (ch != null && ch.supportsTimeshift()) {
                    return CHANNEL_TYPE_TIMESHIFT;
                }
            }
            for (IptvChannel ch : channels) {
                if (ch != null && ch.getRawUrl() != null) {
                    String u = ch.getRawUrl().toLowerCase(Locale.ROOT);
                    if (u.startsWith("http://") && (u.contains("/rtp/") || u.contains("/udp/"))) {
                        return CHANNEL_TYPE_PROXY;
                    }
                }
            }
        }
        return CHANNEL_TYPE_DIRECT;
    }

    /**
     * 先调用服务端接口判断是否存在同名同属性的列表，确认无重名的列表后开始上传 M3U 文件流
     */
    @NonNull
    public ShareResult checkAndUploadSync(@NonNull UploadSettings settings,
                                          @NonNull String m3uContent,
                                          int channelCount) {
        String validationErr = settings.validate();
        if (validationErr != null) {
            return new ShareResult(null, null, false, false, validationErr);
        }
        if (TextUtils.isEmpty(m3uContent) || m3uContent.trim().isEmpty()) {
            return new ShareResult(null, null, false, false, "M3U 频道列表内容为空");
        }

        // 1. 先调用服务端接口判断是否存在同名的列表
        CheckResponse checkResp;
        try {
            checkResp = executeCheckRequest(this.checkEndpointUrl, settings);
            this.lastCheckResponse = checkResp;
        } catch (IOException e) {
            String err = e.getMessage() != null ? e.getMessage() : "网络请求失败";
            return new ShareResult(null, null, false, false, "检测重名列表失败: " + err);
        }

        if (checkResp == null || !checkResp.isSuccess()) {
            String msg = (checkResp != null && !TextUtils.isEmpty(checkResp.message))
                    ? checkResp.message
                    : "检测重名列表接口返回异常";
            return new ShareResult(checkResp, null, false, false, msg);
        }

        if (checkResp.exists) {
            return new ShareResult(
                    checkResp,
                    null,
                    true,
                    false,
                    "服务器已存在相同名称和属性信息的频道列表: " + settings.fileName
            );
        }

        // 2. 确认无重名的列表后开始上传 M3U 文件流与频道列表描述信息
        int resolvedCount = channelCount > 0
                ? channelCount
                : M3uParser.parseString(m3uContent).size();
        String deviceId = DataReportManager.getInstance(appContext).getOrCreateDeviceId();

        UploadResponse uploadResp;
        try {
            byte[] bytes = m3uContent.getBytes(StandardCharsets.UTF_8);
            try (InputStream is = new ByteArrayInputStream(bytes)) {
                uploadResp = executeUploadRequest(
                        this.uploadEndpointUrl,
                        settings,
                        is,
                        resolvedCount,
                        deviceId
                );
            }
            this.lastUploadResponse = uploadResp;
        } catch (IOException e) {
            String err = e.getMessage() != null ? e.getMessage() : "网络上传失败";
            return new ShareResult(checkResp, null, false, false, "上传频道列表失败: " + err);
        }

        if (uploadResp != null && uploadResp.isSuccess()) {
            SharedChannelListItem uploadedItem = new SharedChannelListItem(
                    uploadResp.id,
                    !TextUtils.isEmpty(uploadResp.fileName) ? uploadResp.fileName : settings.fileName,
                    !TextUtils.isEmpty(uploadResp.province) ? uploadResp.province : settings.province,
                    !TextUtils.isEmpty(uploadResp.city) ? uploadResp.city : settings.city,
                    !TextUtils.isEmpty(uploadResp.ispType) ? uploadResp.ispType : settings.ispType,
                    !TextUtils.isEmpty(uploadResp.channelType) ? uploadResp.channelType : settings.channelType,
                    settings.description,
                    uploadResp.channelCount > 0 ? uploadResp.channelCount : resolvedCount,
                    uploadResp.fileSize > 0 ? uploadResp.fileSize : m3uContent.getBytes(StandardCharsets.UTF_8).length,
                    m3uContent,
                    uploadResp.createdAt
            );
            try {
                ChannelRepository.getInstance(appContext).addOrUpdateUploadedChannelSource(uploadedItem);
            } catch (Throwable ignored) {
            }
            return new ShareResult(checkResp, uploadResp, false, true, "");
        }
        if (uploadResp != null && uploadResp.isConflict()) {
            return new ShareResult(
                    checkResp,
                    uploadResp,
                    true,
                    false,
                    "服务器已存在相同名称和属性信息的频道列表: " + settings.fileName
            );
        }

        String errMsg = (uploadResp != null && !TextUtils.isEmpty(uploadResp.message))
                ? uploadResp.message
                : "上传频道列表失败";
        return new ShareResult(checkResp, uploadResp, false, false, errMsg);
    }

    /**
     * 异步执行「先检测同名列表 -> 确认无重名后上传 M3U 文件流」
     */
    public void checkAndUploadAsync(@NonNull UploadSettings settings,
                                    @NonNull String m3uContent,
                                    int channelCount,
                                    @Nullable ShareCallback callback) {
        executor.execute(() -> {
            ShareResult result = checkAndUploadSync(settings, m3uContent, channelCount);
            if (callback == null) {
                return;
            }
            mainHandler.post(() -> {
                if (result.duplicateDetected && result.checkResponse != null) {
                    callback.onDuplicateDetected(settings, result.checkResponse);
                } else if (result.uploaded && result.uploadResponse != null) {
                    callback.onUploadSuccess(settings, result.uploadResponse);
                } else {
                    int status = result.uploadResponse != null
                            ? result.uploadResponse.httpStatusCode
                            : (result.checkResponse != null ? result.checkResponse.httpStatusCode : -1);
                    callback.onFailure(settings, status, result.errorMessage);
                }
            });
        });
    }

    /**
     * 调用服务端 GET /api/v1/channel-lists/check 接口检测是否存在相同名字和属性信息的频道列表
     */
    @Nullable
    public static CheckResponse executeCheckRequest(@NonNull String targetCheckUrl,
                                                    @NonNull UploadSettings settings) throws IOException {
        String sep = targetCheckUrl.contains("?") ? "&" : "?";
        String queryUrl = targetCheckUrl + sep
                + "file_name=" + encodeParam(settings.fileName)
                + "&province=" + encodeParam(settings.province)
                + "&city=" + encodeParam(settings.city)
                + "&isp_type=" + encodeParam(settings.ispType)
                + "&channel_type=" + encodeParam(settings.channelType);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(queryUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", DataReportManager.buildUserAgent());

            int statusCode = conn.getResponseCode();
            InputStream stream = (statusCode >= 200 && statusCode < 400)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String responseText = readStreamAsString(stream);
            CheckResponse parsed = CheckResponse.parse(responseText, statusCode);
            if (parsed != null) {
                return parsed;
            }
            return new CheckResponse(
                    statusCode,
                    -1,
                    responseText != null ? responseText : "",
                    false,
                    settings.fileName,
                    settings.province,
                    settings.city,
                    settings.ispType,
                    settings.channelType,
                    0L
            );
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 调用服务端 POST /api/v1/channel-lists/upload 接口，通过 multipart/form-data 发送 M3U 文件流与频道列表描述信息
     */
    @Nullable
    public static UploadResponse executeUploadRequest(@NonNull String targetUploadUrl,
                                                      @NonNull UploadSettings settings,
                                                      @NonNull InputStream m3uStream,
                                                      int channelCount,
                                                      @Nullable String deviceId) throws IOException {
        String boundary = "----uTVBoundary" + UUID.randomUUID().toString().replace("-", "");
        String lineEnd = "\r\n";
        String twoHyphens = "--";

        ByteArrayOutputStream multipartBody = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(multipartBody);

        writeFormField(dos, boundary, "file_name", settings.fileName);
        writeFormField(dos, boundary, "province", settings.province);
        writeFormField(dos, boundary, "city", settings.city);
        writeFormField(dos, boundary, "isp_type", settings.ispType);
        writeFormField(dos, boundary, "channel_type", settings.channelType);
        writeFormField(dos, boundary, "channel_count", String.valueOf(Math.max(0, channelCount)));
        if (!TextUtils.isEmpty(deviceId)) {
            writeFormField(dos, boundary, "device_id", deviceId);
        }
        String desc = !TextUtils.isEmpty(settings.description)
                ? settings.description
                : String.format(Locale.US, "%s-%s-%s-%s",
                settings.province, settings.city, settings.ispType, settings.channelType);
        writeFormField(dos, boundary, "description", desc);

        // 写入 M3U 文件流分片
        dos.writeBytes(twoHyphens + boundary + lineEnd);
        String disposition = "Content-Disposition: form-data; name=\"file\"; filename=\""
                + settings.fileName.replace("\"", "") + "\"" + lineEnd;
        dos.write(disposition.getBytes(StandardCharsets.UTF_8));
        dos.writeBytes("Content-Type: audio/x-mpegurl" + lineEnd);
        dos.writeBytes(lineEnd);

        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = m3uStream.read(buffer)) != -1) {
            dos.write(buffer, 0, bytesRead);
        }
        dos.writeBytes(lineEnd);
        dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
        dos.flush();

        byte[] payloadBytes = multipartBody.toByteArray();

        HttpURLConnection conn = null;
        try {
            URL url = new URL(targetUploadUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", DataReportManager.buildUserAgent());
            conn.setFixedLengthStreamingMode(payloadBytes.length);

            conn.getOutputStream().write(payloadBytes);
            conn.getOutputStream().flush();

            int statusCode = conn.getResponseCode();
            InputStream respStream = (statusCode >= 200 && statusCode < 400)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String responseText = readStreamAsString(respStream);
            UploadResponse parsed = UploadResponse.parse(responseText, statusCode);
            if (parsed != null) {
                return parsed;
            }
            return new UploadResponse(
                    statusCode,
                    -1,
                    responseText != null ? responseText : "",
                    0L,
                    settings.fileName,
                    settings.province,
                    settings.city,
                    settings.ispType,
                    settings.channelType,
                    channelCount,
                    0L,
                    ""
            );
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 同步从服务端拉取用户上传的频道来源列表 (/api/v1/channel-lists) 并更新至本地仓库
     */
    @NonNull
    public List<SharedChannelListItem> fetchUploadedChannelListsSync() {
        try {
            ListResponse resp = executeListRequest(this.listEndpointUrl);
            this.lastListResponse = resp;
            if (resp != null && resp.isSuccess()) {
                ChannelRepository repo = ChannelRepository.getInstance(appContext);
                repo.mergeServerUploadedChannelSources(resp.items);
                return repo.getUploadedChannelSources();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to fetch uploaded channel lists: " + t.getMessage());
        }
        return ChannelRepository.getInstance(appContext).getUploadedChannelSources();
    }

    /**
     * 异步从服务端拉取用户上传的频道来源列表
     */
    public void fetchUploadedChannelListsAsync(@Nullable FetchListCallback callback) {
        executor.execute(() -> {
            try {
                ListResponse resp = executeListRequest(this.listEndpointUrl);
                this.lastListResponse = resp;
                if (resp != null && resp.isSuccess()) {
                    ChannelRepository repo = ChannelRepository.getInstance(appContext);
                    repo.mergeServerUploadedChannelSources(resp.items);
                    List<SharedChannelListItem> merged = repo.getUploadedChannelSources();
                    if (callback != null) {
                        mainHandler.post(() -> callback.onChannelListsFetched(merged));
                    }
                } else if (callback != null) {
                    int status = resp != null ? resp.httpStatusCode : -1;
                    String msg = (resp != null && !TextUtils.isEmpty(resp.message))
                            ? resp.message
                            : "获取用户上传的频道来源列表失败";
                    mainHandler.post(() -> callback.onFailure(status, msg));
                }
            } catch (Throwable t) {
                if (callback != null) {
                    String err = t.getMessage() != null ? t.getMessage() : "网络请求失败";
                    mainHandler.post(() -> callback.onFailure(-1, err));
                }
            }
        });
    }

    /**
     * 同步按 ID 拉取单个用户上传频道列表的完整内容 (/api/v1/channel-lists/:id)
     */
    @Nullable
    public SharedChannelListItem fetchUploadedChannelListDetailSync(long id) {
        if (id <= 0) {
            return null;
        }
        try {
            SharedChannelListItem item = executeGetDetailRequest(this.listEndpointUrl, id);
            if (item != null && item.hasM3uContent()) {
                ChannelRepository.getInstance(appContext).addOrUpdateUploadedChannelSource(item);
            }
            return item;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to fetch uploaded channel list detail id=" + id + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * 异步按 ID 拉取单个用户上传频道列表的完整内容
     */
    public void fetchUploadedChannelListDetailAsync(long id, @Nullable FetchDetailCallback callback) {
        executor.execute(() -> {
            SharedChannelListItem item = fetchUploadedChannelListDetailSync(id);
            if (callback == null) {
                return;
            }
            mainHandler.post(() -> {
                if (item != null && item.hasM3uContent()) {
                    callback.onChannelListDetailFetched(item);
                } else {
                    callback.onFailure(-1, "获取用户上传的频道列表内容失败");
                }
            });
        });
    }

    /**
     * 调用服务端 GET /api/v1/channel-lists 接口查询用户上传的频道来源列表
     */
    @Nullable
    public static ListResponse executeListRequest(@NonNull String targetListUrl) throws IOException {
        String sep = targetListUrl.contains("?") ? "&" : "?";
        String queryUrl = targetListUrl + sep + "page=1&page_size=100";

        HttpURLConnection conn = null;
        try {
            URL url = new URL(queryUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", DataReportManager.buildUserAgent());

            int statusCode = conn.getResponseCode();
            InputStream stream = (statusCode >= 200 && statusCode < 400)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String responseText = readStreamAsString(stream);
            ListResponse parsed = ListResponse.parse(responseText, statusCode);
            if (parsed != null) {
                return parsed;
            }
            return new ListResponse(
                    statusCode,
                    -1,
                    responseText != null ? responseText : "",
                    Collections.emptyList(),
                    0
            );
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 调用服务端 GET /api/v1/channel-lists/:id 接口获取指定频道来源的完整 M3U 内容
     */
    @Nullable
    public static SharedChannelListItem executeGetDetailRequest(@NonNull String targetListUrl,
                                                                long id) throws IOException {
        String base = targetListUrl;
        int qIdx = base.indexOf('?');
        if (qIdx >= 0) {
            base = base.substring(0, qIdx);
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String detailUrl = base + "/" + id;

        HttpURLConnection conn = null;
        try {
            URL url = new URL(detailUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", DataReportManager.buildUserAgent());

            int statusCode = conn.getResponseCode();
            InputStream stream = (statusCode >= 200 && statusCode < 400)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String responseText = readStreamAsString(stream);
            if (TextUtils.isEmpty(responseText)) {
                return null;
            }
            try {
                JSONObject root = new JSONObject(responseText.trim());
                if (root.optInt("code", -1) != 0) {
                    return null;
                }
                JSONObject data = root.optJSONObject("data");
                return SharedChannelListItem.fromJson(data);
            } catch (Throwable ignored) {
                return null;
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void writeFormField(DataOutputStream dos,
                                       String boundary,
                                       String name,
                                       String value) throws IOException {
        String lineEnd = "\r\n";
        dos.writeBytes("--" + boundary + lineEnd);
        dos.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"" + lineEnd);
        dos.writeBytes("Content-Type: text/plain; charset=UTF-8" + lineEnd);
        dos.writeBytes(lineEnd);
        if (value != null) {
            dos.write(value.getBytes(StandardCharsets.UTF_8));
        }
        dos.writeBytes(lineEnd);
    }

    private static String encodeParam(String val) {
        if (val == null) {
            return "";
        }
        try {
            return URLEncoder.encode(val, "UTF-8");
        } catch (Exception e) {
            return val;
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
