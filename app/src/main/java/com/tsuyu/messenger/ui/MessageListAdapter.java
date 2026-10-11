package com.tsuyu.messenger.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
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
import com.tsuyu.messenger.R;
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
        holder.body.setTextSize(textSize);
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
        if (message.edited) holder.edited.setVisibility(View.VISIBLE);
        if (outgoing && message.pending) {
            holder.status.setImageResource(R.drawable.ic_clock);
            holder.status.setColorFilter(holder.itemView.getContext().getColor(R.color.tsuyu_blue));
            holder.status.setVisibility(View.VISIBLE);
            holder.status.setContentDescription(holder.itemView.getResources().getString(R.string.sending_message));
            if (holder.status.getAnimation() == null) {
                holder.status.startAnimation(AnimationUtils.loadAnimation(holder.itemView.getContext(), R.anim.sending_pulse));
            }
        } else if (outgoing && !message.failed) {
            holder.status.clearAnimation();
            holder.status.setImageResource(row.read ? R.drawable.ic_checks : R.drawable.ic_check);
            holder.status.setColorFilter(holder.itemView.getContext().getColor(row.read ? R.color.tsuyu_blue : R.color.tsuyu_secondary));
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
        holder.bubble.setOnClickListener(v -> listener.onOpenPayload(payload));
        holder.bubble.setOnLongClickListener(v -> {
            listener.onMessageMenu(v, message, payload);
            return true;
        });
        String key = message.id != null ? message.id : message.clientId;
        if (key != null && animatedIds.add(key)) holder.itemView.startAnimation(AnimationUtils.loadAnimation(holder.itemView.getContext(), R.anim.message_enter));
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
