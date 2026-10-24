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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bjunicom.iptv.R;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.bjunicom.iptv.scanner.MulticastScannerEngine;
import com.bjunicom.iptv.scanner.ScanStatistics;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 组播地址动态扫描与 check_iptv_m3u.sh 脚本巡检界面
 * 扫描完成后提供两个选项：
 * 1. 保存到本地：存储为本地的 .m3u 文件；
 * 2. 立即应用：将扫描后的结果替换掉默认使用的 M3U 内容，并刷新频道列表。
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
    private List<IptvChannel> lastScannedChannels;
    private AlertDialog lastCompletionDialog;
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
        findViewById(R.id.btn_export_m3u).setOnClickListener(v -> saveScanResultsToLocalM3u(getTargetChannelsForAction()));
        View btnApplyScan = findViewById(R.id.btn_apply_scan_m3u);
        if (btnApplyScan != null) {
            btnApplyScan.setOnClickListener(v -> applyScanResultsImmediately(getTargetChannelsForAction()));
        }

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
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        };
        int[] scannerFocusableIds = {
                R.id.btn_scanner_back,
                R.id.btn_import_m3u,
                R.id.btn_export_m3u,
                R.id.btn_apply_scan_m3u,
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

    private List<IptvChannel> getTargetChannelsForAction() {
        if (lastScannedChannels != null && !lastScannedChannels.isEmpty()) {
            return lastScannedChannels;
        }
        return repository.getAllChannels();
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
                        mainHandler.post(() -> handleScanCompleted(resultChannels, stats));
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

    /**
     * 扫描完成后的处理入口：弹出对话框给用户提供「保存到本地」与「立即应用」两个选项
     */
    public void handleScanCompleted(List<IptvChannel> resultChannels, @Nullable ScanStatistics stats) {
        this.lastStats = stats;
        this.lastScannedChannels = resultChannels != null ? new ArrayList<>(resultChannels) : new ArrayList<>();
        btnStartScan.setEnabled(true);
        btnStopScan.setEnabled(false);
        btnStartScan.requestFocus();
        pbProgress.setProgress(100);
        tvProgressLabel.setText("扫描完成！请选择「立即应用」替换默认 M3U 并刷新列表，或「保存到本地」存储为 M3U 文件");
        if (stats != null) {
            tvStatsSummary.setText(stats.toSummaryText());
        }
        showScanCompletedOptionsDialog(this.lastScannedChannels, stats);
    }

    /**
     * 弹出扫描完成后的双选项对话框：
     * 1. 「立即应用」：将扫描后的结果替换掉默认使用的 M3U 内容，应用完后刷新频道列表；
     * 2. 「保存到本地」：存储成本地的 .m3u 文件。
     */
    public void showScanCompletedOptionsDialog(final List<IptvChannel> scannedChannels,
                                               @Nullable ScanStatistics stats) {
        if (isFinishing()) {
            return;
        }
        if (lastCompletionDialog != null && lastCompletionDialog.isShowing()) {
            lastCompletionDialog.dismiss();
        }
        List<IptvChannel> effective = repository.extractEffectiveScannedChannels(scannedChannels);
        StringBuilder msg = new StringBuilder();
        if (stats != null) {
            msg.append(stats.toSummaryText()).append("\n\n");
        }
        msg.append("本次扫描共整理出 ").append(effective.size()).append(" 个有效频道，请选择后续操作：\n\n");
        msg.append("• 立即应用：先保存为本地 .m3u 文件，再导入该本地 .m3u 文件更新频道列表，并跳转到第一个频道\n");
        msg.append("• 保存到本地：仅将扫描结果存储为本地的 .m3u 文件");

        lastCompletionDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("频道扫描完成")
                .setMessage(msg.toString())
                .setPositiveButton("立即应用", (d, w) -> applyScanResultsImmediately(scannedChannels))
                .setNeutralButton("保存到本地", null)
                .setNegativeButton("暂不处理", null)
                .create();

        lastCompletionDialog.show();

        // 绑定「保存到本地」按钮：点击后立即保存为本地 .m3u 文件，同时保留弹窗以便用户还可继续点击「立即应用」
        Button btnSaveLocal = lastCompletionDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (btnSaveLocal != null) {
            btnSaveLocal.setOnClickListener(v -> saveScanResultsToLocalM3u(scannedChannels));
        }
    }

    /**
     * 选项一：「保存到本地」—— 将扫描结果存储成本地的 .m3u 文件
     */
    public File saveScanResultsToLocalM3u(List<IptvChannel> scannedChannels) {
        try {
            File savedFile = repository.saveScannedM3uToLocalFile(scannedChannels, null);
            List<IptvChannel> effective = repository.extractEffectiveScannedChannels(scannedChannels);
            String logMsg = String.format(Locale.US,
                    "[SAVE] 已将扫描结果 (%d 个频道) 保存到本地 M3U 文件: %s",
                    effective.size(), savedFile.getAbsolutePath());
            appendConsoleLog(logMsg);
            tvProgressLabel.setText("已保存到本地 M3U: " + savedFile.getAbsolutePath());
            Toast.makeText(this,
                    "已保存到本地 M3U 文件:\n" + savedFile.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
            return savedFile;
        } catch (Exception e) {
            Toast.makeText(this, "保存到本地失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    /**
     * 选项二：「立即应用」——
     * 1. 先保存到本地 .m3u 文件；
     * 2. 执行导入本地 .m3u 文件的操作更新频道列表；
     * 3. 跳转到第一个频道播放。
     */
    public int applyScanResultsImmediately(List<IptvChannel> scannedChannels) {
        int appliedCount = repository.applyScannedResultAsDefaultM3u(scannedChannels);
        if (appliedCount <= 0) {
            Toast.makeText(this, "扫描结果中没有可应用的有效频道", Toast.LENGTH_SHORT).show();
            return 0;
        }
        File savedFile = repository.getLastSavedLocalM3uFile();
        String savedPath = savedFile != null ? savedFile.getAbsolutePath() : "";
        String logMsg = String.format(Locale.US,
                "[APPLY] 已先保存到本地 M3U (%s)，并导入该本地文件更新频道列表 (共 %d 个频道)，已跳转到第 1 个频道。",
                savedPath, appliedCount);
        appendConsoleLog(logMsg);
        tvProgressLabel.setText(String.format(Locale.US,
                "已保存并导入本地 M3U (%d 个频道)，已刷新频道列表并跳转到第一个频道",
                appliedCount));
        setResult(RESULT_OK);
        Toast.makeText(this,
                String.format(Locale.US, "已保存并导入本地 M3U (%d 个频道)，正在播放第 1 个频道", appliedCount),
                Toast.LENGTH_LONG).show();
        finish();
        return appliedCount;
    }

    public AlertDialog getLastCompletionDialog() {
        return lastCompletionDialog;
    }

    /**
     * 适配电视遥控器快捷键：
     * - 绿键 (PROG_GREEN) / 播放暂停键：启动或停止组播扫描
     * - 菜单键 (MENU) / 黄键：弹出扫描完成操作选项（立即应用 / 保存到本地）
     * - 蓝键 (PROG_BLUE)：直接保存到本地 M3U 文件
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
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_PROG_YELLOW) {
                showScanCompletedOptionsDialog(getTargetChannelsForAction(), lastStats);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_PROG_BLUE) {
                saveScanResultsToLocalM3u(getTargetChannelsForAction());
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
