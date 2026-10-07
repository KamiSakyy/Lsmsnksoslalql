package com.tsuyu.messenger;

import android.app.Application;

import com.google.firebase.FirebaseApp;
import com.tsuyu.messenger.notifications.FirebaseMessagingSetup;

public final class TsuyuApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseApp app = FirebaseBootstrap.initialize(this);
        FirebaseMessagingSetup.registerTokenListener(app);
    }
}
