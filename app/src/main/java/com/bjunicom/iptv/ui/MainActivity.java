package com.bjunicom.iptv.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bjunicom.iptv.IptvApplication;
import com.bjunicom.iptv.R;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.network.StreamUrlResolver;
import com.bjunicom.iptv.player.IjkVideoView;
import com.bjunicom.iptv.player.MeasureHelper;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.bjunicom.iptv.ui.adapter.CategoryAdapter;
import com.bjunicom.iptv.ui.adapter.ChannelAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/**
 * 北京联通 IPTV 主播放界面
 *
 * 核心能力：
 * 1. 基于 bilibili/ijkplayer 播放全部北京联通 IPTV 频道 (4K HDR/SDR、高清央视/卫视/北京、少儿、标清)
 * 2. 支持路由器 udpxy 单播代理、Android 内置本地 udpxy 网关、RTP 原生组播三种播放模式
 * 3. 实时探测并回填与 check_iptv_m3u.sh 一致的视频轨道、音频轨道及实测分辨率标签
 * 4. 完整适配【电视遥控器】与【手机/平板触屏】双交互模式：
 *    - 遥控器：上下键/频道键换台、0-9数字键选台(含频道名实时预览与OK立即确认)、OK/左键呼出频道列表并自动定位当前频道焦点、
 *      列表内左右键直接切换分类、长按OK/红键收藏、绿键打开动态组播扫描、黄键切换画面比例、菜单/蓝键打开设置、双击返回退出
 *    - 触屏：单击显隐OSD/收起抽屉、双击切换画面比例、右滑呼出频道抽屉、左滑收起抽屉、全屏上下滑动快速换台
 */
public class MainActivity extends AppCompatActivity {

    private static final long HUD_AUTO_HIDE_MS = 6000L;
    private static final long NUMBER_INPUT_COMMIT_MS = 1800L;
    private static final long DOUBLE_BACK_EXIT_MS = 2000L;

    private IjkVideoView videoView;
    private LinearLayout loadingOverlay;
    private TextView tvLoadingHint;
    private TextView tvNumberInputOsd;
    private RelativeLayout hudOverlay;
    private LinearLayout drawerPanel;

    private TextView tvTopChannelTitle;
    private TextView tvTopStreamSpeed;
    private TextView tvOsdChannelNum;
    private TextView tvOsdChannelName;
    private TextView tvOsdResolutionBadge;
    private TextView tvOsdGroupName;
    private TextView tvOsdStreamUrl;
    private TextView tvOsdTrackInfo;
    private Button btnFavoriteChannel;
    private Button btnPrevChannel;
    private View[] hudButtons;
    private int currentHudFocusIndex = 0;
    private boolean longPressCenterHandled = false;
    private boolean drawerFocusOnCategories = false;

    private TextView tvDrawerTitle;
    private CheckBox cbOnlyAvailable;
    private EditText etSearchChannel;
    private RecyclerView rvCategories;
    private RecyclerView rvChannels;

    private ChannelRepository repository;
    private IptvSettings settings;
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;
    private GestureDetector touchGestureDetector;

    private IptvChannel currentChannel;
    private boolean fallbackToLocalProxyAttempted = false;
    private boolean udpxyUnreachableInSession = false;
    private long lastBackPressTimeMs = 0L;
    private final StringBuilder remoteNumberBuffer = new StringBuilder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable hideHudRunnable = () -> {
        if (drawerPanel.getVisibility() == View.VISIBLE) {
            drawerPanel.setVisibility(View.GONE);
        }
        hudOverlay.setVisibility(View.GONE);
        updateHudButtonsVisualState();
    };

    private final Runnable commitNumberInputRunnable = () -> {
        if (remoteNumberBuffer.length() > 0) {
            try {
                int targetNum = Integer.parseInt(remoteNumberBuffer.toString());
                IptvChannel target = repository.findByChannelNumber(targetNum);
                if (target != null) {
                    playChannel(target);
                } else {
                    Toast.makeText(this, "未找到台号: " + targetNum, Toast.LENGTH_SHORT).show();
                }
            } catch (NumberFormatException ignored) {
            }
            remoteNumberBuffer.setLength(0);
            tvNumberInputOsd.setVisibility(View.GONE);
        }
    };

    private final Runnable telemetryTickRunnable = new Runnable() {
        @Override
        public void run() {
            updateLiveTelemetry();
            mainHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = ChannelRepository.getInstance(this);
        settings = new IptvSettings(this);

        bindViews();
        setupTouchGestures();
        setupCategoryList();
        setupChannelList();
        setupPlayerListeners();
        refreshChannelList();

        // 自动播放上次观看的频道 (默认 CCTV-1综合[1][高清])
        playInitialChannel();

        // 启动时如果抽屉可见，自动将遥控器焦点定位到当前播放的频道项；否则聚焦播放页 HUD 按钮
        if (drawerPanel.getVisibility() == View.VISIBLE) {
            focusChannelListAt(channelAdapter.getPlayingChannelIndex());
        } else {
            focusHudButtonAt(currentHudFocusIndex);
        }
    }

    private void bindViews() {
        videoView = findViewById(R.id.ijk_video_view);
        loadingOverlay = findViewById(R.id.loading_overlay);
        tvLoadingHint = findViewById(R.id.tv_loading_hint);
        tvNumberInputOsd = findViewById(R.id.tv_number_input_osd);
        hudOverlay = findViewById(R.id.player_hud_overlay);
        drawerPanel = findViewById(R.id.channel_drawer_panel);

        tvTopChannelTitle = findViewById(R.id.tv_top_channel_title);
        tvTopStreamSpeed = findViewById(R.id.tv_top_stream_speed);
        tvOsdChannelNum = findViewById(R.id.tv_osd_channel_num);
        tvOsdChannelName = findViewById(R.id.tv_osd_channel_name);
        tvOsdResolutionBadge = findViewById(R.id.tv_osd_resolution_badge);
        tvOsdGroupName = findViewById(R.id.tv_osd_group_name);
        tvOsdStreamUrl = findViewById(R.id.tv_osd_stream_url);
        tvOsdTrackInfo = findViewById(R.id.tv_osd_track_info);
        btnFavoriteChannel = findViewById(R.id.btn_favorite_channel);
        btnPrevChannel = findViewById(R.id.btn_prev_channel);

        tvDrawerTitle = findViewById(R.id.tv_drawer_title);
        cbOnlyAvailable = findViewById(R.id.cb_only_available);
        etSearchChannel = findViewById(R.id.et_search_channel);
        rvCategories = findViewById(R.id.rv_categories);
        rvChannels = findViewById(R.id.rv_channels);

        cbOnlyAvailable.setChecked(settings.isOnlyShowAvailable());
        cbOnlyAvailable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settings.setOnlyShowAvailable(isChecked);
            refreshChannelList();
        });

        etSearchChannel.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshChannelList();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.btn_toggle_drawer).setOnClickListener(v -> toggleChannelDrawer());

        findViewById(R.id.btn_open_scanner).setOnClickListener(v -> openScannerActivity());

        findViewById(R.id.btn_open_settings).setOnClickListener(v -> showSettingsDialog());

        btnPrevChannel.setOnClickListener(v -> switchAdjacentChannel(-1));
        findViewById(R.id.btn_next_channel).setOnClickListener(v -> switchAdjacentChannel(1));

        findViewById(R.id.btn_numpad_dialog).setOnClickListener(v -> showVirtualNumpadDialog());
        findViewById(R.id.btn_drawer_numpad).setOnClickListener(v -> showVirtualNumpadDialog());

        btnFavoriteChannel.setOnClickListener(v -> toggleCurrentChannelFavorite());

        findViewById(R.id.btn_aspect_ratio).setOnClickListener(v -> cycleAspectRatio());

        findViewById(R.id.btn_reload_stream).setOnClickListener(v -> {
            if (currentChannel != null) {
                playChannel(currentChannel);
            }
        });

        setupButtonFocusEffects();
    }

    private void setupButtonFocusEffects() {
        hudButtons = new View[]{
                findViewById(R.id.btn_toggle_drawer),
                findViewById(R.id.btn_open_scanner),
                findViewById(R.id.btn_open_settings),
                findViewById(R.id.btn_prev_channel),
                findViewById(R.id.btn_next_channel),
                findViewById(R.id.btn_numpad_dialog),
                findViewById(R.id.btn_favorite_channel),
                findViewById(R.id.btn_aspect_ratio),
                findViewById(R.id.btn_reload_stream)
        };

        for (int i = 0; i < hudButtons.length; i++) {
            final int btnIndex = i;
            View btn = hudButtons[i];
            if (btn != null) {
                btn.setFocusable(true);
                btn.setBackgroundTintList(null);
                btn.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && drawerPanel.getVisibility() != View.VISIBLE) {
                        currentHudFocusIndex = btnIndex;
                        updateHudButtonsVisualState();
                    } else if (!hasFocus) {
                        v.setFocusableInTouchMode(false);
                    }
                });
            }
        }

        int[] drawerBtnIds = {
                R.id.btn_drawer_numpad,
                R.id.cb_only_available
        };
        View.OnFocusChangeListener drawerFocusListener = (v, hasFocus) -> {
            v.animate()
                    .scaleX(hasFocus ? 1.08f : 1.0f)
                    .scaleY(hasFocus ? 1.08f : 1.0f)
                    .setDuration(120)
                    .start();
            v.setSelected(hasFocus);
            if (!hasFocus) {
                v.setFocusableInTouchMode(false);
            }
        };
        for (int id : drawerBtnIds) {
            View btn = findViewById(id);
            if (btn != null) {
                btn.setFocusable(true);
                btn.setBackgroundTintList(null);
                btn.setOnFocusChangeListener(drawerFocusListener);
                btn.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return false;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        drawerFocusOnCategories = true;
                        focusSelectedCategoryTab();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (v.getId() == R.id.btn_drawer_numpad) {
                            requestFocusCompat(cbOnlyAvailable);
                        } else {
                            requestFocusCompat(findViewById(R.id.btn_drawer_numpad));
                        }
                        return true;
                    }
                    return false;
                });
            }
        }

        if (etSearchChannel != null) {
            etSearchChannel.setOnClickListener(v -> {
                etSearchChannel.setFocusableInTouchMode(true);
                etSearchChannel.requestFocus();
            });
            etSearchChannel.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    v.setFocusableInTouchMode(false);
                }
            });
            etSearchChannel.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        focusSelectedCategoryTab();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        requestFocusCompat(findViewById(R.id.btn_drawer_numpad));
                        return true;
                    }
                }
                return false;
            });
        }
    }

    private void requestFocusCompat(View target) {
        if (target == null) {
            return;
        }
        if (target.isInTouchMode()) {
            target.setFocusableInTouchMode(true);
        }
        target.requestFocus();
    }

    public int getCurrentHudFocusIndex() {
        return currentHudFocusIndex;
    }

    public View getFocusedHudButton() {
        if (hudButtons == null || hudButtons.length == 0) {
            return null;
        }
        int idx = (currentHudFocusIndex % hudButtons.length + hudButtons.length) % hudButtons.length;
        return hudButtons[idx];
    }

    private void updateHudButtonsVisualState() {
        if (hudButtons == null) {
            return;
        }
        boolean showFocus = drawerPanel.getVisibility() != View.VISIBLE
                && hudOverlay.getVisibility() == View.VISIBLE;
        for (int i = 0; i < hudButtons.length; i++) {
            View btn = hudButtons[i];
            if (btn == null) {
                continue;
            }
            boolean isTarget = showFocus && (i == currentHudFocusIndex);
            btn.setSelected(isTarget);
            btn.setBackgroundResource(isTarget ? R.drawable.bg_md3_primary_button : R.drawable.bg_group_tab);
            btn.setBackgroundTintList(null);
            btn.setElevation(isTarget ? 12f : 0f);
            btn.setScaleX(isTarget ? 1.08f : 1.0f);
            btn.setScaleY(isTarget ? 1.08f : 1.0f);
        }
    }

    private void focusHudButtonAt(int index) {
        if (hudButtons == null || hudButtons.length == 0) {
            return;
        }
        currentHudFocusIndex = (index % hudButtons.length + hudButtons.length) % hudButtons.length;
        updateHudButtonsVisualState();
        View target = hudButtons[currentHudFocusIndex];
        if (target != null && drawerPanel.getVisibility() != View.VISIBLE && hudOverlay.getVisibility() == View.VISIBLE) {
            requestFocusCompat(target);
        }
    }

    private void moveHudButtonFocus(int delta) {
        focusHudButtonAt(currentHudFocusIndex + delta);
    }

    /**
     * 配置触屏手势：单击显隐HUD/关闭抽屉、双击切换画面比例、左右滑动开关频道抽屉、上下滑动换台
     */
    private void setupTouchGestures() {
        touchGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (drawerPanel.getVisibility() == View.VISIBLE) {
                    closeChannelDrawer();
                } else if (hudOverlay.getVisibility() == View.VISIBLE) {
                    hudOverlay.setVisibility(View.GONE);
                } else {
                    showHudTemporarily();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                cycleAspectRatio();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();

                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 90 && Math.abs(velocityX) > 200) {
                    if (dx > 0 && drawerPanel.getVisibility() != View.VISIBLE) {
                        openChannelDrawerAndFocus();
                        return true;
                    } else if (dx < 0 && drawerPanel.getVisibility() == View.VISIBLE) {
                        closeChannelDrawer();
                        return true;
                    }
                } else if (drawerPanel.getVisibility() != View.VISIBLE
                        && Math.abs(dy) > Math.abs(dx)
                        && Math.abs(dy) > 90
                        && Math.abs(velocityY) > 200) {
                    // 全屏上下滑动切换频道
                    if (dy < 0) {
                        switchAdjacentChannel(1);
                    } else {
                        switchAdjacentChannel(-1);
                    }
                    return true;
                }
                return false;
            }
        });

        View rootContainer = findViewById(R.id.root_container);
        rootContainer.setOnTouchListener((v, event) -> touchGestureDetector.onTouchEvent(event));
    }

    private void setupCategoryList() {
        rvCategories.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        categoryAdapter = new CategoryAdapter(repository.getCategoryGroups(),
                new CategoryAdapter.OnCategoryInteractionListener() {
                    @Override
                    public void onCategorySelected(String category, int position) {
                        refreshChannelList();
                    }

                    @Override
                    public void onRequestChannelListFocus() {
                        if (channelAdapter != null && channelAdapter.getItemCount() > 0) {
                            drawerFocusOnCategories = false;
                            focusChannelListAt(channelAdapter.getPlayingChannelIndex());
                        }
                    }

                    @Override
                    public void onRequestHeaderFocus() {
                        drawerFocusOnCategories = false;
                        requestFocusCompat(findViewById(R.id.btn_drawer_numpad));
                    }

                    @Override
                    public void onSwitchCategory(int delta) {
                        drawerFocusOnCategories = true;
                        switchCategoryInDrawer(delta);
                    }
                });
        rvCategories.setAdapter(categoryAdapter);
    }

    private void setupChannelList() {
        rvChannels.setLayoutManager(new LinearLayoutManager(this));
        channelAdapter = new ChannelAdapter(new ChannelAdapter.OnChannelActionListener() {
            @Override
            public void onChannelClicked(IptvChannel channel, int position) {
                playChannel(channel);
                closeChannelDrawer();
            }

            @Override
            public void onChannelLongClicked(IptvChannel channel, int position) {
                repository.toggleFavorite(channel);
                refreshChannelList();
                updateFavoriteButtonText();
                Toast.makeText(MainActivity.this,
                        channel.isFavorite() ? "★ 已加入收藏: " + channel.getDisplayName() : "☆ 已取消收藏: " + channel.getDisplayName(),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onSwitchCategory(int delta) {
                drawerFocusOnCategories = false;
                switchCategoryInDrawer(delta);
            }

            @Override
            public void onNavigateUpFromTop() {
                drawerFocusOnCategories = true;
                focusSelectedCategoryTab();
            }

            @Override
            public void onWrapToTop() {
                drawerFocusOnCategories = false;
                focusChannelListAt(0);
            }
        });
        rvChannels.setAdapter(channelAdapter);
    }

    private void switchCategoryInDrawer(int delta) {
        if (categoryAdapter == null) {
            return;
        }
        int newCatPos = categoryAdapter.selectAdjacentCategory(delta);
        rvCategories.scrollToPosition(newCatPos);
        if (drawerFocusOnCategories || channelAdapter == null || channelAdapter.getItemCount() == 0) {
            focusSelectedCategoryTab();
        } else {
            focusChannelListAt(channelAdapter.getPlayingChannelIndex());
        }
    }

    private void setupPlayerListeners() {
        videoView.setOnPreparedListener(mp -> {
            loadingOverlay.setVisibility(View.GONE);
            if (currentChannel != null) {
                currentChannel.setConnected(true);
                currentChannel.setLatencyMs(videoView.getPrepareCostMs());
                String vTrack = videoView.getFormattedVideoTrackInfo();
                String aTrack = videoView.getFormattedAudioTrackInfo();
                String detectedRes = videoView.getDetectedResolutionFlag();
                if (!TextUtils.isEmpty(vTrack)) {
                    currentChannel.setVideoTrackInfo(vTrack);
                }
                if (!TextUtils.isEmpty(aTrack)) {
                    currentChannel.setAudioTrackInfo(aTrack);
                }
                if (!TextUtils.isEmpty(detectedRes)) {
                    currentChannel.setActualResolutionFlag(detectedRes);
                }
                updateOsdWithChannel(currentChannel);
                channelAdapter.notifyDataSetChanged();
            }
            scheduleHideHud();
        });

        videoView.setOnInfoListener((mp, what, extra) -> {
            if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                loadingOverlay.setVisibility(View.VISIBLE);
                tvLoadingHint.setText("正在缓冲视频流...");
            } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END
                    || what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                loadingOverlay.setVisibility(View.GONE);
                if (currentChannel != null) {
                    updateOsdWithChannel(currentChannel);
                }
            }
            return true;
        });

        videoView.setOnErrorListener((mp, frameworkErr, implErr) -> {
            if (currentChannel != null && !fallbackToLocalProxyAttempted) {
                fallbackToLocalProxyAttempted = true;
                if (settings.getStreamMode() == IptvSettings.STREAM_MODE_UDPXY) {
                    udpxyUnreachableInSession = true;
                }
                int localProxyPort = (getApplication() instanceof IptvApplication)
                        ? ((IptvApplication) getApplication()).getLocalProxyPort()
                        : -1;
                if (localProxyPort > 0) {
                    String localUrl = StreamUrlResolver.toUdpxyUrl(currentChannel.getRawUrl(), "127.0.0.1:" + localProxyPort);
                    tvLoadingHint.setText("正在通过网卡 IGMP 组播直连 " + currentChannel.getDisplayName() + "...");
                    tvOsdStreamUrl.setText("RTP 组播直连: " + currentChannel.getRawUrl() + "  ➔  " + localUrl);
                    videoView.setRawChannelUrl(currentChannel.getRawUrl());
                    videoView.setVideoPath(localUrl);
                    videoView.start();
                    return true;
                }
            }
            loadingOverlay.setVisibility(View.GONE);
            if (currentChannel != null) {
                currentChannel.setConnected(false);
                channelAdapter.notifyDataSetChanged();
            }
            tvOsdTrackInfo.setText("连接失败 (请按遥控器[菜单]检查 udpxy/内置组播代理设置，或按[绿键]打开「组播动态扫描」)");
            showHudTemporarily();
            return true;
        });
    }

    public void refreshChannelList() {
        String category = categoryAdapter != null ? categoryAdapter.getSelectedCategory() : ChannelRepository.GROUP_ALL;
        String query = etSearchChannel != null ? etSearchChannel.getText().toString() : "";
        boolean onlyAvailable = cbOnlyAvailable != null && cbOnlyAvailable.isChecked();

        List<IptvChannel> filtered = repository.filterChannels(category, query, onlyAvailable);
        channelAdapter.submitList(filtered);

        List<IptvChannel> all = repository.getAllChannels();
        int availableCount = 0;
        for (IptvChannel c : all) {
            if (Boolean.TRUE.equals(c.getConnected())) {
                availableCount++;
            }
        }
        String statsSuffix = availableCount > 0
                ? String.format(Locale.US, " (%d/%d 可用)", availableCount, all.size())
                : String.format(Locale.US, " (共%d频道)", all.size());
        tvDrawerTitle.setText("uTV" + statsSuffix);
    }

    private void playInitialChannel() {
        String lastUrl = settings.getLastChannelUrl();
        List<IptvChannel> all = repository.getAllChannels();
        IptvChannel target = null;
        for (IptvChannel ch : all) {
            if (lastUrl.equals(ch.getRawUrl())) {
                target = ch;
                break;
            }
        }
        if (target == null && !all.isEmpty()) {
            target = all.get(0);
        }
        if (target != null) {
            playChannel(target);
        }
    }

    public void playChannel(IptvChannel channel) {
        if (channel == null) {
            return;
        }
        currentChannel = channel;
        fallbackToLocalProxyAttempted = false;
        settings.setLastChannelUrl(channel.getRawUrl());
        channelAdapter.setCurrentPlayingUrl(channel.getRawUrl());

        int localProxyPort = -1;
        if (getApplication() instanceof IptvApplication) {
            localProxyPort = ((IptvApplication) getApplication()).getLocalProxyPort();
        }

        String resolvedUrl;
        if (settings.getStreamMode() == IptvSettings.STREAM_MODE_UDPXY
                && udpxyUnreachableInSession
                && localProxyPort > 0) {
            // 若当前会话已检测到外部 udpxy 不可达，切台时直接走本地网卡组播直连，避免每次切台等待 udpxy 超时
            fallbackToLocalProxyAttempted = true;
            resolvedUrl = StreamUrlResolver.toUdpxyUrl(channel.getRawUrl(), "127.0.0.1:" + localProxyPort);
        } else {
            resolvedUrl = StreamUrlResolver.resolvePlaybackUrl(channel, settings, localProxyPort);
        }
        loadingOverlay.setVisibility(View.VISIBLE);
        tvLoadingHint.setText(String.format(Locale.US, "正在连接 [%s] %s...",
                channel.getChannelNumberDisplay(), channel.getDisplayName()));

        updateOsdWithChannel(channel);
        showHudTemporarily();

        videoView.setRawChannelUrl(channel.getRawUrl());
        videoView.setVideoPath(resolvedUrl);
        videoView.start();
    }

    private void updateOsdWithChannel(IptvChannel ch) {
        if (ch == null) {
            return;
        }
        int localProxyPort = (getApplication() instanceof IptvApplication)
                ? ((IptvApplication) getApplication()).getLocalProxyPort()
                : -1;
        String resolvedUrl = StreamUrlResolver.resolvePlaybackUrl(ch, settings, localProxyPort);

        tvTopChannelTitle.setText(String.format(Locale.US, "%s  %s [%s]",
                ch.getChannelNumberDisplay(), ch.getDisplayName(), ch.getResolutionTag()));
        tvOsdChannelNum.setText(ch.getChannelNumberDisplay());
        tvOsdChannelName.setText(ch.getDisplayName());
        String resTag = ch.getResolutionTag();
        tvOsdResolutionBadge.setText(resTag);
        if ("4K".equals(resTag)) {
            tvOsdResolutionBadge.setBackgroundResource(R.drawable.bg_res_badge_4k);
        } else if ("高清".equals(resTag)) {
            tvOsdResolutionBadge.setBackgroundResource(R.drawable.bg_res_badge_hd);
        } else {
            tvOsdResolutionBadge.setBackgroundResource(R.drawable.bg_res_badge_sd);
        }
        tvOsdGroupName.setText(TextUtils.join(" / ", ch.getGroups()));

        if (settings.getStreamMode() == IptvSettings.STREAM_MODE_DIRECT_RTP) {
            tvOsdStreamUrl.setText("RTP 组播直连 (网卡 IGMP): " + ch.getRawUrl());
        } else if (resolvedUrl.equals(ch.getRawUrl())) {
            tvOsdStreamUrl.setText("组播直连: " + ch.getRawUrl());
        } else {
            tvOsdStreamUrl.setText("组播源: " + ch.getRawUrl() + "  ➔  " + resolvedUrl);
        }

        String vTrack = !TextUtils.isEmpty(ch.getVideoTrackInfo())
                ? ch.getVideoTrackInfo()
                : videoView.getFormattedVideoTrackInfo();
        String aTrack = !TextUtils.isEmpty(ch.getAudioTrackInfo())
                ? ch.getAudioTrackInfo()
                : videoView.getFormattedAudioTrackInfo();

        if (!TextUtils.isEmpty(vTrack)) {
            tvOsdTrackInfo.setText(String.format(Locale.US, "Video: %s | Audio: %s | %s",
                    vTrack, aTrack, videoView.getDecoderName()));
        } else {
            tvOsdTrackInfo.setText("解码器: " + videoView.getDecoderName() + " | 等待流媒体轨道信息...");
        }

        updateFavoriteButtonText();
    }

    private void toggleCurrentChannelFavorite() {
        if (currentChannel != null) {
            repository.toggleFavorite(currentChannel);
            updateFavoriteButtonText();
            refreshChannelList();
            Toast.makeText(this,
                    currentChannel.isFavorite()
                            ? "★ 已收藏: " + currentChannel.getDisplayName()
                            : "☆ 已取消收藏: " + currentChannel.getDisplayName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void cycleAspectRatio() {
        int ratio = videoView.toggleAspectRatio();
        String ratioText = MeasureHelper.getAspectRatioText(this, ratio);
        Toast.makeText(this, "画面比例: " + ratioText, Toast.LENGTH_SHORT).show();
        showHudTemporarily();
    }

    private void openScannerActivity() {
        Intent intent = new Intent(MainActivity.this, ScannerActivity.class);
        startActivity(intent);
    }

    private void updateFavoriteButtonText() {
        if (currentChannel != null && currentChannel.isFavorite()) {
            btnFavoriteChannel.setText("已收藏");
            btnFavoriteChannel.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_flat_star_filled, 0, 0, 0);
        } else {
            btnFavoriteChannel.setText("收藏");
            btnFavoriteChannel.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_flat_star_outline, 0, 0, 0);
        }
    }

    private void updateLiveTelemetry() {
        if (videoView == null) {
            return;
        }
        long speedBytes = videoView.getTcpSpeed();
        float fps = videoView.getVideoOutputFramesPerSecond();
        String speedStr;
        if (speedBytes >= 1024 * 1024) {
            speedStr = String.format(Locale.US, "%.2f MB/s", speedBytes / (1024f * 1024f));
        } else if (speedBytes > 0) {
            speedStr = String.format(Locale.US, "%d KB/s", speedBytes / 1024);
        } else {
            speedStr = "直播流";
        }
        int w = videoView.getVideoWidth();
        int h = videoView.getVideoHeight();
        String dimStr = (w > 0 && h > 0) ? (w + "x" + h + " | ") : "";
        String fpsStr = fps > 0 ? String.format(Locale.US, "%.1f fps", fps) : "25 fps";
        tvTopStreamSpeed.setText(dimStr + speedStr + " | " + fpsStr);
    }

    private void switchAdjacentChannel(int delta) {
        List<IptvChannel> list = channelAdapter.getChannels();
        if (list.isEmpty()) {
            list = repository.getAllChannels();
        }
        if (list.isEmpty()) {
            return;
        }
        int curIdx = 0;
        if (currentChannel != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getRawUrl().equals(currentChannel.getRawUrl())) {
                    curIdx = i;
                    break;
                }
            }
        }
        int nextIdx = (curIdx + delta + list.size()) % list.size();
        playChannel(list.get(nextIdx));
        rvChannels.scrollToPosition(nextIdx);
    }

    private void toggleChannelDrawer() {
        if (drawerPanel.getVisibility() == View.VISIBLE) {
            closeChannelDrawer();
        } else {
            openChannelDrawerAndFocus();
        }
    }

    private void openChannelDrawerAndFocus() {
        drawerPanel.setVisibility(View.VISIBLE);
        hudOverlay.setVisibility(View.VISIBLE);
        drawerFocusOnCategories = false;
        updateHudButtonsVisualState();
        if (channelAdapter != null && channelAdapter.getItemCount() > 0) {
            focusChannelListAt(channelAdapter.getPlayingChannelIndex());
        } else {
            focusSelectedCategoryTab();
        }
        scheduleHideHud();
    }

    private void closeChannelDrawer() {
        drawerPanel.setVisibility(View.GONE);
        showHudTemporarily();
    }

    private void focusChannelListAt(int position) {
        if (channelAdapter == null || channelAdapter.getItemCount() == 0) {
            return;
        }
        int safePos = Math.max(0, Math.min(position, channelAdapter.getItemCount() - 1));
        rvChannels.scrollToPosition(safePos);
        rvChannels.post(() -> {
            RecyclerView.ViewHolder vh = rvChannels.findViewHolderForAdapterPosition(safePos);
            if (vh != null && vh.itemView != null) {
                requestFocusCompat(vh.itemView);
            } else {
                rvChannels.post(() -> {
                    RecyclerView.ViewHolder delayedVh = rvChannels.findViewHolderForAdapterPosition(safePos);
                    if (delayedVh != null && delayedVh.itemView != null) {
                        requestFocusCompat(delayedVh.itemView);
                    } else {
                        requestFocusCompat(rvChannels);
                    }
                });
            }
        });
    }

    private void focusSelectedCategoryTab() {
        if (categoryAdapter == null || categoryAdapter.getItemCount() == 0) {
            return;
        }
        int pos = categoryAdapter.getSelectedPosition();
        rvCategories.scrollToPosition(pos);
        rvCategories.post(() -> {
            RecyclerView.ViewHolder vh = rvCategories.findViewHolderForAdapterPosition(pos);
            if (vh != null && vh.itemView != null) {
                requestFocusCompat(vh.itemView);
            } else {
                rvCategories.post(() -> {
                    RecyclerView.ViewHolder delayedVh = rvCategories.findViewHolderForAdapterPosition(pos);
                    if (delayedVh != null && delayedVh.itemView != null) {
                        requestFocusCompat(delayedVh.itemView);
                    } else {
                        requestFocusCompat(rvCategories);
                    }
                });
            }
        });
    }

    private boolean isHudButtonFocused() {
        if (hudOverlay.getVisibility() != View.VISIBLE || drawerPanel.getVisibility() == View.VISIBLE) {
            return false;
        }
        View focused = getCurrentFocus();
        if (focused != null) {
            int id = focused.getId();
            if (id == R.id.btn_toggle_drawer
                    || id == R.id.btn_open_scanner
                    || id == R.id.btn_open_settings
                    || id == R.id.btn_prev_channel
                    || id == R.id.btn_next_channel
                    || id == R.id.btn_numpad_dialog
                    || id == R.id.btn_favorite_channel
                    || id == R.id.btn_aspect_ratio
                    || id == R.id.btn_reload_stream) {
                return true;
            }
        }
        View target = getFocusedHudButton();
        return target != null && target.isSelected();
    }

    private void showHudTemporarily() {
        hudOverlay.setVisibility(View.VISIBLE);
        if (drawerPanel.getVisibility() != View.VISIBLE) {
            focusHudButtonAt(currentHudFocusIndex);
        }
        scheduleHideHud();
    }

    private void scheduleHideHud() {
        mainHandler.removeCallbacks(hideHudRunnable);
        mainHandler.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_MS);
    }

    /**
     * 针对无数字按键、无红绿黄蓝四色键的智能电视极简遥控器：
     * 提供方向键可操控的虚拟数字选台键盘 (3x4 网格，默认聚焦数字 5 居中位)
     */
    private void showVirtualNumpadDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_virtual_numpad, null);
        TextView tvDigits = view.findViewById(R.id.tv_numpad_digits);
        TextView tvPreview = view.findViewById(R.id.tv_numpad_preview);
        StringBuilder inputBuf = new StringBuilder();

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(view)
                .setNegativeButton("关闭", null)
                .create();

        Runnable updatePreview = () -> {
            if (inputBuf.length() == 0) {
                tvDigits.setText("---");
                tvPreview.setText("请选择频道号 (如: 1=CCTV1, 21=北京卫视, 85=CCTV-4K)");
                return;
            }
            String numStr = inputBuf.toString();
            tvDigits.setText(numStr);
            try {
                int num = Integer.parseInt(numStr);
                IptvChannel match = repository.findByChannelNumber(num);
                if (match != null) {
                    tvPreview.setText(String.format(Locale.US, "匹配频道: [%s] %s (%s)",
                            match.getChannelNumberDisplay(), match.getDisplayName(), match.getResolutionTag()));
                } else {
                    tvPreview.setText("未找到台号 " + num + " 对应的频道");
                }
            } catch (NumberFormatException ignored) {
            }
        };

        int[] btnIds = {
                R.id.btn_numpad_0, R.id.btn_numpad_1, R.id.btn_numpad_2, R.id.btn_numpad_3,
                R.id.btn_numpad_4, R.id.btn_numpad_5, R.id.btn_numpad_6,
                R.id.btn_numpad_7, R.id.btn_numpad_8, R.id.btn_numpad_9
        };
        for (int i = 0; i <= 9; i++) {
            final int digit = i;
            Button b = view.findViewById(btnIds[i]);
            b.setOnFocusChangeListener((v, hasFocus) -> {
                v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(100).start();
                v.setSelected(hasFocus);
            });
            b.setOnClickListener(v -> {
                if (inputBuf.length() >= 4) {
                    inputBuf.setLength(0);
                }
                inputBuf.append(digit);
                updatePreview.run();
            });
        }

        View btnDel = view.findViewById(R.id.btn_numpad_del);
        btnDel.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(100).start();
            v.setSelected(hasFocus);
        });
        btnDel.setOnClickListener(v -> {
            if (inputBuf.length() > 0) {
                inputBuf.deleteCharAt(inputBuf.length() - 1);
                updatePreview.run();
            }
        });
        btnDel.setOnLongClickListener(v -> {
            inputBuf.setLength(0);
            updatePreview.run();
            return true;
        });

        View btnGo = view.findViewById(R.id.btn_numpad_go);
        btnGo.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.08f : 1.0f).scaleY(hasFocus ? 1.08f : 1.0f).setDuration(100).start();
            v.setSelected(hasFocus);
        });
        btnGo.setOnClickListener(v -> {
            if (inputBuf.length() == 0) {
                Toast.makeText(this, "请先输入台号", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                int targetNum = Integer.parseInt(inputBuf.toString());
                IptvChannel target = repository.findByChannelNumber(targetNum);
                if (target != null) {
                    dialog.dismiss();
                    closeChannelDrawer();
                    playChannel(target);
                } else {
                    Toast.makeText(this, "未找到台号: " + targetNum, Toast.LENGTH_SHORT).show();
                }
            } catch (NumberFormatException ignored) {
            }
        });

        dialog.show();
        // 默认将遥控器焦点放在正中央的数字 "5" 上，方便上下左右一步触达任意数字
        View centerBtn = view.findViewById(R.id.btn_numpad_5);
        if (centerBtn != null) {
            centerBtn.post(centerBtn::requestFocus);
        }
    }

    /**
     * 针对智能电视极简遥控器（无数字键、无四色键）的全功能快捷操作菜单：
     * 全屏下长按【OK键】或按【菜单键】呼出，所有高频功能均可通过上下键 + OK 一键完成。
     */
    private void showSmartRemoteQuickMenu() {
        String favText = (currentChannel != null && currentChannel.isFavorite())
                ? "取消收藏当前频道 (" + currentChannel.getDisplayName() + ")"
                : "收藏当前频道 (" + (currentChannel != null ? currentChannel.getDisplayName() : "") + ")";

        String[] items = {
                "虚拟数字键盘选台 (按台号直达)",
                "打开频道分类列表 (左右键切换分类)",
                favText,
                "切换画面比例 (16:9 / 4:3 / 铺满 / 适应)",
                "重新连接当前频道流",
                "播放与网络转发设置 (udpxy / RTP 组播直连)",
                "组播地址动态扫描与巡检 (check_iptv_m3u.sh)"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("智能遥控器快捷操作面板")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showVirtualNumpadDialog();
                            break;
                        case 1:
                            openChannelDrawerAndFocus();
                            break;
                        case 2:
                            toggleCurrentChannelFavorite();
                            break;
                        case 3:
                            cycleAspectRatio();
                            break;
                        case 4:
                            if (currentChannel != null) {
                                playChannel(currentChannel);
                            }
                            break;
                        case 5:
                            showSettingsDialog();
                            break;
                        case 6:
                            openScannerActivity();
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSettingsDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_player_settings, null);
        RadioGroup rgStreamMode = view.findViewById(R.id.rg_stream_mode);
        EditText etUdpxy = view.findViewById(R.id.et_settings_udpxy);
        RadioGroup rgPlayerCore = view.findViewById(R.id.rg_player_core);
        CheckBox cbMediaCodec = view.findViewById(R.id.cb_settings_mediacodec);
        CheckBox cbHevc = view.findViewById(R.id.cb_settings_hevc);
        CheckBox cbOpenSlEs = view.findViewById(R.id.cb_settings_opensles);
        CheckBox cbTextureView = view.findViewById(R.id.cb_settings_textureview);
        CheckBox cbAllChannels = view.findViewById(R.id.cb_settings_all_channels);

        int mode = settings.getStreamMode();
        if (mode == IptvSettings.STREAM_MODE_UDPXY) {
            rgStreamMode.check(R.id.rb_stream_udpxy);
        } else if (mode == IptvSettings.STREAM_MODE_LOCAL_PROXY) {
            rgStreamMode.check(R.id.rb_stream_local_proxy);
        } else {
            rgStreamMode.check(R.id.rb_stream_direct_rtp);
        }

        etUdpxy.setText(settings.getUdpxyAddress());

        int playerCore = settings.getPlayerType();
        if (playerCore == IptvSettings.PLAYER_IJK_EXO) {
            rgPlayerCore.check(R.id.rb_core_exo);
        } else if (playerCore == IptvSettings.PLAYER_ANDROID_MEDIA) {
            rgPlayerCore.check(R.id.rb_core_android);
        } else {
            rgPlayerCore.check(R.id.rb_core_ijk);
        }

        cbMediaCodec.setChecked(settings.isMediaCodecEnabled());
        cbHevc.setChecked(settings.isMediaCodecHevcEnabled());
        cbOpenSlEs.setChecked(settings.isOpenSlEsEnabled());
        cbTextureView.setChecked(settings.getRenderViewType() == IjkVideoView.RENDER_TEXTURE_VIEW);
        cbAllChannels.setChecked(settings.isIncludeAllRepoChannels());

        new MaterialAlertDialogBuilder(this)
                .setTitle("uTV 播放与网络配置")
                .setView(view)
                .setPositiveButton("保存并应用", (dialog, which) -> {
                    int checkedMode = rgStreamMode.getCheckedRadioButtonId();
                    if (checkedMode == R.id.rb_stream_udpxy) {
                        settings.setStreamMode(IptvSettings.STREAM_MODE_UDPXY);
                    } else if (checkedMode == R.id.rb_stream_local_proxy) {
                        settings.setStreamMode(IptvSettings.STREAM_MODE_LOCAL_PROXY);
                    } else {
                        settings.setStreamMode(IptvSettings.STREAM_MODE_DIRECT_RTP);
                    }

                    settings.setUdpxyAddress(etUdpxy.getText().toString());
                    udpxyUnreachableInSession = false;

                    int checkedCore = rgPlayerCore.getCheckedRadioButtonId();
                    if (checkedCore == R.id.rb_core_exo) {
                        settings.setPlayerType(IptvSettings.PLAYER_IJK_EXO);
                    } else if (checkedCore == R.id.rb_core_android) {
                        settings.setPlayerType(IptvSettings.PLAYER_ANDROID_MEDIA);
                    } else {
                        settings.setPlayerType(IptvSettings.PLAYER_IJK);
                    }

                    settings.setMediaCodecEnabled(cbMediaCodec.isChecked());
                    settings.setMediaCodecHevcEnabled(cbHevc.isChecked());
                    settings.setOpenSlEsEnabled(cbOpenSlEs.isChecked());
                    int renderType = cbTextureView.isChecked()
                            ? IjkVideoView.RENDER_TEXTURE_VIEW
                            : IjkVideoView.RENDER_SURFACE_VIEW;
                    int prevRenderType = settings.getRenderViewType();
                    settings.setRenderViewType(renderType);
                    if (prevRenderType != renderType) {
                        videoView.setRender(renderType);
                    }

                    boolean prevAll = settings.isIncludeAllRepoChannels();
                    if (prevAll != cbAllChannels.isChecked()) {
                        settings.setIncludeAllRepoChannels(cbAllChannels.isChecked());
                        repository.reloadChannels();
                    }

                    refreshChannelList();
                    if (currentChannel != null) {
                        playChannel(currentChannel);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 全局遥控器按键分发拦截（优先于子控件焦点处理）：
     * - 数字键 0~9 / 小键盘 0~9：输入台号选台，OSD 实时预览台名，按 OK 键立即跳转
     * - 频道加减键 (CHANNEL_UP / CHANNEL_DOWN / PAGE_UP / PAGE_DOWN)：随时上下换台
     * - 全屏状态下（抽屉关闭）：
     *   - 上/下方向键：直接切换上/下一频道
     *   - OK / Enter / 左方向键：打开左侧频道抽屉并自动将焦点落在当前频道上
     *   - 右方向键：唤出底部操控栏并将遥控器焦点移入功能按钮
     * - 机顶盒四色功能键与快捷键：
     *   - 菜单键 / 蓝键 (PROG_BLUE)：打开播放与 udpxy 设置
     *   - 绿键 (PROG_GREEN) / GUIDE：打开组播地址动态扫描器
     *   - 黄键 (PROG_YELLOW)：切换画面比例
     *   - 红键 (PROG_RED)：收藏/取消收藏频道
     *   - INFO 键：显隐流媒体信息栏
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (hudOverlay.getVisibility() == View.VISIBLE || drawerPanel.getVisibility() == View.VISIBLE) {
            scheduleHideHud();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();

            if (hudOverlay.getVisibility() == View.VISIBLE || drawerPanel.getVisibility() == View.VISIBLE) {
                scheduleHideHud();
            }

            // 1. 搜索输入框未处于焦点时，拦截 0-9 及小键盘数字键用于遥控器数字选台
            boolean searchFocused = etSearchChannel != null && etSearchChannel.hasFocus();
            if (!searchFocused) {
                int digit = extractDigitFromKeyCode(keyCode);
                if (digit >= 0) {
                    handleRemoteNumberDigit(digit);
                    return true;
                }
                // 当正在输入台号时，按 OK/Enter 立即确认切台，按返回/删除键取消输入
                if (remoteNumberBuffer.length() > 0) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER
                            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        mainHandler.removeCallbacks(commitNumberInputRunnable);
                        commitNumberInputRunnable.run();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DEL) {
                        mainHandler.removeCallbacks(commitNumberInputRunnable);
                        remoteNumberBuffer.setLength(0);
                        tvNumberInputOsd.setVisibility(View.GONE);
                        return true;
                    }
                }
            }

            // 2. 遥控器专用频道加减键 / 翻页键随时换台 (上=下一台/+1，下=上一台/-1)
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                switchAdjacentChannel(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
                switchAdjacentChannel(-1);
                return true;
            }

            // 3. 遥控器菜单键与四色快捷键（针对无四色键的智能遥控器，按菜单键打开全功能快捷菜单）
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
                showSmartRemoteQuickMenu();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_PROG_BLUE) {
                showSettingsDialog();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_PROG_GREEN || keyCode == KeyEvent.KEYCODE_GUIDE) {
                openScannerActivity();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_PROG_YELLOW || keyCode == KeyEvent.KEYCODE_CAPTIONS) {
                cycleAspectRatio();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (hudOverlay.getVisibility() == View.VISIBLE && drawerPanel.getVisibility() != View.VISIBLE) {
                    hudOverlay.setVisibility(View.GONE);
                } else {
                    showHudTemporarily();
                }
                return true;
            }

            // 4. 当左侧频道列表抽屉打开时的遥控器方向键循环切换逻辑
            if (drawerPanel.getVisibility() == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    View focused = getCurrentFocus();
                    int focusedId = focused != null ? focused.getId() : View.NO_ID;
                    if (focusedId == R.id.btn_drawer_numpad) {
                        requestFocusCompat(cbOnlyAvailable);
                        return true;
                    } else if (focusedId == R.id.cb_only_available) {
                        requestFocusCompat(findViewById(R.id.btn_drawer_numpad));
                        return true;
                    } else if (focusedId == R.id.et_search_channel
                            && etSearchChannel.getText() != null
                            && etSearchChannel.getText().length() > 0) {
                        return super.dispatchKeyEvent(event);
                    }
                    int delta = (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) ? -1 : 1;
                    switchCategoryInDrawer(delta);
                    return true;
                }
            } else {
                // 当左侧频道列表抽屉关闭时的全屏遥控器方向键与长按 OK 逻辑
                if (keyCode == KeyEvent.KEYCODE_PROG_RED || keyCode == KeyEvent.KEYCODE_BOOKMARK) {
                    toggleCurrentChannelFavorite();
                    return true;
                }

                // 播放时方向键逻辑：
                // - 左/右方向键：切换页面上所有 HUD 按钮的焦点（若 HUD 已隐藏则先唤出 HUD 并聚焦按钮）
                // - 上/下方向键：直接切换上/下一频道
                // - 短按 OK：若 HUD 已显示则触发当前聚焦的按钮点击；若 HUD 未显示则打开频道抽屉
                // - 长按 OK 键：弹出「智能遥控器快捷操作面板」
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (hudOverlay.getVisibility() != View.VISIBLE) {
                        showHudTemporarily();
                    } else {
                        scheduleHideHud();
                        moveHudButtonFocus(-1);
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (hudOverlay.getVisibility() != View.VISIBLE) {
                        showHudTemporarily();
                    } else {
                        scheduleHideHud();
                        moveHudButtonFocus(1);
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    switchAdjacentChannel(1);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    switchAdjacentChannel(-1);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (event.getRepeatCount() > 0) {
                        longPressCenterHandled = true;
                        showSmartRemoteQuickMenu();
                    } else {
                        longPressCenterHandled = false;
                    }
                    return true;
                }
            }

            // 5. 返回键分层处理：先关抽屉 -> 再关HUD操控栏 -> 双击退出应用
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (drawerPanel.getVisibility() == View.VISIBLE) {
                    closeChannelDrawer();
                    return true;
                }
                if (hudOverlay.getVisibility() == View.VISIBLE) {
                    hudOverlay.setVisibility(View.GONE);
                    return true;
                }
                long now = System.currentTimeMillis();
                if (now - lastBackPressTimeMs > DOUBLE_BACK_EXIT_MS) {
                    lastBackPressTimeMs = now;
                    Toast.makeText(this, "再按一次返回键退出 uTV", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            int keyCode = event.getKeyCode();
            // 全屏播放时，短按松开 OK 键：若 HUD 显示则执行当前聚焦按钮功能，否则打开频道列表
            if (drawerPanel.getVisibility() != View.VISIBLE
                    && remoteNumberBuffer.length() == 0
                    && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                boolean wasLongPress = longPressCenterHandled;
                longPressCenterHandled = false;
                if (!wasLongPress && !event.isCanceled() && (event.getEventTime() - event.getDownTime() < 500)) {
                    if (hudOverlay.getVisibility() == View.VISIBLE) {
                        View focusedBtn = getFocusedHudButton();
                        if (focusedBtn != null) {
                            scheduleHideHud();
                            focusedBtn.performClick();
                            return true;
                        }
                    }
                    openChannelDrawerAndFocus();
                    return true;
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int extractDigitFromKeyCode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return keyCode - KeyEvent.KEYCODE_0;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }
        return -1;
    }

    private void handleRemoteNumberDigit(int digit) {
        if (remoteNumberBuffer.length() >= 4) {
            remoteNumberBuffer.setLength(0);
        }
        remoteNumberBuffer.append(digit);
        String numStr = remoteNumberBuffer.toString();
        String previewText = numStr;
        try {
            int num = Integer.parseInt(numStr);
            IptvChannel match = repository.findByChannelNumber(num);
            if (match != null) {
                previewText = String.format(Locale.US, "%s  %s", numStr, match.getDisplayName());
            } else {
                previewText = numStr + "  (未匹配)";
            }
        } catch (NumberFormatException ignored) {
        }
        tvNumberInputOsd.setText(previewText);
        tvNumberInputOsd.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(commitNumberInputRunnable);
        mainHandler.postDelayed(commitNumberInputRunnable, NUMBER_INPUT_COMMIT_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshChannelList();
        mainHandler.post(telemetryTickRunnable);
        if (currentChannel != null && !videoView.isPlaying()) {
            videoView.start();
        }
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(telemetryTickRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }
}
