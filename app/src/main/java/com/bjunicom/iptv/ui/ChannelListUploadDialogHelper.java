package com.bjunicom.iptv.ui;

import android.app.Activity;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.bjunicom.iptv.R;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.report.ChannelListShareManager;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 频道列表上传至服务器设置弹窗助手
 *
 * 包含设置项：
 * 1. 运营商信息（省、市、运营商类型：联通/移动/电信/广电）；
 * 2. 频道信息（直连、经过代理后的、还是时移列表）；
 * 3. 文件名信息；
 * 完成设置后先调用服务端接口判断是否存在同名同属性的列表，确认无重名后开始上传 M3U 文件流。
 */
public class ChannelListUploadDialogHelper {

    public interface OnUploadResultListener {
        void onUploadSucceeded(@NonNull ChannelListShareManager.UploadSettings settings,
                               @NonNull ChannelListShareManager.UploadResponse response);
    }

    private final Activity activity;
    private final IptvSettings settings;
    private final ChannelRepository repository;
    private final ChannelListShareManager shareManager;
    @Nullable
    private final OnUploadResultListener listener;

    private AlertDialog activeUploadDialog;
    private EditText etProvince;
    private EditText etCity;
    private RadioGroup rgIspType;
    private RadioGroup rgChannelType;
    private EditText etFileName;
    private TextView tvChannelSummary;
    private ProgressBar pbUploadProgress;
    private TextView tvUploadStatus;

    private String currentM3uContent = "";
    private int currentChannelCount = 0;

    public ChannelListUploadDialogHelper(@NonNull Activity activity,
                                         @Nullable OnUploadResultListener listener) {
        this.activity = activity;
        this.settings = new IptvSettings(activity);
        this.repository = ChannelRepository.getInstance(activity);
        this.shareManager = ChannelListShareManager.getInstance(activity);
        this.listener = listener;
    }

    @Nullable
    public AlertDialog getActiveUploadDialog() {
        return activeUploadDialog;
    }

    @Nullable
    public EditText getProvinceEditText() {
        return etProvince;
    }

    @Nullable
    public EditText getCityEditText() {
        return etCity;
    }

    @Nullable
    public RadioGroup getIspTypeRadioGroup() {
        return rgIspType;
    }

    @Nullable
    public RadioGroup getChannelTypeRadioGroup() {
        return rgChannelType;
    }

    @Nullable
    public EditText getFileNameEditText() {
        return etFileName;
    }

    @Nullable
    public TextView getUploadStatusTextView() {
        return tvUploadStatus;
    }

    /**
     * 针对频道扫描结果弹出上传设置界面
     */
    public void showUploadSettingsDialogForChannels(@Nullable List<IptvChannel> channels,
                                                    @Nullable String defaultFileName) {
        List<IptvChannel> effective = repository.extractEffectiveScannedChannels(channels);
        if (effective.isEmpty()) {
            Toast.makeText(activity, "当前没有可上传的有效频道", Toast.LENGTH_SHORT).show();
            return;
        }
        String m3uContent = M3uParser.toM3uContent(effective, false, null);
        String suggestedName = !TextUtils.isEmpty(defaultFileName)
                ? defaultFileName
                : "iptv_scanned.m3u";
        showUploadSettingsDialog(m3uContent, suggestedName, effective);
    }

    /**
     * 针对自定义 M3U 内容弹出上传设置界面
     */
    public void showUploadSettingsDialog(@NonNull String m3uContent,
                                         @Nullable String suggestedFileName,
                                         @Nullable List<IptvChannel> parsedChannels) {
        if (activity.isFinishing()) {
            return;
        }
        List<IptvChannel> channels = parsedChannels != null
                ? parsedChannels
                : M3uParser.parseString(m3uContent);
        if (TextUtils.isEmpty(m3uContent) || m3uContent.trim().isEmpty() || channels.isEmpty()) {
            Toast.makeText(activity, "M3U 频道列表内容为空或无有效频道，无法上传", Toast.LENGTH_SHORT).show();
            return;
        }

        if (activeUploadDialog != null && activeUploadDialog.isShowing()) {
            activeUploadDialog.dismiss();
        }

        this.currentM3uContent = m3uContent;
        this.currentChannelCount = channels.size();

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_upload_channel_list, null);
        etProvince = view.findViewById(R.id.et_upload_province);
        etCity = view.findViewById(R.id.et_upload_city);
        rgIspType = view.findViewById(R.id.rg_upload_isp_type);
        rgChannelType = view.findViewById(R.id.rg_upload_channel_type);
        etFileName = view.findViewById(R.id.et_upload_file_name);
        tvChannelSummary = view.findViewById(R.id.tv_upload_channel_summary);
        pbUploadProgress = view.findViewById(R.id.pb_upload_progress);
        tvUploadStatus = view.findViewById(R.id.tv_upload_status);

        // 默认回填省、市、运营商类型
        etProvince.setText("北京");
        etCity.setText("北京");
        String defaultIspType = ChannelListShareManager.inferDefaultIspType(settings.getIspProvider());
        if (ChannelListShareManager.ISP_TYPE_MOBILE.equals(defaultIspType)) {
            rgIspType.check(R.id.rb_upload_isp_mobile);
        } else if (ChannelListShareManager.ISP_TYPE_TELECOM.equals(defaultIspType)) {
            rgIspType.check(R.id.rb_upload_isp_telecom);
        } else {
            rgIspType.check(R.id.rb_upload_isp_unicom);
        }

        // 默认推断频道信息（直连、经过代理后的、还是时移列表）
        String defaultChannelType = ChannelListShareManager.inferDefaultChannelType(m3uContent, channels, settings);
        if (ChannelListShareManager.CHANNEL_TYPE_TIMESHIFT.equals(defaultChannelType)) {
            rgChannelType.check(R.id.rb_upload_channel_timeshift);
        } else if (ChannelListShareManager.CHANNEL_TYPE_PROXY.equals(defaultChannelType)) {
            rgChannelType.check(R.id.rb_upload_channel_proxy);
        } else {
            rgChannelType.check(R.id.rb_upload_channel_direct);
        }

        String normFileName = ChannelListShareManager.normalizeFileName(
                !TextUtils.isEmpty(suggestedFileName) ? suggestedFileName : "custom_iptv.m3u");
        etFileName.setText(normFileName);

        long sizeBytes = m3uContent.getBytes(StandardCharsets.UTF_8).length;
        double sizeKb = Math.max(0.1, sizeBytes / 1024.0);
        tvChannelSummary.setText(String.format(Locale.US,
                "待上传有效频道: %d 个  |  文件大小: %.1f KB (%d B)",
                currentChannelCount, sizeKb, sizeBytes));

        activeUploadDialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("上传频道列表至服务器")
                .setView(view)
                .setPositiveButton("确认上传", null)
                .setNegativeButton("取消", null)
                .create();

        activeUploadDialog.show();

        Button btnConfirm = activeUploadDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> triggerCheckAndUpload());
        }
    }

    /**
     * 从当前界面控件读取用户填写的上传设置信息
     */
    @NonNull
    public ChannelListShareManager.UploadSettings collectCurrentUploadSettings() {
        String province = etProvince != null && etProvince.getText() != null
                ? etProvince.getText().toString().trim() : "";
        String city = etCity != null && etCity.getText() != null
                ? etCity.getText().toString().trim() : "";

        String ispType = ChannelListShareManager.ISP_TYPE_UNICOM;
        if (rgIspType != null) {
            int checkedIsp = rgIspType.getCheckedRadioButtonId();
            if (checkedIsp == R.id.rb_upload_isp_mobile) {
                ispType = ChannelListShareManager.ISP_TYPE_MOBILE;
            } else if (checkedIsp == R.id.rb_upload_isp_telecom) {
                ispType = ChannelListShareManager.ISP_TYPE_TELECOM;
            } else if (checkedIsp == R.id.rb_upload_isp_broadnet) {
                ispType = ChannelListShareManager.ISP_TYPE_BROADNET;
            } else {
                ispType = ChannelListShareManager.ISP_TYPE_UNICOM;
            }
        }

        String channelType = ChannelListShareManager.CHANNEL_TYPE_DIRECT;
        if (rgChannelType != null) {
            int checkedType = rgChannelType.getCheckedRadioButtonId();
            if (checkedType == R.id.rb_upload_channel_proxy) {
                channelType = ChannelListShareManager.CHANNEL_TYPE_PROXY;
            } else if (checkedType == R.id.rb_upload_channel_timeshift) {
                channelType = ChannelListShareManager.CHANNEL_TYPE_TIMESHIFT;
            } else {
                channelType = ChannelListShareManager.CHANNEL_TYPE_DIRECT;
            }
        }

        String fileName = etFileName != null && etFileName.getText() != null
                ? etFileName.getText().toString().trim() : "";

        return new ChannelListShareManager.UploadSettings(
                province,
                city,
                ispType,
                channelType,
                fileName
        );
    }

    /**
     * 执行「先检测同名列表 -> 确认无重名后上传 M3U 文件流」
     */
    public void triggerCheckAndUpload() {
        final ChannelListShareManager.UploadSettings uploadSettings = collectCurrentUploadSettings();
        String validationErr = uploadSettings.validate();
        if (validationErr != null) {
            updateStatusText(validationErr, true);
            Toast.makeText(activity, validationErr, Toast.LENGTH_SHORT).show();
            return;
        }
        if (etFileName != null) {
            etFileName.setText(uploadSettings.fileName);
        }

        setLoadingState(true, "正在调用服务端接口检测是否存在同名频道列表...");

        shareManager.checkAndUploadAsync(
                uploadSettings,
                currentM3uContent,
                currentChannelCount,
                new ChannelListShareManager.ShareCallback() {
                    @Override
                    public void onDuplicateDetected(@NonNull ChannelListShareManager.UploadSettings s,
                                                    @NonNull ChannelListShareManager.CheckResponse checkResponse) {
                        String msg = String.format(Locale.US,
                                "已存在同名同属性的频道列表: %s (%s-%s-%s-%s)，请修改文件名后重试",
                                s.fileName, s.province, s.city, s.ispType, s.channelType);
                        setLoadingState(false, msg);
                        if (tvUploadStatus != null) {
                            tvUploadStatus.setTextColor(Color.parseColor("#FCA5A5"));
                        }
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onUploadSuccess(@NonNull ChannelListShareManager.UploadSettings s,
                                                @NonNull ChannelListShareManager.UploadResponse uploadResponse) {
                        String msg = String.format(Locale.US,
                                "上传成功: %s (%s-%s-%s-%s, 共 %d 个频道)",
                                uploadResponse.fileName, s.province, s.city, s.ispType, s.channelType,
                                uploadResponse.channelCount);
                        setLoadingState(false, msg);
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                        if (activeUploadDialog != null && activeUploadDialog.isShowing()) {
                            activeUploadDialog.dismiss();
                        }
                        if (listener != null) {
                            listener.onUploadSucceeded(s, uploadResponse);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull ChannelListShareManager.UploadSettings s,
                                          int httpStatusCode,
                                          @NonNull String errorMessage) {
                        setLoadingState(false, errorMessage);
                        if (tvUploadStatus != null) {
                            tvUploadStatus.setTextColor(Color.parseColor("#FCA5A5"));
                        }
                        Toast.makeText(activity, errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void setLoadingState(boolean loading, String statusMsg) {
        if (pbUploadProgress != null) {
            pbUploadProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        updateStatusText(statusMsg, false);
        if (activeUploadDialog != null) {
            Button btnConfirm = activeUploadDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (btnConfirm != null) {
                btnConfirm.setEnabled(!loading);
            }
        }
    }

    private void updateStatusText(String text, boolean isError) {
        if (tvUploadStatus != null) {
            tvUploadStatus.setText(text);
            tvUploadStatus.setTextColor(Color.parseColor(isError ? "#FCA5A5" : "#A7F3D0"));
        }
    }
}
