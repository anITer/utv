package com.bjunicom.iptv.repository;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.report.ChannelListShareManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 频道数据仓库
 * 负责管理：
 * 1. 内置北京联通 / 北京移动 / 北京电信 IPTV 频道列表
 *    - 北京联通: bj-unicom-iptv.m3u / bj-unicom-iptv-all.m3u
 *    - 北京移动: bj-mobile-iptv.m3u / bj-mobile-iptv-all.m3u
 *    - 北京电信: bj-telecom-iptv.m3u
 * 2. 动态扫描与 check_iptv_m3u.sh 生成的 modified_iptv*.m3u 合并及持久化
 * 3. 分组索引 ([高清]央视, [高清]卫视, [高清]北京, [高清]北京IPTV, [高清]NewTV, [高清]地方, [高清]购物, 4K, 少儿, 标清, 动态扫描发现, 收藏)
 */
public class ChannelRepository {
    public static final String ASSET_UNICOM_LATEST = "bj-unicom-iptv.m3u";
    public static final String ASSET_UNICOM_ALL = "bj-unicom-iptv-all.m3u";
    public static final String ASSET_MOBILE_LATEST = "bj-mobile-iptv.m3u";
    public static final String ASSET_MOBILE_ALL = "bj-mobile-iptv-all.m3u";
    public static final String ASSET_TELECOM_ALL = "bj-telecom-iptv.m3u";

    private static final String CUSTOM_SCANNED_M3U_UNICOM = "modified_iptv.m3u";
    private static final String CUSTOM_SCANNED_M3U_MOBILE = "modified_iptv_mobile.m3u";
    private static final String CUSTOM_SCANNED_M3U_TELECOM = "modified_iptv_telecom.m3u";
    private static final String CUSTOM_SCANNED_M3U_ALL = "modified_iptv_all.m3u";
    private static final String CUSTOM_SCANNED_M3U_CUSTOM = "modified_iptv_custom.m3u";
    private static final String CUSTOM_SCANNED_M3U_UPLOADED = "modified_iptv_uploaded.m3u";
    public static final String CUSTOM_UPLOADED_M3U_FILE = "custom_uploaded_iptv.m3u";

    public static final String GROUP_ALL = "全部频道";
    public static final String GROUP_ISP_UNICOM = "北京联通";
    public static final String GROUP_ISP_MOBILE = "北京移动";
    public static final String GROUP_ISP_TELECOM = "北京电信";
    public static final String GROUP_ISP_CUSTOM = "自定义源";
    public static final String GROUP_FAVORITES = "收藏频道";
    public static final String GROUP_4K = "4K";
    public static final String GROUP_HD_CCTV = "[高清]央视";
    public static final String GROUP_HD_SATELLITE = "[高清]卫视";
    public static final String GROUP_HD_BJ = "[高清]北京";
    public static final String GROUP_HD_IPTV = "[高清]北京IPTV";
    public static final String GROUP_HD_NEWTV = "[高清]NewTV";
    public static final String GROUP_HD_LOCAL = "[高清]地方";
    public static final String GROUP_HD_SHOPPING = "[高清]购物";
    public static final String GROUP_KIDS = "少儿";
    public static final String GROUP_SD_ALL = "标清频道";
    public static final String GROUP_DISCOVERED = "动态扫描发现";

    public interface OnChannelsChangedListener {
        void onChannelsChanged();

        default void onChannelsImportedAndPlayFirst() {
            onChannelsChanged();
        }
    }

    private static volatile ChannelRepository instance;

    private final Context appContext;
    private final IptvSettings settings;
    private final List<IptvChannel> allChannels = new ArrayList<>();
    private final Map<String, IptvChannel> knownMulticastMap = new LinkedHashMap<>();
    private final List<ChannelListShareManager.SharedChannelListItem> uploadedChannelSources = new ArrayList<>();
    private final List<OnChannelsChangedListener> listeners = new CopyOnWriteArrayList<>();
    private volatile File lastSavedLocalM3uFile;

    private ChannelRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.settings = new IptvSettings(appContext);
        loadPersistedUploadedChannelSources();
        reloadChannels();
    }

    public static ChannelRepository getInstance(Context context) {
        Context appCtx = context.getApplicationContext();
        if (instance == null || instance.appContext != appCtx) {
            synchronized (ChannelRepository.class) {
                if (instance == null || instance.appContext != appCtx) {
                    instance = new ChannelRepository(appCtx);
                }
            }
        }
        return instance;
    }

    public void addOnChannelsChangedListener(OnChannelsChangedListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeOnChannelsChangedListener(OnChannelsChangedListener listener) {
        listeners.remove(listener);
    }

    public void notifyChannelsChanged() {
        for (OnChannelsChangedListener l : listeners) {
            try {
                l.onChannelsChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    public void notifyChannelsImportedAndPlayFirst() {
        for (OnChannelsChangedListener l : listeners) {
            try {
                l.onChannelsImportedAndPlayFirst();
            } catch (Throwable ignored) {
            }
        }
    }

    public File getLastSavedLocalM3uFile() {
        return lastSavedLocalM3uFile;
    }

    public synchronized void reloadChannels() {
        allChannels.clear();
        knownMulticastMap.clear();

        int ispProvider = settings.getIspProvider();
        boolean includeAll = settings.isIncludeAllRepoChannels();
        List<IptvChannel> baseList = new ArrayList<>();

        // 若用户曾通过扫描后「立即应用」替换了当前运营商的默认 M3U，则优先直接加载替换后的默认 M3U 内容
        File appliedDefaultFile = getAppliedDefaultM3uFile();
        List<IptvChannel> appliedDefaultList = loadAppliedDefaultWithIsp(appliedDefaultFile, ispProvider);

        if (!appliedDefaultList.isEmpty()) {
            baseList.addAll(appliedDefaultList);
        } else if (ispProvider == IptvSettings.ISP_BEIJING_MOBILE) {
            String assetName = includeAll ? ASSET_MOBILE_ALL : ASSET_MOBILE_LATEST;
            baseList.addAll(loadFromAssetsWithIsp(assetName, GROUP_ISP_MOBILE, false));
        } else if (ispProvider == IptvSettings.ISP_BEIJING_TELECOM) {
            baseList.addAll(loadFromAssetsWithIsp(ASSET_TELECOM_ALL, GROUP_ISP_TELECOM, false));
        } else if (ispProvider == IptvSettings.ISP_ALL_MERGED) {
            String unicomAsset = includeAll ? ASSET_UNICOM_ALL : ASSET_UNICOM_LATEST;
            String mobileAsset = includeAll ? ASSET_MOBILE_ALL : ASSET_MOBILE_LATEST;
            baseList.addAll(loadFromAssetsWithIsp(unicomAsset, GROUP_ISP_UNICOM, true));
            baseList.addAll(loadFromAssetsWithIsp(mobileAsset, GROUP_ISP_MOBILE, true));
            baseList.addAll(loadFromAssetsWithIsp(ASSET_TELECOM_ALL, GROUP_ISP_TELECOM, true));
            if (hasCustomUploadedM3u()) {
                baseList.addAll(loadCustomUploadedWithIsp(true));
            }
            for (ChannelListShareManager.SharedChannelListItem item : uploadedChannelSources) {
                baseList.addAll(loadFromUploadedSourceItem(item, true));
            }
        } else if (ispProvider == IptvSettings.ISP_CUSTOM_M3U) {
            baseList.addAll(loadCustomUploadedWithIsp(false));
        } else if (ispProvider == IptvSettings.ISP_USER_UPLOADED_LIST) {
            ChannelListShareManager.SharedChannelListItem selected = getSelectedUploadedChannelSource();
            if (selected != null) {
                baseList.addAll(loadFromUploadedSourceItem(selected, false));
            }
        } else {
            String assetName = includeAll ? ASSET_UNICOM_ALL : ASSET_UNICOM_LATEST;
            baseList.addAll(loadFromAssetsWithIsp(assetName, GROUP_ISP_UNICOM, false));
        }

        Set<String> favorites = settings.getFavoriteUrls();

        int idx = 1;
        for (IptvChannel ch : baseList) {
            ch.setIndex(idx++);
            ch.setFavorite(favorites.contains(ch.getRawUrl()));
            allChannels.add(ch);
            if (!TextUtils.isEmpty(ch.getMulticastKey())) {
                knownMulticastMap.put(ch.getMulticastKey(), ch);
            }
        }

        // 同时加载联通与电信全量组播字典以便扫描器识别历史频道名称
        for (IptvChannel ch : loadFromAssets(ASSET_UNICOM_ALL)) {
            if (!TextUtils.isEmpty(ch.getMulticastKey()) && !knownMulticastMap.containsKey(ch.getMulticastKey())) {
                ch.setIspName(GROUP_ISP_UNICOM);
                knownMulticastMap.put(ch.getMulticastKey(), ch);
            }
        }
        for (IptvChannel ch : loadFromAssets(ASSET_TELECOM_ALL)) {
            if (!TextUtils.isEmpty(ch.getMulticastKey()) && !knownMulticastMap.containsKey(ch.getMulticastKey())) {
                ch.setIspName(GROUP_ISP_TELECOM);
                knownMulticastMap.put(ch.getMulticastKey(), ch);
            }
        }
    }

    public File getAppliedDefaultM3uFile() {
        return new File(getScanRootDir(), getCustomScannedFileName());
    }

    private String getIspGroupNameForProvider(int ispProvider) {
        switch (ispProvider) {
            case IptvSettings.ISP_BEIJING_MOBILE:
                return GROUP_ISP_MOBILE;
            case IptvSettings.ISP_BEIJING_TELECOM:
                return GROUP_ISP_TELECOM;
            case IptvSettings.ISP_CUSTOM_M3U:
                return GROUP_ISP_CUSTOM;
            case IptvSettings.ISP_USER_UPLOADED_LIST:
                return settings.getSelectedUploadedSourceShortName();
            case IptvSettings.ISP_BEIJING_UNICOM:
            default:
                return GROUP_ISP_UNICOM;
        }
    }

    private List<IptvChannel> loadAppliedDefaultWithIsp(File file, int ispProvider) {
        if (file == null || !file.exists() || file.length() == 0) {
            return new ArrayList<>();
        }
        try (InputStream is = new FileInputStream(file)) {
            List<IptvChannel> list = M3uParser.parse(is);
            if (list.isEmpty()) {
                return list;
            }
            String defaultIspName = getIspGroupNameForProvider(ispProvider);
            for (IptvChannel ch : list) {
                ch.setConnected(true);
                if (TextUtils.isEmpty(ch.getIspName())) {
                    ch.setIspName(defaultIspName);
                }
                List<String> groups = new ArrayList<>(ch.getGroups());
                if (!groups.contains(defaultIspName) && ispProvider != IptvSettings.ISP_ALL_MERGED) {
                    groups.add(defaultIspName);
                }
                ch.setGroups(groups);
            }
            return list;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private String getCustomScannedFileName() {
        switch (settings.getIspProvider()) {
            case IptvSettings.ISP_BEIJING_MOBILE:
                return CUSTOM_SCANNED_M3U_MOBILE;
            case IptvSettings.ISP_BEIJING_TELECOM:
                return CUSTOM_SCANNED_M3U_TELECOM;
            case IptvSettings.ISP_ALL_MERGED:
                return CUSTOM_SCANNED_M3U_ALL;
            case IptvSettings.ISP_CUSTOM_M3U:
                return CUSTOM_SCANNED_M3U_CUSTOM;
            case IptvSettings.ISP_USER_UPLOADED_LIST:
                return CUSTOM_SCANNED_M3U_UPLOADED;
            case IptvSettings.ISP_BEIJING_UNICOM:
            default:
                return CUSTOM_SCANNED_M3U_UNICOM;
        }
    }

    public File getCustomUploadedM3uFile() {
        return new File(getScanRootDir(), CUSTOM_UPLOADED_M3U_FILE);
    }

    public boolean hasCustomUploadedM3u() {
        File file = getCustomUploadedM3uFile();
        return file.exists() && file.length() > 0;
    }

    public String readCustomUploadedM3uContent() {
        File file = getCustomUploadedM3uFile();
        if (!file.exists()) {
            return "";
        }
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int read = is.read(buf);
            if (read > 0) {
                return new String(buf, 0, read, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    private List<IptvChannel> loadCustomUploadedWithIsp(boolean appendIspToDisplay) {
        File file = getCustomUploadedM3uFile();
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try (InputStream is = new FileInputStream(file)) {
            M3uParser.M3uInspectionReport report = M3uParser.inspectAndParse(is, settings.getCustomM3uName());
            List<IptvChannel> list = report.channels;
            for (IptvChannel ch : list) {
                ch.setIspName(GROUP_ISP_CUSTOM);
                List<String> groups = new ArrayList<>(ch.getGroups());
                if (!groups.contains(GROUP_ISP_CUSTOM)) {
                    groups.add(GROUP_ISP_CUSTOM);
                    ch.setGroups(groups);
                }
                if (appendIspToDisplay) {
                    String disp = ch.getDisplayName();
                    if (disp != null && !disp.contains("(自定义)")) {
                        ch.setDisplayName(disp + " (自定义)");
                    }
                }
            }
            return list;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 获取用户上传的频道来源列表（与北京联通、北京移动、北京电信等内置频道来源并列展示）
     */
    public synchronized List<ChannelListShareManager.SharedChannelListItem> getUploadedChannelSources() {
        return new ArrayList<>(uploadedChannelSources);
    }

    public synchronized ChannelListShareManager.SharedChannelListItem getSelectedUploadedChannelSource() {
        if (uploadedChannelSources.isEmpty()) {
            return null;
        }
        String selectedKey = settings.getSelectedUploadedSourceKey();
        if (!TextUtils.isEmpty(selectedKey)) {
            for (ChannelListShareManager.SharedChannelListItem item : uploadedChannelSources) {
                if (selectedKey.equals(item.getSourceKey()) || selectedKey.equals(item.fileName)) {
                    return item;
                }
            }
        }
        return uploadedChannelSources.get(0);
    }

    /**
     * 新增或更新一条用户上传的频道来源（上传成功或从服务端拉取详情后立即加入列表）
     */
    public synchronized void addOrUpdateUploadedChannelSource(ChannelListShareManager.SharedChannelListItem item) {
        if (item == null || TextUtils.isEmpty(item.fileName)) {
            return;
        }
        upsertUploadedSourceLocked(item);
        persistUploadedChannelSourcesLocked();
        if (settings.getIspProvider() == IptvSettings.ISP_USER_UPLOADED_LIST
                || settings.getIspProvider() == IptvSettings.ISP_ALL_MERGED) {
            reloadChannels();
        }
        notifyChannelsChanged();
    }

    /**
     * 合并从服务端拉取的用户上传频道来源列表 (/api/v1/channel-lists)
     */
    public synchronized void mergeServerUploadedChannelSources(List<ChannelListShareManager.SharedChannelListItem> serverItems) {
        if (serverItems == null) {
            return;
        }
        for (ChannelListShareManager.SharedChannelListItem item : serverItems) {
            if (item != null && !TextUtils.isEmpty(item.fileName)) {
                upsertUploadedSourceLocked(item);
            }
        }
        persistUploadedChannelSourcesLocked();
        if (settings.getIspProvider() == IptvSettings.ISP_USER_UPLOADED_LIST
                || settings.getIspProvider() == IptvSettings.ISP_ALL_MERGED) {
            reloadChannels();
        }
        notifyChannelsChanged();
    }

    public synchronized void setUploadedChannelSources(List<ChannelListShareManager.SharedChannelListItem> items) {
        uploadedChannelSources.clear();
        if (items != null) {
            for (ChannelListShareManager.SharedChannelListItem item : items) {
                if (item != null && !TextUtils.isEmpty(item.fileName)) {
                    upsertUploadedSourceLocked(item);
                }
            }
        }
        persistUploadedChannelSourcesLocked();
        if (settings.getIspProvider() == IptvSettings.ISP_USER_UPLOADED_LIST
                || settings.getIspProvider() == IptvSettings.ISP_ALL_MERGED) {
            reloadChannels();
        }
        notifyChannelsChanged();
    }

    public synchronized void clearUploadedChannelSources() {
        uploadedChannelSources.clear();
        settings.setUploadedChannelSourcesJson("");
        if (settings.getIspProvider() == IptvSettings.ISP_USER_UPLOADED_LIST) {
            settings.setIspProvider(IptvSettings.ISP_BEIJING_UNICOM);
        }
        reloadChannels();
        notifyChannelsChanged();
    }

    private void upsertUploadedSourceLocked(ChannelListShareManager.SharedChannelListItem incoming) {
        String key = incoming.getSourceKey();
        for (int i = 0; i < uploadedChannelSources.size(); i++) {
            ChannelListShareManager.SharedChannelListItem existing = uploadedChannelSources.get(i);
            boolean sameId = incoming.id > 0 && existing.id == incoming.id;
            boolean sameKey = key.equals(existing.getSourceKey());
            if (sameId || sameKey) {
                ChannelListShareManager.SharedChannelListItem merged = incoming;
                if (!incoming.hasM3uContent() && existing.hasM3uContent()) {
                    merged = incoming.withM3uContent(existing.m3uContent, existing.channelCount);
                }
                uploadedChannelSources.set(i, merged);
                return;
            }
        }
        uploadedChannelSources.add(incoming);
    }

    private synchronized void loadPersistedUploadedChannelSources() {
        uploadedChannelSources.clear();
        String json = settings.getUploadedChannelSourcesJson();
        if (TextUtils.isEmpty(json)) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                ChannelListShareManager.SharedChannelListItem item =
                        ChannelListShareManager.SharedChannelListItem.fromJson(obj);
                if (item != null) {
                    uploadedChannelSources.add(item);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private synchronized void persistUploadedChannelSourcesLocked() {
        try {
            JSONArray arr = new JSONArray();
            for (ChannelListShareManager.SharedChannelListItem item : uploadedChannelSources) {
                arr.put(item.toJson());
            }
            settings.setUploadedChannelSourcesJson(arr.toString());
        } catch (Throwable ignored) {
        }
    }

    private List<IptvChannel> loadFromUploadedSourceItem(ChannelListShareManager.SharedChannelListItem item,
                                                         boolean appendIspToDisplay) {
        if (item == null || !item.hasM3uContent()) {
            return new ArrayList<>();
        }
        M3uParser.M3uInspectionReport report = M3uParser.inspectAndParse(item.m3uContent, item.fileName);
        List<IptvChannel> list = report.channels;
        String ispTag = item.getShortDisplayName();
        for (IptvChannel ch : list) {
            ch.setIspName(ispTag);
            List<String> groups = new ArrayList<>(ch.getGroups());
            if (!groups.contains(ispTag)) {
                groups.add(ispTag);
                ch.setGroups(groups);
            }
            if (appendIspToDisplay && !TextUtils.isEmpty(ispTag)) {
                String disp = ch.getDisplayName();
                if (disp != null && !disp.contains("(" + ispTag + ")")) {
                    ch.setDisplayName(disp + " (" + ispTag + ")");
                }
            }
        }
        return list;
    }

    /**
     * 切换当前频道来源为指定的用户上传频道列表项
     */
    public synchronized boolean selectUploadedChannelSource(ChannelListShareManager.SharedChannelListItem item) {
        if (item == null) {
            return false;
        }
        upsertUploadedSourceLocked(item);
        persistUploadedChannelSourcesLocked();
        ChannelListShareManager.SharedChannelListItem resolved = item;
        for (ChannelListShareManager.SharedChannelListItem existing : uploadedChannelSources) {
            if (existing.getSourceKey().equals(item.getSourceKey())
                    || (item.id > 0 && existing.id == item.id)) {
                resolved = existing;
                break;
            }
        }
        File staleUploadedScan = new File(getScanRootDir(), CUSTOM_SCANNED_M3U_UPLOADED);
        if (staleUploadedScan.exists()) {
            staleUploadedScan.delete();
        }
        settings.setSelectedUploadedSource(
                resolved.getSourceKey(),
                resolved.getDisplayName(),
                resolved.getShortDisplayName(),
                resolved.ispType
        );
        settings.setIspProvider(IptvSettings.ISP_USER_UPLOADED_LIST);
        reloadChannels();
        if (!allChannels.isEmpty()) {
            settings.setLastChannelUrl(allChannels.get(0).getRawUrl());
        }
        notifyChannelsChanged();
        return !allChannels.isEmpty();
    }

    public synchronized boolean selectUploadedChannelSourceByIndex(int uploadedIndex) {
        if (uploadedIndex < 0 || uploadedIndex >= uploadedChannelSources.size()) {
            return false;
        }
        return selectUploadedChannelSource(uploadedChannelSources.get(uploadedIndex));
    }

    /**
     * 构建首页频道来源列表展示项（内置北京联通、北京移动、北京电信、北京三网聚合、自定义M3U 与用户上传的频道来源列表并列展示）
     */
    public synchronized List<String> getChannelSourceDisplayOptions() {
        List<String> options = new ArrayList<>();
        String customLabel = hasCustomUploadedM3u()
                ? "自定义 M3U 播放列表 (" + settings.getCustomM3uName() + ")"
                : "自定义 M3U 播放列表 (点击上传并检测文件内容)";
        options.add("北京联通 IPTV (bj-unicom-iptv 组播源 rtp://239.3.1.x)");
        options.add("北京移动 IPTV (bj-mobile-iptv 单播源 http://.../*.m3u8)");
        options.add("北京电信 IPTV (bj-telecom-iptv 组播源 rtp://225.1.8.x)");
        options.add("北京三网聚合 (联通 + 移动 + 电信全部频道)");
        options.add(customLabel);
        for (ChannelListShareManager.SharedChannelListItem item : uploadedChannelSources) {
            options.add(item.getOptionTitle());
        }
        return options;
    }

    /**
     * 返回当前选中的频道来源在 getChannelSourceDisplayOptions() 中的索引
     */
    public synchronized int getCurrentChannelSourceOptionIndex() {
        int isp = settings.getIspProvider();
        if (isp == IptvSettings.ISP_USER_UPLOADED_LIST) {
            String selectedKey = settings.getSelectedUploadedSourceKey();
            for (int i = 0; i < uploadedChannelSources.size(); i++) {
                if (uploadedChannelSources.get(i).getSourceKey().equals(selectedKey)) {
                    return IptvSettings.ISP_USER_UPLOADED_BASE + i;
                }
            }
            if (!uploadedChannelSources.isEmpty()) {
                return IptvSettings.ISP_USER_UPLOADED_BASE;
            }
            return IptvSettings.ISP_BEIJING_UNICOM;
        }
        return isp;
    }

    /**
     * 解析、校验并导入用户自定义上传的 M3U 内容
     *
     * @param m3uContent           M3U 文本内容
     * @param fileName             文件名标识 (如 custom.m3u)
     * @param switchToCustomSource true=切换为独立「自定义源」模式；false=合并到当前频道列表
     * @return 解析与内容检测报告
     */
    public synchronized M3uParser.M3uInspectionReport importCustomM3uContent(String m3uContent,
                                                                              String fileName,
                                                                              boolean switchToCustomSource) {
        String safeName = TextUtils.isEmpty(fileName) ? "custom.m3u" : fileName.trim();
        M3uParser.M3uInspectionReport report = M3uParser.inspectAndParse(m3uContent, safeName);
        if (!report.isValid()) {
            return report;
        }

        // 持久化用户上传的原始 M3U 文件
        try {
            File customFile = getCustomUploadedM3uFile();
            try (FileOutputStream fos = new FileOutputStream(customFile)) {
                fos.write(m3uContent.getBytes(StandardCharsets.UTF_8));
            }
            File staleScanned = new File(getScanRootDir(), CUSTOM_SCANNED_M3U_CUSTOM);
            if (staleScanned.exists()) {
                staleScanned.delete();
            }
        } catch (IOException ignored) {
        }

        settings.setCustomM3uName(safeName);
        if (!report.multicastSubnets.isEmpty()) {
            String firstSubnet = report.multicastSubnets.iterator().next();
            if (!TextUtils.isEmpty(firstSubnet)) {
                settings.setScanSubnet(firstSubnet);
            }
        }

        if (switchToCustomSource) {
            settings.setIspProvider(IptvSettings.ISP_CUSTOM_M3U);
            reloadChannels();
            if (report.probedCount > 0) {
                mergeScanResults(report.channels, true);
            }
            if (!allChannels.isEmpty()) {
                settings.setLastChannelUrl(allChannels.get(0).getRawUrl());
            }
        } else {
            for (IptvChannel ch : report.channels) {
                ch.setIspName(GROUP_ISP_CUSTOM);
                List<String> groups = new ArrayList<>(ch.getGroups());
                if (!groups.contains(GROUP_ISP_CUSTOM)) {
                    groups.add(GROUP_ISP_CUSTOM);
                    ch.setGroups(groups);
                }
            }
            mergeScanResults(report.channels, true);
        }

        return report;
    }

    /**
     * 从本地 M3U 文件读取并执行导入操作，更新频道列表
     */
    public synchronized M3uParser.M3uInspectionReport importLocalM3uFile(File localM3uFile,
                                                                         boolean switchToCustomSource) {
        if (localM3uFile == null || !localM3uFile.exists()) {
            return M3uParser.inspectAndParse("", "empty.m3u");
        }
        try (InputStream is = new FileInputStream(localM3uFile)) {
            byte[] buf = new byte[(int) localM3uFile.length()];
            int read = is.read(buf);
            String content = read > 0 ? new String(buf, 0, read, StandardCharsets.UTF_8) : "";
            return importCustomM3uContent(content, localM3uFile.getName(), switchToCustomSource);
        } catch (IOException e) {
            return M3uParser.inspectAndParse("", localM3uFile.getName());
        }
    }

    private List<IptvChannel> loadFromAssetsWithIsp(String fileName, String ispName, boolean appendIspToDisplay) {
        List<IptvChannel> list = loadFromAssets(fileName);
        for (IptvChannel ch : list) {
            ch.setIspName(ispName);
            List<String> groups = new ArrayList<>(ch.getGroups());
            if (!groups.contains(ispName)) {
                groups.add(ispName);
                ch.setGroups(groups);
            }
            if (appendIspToDisplay && !TextUtils.isEmpty(ispName)) {
                String shortTag = ispName.replace("北京", "");
                String disp = ch.getDisplayName();
                if (disp != null && !disp.contains("(" + shortTag + ")")) {
                    ch.setDisplayName(disp + " (" + shortTag + ")");
                }
            }
        }
        return list;
    }

    private List<IptvChannel> loadFromAssets(String fileName) {
        try (InputStream is = appContext.getAssets().open(fileName)) {
            return M3uParser.parse(is);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 合并 check_iptv_m3u.sh 或动态扫描输出的 modified_iptv.m3u
     */
    public synchronized void mergeFromModifiedM3u(File m3uFile) {
        if (m3uFile == null || !m3uFile.exists()) {
            return;
        }
        try (InputStream is = new FileInputStream(m3uFile)) {
            List<IptvChannel> scannedChannels = M3uParser.parse(is);
            mergeScanResults(scannedChannels, false);
        } catch (IOException ignored) {
        }
    }

    /**
     * 合并扫描结果列表到当前频道仓库并持久化
     */
    public synchronized void mergeScanResults(List<IptvChannel> scannedList, boolean persistToDisk) {
        if (scannedList == null || scannedList.isEmpty()) {
            return;
        }
        Map<String, IptvChannel> currentByKey = new LinkedHashMap<>();
        for (IptvChannel ch : allChannels) {
            currentByKey.put(ch.getMulticastKey(), ch);
        }

        Set<String> favorites = settings.getFavoriteUrls();
        for (IptvChannel scanned : scannedList) {
            String key = scanned.getMulticastKey();
            IptvChannel existing = currentByKey.get(key);
            if (existing != null) {
                if (scanned.getConnected() != null) {
                    existing.setConnected(scanned.getConnected());
                } else {
                    existing.setConnected(true);
                }
                if (!TextUtils.isEmpty(scanned.getActualResolutionFlag())) {
                    existing.setActualResolutionFlag(scanned.getActualResolutionFlag());
                } else if (!TextUtils.isEmpty(scanned.getResolutionTag())) {
                    existing.setActualResolutionFlag(scanned.getResolutionTag());
                }
                if (!TextUtils.isEmpty(scanned.getVideoTrackInfo())) {
                    existing.setVideoTrackInfo(scanned.getVideoTrackInfo());
                }
                if (!TextUtils.isEmpty(scanned.getAudioTrackInfo())) {
                    existing.setAudioTrackInfo(scanned.getAudioTrackInfo());
                }
                if (scanned.getLatencyMs() > 0) {
                    existing.setLatencyMs(scanned.getLatencyMs());
                }
                if (!TextUtils.isEmpty(scanned.getCatchupSource())) {
                    existing.setCatchupSource(scanned.getCatchupSource());
                }
                if (!TextUtils.isEmpty(scanned.getCatchupType())) {
                    existing.setCatchupType(scanned.getCatchupType());
                }
                existing.setResolutionMismatch(scanned.isResolutionMismatch());
            } else {
                // 新扫描发现的组播地址
                scanned.setIndex(allChannels.size() + 1);
                scanned.setDiscoveredByScan(true);
                if (scanned.getConnected() == null) {
                    scanned.setConnected(true);
                }
                scanned.setFavorite(favorites.contains(scanned.getRawUrl()));
                List<String> groups = new ArrayList<>(scanned.getGroups());
                if (!groups.contains(GROUP_DISCOVERED)) {
                    groups.add(GROUP_DISCOVERED);
                }
                scanned.setGroups(groups);
                allChannels.add(scanned);
                currentByKey.put(key, scanned);
                knownMulticastMap.put(key, scanned);
            }
        }

        if (persistToDisk) {
            saveModifiedM3u(allChannels);
        }
    }

    public synchronized void saveModifiedM3u(List<IptvChannel> channels) {
        try {
            File outFile = new File(getScanRootDir(), getCustomScannedFileName());
            String m3uContent = M3uParser.toM3uContent(channels, true, null);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(m3uContent.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * 从扫描结果中提取有效频道列表：
     * - 若扫描结果中存在连通 (connected == true) 的频道，仅保留连通可用的频道；
     * - 若在离线/无组播环境中全部为 false/null，则保留非空扫描列表兜底。
     */
    public synchronized List<IptvChannel> extractEffectiveScannedChannels(List<IptvChannel> scannedChannels) {
        List<IptvChannel> source = (scannedChannels != null && !scannedChannels.isEmpty())
                ? scannedChannels
                : allChannels;
        List<IptvChannel> connectedList = new ArrayList<>();
        List<IptvChannel> allValid = new ArrayList<>();
        for (IptvChannel ch : source) {
            if (ch == null || TextUtils.isEmpty(ch.getRawUrl())) {
                continue;
            }
            IptvChannel copy = ch.copy();
            allValid.add(copy);
            if (Boolean.TRUE.equals(ch.getConnected())) {
                connectedList.add(copy);
            }
        }
        return !connectedList.isEmpty() ? connectedList : allValid;
    }

    /**
     * 将扫描结果构建为标准 M3U 文本内容
     */
    public synchronized String buildScannedM3uContent(List<IptvChannel> scannedChannels) {
        List<IptvChannel> effective = extractEffectiveScannedChannels(scannedChannels);
        return M3uParser.toM3uContent(effective, false, null);
    }

    /**
     * 选项一：「保存到本地」—— 将扫描结果存储为本地 M3U 文件（不替换当前默认使用的频道表）
     */
    public synchronized File saveScannedM3uToLocalFile(List<IptvChannel> scannedChannels, String customFileName) {
        String m3uContent = buildScannedM3uContent(scannedChannels);
        String safeName = TextUtils.isEmpty(customFileName)
                ? ("iptv_scanned_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m3u")
                : customFileName.trim();
        if (!safeName.toLowerCase(Locale.ROOT).endsWith(".m3u")
                && !safeName.toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
            safeName = safeName + ".m3u";
        }

        File localScanFile = new File(getScanRootDir(), safeName);
        try (FileOutputStream fos = new FileOutputStream(localScanFile)) {
            fos.write(m3uContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }

        // 同时尝试写入系统公共 Download 目录，方便用户在文件管理器中直接查看
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs()) && downloadDir.canWrite()) {
                File pubFile = new File(downloadDir, safeName);
                try (FileOutputStream fos = new FileOutputStream(pubFile)) {
                    fos.write(m3uContent.getBytes(StandardCharsets.UTF_8));
                }
                lastSavedLocalM3uFile = pubFile;
                return pubFile;
            }
        } catch (Throwable ignored) {
        }
        lastSavedLocalM3uFile = localScanFile;
        return localScanFile;
    }

    /**
     * 选项二：「立即应用」——
     * 1. 先将扫描后的结果保存为本地 M3U 文件；
     * 2. 执行导入该本地 M3U 文件的操作更新频道列表；
     * 3. 将第一个频道设为当前播放频道并通知播放页立即跳转到第一个频道。
     *
     * @return 更新后生效的频道数量
     */
    public synchronized int applyScannedResultAsDefaultM3u(List<IptvChannel> scannedChannels) {
        List<IptvChannel> effective = extractEffectiveScannedChannels(scannedChannels);
        if (effective.isEmpty()) {
            return 0;
        }

        // 1. 先保存到本地 .m3u 文件
        File localM3uFile = saveScannedM3uToLocalFile(effective, null);

        // 2. 执行导入该本地 .m3u 文件的操作更新频道列表
        M3uParser.M3uInspectionReport report = importLocalM3uFile(localM3uFile, true);
        if (!report.isValid() || allChannels.isEmpty()) {
            return 0;
        }

        // 回填本次扫描实测的音视频轨道、分辨率与延迟元数据
        Map<String, IptvChannel> metaByKey = new LinkedHashMap<>();
        for (IptvChannel ch : effective) {
            metaByKey.put(ch.getMulticastKey(), ch);
        }
        for (IptvChannel ch : allChannels) {
            IptvChannel meta = metaByKey.get(ch.getMulticastKey());
            if (meta != null) {
                if (meta.getConnected() != null) {
                    ch.setConnected(meta.getConnected());
                } else {
                    ch.setConnected(true);
                }
                if (!TextUtils.isEmpty(meta.getActualResolutionFlag())) {
                    ch.setActualResolutionFlag(meta.getActualResolutionFlag());
                }
                if (!TextUtils.isEmpty(meta.getVideoTrackInfo())) {
                    ch.setVideoTrackInfo(meta.getVideoTrackInfo());
                }
                if (!TextUtils.isEmpty(meta.getAudioTrackInfo())) {
                    ch.setAudioTrackInfo(meta.getAudioTrackInfo());
                }
                if (meta.getLatencyMs() > 0) {
                    ch.setLatencyMs(meta.getLatencyMs());
                }
                if (!TextUtils.isEmpty(meta.getCatchupSource())) {
                    ch.setCatchupSource(meta.getCatchupSource());
                }
                if (!TextUtils.isEmpty(meta.getCatchupType())) {
                    ch.setCatchupType(meta.getCatchupType());
                }
                ch.setDiscoveredByScan(meta.isDiscoveredByScan());
                ch.setResolutionMismatch(meta.isResolutionMismatch());
            }
        }

        // 3. 记录第一个频道为目标播放频道，并触发播放页刷新列表且跳转到第一个频道
        settings.setLastChannelUrl(allChannels.get(0).getRawUrl());
        notifyChannelsImportedAndPlayFirst();
        return allChannels.size();
    }

    /**
     * 清除已应用的扫描替换文件，恢复内置默认 M3U 播放列表
     */
    public synchronized void clearAppliedDefaultM3u() {
        String[] files = {
                CUSTOM_SCANNED_M3U_UNICOM,
                CUSTOM_SCANNED_M3U_MOBILE,
                CUSTOM_SCANNED_M3U_TELECOM,
                CUSTOM_SCANNED_M3U_ALL,
                CUSTOM_SCANNED_M3U_CUSTOM,
                CUSTOM_SCANNED_M3U_UPLOADED,
                CUSTOM_UPLOADED_M3U_FILE
        };
        File root = getScanRootDir();
        for (String name : files) {
            File f = new File(root, name);
            if (f.exists()) {
                f.delete();
            }
        }
        if (settings.getIspProvider() == IptvSettings.ISP_CUSTOM_M3U
                || settings.getIspProvider() == IptvSettings.ISP_USER_UPLOADED_LIST) {
            settings.setIspProvider(IptvSettings.ISP_BEIJING_UNICOM);
        }
        reloadChannels();
        notifyChannelsChanged();
    }

    public File getScanRootDir() {
        File dir = appContext.getExternalFilesDir("iptv_scan");
        if (dir == null) {
            dir = new File(appContext.getFilesDir(), "iptv_scan");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public synchronized List<IptvChannel> getAllChannels() {
        return new ArrayList<>(allChannels);
    }

    public synchronized IptvChannel findKnownChannelByIpPort(String ip, int port) {
        return knownMulticastMap.get(ip + ":" + port);
    }

    public synchronized List<Integer> getKnownPortsForIp(String ip) {
        List<Integer> ports = new ArrayList<>();
        String prefix = ip + ":";
        for (Map.Entry<String, IptvChannel> entry : knownMulticastMap.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                int port = entry.getValue().getMulticastPort();
                if (port > 0 && !ports.contains(port)) {
                    ports.add(port);
                }
            }
        }
        return ports;
    }

    public synchronized List<String> getCategoryGroups() {
        List<String> categories = new ArrayList<>();
        categories.add(GROUP_ALL);
        int isp = settings.getIspProvider();
        boolean isCustomOrUploaded = (isp == IptvSettings.ISP_CUSTOM_M3U
                || isp == IptvSettings.ISP_USER_UPLOADED_LIST);
        String activeUploadedGroup = settings.getSelectedUploadedSourceShortName();
        if (isp == IptvSettings.ISP_ALL_MERGED) {
            categories.add(GROUP_ISP_UNICOM);
            categories.add(GROUP_ISP_MOBILE);
            categories.add(GROUP_ISP_TELECOM);
            if (hasAnyChannelInGroup(GROUP_ISP_CUSTOM)) {
                categories.add(GROUP_ISP_CUSTOM);
            }
            for (ChannelListShareManager.SharedChannelListItem item : uploadedChannelSources) {
                String upGroup = item.getShortDisplayName();
                if (!TextUtils.isEmpty(upGroup)
                        && hasAnyChannelInGroup(upGroup)
                        && !categories.contains(upGroup)) {
                    categories.add(upGroup);
                }
            }
        }
        if (!isCustomOrUploaded || hasAnyChannelInGroup(GROUP_HD_CCTV)) {
            categories.add(GROUP_HD_CCTV);
        }
        if (!isCustomOrUploaded || hasAnyChannelInGroup(GROUP_HD_SATELLITE)) {
            categories.add(GROUP_HD_SATELLITE);
        }
        if (!isCustomOrUploaded || hasAnyChannelInGroup(GROUP_HD_BJ)) {
            categories.add(GROUP_HD_BJ);
        }
        if (!isCustomOrUploaded || hasAnyChannelInGroup(GROUP_HD_IPTV)) {
            categories.add(GROUP_HD_IPTV);
        }
        if (hasAnyChannelInGroup(GROUP_HD_NEWTV)) {
            categories.add(GROUP_HD_NEWTV);
        }
        if (hasAnyChannelInGroup(GROUP_HD_LOCAL)) {
            categories.add(GROUP_HD_LOCAL);
        }
        if (hasAnyChannelInGroup(GROUP_HD_SHOPPING)) {
            categories.add(GROUP_HD_SHOPPING);
        }

        // 动态添加自定义 M3U 中的自定义分组
        for (IptvChannel ch : allChannels) {
            for (String g : ch.getGroups()) {
                if (TextUtils.isEmpty(g)
                        || GROUP_ISP_UNICOM.equals(g)
                        || GROUP_ISP_MOBILE.equals(g)
                        || GROUP_ISP_TELECOM.equals(g)
                        || (isp == IptvSettings.ISP_CUSTOM_M3U && GROUP_ISP_CUSTOM.equals(g))
                        || (isp == IptvSettings.ISP_USER_UPLOADED_LIST && g.equals(activeUploadedGroup))
                        || GROUP_4K.equals(g)
                        || GROUP_KIDS.equals(g)
                        || GROUP_SD_ALL.equals(g)
                        || GROUP_DISCOVERED.equals(g)
                        || GROUP_FAVORITES.equals(g)) {
                    continue;
                }
                if (!categories.contains(g)) {
                    categories.add(g);
                }
            }
        }

        categories.add(GROUP_4K);
        if (!isCustomOrUploaded || hasAnyChannelInGroup(GROUP_KIDS)) {
            categories.add(GROUP_KIDS);
        }
        categories.add(GROUP_SD_ALL);
        categories.add(GROUP_DISCOVERED);
        categories.add(GROUP_FAVORITES);
        return categories;
    }

    private boolean hasAnyChannelInGroup(String groupName) {
        for (IptvChannel ch : allChannels) {
            for (String g : ch.getGroups()) {
                if (g.equalsIgnoreCase(groupName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized List<IptvChannel> filterChannels(String category, String searchQuery, boolean onlyAvailable) {
        List<IptvChannel> result = new ArrayList<>();
        String query = searchQuery != null ? searchQuery.trim().toLowerCase(Locale.ROOT) : "";

        for (IptvChannel ch : allChannels) {
            if (onlyAvailable && Boolean.FALSE.equals(ch.getConnected())) {
                continue;
            }
            if (!matchesCategory(ch, category)) {
                continue;
            }
            if (!query.isEmpty()) {
                boolean matchName = ch.getDisplayName().toLowerCase(Locale.ROOT).contains(query)
                        || (ch.getRawTitle() != null && ch.getRawTitle().toLowerCase(Locale.ROOT).contains(query));
                boolean matchNum = String.valueOf(ch.getChannelNumber()).equals(query)
                        || ch.getChannelNumberDisplay().equals(query);
                boolean matchIp = ch.getRawUrl() != null && ch.getRawUrl().toLowerCase(Locale.ROOT).contains(query);
                if (!matchName && !matchNum && !matchIp) {
                    continue;
                }
            }
            result.add(ch);
        }
        return result;
    }

    private boolean matchesCategory(IptvChannel ch, String category) {
        if (TextUtils.isEmpty(category) || GROUP_ALL.equals(category)) {
            return true;
        }
        if (category.equals(ch.getIspName())) {
            return true;
        }
        if (GROUP_FAVORITES.equals(category)) {
            return ch.isFavorite();
        }
        if (GROUP_DISCOVERED.equals(category)) {
            return ch.isDiscoveredByScan() || Boolean.TRUE.equals(ch.getConnected());
        }
        if (GROUP_4K.equals(category)) {
            return "4K".equals(ch.getResolutionTag()) || ch.getGroups().contains("4K");
        }
        if (GROUP_SD_ALL.equals(category)) {
            return "标清".equals(ch.getResolutionTag());
        }
        for (String g : ch.getGroups()) {
            if (g.equalsIgnoreCase(category)) {
                return true;
            }
        }
        return false;
    }

    public synchronized IptvChannel findByChannelNumber(int number) {
        for (IptvChannel ch : allChannels) {
            if (ch.getChannelNumber() == number) {
                return ch;
            }
        }
        for (IptvChannel ch : allChannels) {
            if (ch.getIndex() == number) {
                return ch;
            }
        }
        return null;
    }

    public synchronized void toggleFavorite(IptvChannel channel) {
        if (channel == null) {
            return;
        }
        settings.toggleFavorite(channel.getRawUrl());
        boolean isFav = settings.getFavoriteUrls().contains(channel.getRawUrl());
        channel.setFavorite(isFav);
    }

    public synchronized List<Integer> getAllKnownPorts() {
        List<Integer> ports = new ArrayList<>();
        int[] defaultUnicomAndTelecomPorts = {
                8002, 8001, 4120, 8000, 4220, 4222, 2000, 1234, 5008,
                3001, 3002, 3003, 3004, 3005, 3006,
                8004, 8008, 8012, 8016, 8020, 8024, 8028, 8032, 8036, 8040, 8044, 8048,
                8052, 8056, 8060, 8064, 8068, 8072, 8076, 8080, 8084, 8092, 8104, 8108,
                8112, 8116, 8120, 8124, 8128, 8130, 8132, 8136, 8140, 8144, 8148, 8152, 8156,
                8160, 8162, 8164, 8166, 8172, 8174, 8176, 8180, 8184, 8188, 8192, 8196, 8200, 8212, 8216,
                8228, 8232, 8236, 8264, 8268, 8272, 8276, 8280, 8284, 8288, 8292, 8296,
                8300, 8304, 9000, 9004, 9008, 9012, 9016, 9020, 9024, 9136, 9244, 9252, 9268
        };
        for (int p : defaultUnicomAndTelecomPorts) {
            ports.add(p);
        }
        return Collections.unmodifiableList(ports);
    }
}
