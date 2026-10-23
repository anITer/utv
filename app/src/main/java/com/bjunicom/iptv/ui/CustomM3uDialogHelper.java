package com.bjunicom.iptv.ui;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 自定义 M3U 文件上传、解析校验与流连通性检测弹窗助手
 */
public class CustomM3uDialogHelper {

    public static final int REQUEST_CODE_PICK_M3U_FILE = 10086;

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
    private TextView tvFileName;
    private EditText etContent;
    private TextView tvReport;
    private ProgressBar pbProbe;
    private Button btnProbeStreams;

    private String currentFileName = "custom.m3u";
    private M3uParser.M3uInspectionReport lastReport;
    private MulticastScannerEngine probeEngine;

    public CustomM3uDialogHelper(Activity activity, OnCustomM3uAppliedListener listener) {
        this.activity = activity;
        this.repository = ChannelRepository.getInstance(activity);
        this.settings = new IptvSettings(activity);
        this.listener = listener;
    }

    public void showUploadAndInspectDialog() {
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
                    Toast.makeText(activity,
                            String.format(Locale.US, "流检测完成：可用 %d / %d 个频道",
                                    report.reachableCount, report.validChannelCount),
                            Toast.LENGTH_SHORT).show();
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

    private void stopProbeEngine() {
        if (probeEngine != null) {
            probeEngine.cancel();
        }
        if (btnProbeStreams != null) {
            btnProbeStreams.setText("检测流连通性");
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

    private void showLocalM3uFilesPickerDialog() {
        List<File> foundFiles = new ArrayList<>();
        scanDirectoryForM3u(repository.getScanRootDir(), foundFiles, 2);
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            scanDirectoryForM3u(downloadDir, foundFiles, 2);
            File extRoot = Environment.getExternalStorageDirectory();
            scanDirectoryForM3u(extRoot, foundFiles, 1);
        } catch (Exception ignored) {
        }

        if (foundFiles.isEmpty()) {
            Toast.makeText(activity,
                    "未在本地 Download 或应用目录扫描到 .m3u/.m3u8 文件，可点击「选择本地 M3U 文件」或「已存/示例」",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String[] items = new String[foundFiles.size()];
        for (int i = 0; i < foundFiles.size(); i++) {
            File f = foundFiles.get(i);
            long kb = Math.max(1L, f.length() / 1024L);
            items[i] = f.getName() + " (" + kb + " KB)\n" + f.getAbsolutePath();
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle("选择设备上的 M3U 文件 (" + foundFiles.size() + " 个)")
                .setItems(items, (dialog, which) -> {
                    File selected = foundFiles.get(which);
                    loadFromFile(selected);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void scanDirectoryForM3u(File dir, List<File> outList, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || maxDepth < 0 || outList.size() >= 30) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (outList.size() >= 30) {
                break;
            }
            if (f.isFile()) {
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) {
                    if (!outList.contains(f)) {
                        outList.add(f);
                    }
                }
            } else if (f.isDirectory() && maxDepth > 0 && !f.getName().startsWith(".")) {
                scanDirectoryForM3u(f, outList, maxDepth - 1);
            }
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
