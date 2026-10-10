package com.tsuyu.messenger.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;
import com.tsuyu.messenger.Profile;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.ChatSummary;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** RecyclerView adapter whose entire row hierarchy and motion resources live in XML. */
public final class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.Holder> {
    public interface Listener {
        void onChatSelected(ChatSummary summary);
        String previewFor(ChatSummary summary);
    }

    private final Listener listener;
    private final List<ChatSummary> items = new ArrayList<>();
    private final java.util.Set<String> animated = new java.util.HashSet<>();

    public ChatListAdapter(Listener listener) { this.listener = listener; }

    public void submit(List<ChatSummary> summaries) {
        items.clear();
        items.addAll(summaries);
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_chat, parent, false);
        return new Holder(row);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        ChatSummary summary = items.get(position);
        Profile peer = summary.peer == null ? new Profile() : summary.peer;
        String title = peer.displayNameOrUsername();
        holder.name.setText(title);
        holder.time.setText(summary.lastAt <= 0 ? "" : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(summary.lastAt)));
        String preview = summary.typing ? "печатает…" : listener.previewFor(summary);
        holder.preview.setText(preview);
        holder.preview.setTextColor(summary.typing
                ? holder.itemView.getContext().getColor(R.color.tsuyu_blue)
                : holder.itemView.getContext().getColor(R.color.tsuyu_secondary));
        holder.online.setVisibility(summary.online ? View.VISIBLE : View.GONE);
        bindAvatar(holder.avatar, holder.initial, peer, title);
        holder.itemView.setContentDescription("Диалог с " + title + ". " + preview);
        holder.itemView.setOnClickListener(v -> listener.onChatSelected(summary));
        String key = summary.chatId == null ? title : summary.chatId;
        if (animated.add(key)) holder.itemView.startAnimation(AnimationUtils.loadAnimation(holder.itemView.getContext(), R.anim.message_enter));
    }

    public static void bindAvatar(ShapeableImageView image, TextView initial, Profile profile, String displayName) {
        String label = profile == null ? "" : profile.displayName;
        if (label == null || label.trim().isEmpty()) label = profile == null ? "" : profile.username;
        if (label == null || label.trim().isEmpty()) label = displayName;
        label = label == null ? "T" : label.trim().replaceFirst("^@", "");
        initial.setText(label.isEmpty() ? "T" : label.substring(0, 1).toUpperCase(Locale.ROOT));
        String encoded = profile == null ? null : profile.avatarBase64;
        if (encoded == null || encoded.isEmpty()) {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
            initial.setVisibility(View.VISIBLE);
            image.setContentDescription("Аватар " + displayName);
            return;
        }
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new IllegalArgumentException("Invalid avatar bitmap");
            image.setImageBitmap(bitmap);
            image.setVisibility(View.VISIBLE);
            initial.setVisibility(View.GONE);
            image.setContentDescription("Аватар " + displayName);
        } catch (Exception ignored) {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
            initial.setVisibility(View.VISIBLE);
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView avatar;
        final TextView initial;
        final View online;
        final TextView name;
        final TextView time;
        final TextView preview;
        Holder(View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.chat_avatar);
            initial = itemView.findViewById(R.id.chat_initial);
            online = itemView.findViewById(R.id.chat_online_dot);
            name = itemView.findViewById(R.id.chat_name);
            time = itemView.findViewById(R.id.chat_time);
            preview = itemView.findViewById(R.id.chat_preview);
        }
    }
}
