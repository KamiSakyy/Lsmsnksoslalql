package com.fcmessenger.app.fcm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.fcmessenger.app.MainActivity
import com.fcmessenger.app.R
import com.fcmessenger.app.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Notifications(private val ctx: Context, private val db: AppDatabase) {

    private val mgr = ctx.getSystemService(NotificationManager::class.java)

    companion object {
        const val CH = "messages"
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CH, "Сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    setShowBadge(true)
                }
            )
        }
    }

    suspend fun showMessage(peerUid: String, peerName: String, text: String) =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return@withContext

            val intent = Intent(ctx, MainActivity::class.java).apply {
                action = "open_chat"
                putExtra("peer", peerUid)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                ctx, peerUid.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val me = Person.Builder().setName("Вы").build()
            val him = Person.Builder().setName(peerName).build()
            val style = NotificationCompat.MessagingStyle(me).setConversationTitle(peerName)
            style.addMessage(text, System.currentTimeMillis(), him)

            val n = NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_chat)
                .setStyle(style)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
            mgr.notify("chat", peerUid.hashCode(), n)
        }

    fun cancel(peerUid: String) {
        mgr.cancel("chat", peerUid.hashCode())
    }
}
