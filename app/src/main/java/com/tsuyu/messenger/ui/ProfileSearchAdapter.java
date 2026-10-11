package com.tsuyu.messenger.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.tsuyu.messenger.Profile;
import com.tsuyu.messenger.R;

import java.util.ArrayList;
import java.util.List;

/** Search result rows are inflated from row_user_search.xml. */
public final class ProfileSearchAdapter extends RecyclerView.Adapter<ProfileSearchAdapter.Holder> {
    public interface Listener { void onSelect(Profile profile); }
    private final Listener listener;
    private final List<Profile> items = new ArrayList<>();
    private final java.util.Set<String> animated = new java.util.HashSet<>();

    public ProfileSearchAdapter(Listener listener) { this.listener = listener; }

    /** Список результатов обновляется по строкам: полной перерисовки нет. */
    public void submit(List<Profile> profiles) {
        List<Profile> next = profiles == null ? new ArrayList<>() : new ArrayList<>(profiles);
        int limit = Math.min(items.size(), next.size());
        for (int index = 0; index < limit; index++) {
            Profile fresh = next.get(index);
            String first = items.get(index).uid;
            String second = fresh.uid;
            if (first == null ? second != null : !first.equals(second)) {
                items.clear();
                items.addAll(next);
                notifyItemRangeChanged(0, items.size());
                return;
            }
            items.set(index, fresh);
        }
        if (items.size() < next.size()) {
            int from = items.size();
            items.addAll(next.subList(from, next.size()));
            notifyItemRangeInserted(from, items.size() - from);
        } else if (items.size() > next.size()) {
            int from = next.size();
            int removed = items.size() - from;
            items.subList(from, items.size()).clear();
            notifyItemRangeRemoved(from, removed);
        }
        if (limit > 0) notifyItemRangeChanged(0, Math.min(limit, items.size()));
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_user_search, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Profile profile = items.get(position);
        String name = profile.displayNameOrUsername();
        holder.name.setText(name);
        holder.handle.setText(profile.username == null ? "" : "@" + profile.username);
        ChatListAdapter.bindAvatar(holder.avatar, holder.initial, profile, name);
        holder.itemView.setContentDescription(name + (profile.username == null ? "" : ", @" + profile.username));
        holder.itemView.setOnClickListener(v -> listener.onSelect(profile));
        holder.message.setOnClickListener(v -> listener.onSelect(profile));
        String key = profile.uid == null ? name : profile.uid;
        // Мгновенная отрисовка: строки не «влетают» с анимацией.
    }

    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView avatar;
        final TextView initial;
        final TextView name;
        final TextView handle;
        final MaterialButton message;
        Holder(View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.user_avatar);
            initial = itemView.findViewById(R.id.user_initial);
            name = itemView.findViewById(R.id.user_name);
            handle = itemView.findViewById(R.id.user_handle);
            message = itemView.findViewById(R.id.user_message_button);
        }
    }
}
