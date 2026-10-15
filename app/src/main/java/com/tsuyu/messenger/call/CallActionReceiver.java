package com.tsuyu.messenger.call;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Кнопки уведомления звонка: «Ответить», «Отклонить», «Завершить».
 * Работают, даже если экран приложения не открыт.
 */
public final class CallActionReceiver extends BroadcastReceiver {

    public static final String ACTION_ANSWER = "com.tsuyu.messenger.call.ANSWER";
    public static final String ACTION_DECLINE = "com.tsuyu.messenger.call.DECLINE";
    public static final String ACTION_END = "com.tsuyu.messenger.call.END";

    public static Intent answerIntent(Context context, String chatId, String callId, String peerUid,
                                      String peerName, boolean video) {
        return CallActivity.incomingIntent(context, chatId, callId, peerUid, peerName, video)
                .setAction(ACTION_ANSWER);
    }

    public static Intent declineIntent(Context context, String chatId, String callId, String peerUid) {
        return base(context, chatId, callId, peerUid).setAction(ACTION_DECLINE);
    }

    public static Intent endIntent(Context context) {
        return new Intent(context, CallActionReceiver.class).setAction(ACTION_END);
    }

    private static Intent base(Context context, String chatId, String callId, String peerUid) {
        Intent intent = new Intent(context, CallActionReceiver.class);
        intent.putExtra(CallActivity.EXTRA_CHAT_ID, chatId);
        intent.putExtra(CallActivity.EXTRA_CALL_ID, callId);
        intent.putExtra(CallActivity.EXTRA_PEER_UID, peerUid);
        return intent;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_END.equals(action)) {
            CallEngine.get().hangup();
            return;
        }
        String chatId = intent.getStringExtra(CallActivity.EXTRA_CHAT_ID);
        String callId = intent.getStringExtra(CallActivity.EXTRA_CALL_ID);
        String peerUid = intent.getStringExtra(CallActivity.EXTRA_PEER_UID);
        if (ACTION_DECLINE.equals(action)) {
            CallNotifier.clear(context);
            String uid = currentUid();
            if (uid != null && chatId != null && callId != null) {
                // Отказ пишем прямо в базу: движок мог ещё не подняться.
                if (CallEngine.busy()) {
                    CallEngine.get().decline();
                } else {
                    CallSignal.declineRemote(context, chatId, callId, uid, peerUid);
                }
            }
            return;
        }
        if (ACTION_ANSWER.equals(action)) {
            CallNotifier.clear(context);
            Intent open = new Intent(intent).setClass(context, CallActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                context.startActivity(open);
            } catch (Exception ignored) { }
        }
    }

    private static String currentUid() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            return user == null ? null : user.getUid();
        } catch (Exception ignored) {
            return null;
        }
    }
}
