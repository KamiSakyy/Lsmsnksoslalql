package com.fcmessenger.app.fcm

import com.fcmessenger.app.App
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Сердце приёма: сюда прилетают FCM data-сообщения.
 * Весь текст УЖЕ внутри пуша — ничего докачивать не нужно,
 * поэтому доходит даже на нулевом балансе, пока жив push-канал Google.
 */
class FcmService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            runCatching { (application as App).container.repo.onTokenRefresh(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        scope.launch {
            runCatching { (application as App).container.repo.onPushReceived(message.data) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
