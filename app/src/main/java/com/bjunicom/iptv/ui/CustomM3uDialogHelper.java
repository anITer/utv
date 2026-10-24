package com.bjunicom.iptv.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.method.ArrowKeyMovementMethod;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bjunicom.iptv.R;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.bjunicom.iptv.scanner.MulticastScannerEngine;
import com.bjunicom.iptv.scanner.ScanStatistics;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 自定义 M3U 文件上传、解析校验与流连通性检测弹窗助手
 *
 * 「扫描设备 M3U」目标文件夹支持：
 * 1. 系统公共 Download 目录（/storage/emulated/0/Download、/sdcard/Download 等）；
 * 2. 外接 U 盘 / 移动存储目录（通过 StorageManager、getExternalFilesDirs、/proc/mounts 及主流 Android TV/机顶盒挂载点如
 *    /storage/XXXX-XXXX、/mnt/media_rw、/mnt/usb_storage、/mnt/usb、/mnt/sda1 等自动识别）。
 */
public class CustomM3uDialogHelper {

    public static final int REQUEST_CODE_PICK_M3U_FILE = 10086;
    public static final int REQUEST_CODE_STORAGE_PERMISSION = 10087;
    private static final int MAX_SCANNED_M3U_FILES = 100;

    // Android TV / 机顶盒 (海思/晶晨/瑞芯微/全志/晨星等) 常见外接 U 盘挂载根目录
    private static final String[] COMMON_USB_MOUNT_ROOTS = {
            "/mnt/media_rw",
            "/mnt/usb_storage",
            "/mnt/usb",
            "/mnt/udisk",
            "/mnt/usbdisk",
            "/mnt/usbdrive",
            "/mnt/usbotg",
            "/mnt/sdcard/usbStorage",
            "/mnt/sdcard/usb_storage",
            "/mnt/ext_sdcard",
            "/mnt/external_sd",
            "/mnt/other_sdcard",
            "/mnt/sda",
            "/mnt/sda1",
            "/mnt/sda2",
            "/mnt/sdb",
            "/mnt/sdb1",
            "/mnt/sdb2",
            "/storage/usb_storage",
            "/storage/usb",
            "/storage/udisk",
            "/storage/udisk0",
            "/storage/usbdisk",
            "/storage/usbotg",
            "/storage/external_storage",
            "/storage/sda1",
            "/storage/sdb1"
    };

    private static final List<File> sCustomUsbScanRoots = new CopyOnWriteArrayList<>();

    private static final String SAMPLE_CUSTOM_M3U =
            "#EXTM3U x-tvg-url=\"http://epg.51zmt.top:8000/e.xml.gz\"\n"
                    + "#EXTINF:-1 tvg-id=\"1\" tvg-name=\"CCTV1\" group-title=\"自定义央视\",CCTV-1综合[1][高清]\n"
                    + "rtp://239.3.1.129:8008\n"
                    + "#EXTINF:-1 tvg-id=\"21\" tvg-name=\"北京卫视\" group-title=\"自定义卫视\",北京卫视[21][高清]\n"
                    + "rtp://239.3.1.141:8000\n"
                    + "#EXTINF:-1 tvg-id=\"85\" tvg-name=\"CCTV4K\" group-title=\"自定义4K;4K\",CCTV-4K超高清[85][4K]\n"
                    + "rtp://239.3.1.244:2000\n";

    public interface OnCustomM3uAppliedListener {
        void onCustomM3uApplied(M3uParser.M3uInspectionReport report, boolean switchedToCustomIsp);
    }

    private final Activity activity;
    private final ChannelRepository repository;
    private final IptvSettings settings;
    private final OnCustomM3uAppliedListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AlertDialog activeDialog;
    private AlertDialog lastLocalFilesDialog;
    private List<File> lastScannedM3uFiles = Collections.emptyList();
    private TextView tvFileName;
    private EditText etContent;
    private TextView tvReport;
    private ProgressBar pbProbe;
    private Button btnProbeStreams;
    private boolean contentScrollBarEnabled = false;

    private String currentFileName = "custom.m3u";
    private M3uParser.M3uInspectionReport lastReport;
    private MulticastScannerEngine probeEngine;

    public CustomM3uDialogHelper(Activity activity, OnCustomM3uAppliedListener listener) {
        this.activity = activity;
        this.repository = ChannelRepository.getInstance(activity);
        this.settings = new IptvSettings(activity);
        this.listener = listener;
    }

    /**
     * 注册自定义外接 U 盘挂载根目录（供特殊机顶盒路径扩展或单元测试注入）
     */
    public static void addCustomUsbScanRoot(File usbRootDir) {
        if (usbRootDir != null && !sCustomUsbScanRoots.contains(usbRootDir)) {
            sCustomUsbScanRoots.add(usbRootDir);
        }
    }

    public static void clearCustomUsbScanRoots() {
        sCustomUsbScanRoots.clear();
    }

    public List<File> getLastScannedM3uFiles() {
        return lastScannedM3uFiles;
    }

    @Nullable
    public AlertDialog getLastLocalFilesDialog() {
        return lastLocalFilesDialog;
    }

    @Nullable
    public AlertDialog getActiveDialog() {
        return activeDialog;
    }

    @Nullable
    public EditText getContentPreviewView() {
        return etContent;
    }

    public boolean isContentPreviewScrollBarEnabled() {
        return contentScrollBarEnabled && etContent != null && etContent.isVerticalScrollBarEnabled();
    }

    private void setContentPreviewScrollBarEnabled(boolean enabled) {
        contentScrollBarEnabled = enabled;
        if (etContent == null) {
            return;
        }
        etContent.setVerticalScrollBarEnabled(enabled);
        etContent.setHorizontalScrollBarEnabled(false);
        etContent.setScrollbarFadingEnabled(!enabled);
        etContent.setCursorVisible(enabled);
        if (enabled) {
            etContent.setMovementMethod(ArrowKeyMovementMethod.getInstance());
        } else {
            etContent.setMovementMethod(null);
        }
        etContent.invalidate();
    }

    private void setupContentPreviewScrollBarBehavior(final View upTarget, final View downTarget) {
        if (etContent == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            etContent.setShowSoftInputOnFocus(false);
        }
        // 默认不启用 scrollbar；当内容预览窗口被选中成为焦点时同样默认不启用 scrollbar
        setContentPreviewScrollBarEnabled(false);

        etContent.setOnFocusChangeListener((v, hasFocus) -> {
            v.setSelected(hasFocus);
            // 成为焦点或失去焦点时均默认关闭 scrollbar，只有成为焦点后再次按 OK 键才启用 scrollbar
            setContentPreviewScrollBarEnabled(false);
        });

        etContent.setOnKeyListener((v, keyCode, event) -> {
            if (!etContent.hasFocus()) {
                return false;
            }
            boolean isOkKey = (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER);
            if (isOkKey) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    if (!contentScrollBarEnabled) {
                        setContentPreviewScrollBarEnabled(true);
                    }
                }
                return true;
            }

            if (!contentScrollBarEnabled) {
                // 未按 OK 键启用 scrollbar 前：方向键直接切换焦点到上下相邻控件，不触发内容滚动
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        View next = (upTarget != null && upTarget.isShown())
                                ? upTarget
                                : v.focusSearch(View.FOCUS_UP);
                        if (next != null && next != v) {
                            next.requestFocus();
                        }
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        View next = (downTarget != null && downTarget.isShown())
                                ? downTarget
                                : v.focusSearch(View.FOCUS_DOWN);
                        if (next != null && next != v) {
                            next.requestFocus();
                        }
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int dir = (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
                        View next = v.focusSearch(dir);
                        if (next != null && next != v) {
                            next.requestFocus();
                        }
                    }
                    return true;
                }
                return false;
            }

            // 已成为焦点且按过 OK 键启用 scrollbar 后：上下方向键滚动预览内容
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int step = Math.max(etContent.getLineHeight(), 28);
                    int maxScrollY = computeMaxScrollY(etContent);
                    int targetY = Math.min(maxScrollY, etContent.getScrollY() + step);
                    etContent.scrollTo(0, targetY);
                    etContent.invalidate();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int step = Math.max(etContent.getLineHeight(), 28);
                    int targetY = Math.max(0, etContent.getScrollY() - step);
                    etContent.scrollTo(0, targetY);
                    etContent.invalidate();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    setContentPreviewScrollBarEnabled(false);
                }
                return true;
            }
            return false;
        });
    }

    private static int computeMaxScrollY(EditText editText) {
        if (editText == null || editText.getLayout() == null) {
            return Integer.MAX_VALUE / 2;
        }
        int contentHeight = editText.getLayout().getHeight();
        int visibleHeight = editText.getHeight()
                - editText.getCompoundPaddingTop()
                - editText.getCompoundPaddingBottom();
        if (visibleHeight <= 0) {
            return Math.max(0, contentHeight);
        }
        return Math.max(0, contentHeight - visibleHeight);
    }

    public void showUploadAndInspectDialog() {
        ensureStoragePermissionIfNeeded(false);

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_custom_m3u, null);
        tvFileName = view.findViewById(R.id.tv_custom_m3u_filename);
        etContent = view.findViewById(R.id.et_custom_m3u_content);
        tvReport = view.findViewById(R.id.tv_custom_m3u_report);
        pbProbe = view.findViewById(R.id.pb_custom_m3u_probe);
        btnProbeStreams = view.findViewById(R.id.btn_probe_m3u_streams);

        Button btnPickFile = view.findViewById(R.id.btn_pick_m3u_file);
        Button btnScanLocal = view.findViewById(R.id.btn_scan_local_m3u_files);
        Button btnLoadSample = view.findViewById(R.id.btn_load_sample_m3u);
        Button btnInspect = view.findViewById(R.id.btn_inspect_m3u_content);

        setupContentPreviewScrollBarBehavior(btnScanLocal, btnInspect);

        // 如果之前已经上传过自定义 M3U，默认回显并自动解析检测
        if (repository.hasCustomUploadedM3u()) {
            currentFileName = settings.getCustomM3uName();
            tvFileName.setText(currentFileName);
            etContent.setText(repository.readCustomUploadedM3uContent());
            inspectCurrentContent();
        }

        btnPickFile.setOnClickListener(v -> launchSystemFilePicker());
        btnScanLocal.setOnClickListener(v -> showLocalM3uFilesPickerDialog());
        btnLoadSample.setOnClickListener(v -> {
            if (repository.hasCustomUploadedM3u()) {
                currentFileName = settings.getCustomM3uName();
                etContent.setText(repository.readCustomUploadedM3uContent());
            } else {
                currentFileName = "sample_custom.m3u";
                etContent.setText(SAMPLE_CUSTOM_M3U);
            }
            tvFileName.setText(currentFileName);
            inspectCurrentContent();
        });

        btnInspect.setOnClickListener(v -> {
            M3uParser.M3uInspectionReport report = inspectCurrentContent();
            if (report.isValid()) {
                Toast.makeText(activity,
                        "解析成功：共检测到 " + report.validChannelCount + " 个有效频道",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(activity,
                        "内容校验未通过，请查看下方检测报告",
                        Toast.LENGTH_SHORT).show();
            }
        });

        btnProbeStreams.setOnClickListener(v -> toggleStreamConnectivityProbe());

        activeDialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("上传并检测自定义 M3U 播放列表")
                .setView(view)
                .setPositiveButton("设为自定义源并播放", null)
                .setNeutralButton("合并到当前列表", null)
                .setNegativeButton("关闭", (d, w) -> stopProbeEngine())
                .create();

        activeDialog.setOnDismissListener(d -> stopProbeEngine());
        activeDialog.show();

        // 拦截 Positive 与 Neutral 按钮点击，先执行解析与校验，校验通过才关闭弹窗
        Button positiveBtn = activeDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positiveBtn != null) {
            positiveBtn.setOnClickListener(v -> applyCustomM3u(true));
        }
        Button neutralBtn = activeDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutralBtn != null) {
            neutralBtn.setOnClickListener(v -> applyCustomM3u(false));
        }

        btnPickFile.post(btnPickFile::requestFocus);
    }

    private void applyCustomM3u(boolean switchToCustomSource) {
        stopProbeEngine();
        resolveFilePathIfEnteredInEditor();
        String rawText = etContent != null ? etContent.getText().toString() : "";
        M3uParser.M3uInspectionReport report = repository.importCustomM3uContent(
                rawText, currentFileName, switchToCustomSource);
        lastReport = report;
        renderInspectionReport(report);

        if (!report.isValid()) {
            Toast.makeText(activity,
                    "M3U 文件内容校验未通过，无法导入，请检查报告提示",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        if (listener != null) {
            listener.onCustomM3uApplied(report, switchToCustomSource);
        }
    }

    public M3uParser.M3uInspectionReport inspectCurrentContent() {
        resolveFilePathIfEnteredInEditor();
        String rawText = etContent != null ? etContent.getText().toString() : "";
        lastReport = M3uParser.inspectAndParse(rawText, currentFileName);
        renderInspectionReport(lastReport);
        return lastReport;
    }

    private void resolveFilePathIfEnteredInEditor() {
        if (etContent == null) {
            return;
        }
        String text = etContent.getText().toString().trim();
        if (text.isEmpty() || text.contains("\n")) {
            return;
        }
        if (text.startsWith("/") || text.startsWith("file://")) {
            String path = text.startsWith("file://") ? Uri.parse(text).getPath() : text;
            if (path != null) {
                File f = new File(path);
                if (f.exists() && f.isFile()) {
                    loadFromFile(f);
                }
            }
        }
    }

    private void renderInspectionReport(M3uParser.M3uInspectionReport report) {
        if (tvReport == null || report == null) {
            return;
        }
        tvReport.setText(report.toSummaryText());
        if (!report.isValid()) {
            tvReport.setTextColor(Color.parseColor("#FCA5A5"));
        } else if (!report.warnings.isEmpty()) {
            tvReport.setTextColor(Color.parseColor("#FDE68A"));
        } else {
            tvReport.setTextColor(Color.parseColor("#A7F3D0"));
        }
    }

    private void toggleStreamConnectivityProbe() {
        if (probeEngine != null && probeEngine.isRunning()) {
            stopProbeEngine();
            Toast.makeText(activity, "已停止频道流连通性检测", Toast.LENGTH_SHORT).show();
            return;
        }

        M3uParser.M3uInspectionReport report = inspectCurrentContent();
        if (!report.isValid()) {
            Toast.makeText(activity, "请先上传或粘贴包含有效频道的 M3U 内容", Toast.LENGTH_SHORT).show();
            return;
        }

        if (probeEngine == null) {
            probeEngine = new MulticastScannerEngine(activity);
        }
        if (pbProbe != null) {
            pbProbe.setVisibility(View.VISIBLE);
            pbProbe.setProgress(0);
        }
        if (btnProbeStreams != null) {
            btnProbeStreams.setText("停止检测");
        }

        int timeoutMs = Math.min(settings.getScanTimeoutMs(), 1500);
        probeEngine.startCustomReportCheck(report, timeoutMs, new MulticastScannerEngine.ScanCallback() {
            @Override
            public void onLogLine(String line) {
            }

            @Override
            public void onProgress(int current, int total, String currentTarget, ScanStatistics stats) {
                mainHandler.post(() -> {
                    if (pbProbe != null) {
                        int pct = total > 0 ? (current * 100 / total) : 0;
                        pbProbe.setProgress(pct);
                    }
                    renderInspectionReport(report);
                });
            }

            @Override
            public void onChannelProbed(IptvChannel channel) {
            }

            @Override
            public void onCompleted(List<IptvChannel> resultChannels, ScanStatistics stats) {
                mainHandler.post(() -> {
                    if (pbProbe != null) {
                        pbProbe.setProgress(100);
                    }
                    if (btnProbeStreams != null) {
                        btnProbeStreams.setText("检测流连通性");
                    }
                    renderInspectionReport(report);
                    showProbeCompletedOptionsDialog(resultChannels, report);
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    if (btnProbeStreams != null) {
                        btnProbeStreams.setText("检测流连通性");
                    }
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showProbeCompletedOptionsDialog(final List<IptvChannel> resultChannels,
                                                 final M3uParser.M3uInspectionReport report) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        String msg = String.format(Locale.US,
                "频道流检测完成：可用 %d / %d 个频道。\n\n"
                        + "• 立即应用：将扫描后的结果替换掉默认使用的 M3U 内容，并立即刷新频道列表\n"
                        + "• 保存到本地：将扫描结果存储为本地的 .m3u 文件",
                report.reachableCount, report.validChannelCount);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("频道检测完成")
                .setMessage(msg)
                .setPositiveButton("立即应用", (d, w) -> {
                    int appliedCount = repository.applyScannedResultAsDefaultM3u(resultChannels);
                    if (activeDialog != null && activeDialog.isShowing()) {
                        activeDialog.dismiss();
                    }
                    if (listener != null) {
                        listener.onCustomM3uApplied(report, true);
                    }
                    Toast.makeText(activity,
                            String.format(Locale.US, "已保存并导入本地 M3U (%d 个频道)，正在播放第 1 个频道", appliedCount),
                            Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("保存到本地", null)
                .setNegativeButton("暂不处理", null)
                .create();

        dialog.show();
        Button btnSaveLocal = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (btnSaveLocal != null) {
            btnSaveLocal.setOnClickListener(v -> {
                File savedFile = repository.saveScannedM3uToLocalFile(resultChannels, null);
                Toast.makeText(activity,
                        "已保存到本地 M3U 文件:\n" + savedFile.getAbsolutePath(),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void stopProbeEngine() {
        if (probeEngine != null) {
            probeEngine.cancel();
        }
        if (btnProbeStreams != null) {
            btnProbeStreams.setText("检测流连通性");
        }
    }

    private void ensureStoragePermissionIfNeeded(boolean requestAllFilesIfR) {
        if (activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                            activity,
                            new String[]{
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                            },
                            REQUEST_CODE_STORAGE_PERMISSION);
                }
            } catch (Throwable ignored) {
            }
        }
        if (requestAllFilesIfR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (!Environment.isExternalStorageManager()) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                }
            } catch (Throwable t) {
                try {
                    Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    activity.startActivity(fallback);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void launchSystemFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "audio/x-mpegurl",
                    "application/x-mpegurl",
                    "application/vnd.apple.mpegurl",
                    "text/plain",
                    "application/octet-stream",
                    "*/*"
            });
            activity.startActivityForResult(intent, REQUEST_CODE_PICK_M3U_FILE);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                activity.startActivityForResult(
                        Intent.createChooser(fallback, "选择 M3U 播放列表文件"),
                        REQUEST_CODE_PICK_M3U_FILE);
            } catch (Exception ex) {
                Toast.makeText(activity,
                        "当前设备未安装系统文件选择器，可使用「扫描设备 M3U」或直接粘贴内容",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE_PICK_M3U_FILE) {
            return false;
        }
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            loadFromUri(data.getData());
        }
        return true;
    }

    public void loadFromUri(Uri uri) {
        if (uri == null) {
            return;
        }
        ContentResolver resolver = activity.getContentResolver();
        String displayName = resolveDisplayName(resolver, uri);
        if (!TextUtils.isEmpty(displayName)) {
            currentFileName = displayName;
        }
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) {
                Toast.makeText(activity, "无法打开所选文件流", Toast.LENGTH_SHORT).show();
                return;
            }
            String content = readStreamToString(is);
            if (tvFileName != null) {
                tvFileName.setText(currentFileName);
            }
            if (etContent != null) {
                etContent.setText(content);
            }
            M3uParser.M3uInspectionReport report = inspectCurrentContent();
            Toast.makeText(activity,
                    report.isValid()
                            ? ("已读取并校验 " + currentFileName + " (" + report.validChannelCount + " 频道)")
                            : ("文件 " + currentFileName + " 校验未通过，请查看报告"),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(activity, "读取文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void loadFromFile(File file) {
        if (file == null || !file.exists()) {
            Toast.makeText(activity, "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        currentFileName = file.getName();
        try (InputStream is = new FileInputStream(file)) {
            String content = readStreamToString(is);
            if (tvFileName != null) {
                tvFileName.setText(currentFileName);
            }
            if (etContent != null) {
                etContent.setText(content);
            }
            inspectCurrentContent();
        } catch (IOException e) {
            Toast.makeText(activity, "读取文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 收集系统 Download 目录列表（涵盖标准 Environment.DIRECTORY_DOWNLOADS、/sdcard/Download 及大小写变体）
     */
    public static List<File> collectSystemDownloadDirectories(@Nullable Context context) {
        List<File> result = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();

        try {
            addUniqueDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), result, seenPaths);
        } catch (Throwable ignored) {
        }
        try {
            File extRoot = Environment.getExternalStorageDirectory();
            if (extRoot != null) {
                addUniqueDir(new File(extRoot, "Download"), result, seenPaths);
                addUniqueDir(new File(extRoot, "Downloads"), result, seenPaths);
                addUniqueDir(new File(extRoot, "download"), result, seenPaths);
            }
        } catch (Throwable ignored) {
        }

        String[] fallbackDownloadPaths = {
                "/storage/emulated/0/Download",
                "/storage/emulated/0/Downloads",
                "/storage/emulated/0/download",
                "/sdcard/Download",
                "/sdcard/Downloads",
                "/sdcard/download",
                "/storage/self/primary/Download",
                "/storage/self/primary/Downloads"
        };
        for (String path : fallbackDownloadPaths) {
            addUniqueDir(new File(path), result, seenPaths);
        }

        if (context != null) {
            try {
                addUniqueDir(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), result, seenPaths);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    /**
     * 收集外接 U 盘 / 移动存储挂载目录列表：
     * 1. StorageManager (StorageVolume.getDirectory / getPath 反射 / getVolumePaths 反射)；
     * 2. Context.getExternalFilesDirs(null) / getExternalMediaDirs() 反推外接卷根目录；
     * 3. /proc/mounts 实时挂载表解析（识别 vfat / exfat / ntfs / fuseblk / /dev/block/vold 等 U 盘挂载点）；
     * 4. /storage 子目录枚举及主流 Android TV / 机顶盒 U 盘挂载路径（/mnt/usb_storage、/mnt/usb、/mnt/media_rw、/mnt/sda1 等）。
     */
    public static List<File> collectExternalUsbDirectories(@Nullable Context context) {
        List<File> result = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();

        String primaryCanonical = null;
        try {
            File primary = Environment.getExternalStorageDirectory();
            if (primary != null) {
                primaryCanonical = getCanonicalPathSafe(primary);
            }
        } catch (Throwable ignored) {
        }

        // 0. 注入的自定义 U 盘根目录
        for (File customRoot : sCustomUsbScanRoots) {
            addUniqueUsbDirAndChildren(customRoot, primaryCanonical, result, seenPaths);
        }

        // 1. 通过 StorageManager 获取所有可移动/外接存储卷
        if (context != null) {
            try {
                StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
                if (sm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        List<StorageVolume> volumes = sm.getStorageVolumes();
                        if (volumes != null) {
                            for (StorageVolume vol : volumes) {
                                if (vol == null) {
                                    continue;
                                }
                                if (!vol.isPrimary() || vol.isRemovable()) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        File volDir = vol.getDirectory();
                                        addUniqueUsbDir(volDir, primaryCanonical, result, seenPaths);
                                    }
                                    try {
                                        Method getPathMethod = vol.getClass().getMethod("getPath");
                                        Object pathObj = getPathMethod.invoke(vol);
                                        if (pathObj instanceof String && !TextUtils.isEmpty((String) pathObj)) {
                                            addUniqueUsbDir(new File((String) pathObj), primaryCanonical, result, seenPaths);
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                    String uuid = vol.getUuid();
                                    if (!TextUtils.isEmpty(uuid)) {
                                        addUniqueUsbDir(new File("/storage", uuid), primaryCanonical, result, seenPaths);
                                        addUniqueUsbDir(new File("/mnt/media_rw", uuid), primaryCanonical, result, seenPaths);
                                    }
                                }
                            }
                        }
                    }
                    try {
                        Method getVolumePaths = sm.getClass().getMethod("getVolumePaths");
                        Object pathsObj = getVolumePaths.invoke(sm);
                        if (pathsObj instanceof String[]) {
                            for (String p : (String[]) pathsObj) {
                                if (!TextUtils.isEmpty(p) && !isInternalEmulatedPath(p)) {
                                    addUniqueUsbDir(new File(p), primaryCanonical, result, seenPaths);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }

            // 2. 通过 getExternalFilesDirs / getExternalMediaDirs 反推外接 U 盘根目录
            try {
                File[] extDirs = ContextCompat.getExternalFilesDirs(context, null);
                if (extDirs != null) {
                    for (int i = 0; i < extDirs.length; i++) {
                        File extDir = extDirs[i];
                        if (extDir == null) {
                            continue;
                        }
                        File volumeRoot = extractVolumeRootFromAppExternalDir(extDir);
                        if (volumeRoot != null && (i > 0 || !isInternalEmulatedPath(volumeRoot.getAbsolutePath()))) {
                            addUniqueUsbDir(volumeRoot, primaryCanonical, result, seenPaths);
                            addUniqueUsbDir(extDir, primaryCanonical, result, seenPaths);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    File[] mediaDirs = context.getExternalMediaDirs();
                    if (mediaDirs != null) {
                        for (int i = 0; i < mediaDirs.length; i++) {
                            File mediaDir = mediaDirs[i];
                            if (mediaDir == null) {
                                continue;
                            }
                            File volumeRoot = extractVolumeRootFromAppExternalDir(mediaDir);
                            if (volumeRoot != null && (i > 0 || !isInternalEmulatedPath(volumeRoot.getAbsolutePath()))) {
                                addUniqueUsbDir(volumeRoot, primaryCanonical, result, seenPaths);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // 3. 解析 /proc/mounts 中的外接 U 盘挂载点
        for (String mountPath : readUsbMountPointsFromProcMounts()) {
            addUniqueUsbDir(new File(mountPath), primaryCanonical, result, seenPaths);
        }

        // 4. 枚举 /storage 下的外接卷目录（排除 emulated 与 self）
        try {
            File storageRoot = new File("/storage");
            File[] children = storageRoot.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child != null && child.isDirectory()) {
                        String name = child.getName();
                        if (!"emulated".equalsIgnoreCase(name) && !"self".equalsIgnoreCase(name)) {
                            addUniqueUsbDir(child, primaryCanonical, result, seenPaths);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 5. 枚举常见 Android TV / 机顶盒 U 盘挂载路径及其子分区目录 (如 /mnt/usb/sda1、/mnt/usb_storage/USB_DISK0)
        for (String rootPath : COMMON_USB_MOUNT_ROOTS) {
            addUniqueUsbDirAndChildren(new File(rootPath), primaryCanonical, result, seenPaths);
        }

        return result;
    }

    /**
     * 解析 /proc/mounts 文本行，提取外接 U 盘 / 移动存储的挂载路径
     */
    public static List<String> parseUsbMountPointsFromProcMounts(List<String> mountLines) {
        List<String> mountPoints = new ArrayList<>();
        if (mountLines == null) {
            return mountPoints;
        }
        for (String rawLine : mountLines) {
            if (TextUtils.isEmpty(rawLine)) {
                continue;
            }
            String[] parts = rawLine.trim().split("\\s+");
            if (parts.length < 3) {
                continue;
            }
            String device = parts[0];
            String mountPoint = parts[1].replace("\\040", " ");
            String fsType = parts[2].toLowerCase(Locale.ROOT);

            if (isSystemOrInternalMountPoint(mountPoint)) {
                continue;
            }

            boolean isRemovableDevice = device.startsWith("/dev/block/vold/")
                    || device.startsWith("/dev/block/sd")
                    || device.startsWith("/dev/sd")
                    || device.startsWith("/dev/fuse");
            boolean isUsbFs = "vfat".equals(fsType)
                    || "exfat".equals(fsType)
                    || "ntfs".equals(fsType)
                    || "sdfat".equals(fsType)
                    || "texfat".equals(fsType)
                    || "fuseblk".equals(fsType)
                    || "iso9660".equals(fsType);
            boolean isUsbMountPrefix = mountPoint.startsWith("/storage/")
                    || mountPoint.startsWith("/mnt/media_rw/")
                    || mountPoint.startsWith("/mnt/usb")
                    || mountPoint.startsWith("/mnt/udisk")
                    || mountPoint.startsWith("/mnt/sd")
                    || mountPoint.startsWith("/mnt/ext")
                    || mountPoint.startsWith("/mnt/other")
                    || mountPoint.startsWith("/udisk")
                    || mountPoint.startsWith("/usb");

            if (isUsbMountPrefix && (isRemovableDevice || isUsbFs || mountPoint.startsWith("/mnt/usb") || mountPoint.startsWith("/storage/"))) {
                if (!mountPoints.contains(mountPoint)) {
                    mountPoints.add(mountPoint);
                }
            }
        }
        return mountPoints;
    }

    private static List<String> readUsbMountPointsFromProcMounts() {
        List<String> lines = new ArrayList<>();
        File procMounts = new File("/proc/mounts");
        if (!procMounts.exists() || !procMounts.canRead()) {
            return Collections.emptyList();
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(procMounts), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (Throwable ignored) {
        }
        return parseUsbMountPointsFromProcMounts(lines);
    }

    private static boolean isInternalEmulatedPath(String path) {
        if (TextUtils.isEmpty(path)) {
            return true;
        }
        String norm = path.trim();
        return norm.equals("/sdcard")
                || norm.startsWith("/sdcard/")
                || norm.equals("/storage/emulated")
                || norm.startsWith("/storage/emulated/")
                || norm.equals("/storage/self")
                || norm.startsWith("/storage/self/");
    }

    private static boolean isSystemOrInternalMountPoint(String mountPoint) {
        if (TextUtils.isEmpty(mountPoint) || isInternalEmulatedPath(mountPoint)) {
            return true;
        }
        return mountPoint.equals("/storage")
                || mountPoint.equals("/mnt")
                || mountPoint.startsWith("/mnt/runtime")
                || mountPoint.startsWith("/mnt/user")
                || mountPoint.startsWith("/mnt/pass_through")
                || mountPoint.startsWith("/mnt/installer")
                || mountPoint.startsWith("/mnt/androidwritable")
                || mountPoint.startsWith("/mnt/asec")
                || mountPoint.startsWith("/mnt/obb")
                || mountPoint.startsWith("/mnt/secure")
                || mountPoint.startsWith("/mnt/vendor")
                || mountPoint.startsWith("/mnt/product")
                || mountPoint.startsWith("/mnt/expand");
    }

    @Nullable
    public static File extractVolumeRootFromAppExternalDir(@Nullable File appExternalDir) {
        if (appExternalDir == null) {
            return null;
        }
        File current = appExternalDir;
        while (current != null) {
            File parent = current.getParentFile();
            if ("Android".equalsIgnoreCase(current.getName()) && parent != null) {
                return parent;
            }
            current = parent;
        }
        return null;
    }

    private static void addUniqueDir(@Nullable File dir, List<File> outDirs, Set<String> seenPaths) {
        if (dir == null) {
            return;
        }
        String canonical = getCanonicalPathSafe(dir);
        if (seenPaths.add(canonical)) {
            outDirs.add(dir);
        }
    }

    private static void addUniqueUsbDir(@Nullable File dir,
                                        @Nullable String primaryCanonical,
                                        List<File> outDirs,
                                        Set<String> seenPaths) {
        if (dir == null || isInternalEmulatedPath(dir.getAbsolutePath())) {
            return;
        }
        String canonical = getCanonicalPathSafe(dir);
        if (primaryCanonical != null && (canonical.equals(primaryCanonical) || canonical.startsWith(primaryCanonical + "/"))) {
            // 但允许在单元测试或挂载在特殊子目录中的显式 customUsbScanRoots
            boolean isExplicitCustom = false;
            for (File custom : sCustomUsbScanRoots) {
                String customCanon = getCanonicalPathSafe(custom);
                if (canonical.equals(customCanon) || canonical.startsWith(customCanon + "/")) {
                    isExplicitCustom = true;
                    break;
                }
            }
            if (!isExplicitCustom) {
                return;
            }
        }
        if (seenPaths.add(canonical)) {
            outDirs.add(dir);
        }
    }

    private static void addUniqueUsbDirAndChildren(@Nullable File rootDir,
                                                   @Nullable String primaryCanonical,
                                                   List<File> outDirs,
                                                   Set<String> seenPaths) {
        if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory()) {
            return;
        }
        addUniqueUsbDir(rootDir, primaryCanonical, outDirs, seenPaths);
        try {
            File[] children = rootDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child != null && child.isDirectory() && !child.getName().startsWith(".")) {
                        addUniqueUsbDir(child, primaryCanonical, outDirs, seenPaths);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String getCanonicalPathSafe(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Throwable ignored) {
            return file.getAbsolutePath();
        }
    }

    /**
     * 扫描设备上的 .m3u / .m3u8 文件（目标文件夹：外接 U 盘目录 + 系统 Download 目录 + 媒体库及应用目录兜底）
     */
    public List<File> scanDeviceM3uFiles() {
        List<File> foundFiles = new ArrayList<>();
        Set<String> seenFilePaths = new LinkedHashSet<>();

        List<File> usbDirs = collectExternalUsbDirectories(activity);
        List<File> downloadDirs = collectSystemDownloadDirectories(activity);

        // 1. 优先扫描外接 U 盘目录（支持 U 盘根目录及最多 3 层子目录，如 U盘/Download、U盘/iptv 等）
        for (File usbDir : usbDirs) {
            scanDirectoryForM3u(usbDir, foundFiles, seenFilePaths, 3, false);
        }

        // 2. 扫描系统 Download 目录（支持 Download 根目录及 2 层子目录）
        for (File downloadDir : downloadDirs) {
            scanDirectoryForM3u(downloadDir, foundFiles, seenFilePaths, 2, false);
        }

        // 3. 通过 MediaStore 查询系统 Download 与外接存储中的 .m3u/.m3u8 文件路径（兼容 Android 10+ 分区存储）
        queryMediaStoreForM3uFiles(foundFiles, seenFilePaths);

        // 4. 扫描内部存储根目录（1 层）与应用扫描目录（排除脚本临时文件 iptv.m3u / temp.m3u）
        try {
            File extRoot = Environment.getExternalStorageDirectory();
            scanDirectoryForM3u(extRoot, foundFiles, seenFilePaths, 1, false);
        } catch (Throwable ignored) {
        }
        scanDirectoryForM3u(repository.getScanRootDir(), foundFiles, seenFilePaths, 1, true);

        lastScannedM3uFiles = new ArrayList<>(foundFiles);
        return foundFiles;
    }

    public void showLocalM3uFilesPickerDialog() {
        ensureStoragePermissionIfNeeded(false);

        List<File> usbDirs = collectExternalUsbDirectories(activity);
        List<File> downloadDirs = collectSystemDownloadDirectories(activity);
        List<File> foundFiles = scanDeviceM3uFiles();

        if (foundFiles.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                Toast.makeText(activity,
                        "未扫描到 .m3u 文件，Android 11+ 访问系统 Download 及外接 U 盘可能需授予「所有文件访问权限」",
                        Toast.LENGTH_LONG).show();
                ensureStoragePermissionIfNeeded(true);
            } else {
                Toast.makeText(activity,
                        "未在系统 Download 目录或外接 U 盘扫描到 .m3u/.m3u8 文件，可点击「选择本地 M3U 文件」或「已存/示例」",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        String[] items = new String[foundFiles.size()];
        for (int i = 0; i < foundFiles.size(); i++) {
            File f = foundFiles.get(i);
            long kb = Math.max(1L, f.length() / 1024L);
            String sourceTag = formatScannedFileSourceTag(f, usbDirs, downloadDirs);
            items[i] = sourceTag + " " + f.getName() + " (" + kb + " KB)\n" + f.getAbsolutePath();
        }

        lastLocalFilesDialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("选择设备上的 M3U 文件 (系统 Download / 外接 U 盘，共 " + foundFiles.size() + " 个)")
                .setItems(items, (dialog, which) -> {
                    File selected = foundFiles.get(which);
                    loadFromFile(selected);
                })
                .setNegativeButton("取消", null)
                .create();
        lastLocalFilesDialog.show();
    }

    public static String formatScannedFileSourceTag(File file,
                                                    @Nullable List<File> usbDirs,
                                                    @Nullable List<File> downloadDirs) {
        if (file == null) {
            return "[本地]";
        }
        String fileCanon = getCanonicalPathSafe(file);
        String fileAbs = file.getAbsolutePath();

        if (usbDirs != null) {
            for (File usbDir : usbDirs) {
                if (usbDir == null) {
                    continue;
                }
                String usbCanon = getCanonicalPathSafe(usbDir);
                String usbAbs = usbDir.getAbsolutePath();
                if (fileCanon.startsWith(usbCanon + "/") || fileAbs.startsWith(usbAbs + "/")) {
                    return "[外接U盘]";
                }
            }
        }
        if (downloadDirs != null) {
            for (File dlDir : downloadDirs) {
                if (dlDir == null) {
                    continue;
                }
                String dlCanon = getCanonicalPathSafe(dlDir);
                String dlAbs = dlDir.getAbsolutePath();
                if (fileCanon.startsWith(dlCanon + "/") || fileAbs.startsWith(dlAbs + "/")) {
                    return "[系统Download]";
                }
            }
        }
        String lowerPath = fileAbs.toLowerCase(Locale.ROOT);
        if (lowerPath.contains("/usb") || lowerPath.contains("/udisk") || lowerPath.contains("/media_rw/")
                || lowerPath.contains("/sda") || lowerPath.contains("/sdb")) {
            return "[外接U盘]";
        }
        if (lowerPath.contains("/download/") || lowerPath.contains("/downloads/")) {
            return "[系统Download]";
        }
        return "[本地存储]";
    }

    private void scanDirectoryForM3u(File dir,
                                     List<File> outList,
                                     Set<String> seenFilePaths,
                                     int maxDepth,
                                     boolean skipInternalScratchFiles) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || maxDepth < 0
                || outList.size() >= MAX_SCANNED_M3U_FILES) {
            return;
        }
        File[] files;
        try {
            files = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (outList.size() >= MAX_SCANNED_M3U_FILES) {
                break;
            }
            if (f == null) {
                continue;
            }
            String name = f.getName();
            if (f.isFile()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (skipInternalScratchFiles && ("temp.m3u".equals(lower) || "iptv.m3u".equals(lower))) {
                    continue;
                }
                if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) {
                    String canon = getCanonicalPathSafe(f);
                    if (seenFilePaths.add(canon)) {
                        outList.add(f);
                    }
                }
            } else if (f.isDirectory() && maxDepth > 0 && !name.startsWith(".")) {
                // 避免扫描系统 Android/data 缓存深层无关目录以提升 U 盘与存储扫描速度
                if ("Android".equalsIgnoreCase(name) || "LOST.DIR".equalsIgnoreCase(name)
                        || "System Volume Information".equalsIgnoreCase(name)) {
                    continue;
                }
                scanDirectoryForM3u(f, outList, seenFilePaths, maxDepth - 1, skipInternalScratchFiles);
            }
        }
    }

    private void queryMediaStoreForM3uFiles(List<File> outList, Set<String> seenFilePaths) {
        if (activity == null || outList.size() >= MAX_SCANNED_M3U_FILES) {
            return;
        }
        try {
            ContentResolver resolver = activity.getContentResolver();
            Uri filesUri = MediaStore.Files.getContentUri("external");
            String[] projection = {
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME
            };
            String selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? OR "
                    + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = {"%.m3u", "%.m3u8"};
            try (Cursor cursor = resolver.query(filesUri, projection, selection, selectionArgs, null)) {
                if (cursor != null) {
                    int dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                    while (cursor.moveToNext() && outList.size() < MAX_SCANNED_M3U_FILES) {
                        if (dataIdx >= 0) {
                            String path = cursor.getString(dataIdx);
                            if (!TextUtils.isEmpty(path)) {
                                File f = new File(path);
                                if (f.exists() && f.isFile()) {
                                    String canon = getCanonicalPathSafe(f);
                                    if (seenFilePaths.add(canon)) {
                                        outList.add(f);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private String resolveDisplayName(ContentResolver resolver, Uri uri) {
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        String name = cursor.getString(idx);
                        if (!TextUtils.isEmpty(name)) {
                            return name;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String lastSeg = uri.getLastPathSegment();
        if (!TextUtils.isEmpty(lastSeg)) {
            int slash = lastSeg.lastIndexOf('/');
            return slash >= 0 ? lastSeg.substring(slash + 1) : lastSeg;
        }
        return "custom.m3u";
    }

    private String readStreamToString(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        char[] buf = new char[4096];
        int len;
        while ((len = reader.read(buf)) != -1) {
            sb.append(buf, 0, len);
            if (sb.length() > 4 * 1024 * 1024) {
                break;
            }
        }
        return sb.toString();
    }
}
