package com.fcmessenger.app

import android.app.Application
import com.fcmessenger.app.crypto.CryptoManager
import com.fcmessenger.app.data.Prefs
import com.fcmessenger.app.data.Repo
import com.fcmessenger.app.data.local.AppDatabase
import com.fcmessenger.app.data.remote.Directory
import com.fcmessenger.app.data.remote.Relay
import com.fcmessenger.app.fcm.Notifications
import com.google.crypto.tink.config.TinkConfig

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        TinkConfig.register()
        container = AppContainer(this)
        container.notifications.ensureChannel()
        container.repo.scheduleRetry()
    }
}

/** Простой DI-контейнер без фреймворков. */
class AppContainer(val app: Application) {
    val prefs = Prefs(app)
    val db: AppDatabase by lazy { AppDatabase.get(app) }
    val crypto by lazy { CryptoManager(app) }
    val directory = Directory()
    val relay = Relay()
    val notifications by lazy { Notifications(app, db) }
    val repo by lazy { Repo(app, prefs, db, crypto, directory, relay, notifications) }
}
