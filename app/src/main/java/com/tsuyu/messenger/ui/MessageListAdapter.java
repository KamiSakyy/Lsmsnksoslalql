package com.tsuyu.messenger.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.call.CallEngine;
import com.tsuyu.messenger.data.ChatMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Renders encrypted message records into XML-defined Material bubble templates. */
public final class MessageListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onOpenPayload(String payload);
        void onMessageMenu(View anchor, ChatMessage message, String payload);
        void onRetry(ChatMessage message);
        void onReact(ChatMessage message);
        void onGalleryImage(String encodedImage);
        /** Swipe-to-reply gesture on a bubble. */
        void onReply(ChatMessage message);
        /** Tap on a bubble while the multi-select action bar is open. */
        void onToggleSelect(ChatMessage message);
        /** Тап по опросу: голосование за вариант. */
        void onVote(ChatMessage message, String payload);
    }

    public static final class Row {
        public final ChatMessage message;
        public final String payload;
        public final int reactionCount;
        public final boolean read;
        /** Non-null only for date separators such as «Сегодня». */
        public final String dateLabel;
        /** True for the single unread-messages divider row. */
        public final boolean unreadDivider;

        public Row(ChatMessage message, String payload, int reactionCount, boolean read) {
            this.message = message;
            this.payload = payload;
            this.reactionCount = reactionCount;
            this.read = read;
            this.dateLabel = null;
            this.unreadDivider = false;
        }

        public Row(String dateLabel) {
            this.message = null;
            this.payload = null;
            this.reactionCount = 0;
            this.read = false;
            this.dateLabel = dateLabel;
            this.unreadDivider = false;
        }

        private Row(boolean unreadDivider) {
            this.message = null;
            this.payload = null;
            this.reactionCount = 0;
            this.read = false;
            this.dateLabel = null;
            this.unreadDivider = unreadDivider;
        }

        /** The blue «Непрочитанные сообщения» line shown once inside a conversation. */
        public static Row unreadDivider() { return new Row(true); }

        public boolean isDate() { return dateLabel != null; }
    }

    /** Consecutive messages from one author inside this window form a visual group. */
    private static final long GROUP_WINDOW_MS = 5 * 60 * 1000L;

    private final String ownUid;
    private final Listener listener;
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> animatedIds = new HashSet<>();
    private final Set<String> selectedKeys = new HashSet<>();
    private boolean selectionMode;
    /** Голоса опросов: messageId -> (uid -> номер варианта). Заполняет экран чата. */
    private java.util.Map<String, java.util.Map<String, Integer>> pollVotes = java.util.Collections.emptyMap();
    private int textSize;
    private boolean bold;
    private boolean italic;

    public MessageListAdapter(String ownUid, int textSize, boolean bold, boolean italic, Listener listener) {
        this.ownUid = ownUid;
        this.textSize = textSize;
        this.bold = bold;
        this.italic = italic;
        this.listener = listener;
    }

    public void setPollVotes(java.util.Map<String, java.util.Map<String, Integer>> votes) {
        pollVotes = votes == null ? java.util.Collections.emptyMap() : votes;
    }

    /** Turns tap-to-select on for every bubble (web .msg-select-mode). */
    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        if (!enabled) selectedKeys.clear();
        notifyItemRangeChanged(0, rows.size());
    }

    /** Replaces the set of highlighted messages, keyed by id or clientId. */
    public void setSelectedKeys(Set<String> keys) {
        selectedKeys.clear();
        if (keys != null) selectedKeys.addAll(keys);
        notifyItemRangeChanged(0, rows.size());
    }

    /** Adapter position of a message id, or -1 when it is not rendered yet. */
    public int positionOf(String messageId) {
        if (messageId == null) return -1;
        for (int index = 0; index < rows.size(); index++) {
            ChatMessage message = rows.get(index).message;
            if (message != null && messageId.equals(message.id)) return index;
        }
        return -1;
    }

    public void setTextStyle(int textSize, boolean bold, boolean italic) {
        boolean changed = this.textSize != textSize || this.bold != bold || this.italic != italic;
        this.textSize = textSize;
        this.bold = bold;
        this.italic = italic;
        if (changed && !rows.isEmpty()) {
            notifyItemRangeChanged(0, rows.size());
        }
    }

    public void submit(List<Row> next) {
        final List<Row> incoming = new ArrayList<>(next);
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return rows.size(); }
            @Override public int getNewListSize() { return incoming.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return rowKey(rows.get(oldPos)).equals(rowKey(incoming.get(newPos)));
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                return sameContents(rows.get(oldPos), incoming.get(newPos));
            }
        });
        rows.clear();
        rows.addAll(incoming);
        result.dispatchUpdatesTo(this);
    }

    private static String rowKey(Row row) {
        if (row.unreadDivider) return "unread-divider";
        if (row.isDate()) return "date:" + row.dateLabel;
        ChatMessage message = row.message;
        if (message.id != null) return "id:" + message.id;
        return "client:" + message.clientId;
    }

    private static boolean sameContents(Row first, Row second) {
        if (first.read != second.read || first.reactionCount != second.reactionCount) return false;
        if (first.isDate() || second.isDate() || first.unreadDivider || second.unreadDivider) {
            return first.isDate() == second.isDate() && first.unreadDivider == second.unreadDivider;
        }
        ChatMessage one = first.message;
        ChatMessage two = second.message;
        if (one.pending != two.pending || one.failed != two.failed || one.edited != two.edited) return false;
        if (!String.valueOf(first.payload).equals(String.valueOf(second.payload))) return false;
        return one.sentAt == two.sentAt;
    }

    @Override public int getItemViewType(int position) {
        Row row = rows.get(position);
        if (row.unreadDivider) return 3;
        if (row.isDate()) return 2;
        return ownUid != null && ownUid.equals(row.message.senderUid) ? 1 : 0;
    }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 3) {
            return new LabelHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_message_unread, parent, false));
        }
        if (viewType == 2) {
            return new DateHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_message_date, parent, false));
        }
        int layout = viewType == 1 ? R.layout.row_message_outgoing : R.layout.row_message_incoming;
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof LabelHolder) {
            return;
        }
        if (holder instanceof DateHolder) {
            ((DateHolder) holder).label.setText(row.dateLabel);
            return;
        }
        bindMessage((Holder) holder, row, position);
    }

    private void bindMessage(Holder holder, Row row, int position) {
        ChatMessage message = row.message;
        String payload = row.payload;
        boolean outgoing = ownUid != null && ownUid.equals(message.senderUid);

        // Tight spacing inside a burst of messages from one author, roomier between bursts.
        int topGap = isFirstInGroup(position) ? R.dimen.message_gap_block : R.dimen.message_gap_group;
        holder.itemView.setPadding(
                0,
                holder.itemView.getResources().getDimensionPixelSize(topGap),
                0,
                holder.itemView.getResources().getDimensionPixelSize(R.dimen.message_gap_group));

        holder.body.setVisibility(View.VISIBLE);
        holder.loading.setVisibility(View.GONE);
        holder.image.setVisibility(View.GONE);
        holder.gallery.setVisibility(View.GONE);
        holder.video.setVisibility(View.GONE);
        holder.audio.setVisibility(View.GONE);
        holder.reaction.setVisibility(View.GONE);
        holder.retry.setVisibility(View.GONE);
        holder.edited.setVisibility(View.GONE);
        holder.status.setVisibility(View.GONE);
        resetBubbleChrome(holder, outgoing);
        if (message.id != null && selectedKeys.contains(message.id)
                || message.clientId != null && selectedKeys.contains(message.clientId)) {
            holder.bubble.setStrokeColor(holder.itemView.getContext().getColor(R.color.tsuyu_blue));
            holder.bubble.setStrokeWidth(Math.max(1, Math.round(holder.itemView.getContext().getResources().getDisplayMetrics().density)));
            holder.bubble.setCardBackgroundColor(holder.itemView.getContext().getColor(R.color.tsuyu_accent_tint_strong));
        }
        holder.body.setTextSize(textSize);
        // Ширина пузыря считается от экрана, а не от жёстких 272dp: текст и медиа
        // занимают одну и ту же долю полосы на телефоне, складном экране и планшете.
        android.util.DisplayMetrics metrics = holder.itemView.getContext().getResources().getDisplayMetrics();
        int adaptiveCap = Math.max(Math.round(200 * metrics.density), Math.round(metrics.widthPixels * 0.72f));
        holder.body.setMaxWidth(adaptiveCap);
        scaleMedia(holder, metrics, adaptiveCap);
        int style = bold ? Typeface.BOLD : Typeface.NORMAL;
        if (italic) style |= Typeface.ITALIC;
        holder.body.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        holder.body.setTextColor(holder.itemView.getContext().getColor(R.color.tsuyu_white));

        JSONObject object = null;
        String kind = "text";
        if (payload != null) {
            try { object = new JSONObject(payload); kind = object.optString("kind", "text"); }
            catch (Exception ignored) { }
        }
        if (object == null) {
            if ("decrypt_error".equals(payload)) {
                holder.body.setText("Не удалось открыть сообщение");
            } else {
                holder.body.setVisibility(View.GONE);
                holder.loading.setVisibility(View.VISIBLE);
            }
        } else if ("text".equals(kind)) {
            holder.body.setText(object.optString("text", ""));
        } else if ("decrypt_error".equals(kind)) {
            holder.body.setText("Не удалось открыть сообщение");
        } else if ("image".equals(kind)) {
            holder.body.setVisibility(View.GONE);
            bindImage(holder.image, object.optString("data", ""));
            holder.image.setVisibility(View.VISIBLE);
            holder.image.setOnClickListener(v -> listener.onOpenPayload(payload));
        } else if ("collage".equals(kind)) {
            holder.body.setVisibility(View.GONE);
            JSONArray images = object.optJSONArray("items");
            List<String> encoded = new ArrayList<>();
            if (images != null) for (int i = 0; i < images.length(); i++) {
                JSONObject item = images.optJSONObject(i);
                if (item != null && !item.optString("data", "").isEmpty()) encoded.add(item.optString("data"));
            }
            holder.gallery.setVisibility(encoded.isEmpty() ? View.GONE : View.VISIBLE);
            if (holder.gallery.getLayoutManager() == null) holder.gallery.setLayoutManager(new GridLayoutManager(holder.gallery.getContext(), 2));
            holder.gallery.setNestedScrollingEnabled(false);
            holder.gallery.setAdapter(new GalleryAdapter(encoded, listener::onGalleryImage));
        } else if ("video".equals(kind)) {
            holder.body.setVisibility(View.GONE);
            holder.video.setVisibility(View.VISIBLE);
            holder.video.setOnClickListener(v -> listener.onOpenPayload(payload));
        } else if ("circle".equals(kind)) {
            // Кружок живёт прямо в переписке, как в Telegram: круглый плеер, первый кадр,
            // тап — play/pause, длительность в углу. Никаких серых заглушек и диалогов.
            holder.body.setVisibility(View.GONE);
            bindCircleNote(holder, object, payload, message);
        } else if ("sticker".equals(kind)) {
            // Sticker: no bubble chrome, 140dp image (web .msg-sticker).
            holder.body.setVisibility(View.GONE);
            float d = holder.itemView.getContext().getResources().getDisplayMetrics().density;
            int side = Math.round(140 * d);
            ViewGroup.LayoutParams stickerParams = holder.image.getLayoutParams();
            stickerParams.width = side;
            stickerParams.height = side;
            holder.image.setLayoutParams(stickerParams);
            holder.image.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(0).build());
            holder.bubble.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
            holder.bubble.setStrokeWidth(0);
            bindImage(holder.image, object.optString("data", ""));
            holder.image.setVisibility(View.VISIBLE);
            holder.image.setOnClickListener(v -> listener.onOpenPayload(payload));
        } else if ("poll".equals(kind)) {
            // Опрос: вопрос, варианты и проценты — всё внутри пузыря.
            holder.body.setText(pollText(object, message));
            holder.body.setOnClickListener(v -> listener.onVote(message, payload));
        } else if ("contact".equals(kind)) {
            holder.body.setText(contactText(object));
        } else if ("call".equals(kind)) {
            // Итог звонка как обычная строка переписки, а не системный мусор.
            holder.body.setText(callLogText(holder.itemView.getContext(), message, object));
        } else if ("voice".equals(kind) || "audio".equals(kind)) {
            holder.body.setVisibility(View.GONE);
            holder.audio.setVisibility(View.VISIBLE);
            holder.audioLabel.setText("voice".equals(kind) ? "Голосовое" : "Аудиофайл");
            holder.audio.setOnClickListener(v -> listener.onOpenPayload(payload));
            holder.audioPlay.setOnClickListener(v -> listener.onOpenPayload(payload));
        } else {
            holder.body.setText("Сообщение");
        }

        if (message.sentAt > 0) holder.time.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(message.sentAt)));
        else holder.time.setText("");
        int burn = burnSeconds(payload);
        if (burn > 0) {
            // Оранжевый пунктир и бейдж таймера: видно, что сообщение сгорит само.
            long left = message.sentAt > 0
                    ? message.sentAt + burn * 1000L - System.currentTimeMillis()
                    : burn * 1000L;
            holder.time.setText(holder.time.getText() + "  🔥 " + shortDuration(Math.max(0L, left)));
            holder.time.setTextColor(holder.itemView.getContext().getColor(R.color.tsuyu_burn));
            holder.bubble.setForeground(holder.itemView.getContext()
                    .getDrawable(R.drawable.bg_burn_bubble));
        }
        if (message.edited) holder.edited.setVisibility(View.VISIBLE);
        if (outgoing && message.pending) {
            holder.status.setImageResource(R.drawable.ic_clock);
            holder.status.setColorFilter(holder.itemView.getContext().getColor(R.color.tsuyu_blue));
            holder.status.setVisibility(View.VISIBLE);
            holder.status.setContentDescription(holder.itemView.getResources().getString(R.string.sending_message));
            if (holder.status.getAnimation() == null) {
                holder.status.clearAnimation();
            }
        } else if (outgoing && !message.failed) {
            holder.status.clearAnimation();
            holder.status.setImageResource(row.read ? R.drawable.ic_checks : R.drawable.ic_check);
            // На синем исходящем пузыре галочки светлые: синий на синем не читается.
            int tickColor = outgoing
                    ? (row.read ? 0xFFF2F2F7 : 0x99F2F2F7)
                    : holder.itemView.getContext().getColor(row.read ? R.color.tsuyu_blue : R.color.tsuyu_secondary);
            holder.status.setColorFilter(tickColor);
            holder.status.setVisibility(View.VISIBLE);
            holder.status.setContentDescription(holder.itemView.getResources().getString(row.read ? R.string.message_read : R.string.message_sent));
        } else {
            holder.status.clearAnimation();
            holder.status.clearColorFilter();
        }
        if (message.failed) {
            holder.retry.setVisibility(View.VISIBLE);
            holder.retry.setOnClickListener(v -> listener.onRetry(message));
        }
        if (row.reactionCount > 0) {
            holder.reaction.setVisibility(View.VISIBLE);
            holder.reaction.setText(Integer.toString(row.reactionCount));
            holder.reaction.setContentDescription(holder.itemView.getResources().getQuantityString(
                    R.plurals.message_reactions, row.reactionCount, row.reactionCount));
            holder.reaction.setOnClickListener(v -> listener.onReact(message));
        }
        holder.bubble.setOnClickListener(v -> {
            if (selectionMode) listener.onToggleSelect(message);
            else listener.onOpenPayload(payload);
        });
        holder.bubble.setOnLongClickListener(v -> {
            if (selectionMode) listener.onToggleSelect(message);
            else listener.onMessageMenu(v, message, payload);
            return true;
        });
        attachSwipeToReply(holder, message);
        String key = message.id != null ? message.id : message.clientId;
        // Мгновенная отрисовка: строки не «влетают» с анимацией.
    }

    /** Restores the default bubble look so recycled holders never keep sticker/circle styling. */
    /** Кружок: круглый плеер в пузыре, первый кадр вместо серой карточки. */
    private static int burnSeconds(String payload) {
        if (payload == null) return 0;
        try {
            return new org.json.JSONObject(payload).optInt("burn", 0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String shortDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        if (seconds >= 86400L) return (seconds / 86400L) + "д";
        if (seconds >= 3600L) return (seconds / 3600L) + "ч";
        if (seconds >= 60L) return (seconds / 60L) + "м";
        return seconds + "с";
    }

    /** Опрос как в веб-клиенте: вопрос, варианты, голоса и проценты. */
    private String pollText(JSONObject object, ChatMessage message) {
        StringBuilder out = new StringBuilder();
        out.append("📊 ").append(object.optString("question", "")).append('\n');
        JSONArray options = object.optJSONArray("options");
        java.util.Map<String, Integer> votes =
                (message == null || message.id == null) ? null : pollVotes.get(message.id);
        int total = 0;
        if (votes != null) for (Integer value : votes.values()) if (value != null) total++;
        if (options != null) {
            for (int i = 0; i < options.length(); i++) {
                int count = 0;
                Integer mine = null;
                if (votes != null) for (java.util.Map.Entry<String, Integer> entry : votes.entrySet()) {
                    if (entry.getValue() != null && entry.getValue() == i) count++;
                    if (ownUid != null && ownUid.equals(entry.getKey())) mine = entry.getValue();
                }
                int percent = total == 0 ? 0 : Math.round(count * 100f / total);
                boolean chosen = mine != null && mine == i;
                out.append(chosen ? "● " : "○ ")
                        .append(options.optString(i, ""))
                        .append(" — ")
                        .append(percent).append("% (").append(count).append(')')
                        .append(i == options.length() - 1 ? "" : "\n");
            }
        }
        out.append("\n").append(total).append(" голосов");
        return out.toString();
    }

    /** Визитка контакта: имя и @username, как в веб-клиенте. */
    private static String contactText(JSONObject object) {
        String name = object.optString("name", "");
        String username = object.optString("username", "");
        StringBuilder out = new StringBuilder("👤 ");
        out.append(name.isEmpty() ? "Контакт" : name);
        if (!username.isEmpty()) out.append("\n@").append(username);
        return out.toString();
    }

    /**
     * Формулировка записи о звонке с точки зрения того, кто читает переписку:
     * «Исходящий», «Входящий», «Пропущенный», «Отклонённый» плюс длительность и «Видео».
     */
    private String callLogText(android.content.Context context, ChatMessage message, JSONObject object) {
        boolean mine = message != null && message.senderUid != null && message.senderUid.equals(ownUid);
        boolean video = object.optBoolean("video", false);
        long seconds = object.optLong("seconds", 0L);
        boolean missed = object.optBoolean("missed", false);
        boolean declined = object.optBoolean("declined", false);
        String label;
        if (mine) {
            if (declined) label = context.getString(R.string.call_log_declined);
            else if (missed || seconds <= 0L) label = context.getString(R.string.call_log_no_answer);
            else label = context.getString(R.string.call_log_outgoing);
        } else {
            if (seconds > 0L) label = context.getString(R.string.call_log_incoming);
            else label = context.getString(R.string.call_log_missed_in);
        }
        StringBuilder text = new StringBuilder(label);
        if (video) text.append(" · ").append(context.getString(R.string.call_log_video));
        if (seconds > 0L) text.append(" · ").append(CallEngine.formatDuration(seconds * 1000L));
        return text.toString();
    }

    private void bindCircleNote(Holder holder, org.json.JSONObject object, String payload, ChatMessage message) {
        android.content.Context context = holder.itemView.getContext();
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int cap = Math.max(Math.round(200 * metrics.density), Math.round(metrics.widthPixels * 0.72f));
        int side = Math.min(Math.round(200 * metrics.density), cap);
        ViewGroup.LayoutParams params = holder.circle.getLayoutParams();
        params.width = side;
        params.height = side;
        holder.circle.setLayoutParams(params);
        holder.circleCard.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setAllCornerSizes(ShapeAppearanceModel.PILL).build());
        holder.circle.setVisibility(View.VISIBLE);
        // Кружок сам себе пузырь: фон и рамку убираем, как .has-media в веб-дизайне.
        holder.bubble.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
        holder.bubble.setStrokeWidth(0);
        holder.circlePlay.setVisibility(View.VISIBLE);
        holder.circleProgress.setVisibility(View.VISIBLE);
        String duration = object.optString("duration", "");
        holder.circleTime.setText(duration.isEmpty() ? "0:00" : duration);
        final String key = message.id != null ? message.id
                : message.clientId != null ? message.clientId : String.valueOf(payload.hashCode());
        holder.circle.setTag(R.id.message_circle, key);
        holder.circleVideo.stopPlayback();
        holder.circle.setOnClickListener(v -> toggleCirclePlayback(holder));

        java.io.File cached = circleFiles.get(key);
        if (cached != null && cached.exists()) {
            attachCircleFile(holder, cached, key);
            return;
        }
        final String data = object.optString("data", "");
        if (data.isEmpty()) {
            holder.circleProgress.setVisibility(View.GONE);
            return;
        }
        circleExecutor.execute(() -> {
            try {
                java.io.File file = new java.io.File(context.getCacheDir(), "tsuyu-circle-" + key.hashCode() + ".mp4");
                if (!file.exists() || file.length() == 0) {
                    byte[] bytes = android.util.Base64.decode(data, android.util.Base64.NO_WRAP);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(bytes); }
                }
                circleFiles.put(key, file);
                holder.itemView.post(() -> {
                    if (key.equals(holder.circle.getTag(R.id.message_circle))) attachCircleFile(holder, file, key);
                });
            } catch (Exception failed) {
                holder.itemView.post(() -> holder.circleProgress.setVisibility(View.GONE));
            }
        });
    }

    private void attachCircleFile(Holder holder, java.io.File file, String key) {
        if (!key.equals(holder.circle.getTag(R.id.message_circle))) return;
        holder.circleVideo.setVideoURI(android.net.Uri.fromFile(file));
        holder.circleVideo.setOnPreparedListener(player -> {
            player.setLooping(true);
            holder.circleProgress.setVisibility(View.GONE);
            int total = player.getDuration();
            if (total > 0) {
                holder.circleTime.setText(String.format(java.util.Locale.getDefault(),
                        "%d:%02d", total / 60000, (total / 1000) % 60));
            }
            // Первый кадр: короткий seek и play — кружок сразу видно, а не серый круг.
            try {
                player.seekTo(1);
                holder.circleVideo.start();
                holder.circlePlay.setVisibility(View.GONE);
            } catch (Exception ignored) { }
        });
        holder.circleVideo.setOnCompletionListener(player -> holder.circlePlay.setVisibility(View.VISIBLE));
        holder.circleVideo.setOnErrorListener((player, what, extra) -> {
            holder.circleProgress.setVisibility(View.GONE);
            holder.circlePlay.setVisibility(View.VISIBLE);
            return true;
        });
    }

    private void toggleCirclePlayback(Holder holder) {
        if (holder.circleVideo.isPlaying()) {
            holder.circleVideo.pause();
            holder.circlePlay.setVisibility(View.VISIBLE);
        } else {
            holder.circleVideo.start();
            holder.circlePlay.setVisibility(View.GONE);
        }
    }

    /** Медиа внутри пузыря масштабируется пропорционально выбранной ширине полосы. */
    private void scaleMedia(Holder holder, android.util.DisplayMetrics metrics, int adaptiveCap) {
        android.content.Context context = holder.itemView.getContext();
        int baseWidth = context.getResources().getDimensionPixelSize(R.dimen.media_width);
        float scale = Math.min(1f, adaptiveCap / (float) baseWidth);
        applySize(holder.image, context, R.dimen.media_width, R.dimen.media_height_image, scale);
        applySize(holder.video, context, R.dimen.media_width, R.dimen.media_height_video, scale);
        applySize(holder.gallery, context, R.dimen.media_width, R.dimen.media_height_gallery, scale);
    }

    /** Размер считается заново от dimens, поэтому переработанные строки не «сжимаются». */
    private void applySize(View view, android.content.Context context, int widthRes, int heightRes, float scale) {
        if (view == null) return;
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return;
        params.width = Math.round(context.getResources().getDimensionPixelSize(widthRes) * scale);
        params.height = Math.round(context.getResources().getDimensionPixelSize(heightRes) * scale);
        view.setLayoutParams(params);
    }

    private void resetBubbleChrome(Holder holder, boolean outgoing) {
        holder.bubble.setForeground(null);
        holder.time.setTextColor(holder.itemView.getContext().getColor(R.color.tsuyu_time));
        holder.body.setOnClickListener(null);
        android.content.Context context = holder.itemView.getContext();
        holder.bubble.setCardBackgroundColor(context.getColor(
                outgoing ? R.color.tsuyu_outgoing : R.color.tsuyu_incoming));
        holder.bubble.setStrokeWidth(0);
        holder.bubble.setStrokeColor(android.graphics.Color.TRANSPARENT);
        float d = context.getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams imageParams = holder.image.getLayoutParams();
        imageParams.width = Math.round(224 * d);
        imageParams.height = Math.round(168 * d);
        holder.image.setLayoutParams(imageParams);
        holder.image.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(14 * d).build());
        ViewGroup.LayoutParams videoParams = holder.video.getLayoutParams();
        videoParams.width = Math.round(224 * d);
        videoParams.height = Math.round(126 * d);
        holder.video.setLayoutParams(videoParams);
        holder.video.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(16 * d).build());
        holder.video.setCardBackgroundColor(context.getColor(R.color.tsuyu_surface));
        holder.videoLabel.setText("Открыть видео");
        // Пузырь как в новом веб-дизайне: радиус 16dp и «хвост» 4dp у угла отправителя.
        float radius = context.getResources().getDimension(R.dimen.bubble_radius_web);
        float tail = context.getResources().getDimension(R.dimen.bubble_tail_radius_web);
        holder.bubble.setShapeAppearanceModel(ShapeAppearanceModel.builder()
                .setAllCornerSizes(radius)
                .setBottomRightCornerSize(outgoing ? tail : radius)
                .setBottomLeftCornerSize(outgoing ? radius : tail)
                .build());
        int metaColor = outgoing ? 0xCCF2F2F7 : context.getColor(R.color.tsuyu_time);
        holder.time.setTextColor(metaColor);
        holder.edited.setTextColor(metaColor);
        if (holder.circle != null) {
            holder.circle.setVisibility(View.GONE);
            holder.circle.setTag(R.id.message_circle, null);
            holder.circleVideo.stopPlayback();
            holder.circlePlay.setVisibility(View.VISIBLE);
            holder.circleProgress.setVisibility(View.VISIBLE);
        }
        holder.bubble.setTranslationX(0f);
        if (holder.swipeIcon != null) holder.swipeIcon.setAlpha(0f);
    }

    /** Horizontal drag on a bubble reveals a reply arrow and starts a reply (web attachSwipeToReply). */
    private void attachSwipeToReply(final Holder holder, final ChatMessage message) {
        final float density = holder.itemView.getContext().getResources().getDisplayMetrics().density;
        final float slop = 12f * density;
        final float trigger = 48f * density;
        final float limit = 72f * density;
        holder.bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean swiping;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        swiping = false;
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (!swiping) {
                            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 2f) {
                                swiping = true;
                                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                            } else {
                                return false;
                            }
                        }
                        float shift = Math.max(-limit, Math.min(0f, dx));
                        holder.bubble.setTranslationX(shift);
                        if (holder.swipeIcon != null) holder.swipeIcon.setAlpha(Math.min(1f, -shift / (trigger + slop)));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!swiping) return false;
                        boolean fire = holder.bubble.getTranslationX() < -trigger;
                        holder.bubble.animate().translationX(0f).setDuration(140).start();
                        if (holder.swipeIcon != null) holder.swipeIcon.animate().alpha(0f).setDuration(140).start();
                        swiping = false;
                        if (fire) {
                            holder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                            listener.onReply(message);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private boolean isFirstInGroup(int position) {
        if (position <= 0) return true;
        Row previousRow = rows.get(position - 1);
        if (previousRow.isDate()) return true;
        ChatMessage previous = previousRow.message;
        ChatMessage current = rows.get(position).message;
        boolean sameAuthor = previous.senderUid == null
                ? current.senderUid == null
                : previous.senderUid.equals(current.senderUid);
        if (!sameAuthor) return true;
        if (previous.sentAt <= 0 || current.sentAt <= 0) return true;
        return current.sentAt - previous.sentAt > GROUP_WINDOW_MS;
    }

    private static void bindImage(ShapeableImageView image, String encoded) {
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            image.setImageBitmap(bitmap);
        } catch (Exception ignored) { image.setImageDrawable(null); }
    }

    @Override public int getItemCount() { return rows.size(); }

    static final class LabelHolder extends RecyclerView.ViewHolder {
        LabelHolder(View itemView) { super(itemView); }
    }

    static final class DateHolder extends RecyclerView.ViewHolder {
        final TextView label;
        DateHolder(View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.message_date_label);
        }
    }

    /** Расшифрованные кружки: файлы в кэше, разбор в одном рабочем потоке. */
    private final java.util.Map<String, java.io.File> circleFiles = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService circleExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView bubble;
        final TextView body;
        final ProgressBar loading;
        final ShapeableImageView image;
        final RecyclerView gallery;
        final MaterialCardView video;
        final MaterialCardView audio;
        final TextView audioLabel;
        final MaterialButton audioPlay;
        final TextView reaction;
        final TextView edited;
        final TextView time;
        final ImageView status;
        final MaterialButton retry;
        final TextView videoLabel;
        final ImageView swipeIcon;
        final View circle;
        final MaterialCardView circleCard;
        final android.widget.VideoView circleVideo;
        final ImageView circlePlay;
        final TextView circleTime;
        final ProgressBar circleProgress;
        Holder(View itemView) {
            super(itemView);
            bubble = itemView.findViewById(R.id.message_bubble);
            body = itemView.findViewById(R.id.message_body);
            loading = itemView.findViewById(R.id.message_loading);
            image = itemView.findViewById(R.id.message_image);
            gallery = itemView.findViewById(R.id.message_gallery);
            video = itemView.findViewById(R.id.message_video);
            audio = itemView.findViewById(R.id.message_audio);
            audioLabel = itemView.findViewById(R.id.message_audio_label);
            audioPlay = itemView.findViewById(R.id.message_audio_play);
            reaction = itemView.findViewById(R.id.message_reaction);
            edited = itemView.findViewById(R.id.message_edited);
            time = itemView.findViewById(R.id.message_time);
            status = itemView.findViewById(R.id.message_status);
            retry = itemView.findViewById(R.id.message_retry);
            videoLabel = itemView.findViewById(R.id.message_video_label);
            swipeIcon = itemView.findViewById(R.id.message_swipe_icon);
            circle = itemView.findViewById(R.id.message_circle);
            circleCard = itemView.findViewById(R.id.message_circle_card);
            circleVideo = itemView.findViewById(R.id.message_circle_video);
            circlePlay = itemView.findViewById(R.id.message_circle_play);
            circleTime = itemView.findViewById(R.id.message_circle_time);
            circleProgress = itemView.findViewById(R.id.message_circle_progress);
        }
    }

    private static final class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.GalleryHolder> {
        interface ClickListener { void onImage(String encoded); }
        private final List<String> images;
        private final ClickListener listener;
        GalleryAdapter(List<String> images, ClickListener listener) { this.images = images; this.listener = listener; }
        @NonNull @Override public GalleryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new GalleryHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_media_thumb, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull GalleryHolder holder, int position) {
            String data = images.get(position);
            bindImage(holder.image, data);
            holder.itemView.setOnClickListener(v -> listener.onImage(data));
        }
        @Override public int getItemCount() { return images.size(); }
        static final class GalleryHolder extends RecyclerView.ViewHolder {
            final ShapeableImageView image;
            GalleryHolder(View itemView) { super(itemView); image = itemView.findViewById(R.id.media_thumb); }
        }
    }
}
