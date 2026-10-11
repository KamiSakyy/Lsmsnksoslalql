package com.tsuyu.messenger.call;

import android.content.Context;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.notifications.FcmPushSender;

import java.util.HashMap;
import java.util.Map;

/**
 * «Страница» о звонке — два независимых канала, чтобы вызов доходил всегда:
 *
 * <ol>
 *   <li>почтовый ящик вызовов в базе ({@code users/{uid}/callInbox}) — работает, когда
 *       приложение открыто или поднято сервисом уведомлений;</li>
 *   <li>мгновенный пуш (тот же встроенный FCM-отправитель, что и для сообщений) —
 *       поднимает приложение, даже если оно выгружено из памяти.</li>
 * </ol>
 *
 * Релеи и медиа в этом не участвуют: они нужны только самому WebRTC.
 */
public final class CallSignal {

    public static final String TYPE_CALL = "call";
    public static final String TYPE_ANSWER = "call_answer";
    public static final String TYPE_END = "call_end";

    private CallSignal() { }

    /** Приглашение: ящик + пуш. Вызывается сразу после записи звонка в базу. */
    public static void announce(Context context, String chatId, String callId, String fromUid,
                                String toUid, boolean video) {
        if (context == null || chatId == null || callId == null || toUid == null) return;
        try {
            Map<String, Object> inbox = new HashMap<>();
            inbox.put("callId", callId);
            inbox.put("from", fromUid);
            inbox.put("video", video);
            inbox.put("at", ServerValue.TIMESTAMP);
            FirebaseDatabase.getInstance().getReference("users").child(toUid)
                    .child("callInbox").child(chatId).setValue(inbox);
        } catch (Exception ignored) {
            // Ящик — подстраховка; пуш ниже остаётся основным путём.
        }
        try {
            FcmPushSender.pushCall(context, chatId, callId, fromUid, toUid, video, TYPE_CALL);
        } catch (Exception ignored) { }
    }

    /** Абонент взял трубку — вызывающий узнаёт об этом сразу, а не по опросу базы. */
    public static void answer(Context context, String chatId, String callId, String uid, String peerUid) {
        if (context == null || chatId == null || callId == null || peerUid == null) return;
        clearInbox(uid, chatId);
        try {
            FcmPushSender.pushCall(context, chatId, callId, uid, peerUid, false, TYPE_ANSWER);
        } catch (Exception ignored) { }
    }

    /** Отбой/отклонение: убираем приглашение и гасим экран вызова у собеседника. */
    public static void cancel(Context context, String chatId, String callId, String uid,
                              String peerUid, String reason) {
        if (context == null || chatId == null || callId == null || peerUid == null) return;
        clearInbox(uid, chatId);
        try {
            FcmPushSender.pushCall(context, chatId, callId, uid, peerUid, false, TYPE_END);
        } catch (Exception ignored) { }
    }

    /** Отказ из уведомления, когда экран вызова даже не открывался. */
    public static void declineRemote(Context context, String chatId, String callId, String uid, String peerUid) {
        if (chatId == null || callId == null || uid == null) return;
        clearInbox(uid, chatId);
        try {
            Map<String, Object> update = new HashMap<>();
            update.put("state", "declined");
            update.put("endedBy", uid);
            update.put("endedAt", ServerValue.TIMESTAMP);
            node(chatId, callId).updateChildren(update);
        } catch (Exception ignored) { }
        try {
            if (peerUid != null) FcmPushSender.pushCall(context, chatId, callId, uid, peerUid, false, TYPE_END);
        } catch (Exception ignored) { }
    }

    private static void clearInbox(String uid, String chatId) {
        try {
            FirebaseDatabase.getInstance().getReference("users").child(uid)
                    .child("callInbox").child(chatId).removeValue();
        } catch (Exception ignored) { }
    }

    /** Одно приглашение из ящика вызовов. */
    public static final class Invite {
        public String callId;
        public String from;
        public String chatId;
        public boolean video;
        public long at;
    }

    /**
     * Слушает свой ящик вызовов и отдаёт свежие приглашения (не старше минуты) —
     * подстраховка к пушу, когда приложение уже открыто.
     */
    public static ValueEventListener watchInbox(final String myUid, final Callback callback) {
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                long now = System.currentTimeMillis();
                for (DataSnapshot chat : snapshot.getChildren()) {
                    DataSnapshot callId = chat.child("callId");
                    String id = callId.getValue(String.class);
                    String from = chat.child("from").getValue(String.class);
                    if (id == null || from == null || from.equals(myUid)) continue;
                    Object rawAt = chat.child("at").getValue();
                    boolean video = Boolean.TRUE.equals(chat.child("video").getValue(Boolean.class));
                    long stamp = rawAt instanceof Number ? ((Number) rawAt).longValue() : now;
                    if (now - stamp > 60_000L) continue;
                    Invite invite = new Invite();
                    invite.callId = id;
                    invite.from = from;
                    invite.chatId = chat.getKey();
                    invite.video = video;
                    invite.at = stamp;
                    callback.onInvite(invite);
                }
            }

            @Override public void onCancelled(com.google.firebase.database.DatabaseError error) { }
        };
        FirebaseDatabase.getInstance().getReference("users").child(myUid)
                .child("callInbox").addValueEventListener(listener);
        return listener;
    }

    public interface Callback {
        void onInvite(Invite invite);
    }

    /** Узел звонка — для повторного подключения к уже идущему разговору. */
    public static DatabaseReference node(String chatId, String callId) {
        return FirebaseDatabase.getInstance().getReference("chats").child(chatId).child("calls").child(callId);
    }
}
