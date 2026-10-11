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
    private final List<String> signatures = new ArrayList<>();

    public ChatListAdapter(Listener listener) { this.listener = listener; }

    /** Номер последней отправки: устаревшие сравнения не должны менять список. */
    private final java.util.concurrent.atomic.AtomicInteger submitGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Список чатов обновляется точно и без анимаций: сравнение старых и новых строк идёт
     * в фоне, а на экран отдаётся готовый результат. Дубли невозможны — каждая строка
     * опознаётся по своему номеру чата.
     */
    public void submit(List<ChatSummary> summaries) {
        final List<ChatSummary> next = summaries == null ? new ArrayList<>() : new ArrayList<>(summaries);
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            com.tsuyu.messenger.util.Background.main(() -> submit(next));
            return;
        }
        submitGeneration.incrementAndGet();
        if (next.size() == items.size() && sameOrder(next)) {
            // Состав и порядок те же: перерисовываем только те строки, что реально изменились.
            for (int position = 0; position < next.size(); position++) {
                ChatSummary fresh = next.get(position);
                String freshSignature = signature(fresh);
                boolean changed = !freshSignature.equals(signatures.get(position));
                items.set(position, fresh);
                if (changed) {
                    signatures.set(position, freshSignature);
                    notifyItemChanged(position);
                }
            }
            return;
        }
        final List<ChatSummary> current = new ArrayList<>(items);
        final int generation = submitGeneration.get();
        com.tsuyu.messenger.util.Background.diff().execute(() -> {
            final androidx.recyclerview.widget.DiffUtil.DiffResult result =
                    androidx.recyclerview.widget.DiffUtil.calculateDiff(
                            new androidx.recyclerview.widget.DiffUtil.Callback() {
                                @Override public int getOldListSize() { return current.size(); }
                                @Override public int getNewListSize() { return next.size(); }
                                @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                                    String first = current.get(oldPos).chatId;
                                    String second = next.get(newPos).chatId;
                                    return first != null && first.equals(second);
                                }
                                @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                                    return sameContents(current.get(oldPos), next.get(newPos));
                                }
                            });
            com.tsuyu.messenger.util.Background.main(() -> {
                if (generation != submitGeneration.get()) return; // пришло более свежее обновление
                items.clear();
                items.addAll(next);
                signatures.clear();
                for (ChatSummary summary : next) signatures.add(signature(summary));
                result.dispatchUpdatesTo(this);
            });
        });
    }

    /** Размер аватара строки берётся из настроек: 54dp по умолчанию, меньше или больше по вкусу. */
    private void applyIconSize(Holder holder) {
        android.view.View parent = (android.view.View) holder.avatar.getParent();
        if (parent == null) return;
        int dp = com.tsuyu.messenger.util.Settings.getInt("icon_size", 54);
        if (dp < 40) dp = 40;
        if (dp > 64) dp = 64;
        int size = Math.round(dp * holder.itemView.getResources().getDisplayMetrics().density);
        android.view.ViewGroup.LayoutParams params = parent.getLayoutParams();
        if (params == null || params.width == size) return;
        params.width = size;
        params.height = size;
        parent.setLayoutParams(params);
    }

    /** Chat id at a list position, used by swipe gestures. */
    public ChatSummary summaryAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    private boolean sameOrder(List<ChatSummary> next) {
        for (int position = 0; position < next.size(); position++) {
            String first = items.get(position).chatId;
            String second = next.get(position).chatId;
            if (first == null ? second != null : !first.equals(second)) return false;
        }
        return true;
    }

    /** Подпись строки: если она не изменилась, строку перерисовывать не нужно. */
    private String signature(ChatSummary summary) {
        String avatar = summary.peer == null ? null : summary.peer.avatarBase64;
        return titleOf(summary) + '\u0001' + summary.lastAt + '\u0001' + summary.typing + '\u0001'
                + summary.online + '\u0001' + summary.pinned + '\u0001' + summary.unread + '\u0001'
                + listener.ownMessageState(summary) + '\u0001' + previewOf(summary) + '\u0001'
                + (avatar == null ? 0 : avatar.length());
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
        applyIconSize(holder);
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
        SimpleDateFormat clock = clockFormat;
        if (clock == null) {
            // Форматтеры и календари создаются один раз: листание списка не мусорит объектами.
            clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
            dayFormat = new SimpleDateFormat("d MMM", new Locale("ru"));
            dayYearFormat = new SimpleDateFormat("d MMM yy", new Locale("ru"));
            clockFormat = clock;
        }
        stampNow.setTimeInMillis(System.currentTimeMillis());
        stampThen.setTimeInMillis(at);
        boolean sameYear = stampNow.get(Calendar.YEAR) == stampThen.get(Calendar.YEAR);
        if (sameYear && stampNow.get(Calendar.DAY_OF_YEAR) == stampThen.get(Calendar.DAY_OF_YEAR)) {
            return clock.format(new Date(at));
        }
        stampYesterday.setTimeInMillis(System.currentTimeMillis());
        stampYesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (stampYesterday.get(Calendar.YEAR) == stampThen.get(Calendar.YEAR)
                && stampYesterday.get(Calendar.DAY_OF_YEAR) == stampThen.get(Calendar.DAY_OF_YEAR)) {
            return "вчера";
        }
        return (sameYear ? dayFormat : dayYearFormat).format(new Date(at));
    }

    private static SimpleDateFormat clockFormat;
    private static SimpleDateFormat dayFormat;
    private static SimpleDateFormat dayYearFormat;
    private static final Calendar stampNow = Calendar.getInstance();
    private static final Calendar stampThen = Calendar.getInstance();
    private static final Calendar stampYesterday = Calendar.getInstance();

    /** Аватары готовятся в общем фоновом пуле картинок: список чатов листается без рывков. */
    private static final java.util.concurrent.ExecutorService AvatarQueue =
            com.tsuyu.messenger.util.Background.images();

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
        AvatarQueue.execute(() -> {
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
