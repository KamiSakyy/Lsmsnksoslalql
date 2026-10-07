package com.meowmessenger.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MeowFirebaseMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL_ID = "meow_messages";

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String text = message.getData().get("text");
        if ((text == null || text.isEmpty()) && message.getNotification() != null) {
            text = message.getNotification().getBody();
        }
        if (text == null || text.isEmpty()) {
            return;
        }

        ensureChannel();
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                (int) (System.currentTimeMillis() & 0x7fffffff),
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("MeowMessenger")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        try {
            NotificationManagerCompat.from(this).notify(
                    (int) (System.currentTimeMillis() & 0x7fffffff), notification.build());
        } catch (SecurityException ignored) {
            // Android 13+ requires the user to grant POST_NOTIFICATIONS.
        }
    }

    @Override
    public void onNewToken(String token) {
        // The app fetches the current token whenever it opens; no private key is stored here.
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Сообщения MeowMessenger",
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Входящие сообщения FCM");
                manager.createNotificationChannel(channel);
            }
        }
    }
}
