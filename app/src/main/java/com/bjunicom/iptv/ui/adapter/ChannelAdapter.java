package com.bjunicom.iptv.ui.adapter;

import android.graphics.Color;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bjunicom.iptv.R;
import com.bjunicom.iptv.model.IptvChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * 北京联通 IPTV 频道列表适配器
 * 同时支持手机触屏点击/长按与电视遥控器方向键、菜单键、红键操作
 */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    public interface OnChannelActionListener {
        void onChannelClicked(IptvChannel channel, int position);

        void onChannelLongClicked(IptvChannel channel, int position);

        default void onSwitchCategory(int delta) {
        }

        default void onNavigateUpFromTop() {
        }

        default void onWrapToTop() {
        }
    }

    private final List<IptvChannel> channels = new ArrayList<>();
    private String currentPlayingUrl = "";
    private final OnChannelActionListener listener;
    private RecyclerView attachedRecyclerView;

    public ChannelAdapter(OnChannelActionListener listener) {
        this.listener = listener;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.attachedRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (this.attachedRecyclerView == recyclerView) {
            this.attachedRecyclerView = null;
        }
    }

    private void safeNotifyDataSetChanged() {
        if (attachedRecyclerView != null && attachedRecyclerView.isComputingLayout()) {
            attachedRecyclerView.post(this::notifyDataSetChanged);
        } else {
            notifyDataSetChanged();
        }
    }

    public void submitList(List<IptvChannel> newList) {
        channels.clear();
        if (newList != null) {
            channels.addAll(newList);
        }
        safeNotifyDataSetChanged();
    }

    public void setCurrentPlayingUrl(String rawUrl) {
        this.currentPlayingUrl = rawUrl != null ? rawUrl : "";
        safeNotifyDataSetChanged();
    }

    public List<IptvChannel> getChannels() {
        return channels;
    }

    /**
     * 查找当前正在播放的频道在列表中的索引，便于打开抽屉时遥控器焦点自动定位
     */
    public int getPlayingChannelIndex() {
        if (TextUtils.isEmpty(currentPlayingUrl)) {
            return 0;
        }
        for (int i = 0; i < channels.size(); i++) {
            if (currentPlayingUrl.equals(channels.get(i).getRawUrl())) {
                return i;
            }
        }
        return 0;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        IptvChannel ch = channels.get(position);
        holder.tvNum.setText(ch.getChannelNumberDisplay());
        holder.tvName.setText(ch.getDisplayName());
        holder.tvFav.setVisibility(ch.isFavorite() ? View.VISIBLE : View.GONE);

        // 分辨率徽章: 4K / 高清 / 标清 (使用 Material 3 圆角胶囊 Drawable)
        String res = ch.getResolutionTag();
        holder.tvResBadge.setText(res);
        if ("4K".equals(res)) {
            holder.tvResBadge.setBackgroundResource(R.drawable.bg_res_badge_4k);
            holder.tvResBadge.setTextColor(Color.parseColor("#FDE68A"));
        } else if ("高清".equals(res)) {
            holder.tvResBadge.setBackgroundResource(R.drawable.bg_res_badge_hd);
            holder.tvResBadge.setTextColor(Color.parseColor("#DBEAFE"));
        } else {
            holder.tvResBadge.setBackgroundResource(R.drawable.bg_res_badge_sd);
            holder.tvResBadge.setTextColor(Color.parseColor("#CBD5E1"));
        }

        // 组播地址与探测到的视频轨道摘要
        String subText = ch.getRawUrl();
        if (!TextUtils.isEmpty(ch.getVideoTrackInfo())) {
            subText = subText + "  •  " + ch.getVideoTrackInfo().replace("#1 ", "");
        }
        holder.tvAddr.setText(subText);

        // 连通性状态标识 (对应 check_iptv_m3u.sh 的 connect_flag)
        Boolean connected = ch.getConnected();
        if (Boolean.TRUE.equals(connected)) {
            String lat = ch.getLatencyMs() > 0 ? (" " + ch.getLatencyMs() + "ms") : "";
            holder.tvStatus.setText("● 可用" + lat);
            holder.tvStatus.setTextColor(Color.parseColor("#10B981"));
        } else if (Boolean.FALSE.equals(connected)) {
            holder.tvStatus.setText("○ 不可用");
            holder.tvStatus.setTextColor(Color.parseColor("#EF4444"));
        } else {
            holder.tvStatus.setText(ch.isDiscoveredByScan() ? "★ 新发现" : "");
            holder.tvStatus.setTextColor(Color.parseColor("#94A3B8"));
        }

        boolean isPlaying = currentPlayingUrl.equals(ch.getRawUrl());
        holder.itemView.setSelected(isPlaying);
        holder.itemView.setLongClickable(true);

        // 电视遥控器焦点缩放视觉反馈
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate()
                    .scaleX(hasFocus ? 1.02f : 1.0f)
                    .scaleY(hasFocus ? 1.02f : 1.0f)
                    .setDuration(100)
                    .start();
        });

        holder.itemView.setOnClickListener(v -> {
            int adapterPos = holder.getAdapterPosition();
            if (adapterPos == RecyclerView.NO_POSITION) {
                adapterPos = position;
            }
            if (listener != null && adapterPos >= 0 && adapterPos < channels.size()) {
                listener.onChannelClicked(channels.get(adapterPos), adapterPos);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            int adapterPos = holder.getAdapterPosition();
            if (adapterPos == RecyclerView.NO_POSITION) {
                adapterPos = position;
            }
            if (listener != null && adapterPos >= 0 && adapterPos < channels.size()) {
                listener.onChannelLongClicked(channels.get(adapterPos), adapterPos);
                return true;
            }
            return false;
        });

        // 电视遥控器在频道列表中的快捷导航：
        // - 左/右方向键：直接切换频道分类标签 ([高清]央视 / [高清]卫视 / 4K / 标清 等)
        // - 红键/星号键/书签键：一键收藏/取消收藏当前焦点频道
        // - 首项按上键跳到分类栏，末项按下键循环回首项
        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || listener == null) {
                return false;
            }
            int adapterPos = holder.getAdapterPosition();
            if (adapterPos == RecyclerView.NO_POSITION) {
                adapterPos = position;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                listener.onSwitchCategory(-1);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                listener.onSwitchCategory(1);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && adapterPos == 0) {
                listener.onNavigateUpFromTop();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && adapterPos == channels.size() - 1) {
                listener.onWrapToTop();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_PROG_RED
                    || keyCode == KeyEvent.KEYCODE_STAR
                    || keyCode == KeyEvent.KEYCODE_BOOKMARK) {
                if (adapterPos >= 0 && adapterPos < channels.size()) {
                    listener.onChannelLongClicked(channels.get(adapterPos), adapterPos);
                }
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvNum;
        final TextView tvName;
        final TextView tvFav;
        final TextView tvResBadge;
        final TextView tvAddr;
        final TextView tvStatus;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvNum = itemView.findViewById(R.id.tv_item_channel_num);
            tvName = itemView.findViewById(R.id.tv_item_channel_name);
            tvFav = itemView.findViewById(R.id.tv_item_fav);
            tvResBadge = itemView.findViewById(R.id.tv_item_res_badge);
            tvAddr = itemView.findViewById(R.id.tv_item_multicast_addr);
            tvStatus = itemView.findViewById(R.id.tv_item_status_badge);
        }
    }
}
