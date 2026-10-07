package com.tsuyu.messenger;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.FirebaseDatabase;

/** Firebase Android client configuration. The API key is a public client identifier, not an Admin key. */
public final class FirebaseBootstrap {
    private static final String API_KEY = "AIzaSyB06mboI0HKUbXjgyj3BYd_sbRVPEtit3Y";
    private static final String APP_ID = "1:906512822162:android:ce57bfb21f57090681db4b";
    private static final String PROJECT_ID = "meowmessenger";
    private static final String SENDER_ID = "906512822162";
    private static final String DATABASE_URL = "https://meowmessenger-default-rtdb.europe-west1.firebasedatabase.app";

    private FirebaseBootstrap() {}

    public static synchronized FirebaseApp initialize(Context context) {
        Context appContext = context.getApplicationContext();
        if (!FirebaseApp.getApps(appContext).isEmpty()) {
            return FirebaseApp.getInstance();
        }
        FirebaseOptions options = new FirebaseOptions.Builder()
                .setApplicationId(APP_ID)
                .setApiKey(API_KEY)
                .setProjectId(PROJECT_ID)
                .setGcmSenderId(SENDER_ID)
                .setDatabaseUrl(DATABASE_URL)
                .build();
        FirebaseApp app = FirebaseApp.initializeApp(appContext, options);
        FirebaseDatabase database = FirebaseDatabase.getInstance(app);
        database.setPersistenceEnabled(true);
        database.setPersistenceCacheSizeBytes(24L * 1024L * 1024L);
        return app;
    }
}
