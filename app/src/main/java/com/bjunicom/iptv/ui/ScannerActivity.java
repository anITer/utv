package com.bjunicom.iptv.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bjunicom.iptv.R;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.bjunicom.iptv.scanner.MulticastScannerEngine;
import com.bjunicom.iptv.scanner.ScanStatistics;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 组播地址动态扫描与 check_iptv_m3u.sh 脚本巡检界面
 * 同时支持触屏操作与电视遥控器方向键、绿键(启动/停止扫描)、菜单键(导出报告)操作
 */
public class ScannerActivity extends AppCompatActivity {

    private RadioGroup rgScanMode;
    private EditText etScanSubnet;
    private EditText etScanStart;
    private EditText etScanEnd;
    private EditText etScanUdpxy;
    private EditText etScanTimeout;
    private CheckBox cbFullPortScan;
    private Button btnStartScan;
    private Button btnStopScan;
    private TextView tvProgressLabel;
    private ProgressBar pbProgress;
    private TextView tvStatsSummary;
    private ScrollView svLog;
    private TextView tvConsoleLog;

    private IptvSettings settings;
    private ChannelRepository repository;
    private MulticastScannerEngine scannerEngine;
    private CustomM3uDialogHelper customM3uDialogHelper;
    private ScanStatistics lastStats;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        settings = new IptvSettings(this);
        repository = ChannelRepository.getInstance(this);
        scannerEngine = new MulticastScannerEngine(this);
        customM3uDialogHelper = new CustomM3uDialogHelper(this, (report, switchedToCustomIsp) -> {
            etScanSubnet.setText(settings.getScanSubnet());
            rgScanMode.check(R.id.rb_mode_check_playlist);
            appendConsoleLog("----------------------------------------");
            appendConsoleLog(report.toSummaryText());
            appendConsoleLog("[IMPORT] 已导入自定义 M3U (" + report.sourceName + ")，可点击「开始动态扫描」对全部频道执行 check_iptv_m3u.sh 巡检。");
            tvProgressLabel.setText(String.format(Locale.US,
                    "已导入 %s (%d 频道)，按[OK]或[绿键]启动频道流巡检",
                    report.sourceName, report.validChannelCount));
        });

        bindViews();

        // 默认将遥控器焦点放在「开始动态扫描」按钮上，按 OK 键即可直接开扫
        btnStartScan.post(() -> btnStartScan.requestFocus());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (customM3uDialogHelper != null) {
            customM3uDialogHelper.handleActivityResult(requestCode, resultCode, data);
        }
    }

    private void bindViews() {
        rgScanMode = findViewById(R.id.rg_scan_mode);
        etScanSubnet = findViewById(R.id.et_scan_subnet);
        etScanStart = findViewById(R.id.et_scan_start);
        etScanEnd = findViewById(R.id.et_scan_end);
        etScanUdpxy = findViewById(R.id.et_scan_udpxy);
        etScanTimeout = findViewById(R.id.et_scan_timeout);
        cbFullPortScan = findViewById(R.id.cb_full_port_scan);
        btnStartScan = findViewById(R.id.btn_start_scan);
        btnStopScan = findViewById(R.id.btn_stop_scan);
        tvProgressLabel = findViewById(R.id.tv_scan_progress_label);
        pbProgress = findViewById(R.id.pb_scan_progress);
        tvStatsSummary = findViewById(R.id.tv_scan_stats_summary);
        svLog = findViewById(R.id.sv_scan_log);
        tvConsoleLog = findViewById(R.id.tv_scan_console_log);

        etScanSubnet.setText(settings.getScanSubnet());
        etScanStart.setText(String.valueOf(settings.getScanStartHost()));
        etScanEnd.setText(String.valueOf(settings.getScanEndHost()));
        etScanUdpxy.setText(settings.getUdpxyAddress());
        etScanTimeout.setText(String.valueOf(settings.getScanTimeoutMs()));

        findViewById(R.id.btn_scanner_back).setOnClickListener(v -> finish());
        View btnImportM3u = findViewById(R.id.btn_import_m3u);
        if (btnImportM3u != null) {
            btnImportM3u.setOnClickListener(v -> customM3uDialogHelper.showUploadAndInspectDialog());
        }
        findViewById(R.id.btn_export_m3u).setOnClickListener(v -> showExportSummaryDialog());

        btnStartScan.setOnClickListener(v -> startScanning());
        btnStopScan.setOnClickListener(v -> stopScanningByUser());

        View.OnFocusChangeListener focusScale = (v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.03f : 1.0f).scaleY(hasFocus ? 1.03f : 1.0f).setDuration(120).start();
            v.setSelected(hasFocus);
            if (hasFocus) {
                ScrollView svParams = findViewById(R.id.sv_scanner_params);
                if (svParams != null && svParams.getHeight() > 0) {
                    android.graphics.Rect rect = new android.graphics.Rect();
                    v.getDrawingRect(rect);
                    try {
                        svParams.offsetDescendantRectToMyCoords(v, rect);
                        int targetScrollY = Math.max(0, rect.centerY() - svParams.getHeight() / 2);
                        svParams.smoothScrollTo(0, targetScrollY);
                    } catch ( IllegalArgumentException ignored) {
                    }
                }
            }
        };
        int[] scannerFocusableIds = {
                R.id.btn_scanner_back,
                R.id.btn_import_m3u,
                R.id.btn_export_m3u,
                R.id.btn_start_scan,
                R.id.btn_stop_scan,
                R.id.rb_mode_dynamic_subnet,
                R.id.rb_mode_check_playlist,
                R.id.rb_mode_shell_script,
                R.id.cb_full_port_scan
        };
        for (int id : scannerFocusableIds) {
            View v = findViewById(id);
            if (v != null) {
                v.setOnFocusChangeListener(focusScale);
            }
        }
    }

    private void stopScanningByUser() {
        scannerEngine.cancel();
        btnStartScan.setEnabled(true);
        btnStopScan.setEnabled(false);
        btnStartScan.requestFocus();
        appendConsoleLog("[USER] 已停止当前扫描任务。");
    }

    private void startScanning() {
        String subnet = etScanSubnet.getText().toString().trim();
        int startHost = parseIntSafe(etScanStart.getText().toString(), 1);
        int endHost = parseIntSafe(etScanEnd.getText().toString(), 254);
        String udpxy = etScanUdpxy.getText().toString().trim();
        int timeoutMs = parseIntSafe(etScanTimeout.getText().toString(), 1500);
        boolean fullPort = cbFullPortScan.isChecked();

        settings.setScanSubnet(subnet);
        settings.setScanStartHost(startHost);
        settings.setScanEndHost(endHost);
        settings.setUdpxyAddress(udpxy);
        settings.setScanTimeoutMs(timeoutMs);

        int checkedId = rgScanMode.getCheckedRadioButtonId();
        int mode;
        if (checkedId == R.id.rb_mode_check_playlist) {
            mode = MulticastScannerEngine.MODE_CHECK_PLAYLIST;
        } else if (checkedId == R.id.rb_mode_shell_script) {
            mode = MulticastScannerEngine.MODE_SHELL_SCRIPT;
        } else {
            mode = MulticastScannerEngine.MODE_DYNAMIC_SUBNET_SCAN;
        }

        logBuffer.setLength(0);
        tvConsoleLog.setText("");
        pbProgress.setProgress(0);
        btnStartScan.setEnabled(false);
        btnStopScan.setEnabled(true);
        btnStopScan.requestFocus();

        scannerEngine.startScan(mode, subnet, startHost, endHost, fullPort, timeoutMs,
                new MulticastScannerEngine.ScanCallback() {
                    @Override
                    public void onLogLine(String line) {
                        mainHandler.post(() -> appendConsoleLog(line));
                    }

                    @Override
                    public void onProgress(int current, int total, String currentTarget, ScanStatistics stats) {
                        mainHandler.post(() -> {
                            int pct = total > 0 ? (current * 100 / total) : 0;
                            pbProgress.setProgress(pct);
                            tvProgressLabel.setText(String.format(Locale.US,
                                    "进度: %d / %d (%d%%)  —  %s", current, total, pct, currentTarget));
                            tvStatsSummary.setText(stats.toSummaryText());
                        });
                    }

                    @Override
                    public void onChannelProbed(IptvChannel channel) {
                    }

                    @Override
                    public void onCompleted(List<IptvChannel> resultChannels, ScanStatistics stats) {
                        mainHandler.post(() -> {
                            lastStats = stats;
                            btnStartScan.setEnabled(true);
                            btnStopScan.setEnabled(false);
                            btnStartScan.requestFocus();
                            pbProgress.setProgress(100);
                            tvProgressLabel.setText("扫描与巡检完成！已更新频道列表及 modified_iptv.m3u / results.csv");
                            tvStatsSummary.setText(stats.toSummaryText());
                            Toast.makeText(ScannerActivity.this,
                                    "扫描完成，可用频道: " + stats.connectTrueCount + " 个",
                                    Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> {
                            btnStartScan.setEnabled(true);
                            btnStopScan.setEnabled(false);
                            btnStartScan.requestFocus();
                            appendConsoleLog("[ERROR] " + message);
                            Toast.makeText(ScannerActivity.this, message, Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void showExportSummaryDialog() {
        try {
            File outDir = repository.getScanRootDir();
            File m3uFile = new File(outDir, "modified_iptv.m3u");
            File csvFile = new File(outDir, "results.csv");

            List<IptvChannel> all = repository.getAllChannels();
            String m3uContent = M3uParser.toM3uContent(all, false, settings.getUdpxyAddress());
            String csvContent = M3uParser.toResultsCsvContent(all);

            try (FileOutputStream fos = new FileOutputStream(m3uFile)) {
                fos.write(m3uContent.getBytes(StandardCharsets.UTF_8));
            }
            try (FileOutputStream fos = new FileOutputStream(csvFile)) {
                fos.write(csvContent.getBytes(StandardCharsets.UTF_8));
            }

            StringBuilder msg = new StringBuilder();
            msg.append("已按 check_iptv_m3u.sh 规范导出文件：\n\n");
            msg.append("1. M3U 播放列表:\n").append(m3uFile.getAbsolutePath()).append("\n\n");
            msg.append("2. CSV 巡检报告:\n").append(csvFile.getAbsolutePath()).append("\n\n");
            if (lastStats != null && lastStats.outputRootDir != null) {
                msg.append("3. 最近一次运行目录:\n").append(lastStats.outputRootDir.getAbsolutePath());
            }

            new MaterialAlertDialogBuilder(this)
                    .setTitle("导出成功")
                    .setMessage(msg.toString())
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 适配电视遥控器快捷键：
     * - 绿键 (PROG_GREEN) / 播放暂停键：启动或停止组播扫描
     * - 菜单键 (MENU) / 黄键 / 蓝键：导出 modified_iptv.m3u 与 results.csv
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_PROG_GREEN || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (!btnStartScan.isEnabled()) {
                    stopScanningByUser();
                } else {
                    startScanning();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU
                    || keyCode == KeyEvent.KEYCODE_PROG_YELLOW
                    || keyCode == KeyEvent.KEYCODE_PROG_BLUE) {
                showExportSummaryDialog();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void appendConsoleLog(String line) {
        if (logBuffer.length() > 24000) {
            logBuffer.delete(0, 6000);
        }
        logBuffer.append(line).append("\n");
        tvConsoleLog.setText(logBuffer.toString());
        if (!svLog.hasFocus()) {
            svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
        }
    }

    private int parseIntSafe(String val, int fallback) {
        try {
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    protected void onDestroy() {
        if (scannerEngine != null) {
            scannerEngine.cancel();
        }
        super.onDestroy();
    }
}
