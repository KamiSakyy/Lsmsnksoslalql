package com.noir.p2pchat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.RemoteInput;

import com.noir.p2pchat.service.ChatConnectionService;
import com.noir.p2pchat.ui.ChatActivity;

/** Handles explicit, user-triggered notification actions without exposing a public component. */
public final class NotificationActionReceiver extends BroadcastReceiver {
    public static final String ACTION_REPLY = "com.noir.p2pchat.notification.REPLY";
    public static final String ACTION_ACCEPT_INVITE = "com.noir.p2pchat.notification.ACCEPT_INVITE";
    public static final String ACTION_DECLINE_INVITE = "com.noir.p2pchat.notification.DECLINE_INVITE";
    public static final String ACTION_DECLINE_CALL = "com.noir.p2pchat.notification.DECLINE_CALL";
    public static final String EXTRA_CALL_ID = "notification_call_id";
    public static final String EXTRA_PEER_UID = "notification_peer_uid";
    public static final String EXTRA_REPLY_TEXT = "notification_reply_text";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String peerUid = intent.getStringExtra(EXTRA_PEER_UID);
        if (ACTION_DECLINE_CALL.equals(intent.getAction())) {
            String callId = intent.getStringExtra(EXTRA_CALL_ID);
            if (callId == null || !callId.matches("[A-Za-z0-9_-]{1,128}")) return;
            Intent decline = new Intent(context, ChatConnectionService.class)
                    .setAction(ChatConnectionService.ACTION_DECLINE_CALL)
                    .putExtra(ChatConnectionService.EXTRA_CALL_ID, callId);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(decline);
                else context.startService(decline);
            } catch (IllegalStateException | SecurityException ignored) {
                com.noir.p2pchat.MessageNotifications.cancelIncomingCall(context, callId);
            }
            return;
        }
        if (peerUid == null || !peerUid.matches("[A-Za-z0-9_-]{8,128}")) return;

        String serviceAction;
        String reply = null;
        if (ACTION_ACCEPT_INVITE.equals(intent.getAction())) {
            serviceAction = ChatConnectionService.ACTION_ACCEPT_INVITE;
        } else if (ACTION_DECLINE_INVITE.equals(intent.getAction())) {
            serviceAction = ChatConnectionService.ACTION_DECLINE_INVITE;
        } else if (ACTION_REPLY.equals(intent.getAction())) {
            android.os.Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence input = results == null ? null : results.getCharSequence(EXTRA_REPLY_TEXT);
            reply = input == null ? "" : input.toString().trim();
            if (reply.isEmpty()) return;
            serviceAction = ChatConnectionService.ACTION_SEND_TEXT;
        } else {
            return;
        }

        Intent service = new Intent(context, ChatConnectionService.class)
                .setAction(serviceAction)
                .putExtra(ChatConnectionService.EXTRA_PEER_UID, peerUid);
        if (reply != null) service.putExtra(ChatConnectionService.EXTRA_MESSAGE_TEXT, reply);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
            else context.startService(service);
        } catch (IllegalStateException | SecurityException backgroundStartBlocked) {
            // The OS may block background service starts. Preserve the reply and let the user send it in chat.
            Intent open = new Intent(context, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_PEER_UID, peerUid)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (reply != null) open.putExtra(ChatActivity.EXTRA_DRAFT_TEXT, reply);
            context.startActivity(open);
        }
    }
}
