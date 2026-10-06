package com.noir.p2pchat;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.noir.p2pchat.core.FirebaseRestClient;
import com.noir.p2pchat.core.MessageStore;
import com.noir.p2pchat.core.P2pEngine;

/** App-scoped owners for signaling, local message storage and foreground UI state. */
public final class AppKernel extends Application implements Application.ActivityLifecycleCallbacks {
    private FirebaseRestClient firebase;
    private MessageStore messageStore;
    private P2pEngine p2pEngine;
    private int startedActivities;
    private volatile String visibleChatPeer;

    @Override
    public void onCreate() {
        super.onCreate();
        firebase = new FirebaseRestClient(this);
        messageStore = new MessageStore(this);
        p2pEngine = new P2pEngine(this, firebase, messageStore);
        registerActivityLifecycleCallbacks(this);
        MessageNotifications.createChannels(this);
    }

    public FirebaseRestClient firebase() { return firebase; }
    public MessageStore messages() { return messageStore; }
    public P2pEngine p2p() { return p2pEngine; }

    public synchronized boolean isAppVisible() {
        return startedActivities > 0;
    }

    public boolean shouldSuppressNotification(String peerUid) {
        return isAppVisible() && peerUid != null && peerUid.equals(visibleChatPeer);
    }

    public void setVisibleChatPeer(String peerUid) {
        visibleChatPeer = peerUid;
        p2pEngine.setActiveChatPeer(peerUid);
    }

    @Override public synchronized void onActivityStarted(Activity activity) { startedActivities++; }
    @Override public synchronized void onActivityStopped(Activity activity) { startedActivities = Math.max(0, startedActivities - 1); }
    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
