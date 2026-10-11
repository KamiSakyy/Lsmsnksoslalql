package com.tsuyu.messenger.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;
import com.tsuyu.messenger.Profile;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.data.ChatSummary;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Telegram-grade conversation row: avatar, unread badge, pin marker and delivery marks. */
public final class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.Holder> {
    public interface Listener {
        void onChatSelected(ChatSummary summary);

        void onChatLongPress(ChatSummary summary);

        String previewFor(ChatSummary summary);

        /** 0 — no mark, 1 — sent, 2 — read. */
        int ownMessageState(ChatSummary summary);
    }

    private final Listener listener;
    private final List<ChatSummary> items = new ArrayList<>();
    private final java.util.Set<String> animated = new java.util.HashSet<>();

    public ChatListAdapter(Listener listener) { this.listener = listener; }

    public void submit(List<ChatSummary> summaries) {
        // Список чатов показывается мгновенно: без анимаций и без «умных» сравнений,
        // поэтому строки не задваиваются.
        items.clear();
        if (summaries != null) items.addAll(summaries);
        notifyDataSetChanged();
    }

    /** Chat id at a list position, used by swipe gestures. */
    public ChatSummary summaryAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    private boolean sameContents(ChatSummary first, ChatSummary second) {
        if (first.lastAt != second.lastAt || first.unread != second.unread
                || first.pinned != second.pinned || first.online != second.online
                || first.typing != second.typing || first.lastState != second.lastState) return false;
        return String.valueOf(previewOf(first)).equals(String.valueOf(previewOf(second)))
                && String.valueOf(titleOf(first)).equals(String.valueOf(titleOf(second)));
    }

    private static String titleOf(ChatSummary summary) {
        Profile peer = summary.peer == null ? new Profile() : summary.peer;
        return peer.displayNameOrUsername();
    }

    private CharSequence previewOf(ChatSummary summary) {
        if (summary.typing) return "печатает…";
        return listener.previewFor(summary);
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
        holder.time.setText(summary.lastAt <= 0 ? "" : formatStamp(summary.lastAt));
        holder.time.setTextColor(holder.itemView.getContext().getColor(R.color.tsuyu_time));
        String preview = summary.typing ? "печатает…" : listener.previewFor(summary);
        holder.preview.setText(preview);
        holder.preview.setTextColor(summary.typing
                ? holder.itemView.getContext().getColor(R.color.tsuyu_blue)
                : holder.itemView.getContext().getColor(R.color.tsuyu_preview));
        holder.online.setVisibility(summary.online ? View.VISIBLE : View.GONE);
        holder.pin.setVisibility(summary.pinned ? View.VISIBLE : View.GONE);
        if (summary.unread > 0) {
            holder.unread.setVisibility(View.VISIBLE);
            holder.unread.setText(summary.unread > 99 ? "99+" : Integer.toString(summary.unread));
        } else {
            holder.unread.setVisibility(View.GONE);
        }
        int state = listener.ownMessageState(summary);
        if (state > 0) {
            holder.previewStatus.setVisibility(View.VISIBLE);
            holder.previewStatus.setImageResource(state == 2 ? R.drawable.ic_checks : R.drawable.ic_check);
            holder.previewStatus.setColorFilter(holder.itemView.getContext().getColor(
                    state == 2 ? R.color.tsuyu_blue : R.color.tsuyu_secondary));
        } else {
            holder.previewStatus.setVisibility(View.GONE);
        }
        holder.divider.setVisibility(position == items.size() - 1 ? View.GONE : View.VISIBLE);
        bindAvatar(holder.avatar, holder.initial, peer, title);
        String description = "Диалог с " + title + ". " + preview
                + (summary.unread > 0 ? ". Непрочитанных: " + summary.unread : "");
        holder.itemView.setContentDescription(description);
        holder.itemView.setOnClickListener(v -> listener.onChatSelected(summary));
        holder.itemView.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onChatLongPress(summary);
            return true;
        });
        String key = summary.chatId == null ? title : summary.chatId;
        // Мгновенная отрисовка: строки не «влетают» с анимацией.
    }

    /** Today shows the clock, yesterday a word, older messages a short date — like Telegram. */
    private static String formatStamp(long at) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(at);
        boolean sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR);
        boolean sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        if (sameDay) return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(at));
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        boolean isYesterday = sameYear && yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        if (isYesterday) return "вчера";
        return new SimpleDateFormat(sameYear ? "d MMM" : "d MMM yy", new Locale("ru")).format(new Date(at));
    }

    /** Аватары готовятся в фоне: список чатов листается без рывков. */
    private static final java.util.concurrent.ExecutorService AvatarQueue =
            java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "tsuyu-avatar");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });

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
        // Аватар готовится в фоне и запоминается: прокрутка списка не тормозит даже с фото.
        final Bitmap ready = com.tsuyu.messenger.TsuyuPhotoCache.avatarPeek(encoded);
        if (ready != null) {
            image.setImageBitmap(ready);
            image.setVisibility(View.VISIBLE);
            initial.setVisibility(View.GONE);
            image.setContentDescription("Аватар " + displayName);
            return;
        }
        image.setTag(encoded);
        AvatarQueue.post(() -> {
            final Bitmap bitmap = com.tsuyu.messenger.TsuyuPhotoCache.avatar(image.getContext(), encoded);
            image.post(() -> {
                if (bitmap == null || !encoded.equals(image.getTag())) return;
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
                initial.setVisibility(View.GONE);
                image.setContentDescription("Аватар " + displayName);
            });
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView avatar;
        final TextView initial;
        final View online;
        final TextView name;
        final TextView time;
        final TextView preview;
        final ImageView previewStatus;
        final ImageView pin;
        final TextView unread;
        final View divider;
        Holder(View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.chat_avatar);
            initial = itemView.findViewById(R.id.chat_initial);
            online = itemView.findViewById(R.id.chat_online_dot);
            name = itemView.findViewById(R.id.chat_name);
            time = itemView.findViewById(R.id.chat_time);
            preview = itemView.findViewById(R.id.chat_preview);
            previewStatus = itemView.findViewById(R.id.chat_preview_status);
            pin = itemView.findViewById(R.id.chat_pin);
            unread = itemView.findViewById(R.id.chat_unread);
            divider = itemView.findViewById(R.id.chat_row_divider);
        }
    }
}
