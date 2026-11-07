package com.bjunicom.iptv.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.ui.MainActivity;

/**
 * 系统开机广播接收器
 * 当用户在设置中开启「开机自动启动」时，设备开机完成后自动拉起 uTV 主界面进行直播播放
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BootCompletedReceiver";

    public static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    public static final String ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON";
    public static final String EXTRA_FROM_BOOT_COMPLETED = "extra_from_boot_completed";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!isBootAction(action)) {
            return;
        }

        try {
            IptvSettings settings = new IptvSettings(context);
            if (!settings.isAutoStartOnBoot()) {
                Log.i(TAG, "Auto-start on boot is disabled, skipping launch on action=" + action);
                return;
            }

            Log.i(TAG, "Auto-start on boot is enabled, launching MainActivity on action=" + action);
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launchIntent.putExtra(EXTRA_FROM_BOOT_COMPLETED, true);
            context.startActivity(launchIntent);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to auto-start MainActivity on boot: " + t.getMessage());
        }
    }

    public static boolean isBootAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || ACTION_HTC_QUICKBOOT_POWERON.equals(action);
    }
}
