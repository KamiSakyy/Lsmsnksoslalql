package com.tsuyu.messenger;

import android.app.Application;

import com.google.firebase.FirebaseApp;
import com.tsuyu.messenger.data.ChatRepository;
import com.tsuyu.messenger.notifications.FcmPushSender;
import com.tsuyu.messenger.notifications.FirebaseMessagingSetup;

public final class TsuyuApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseApp app = FirebaseBootstrap.initialize(this);
        FirebaseMessagingSetup.registerTokenListener(app);
        // Every committed message pushes straight to the peer's devices: phone to phone
        // through FCM HTTP v1, with no relay server in the middle.
        ChatRepository.setPushDispatcher((chatId, senderUid, recipientUid, messageId) ->
                FcmPushSender.pushMessage(this, chatId, senderUid, recipientUid, messageId));
    }
}
