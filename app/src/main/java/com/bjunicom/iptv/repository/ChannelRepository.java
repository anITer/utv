package com.bjunicom.iptv.repository;

import android.content.Context;
import android.text.TextUtils;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 频道数据仓库
 * 负责管理：
 * 1. 内置北京联通 IPTV 频道列表 (bj-unicom-iptv.m3u / bj-unicom-iptv-all.m3u)
 * 2. 动态扫描与 check_iptv_m3u.sh 生成的 modified_iptv.m3u 合并及持久化
 * 3. 分组索引 ([高清]央视, [高清]卫视, [高清]北京, [高清]北京IPTV, 4K, 少儿, 标清, 动态扫描发现, 收藏)
 */
public class ChannelRepository {
    private static final String ASSET_M3U_LATEST = "bj-unicom-iptv.m3u";
    private static final String ASSET_M3U_ALL = "bj-unicom-iptv-all.m3u";
    private static final String CUSTOM_SCANNED_M3U = "modified_iptv.m3u";

    public static final String GROUP_ALL = "全部频道";
    public static final String GROUP_FAVORITES = "收藏频道";
    public static final String GROUP_4K = "4K";
    public static final String GROUP_HD_CCTV = "[高清]央视";
    public static final String GROUP_HD_SATELLITE = "[高清]卫视";
    public static final String GROUP_HD_BJ = "[高清]北京";
    public static final String GROUP_HD_IPTV = "[高清]北京IPTV";
    public static final String GROUP_KIDS = "少儿";
    public static final String GROUP_SD_ALL = "标清频道";
    public static final String GROUP_DISCOVERED = "动态扫描发现";

    private static volatile ChannelRepository instance;

    private final Context appContext;
    private final IptvSettings settings;
    private final List<IptvChannel> allChannels = new ArrayList<>();
    private final Map<String, IptvChannel> knownMulticastMap = new LinkedHashMap<>();

    private ChannelRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.settings = new IptvSettings(appContext);
        reloadChannels();
    }

    public static ChannelRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (ChannelRepository.class) {
                if (instance == null) {
                    instance = new ChannelRepository(context);
                }
            }
        }
        return instance;
    }

    public synchronized void reloadChannels() {
        allChannels.clear();
        knownMulticastMap.clear();

        String assetName = settings.isIncludeAllRepoChannels() ? ASSET_M3U_ALL : ASSET_M3U_LATEST;
        List<IptvChannel> baseList = loadFromAssets(assetName);
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

        // 同时加载全量字典以便扫描器识别历史频道名称
        if (!ASSET_M3U_ALL.equals(assetName)) {
            for (IptvChannel ch : loadFromAssets(ASSET_M3U_ALL)) {
                if (!TextUtils.isEmpty(ch.getMulticastKey()) && !knownMulticastMap.containsKey(ch.getMulticastKey())) {
                    knownMulticastMap.put(ch.getMulticastKey(), ch);
                }
            }
        }

        // 如果存在动态扫描保存的 modified_iptv.m3u，合并检测状态与新发现的组播地址
        File scannedFile = new File(getScanRootDir(), CUSTOM_SCANNED_M3U);
        if (scannedFile.exists()) {
            mergeFromModifiedM3u(scannedFile);
        }
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
            File outFile = new File(getScanRootDir(), CUSTOM_SCANNED_M3U);
            String m3uContent = M3uParser.toM3uContent(channels, true, null);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(m3uContent.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
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
        categories.add(GROUP_HD_CCTV);
        categories.add(GROUP_HD_SATELLITE);
        categories.add(GROUP_HD_BJ);
        categories.add(GROUP_HD_IPTV);
        categories.add(GROUP_4K);
        categories.add(GROUP_KIDS);
        categories.add(GROUP_SD_ALL);
        categories.add(GROUP_DISCOVERED);
        categories.add(GROUP_FAVORITES);
        return categories;
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
            if ("标清".equals(ch.getResolutionTag())) {
                return true;
            }
            for (String g : ch.getGroups()) {
                if (g.contains("标清") || (!g.contains("高清") && !g.contains("4K") && !g.contains("少儿"))) {
                    return true;
                }
            }
            return false;
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
        int[] defaultUnicomPorts = {
                8001, 4120, 8000, 4220, 2000, 1234,
                3001, 3002, 3003, 3004, 3005, 3006,
                8004, 8008, 8012, 8016, 8020, 8024, 8028, 8032, 8036, 8040, 8044, 8048,
                8052, 8056, 8060, 8064, 8072, 8076, 8084, 8092, 8104, 8108, 8112, 8116,
                8120, 8124, 8128, 8132, 8136, 8140, 8144, 8148, 8152, 8156, 8160, 8164,
                8172, 8176, 8180, 8184, 8188, 8192, 8196, 8200, 8212, 8216, 8228, 8232,
                8236, 8268, 8272, 8276, 8280, 8284, 8288, 8292, 8296, 8300, 8304,
                9000, 9012, 9020, 9024, 9136, 9244, 9252, 9268
        };
        for (int p : defaultUnicomPorts) {
            ports.add(p);
        }
        return Collections.unmodifiableList(ports);
    }
}
