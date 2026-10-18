package com.bjunicom.iptv.ui.adapter;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bjunicom.iptv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道分组标签栏适配器（同时支持触控点击与电视遥控器方向键切换）
 */
public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {

    public interface OnCategoryInteractionListener {
        void onCategorySelected(String category, int position);

        default void onRequestChannelListFocus() {
        }
    }

    private final List<String> categories = new ArrayList<>();
    private String selectedCategory = "全部频道";
    private final OnCategoryInteractionListener listener;
    private RecyclerView attachedRecyclerView;

    public CategoryAdapter(List<String> initialCategories, OnCategoryInteractionListener listener) {
        if (initialCategories != null) {
            categories.addAll(initialCategories);
        }
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

    public void setSelectedCategory(String category) {
        this.selectedCategory = category;
        safeNotifyDataSetChanged();
    }

    public String getSelectedCategory() {
        return selectedCategory;
    }

    public int getSelectedPosition() {
        int idx = categories.indexOf(selectedCategory);
        return Math.max(0, idx);
    }

    /**
     * 供遥控器左/右方向键在任意位置快速切换分类
     */
    public int selectAdjacentCategory(int delta) {
        if (categories.isEmpty()) {
            return 0;
        }
        int curIdx = getSelectedPosition();
        int nextIdx = (curIdx + delta + categories.size()) % categories.size();
        selectedCategory = categories.get(nextIdx);
        safeNotifyDataSetChanged();
        if (listener != null) {
            listener.onCategorySelected(selectedCategory, nextIdx);
        }
        return nextIdx;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_category_tab, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String cat = categories.get(position);
        holder.tvName.setText(cat);
        boolean isSelected = cat.equals(selectedCategory);
        holder.tvName.setSelected(isSelected);

        holder.tvName.setOnClickListener(v -> selectCategoryAt(position));

        holder.tvName.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.06f : 1.0f).scaleY(hasFocus ? 1.06f : 1.0f).setDuration(120).start();
            if (hasFocus && !cat.equals(selectedCategory)) {
                selectCategoryAt(position);
            }
        });

        holder.tvName.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (listener != null) {
                    listener.onRequestChannelListFocus();
                    return true;
                }
            }
            return false;
        });
    }

    private void selectCategoryAt(int position) {
        if (position < 0 || position >= categories.size()) {
            return;
        }
        String cat = categories.get(position);
        selectedCategory = cat;
        safeNotifyDataSetChanged();
        if (listener != null) {
            listener.onCategorySelected(cat, position);
        }
    }

    @Override
    public int getItemCount() {
        return categories.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvName;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_category_name);
        }
    }
}
