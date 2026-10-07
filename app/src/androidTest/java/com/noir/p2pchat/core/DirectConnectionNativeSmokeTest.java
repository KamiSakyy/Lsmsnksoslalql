package com.noir.p2pchat.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.noir.p2pchat.AppKernel;
import com.noir.p2pchat.service.ChatConnectionService;
import com.noir.p2pchat.ui.ChatActivity;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the same first-use crypto and direct-DataChannel native path used by the app. */
@RunWith(AndroidJUnit4.class)
public final class DirectConnectionNativeSmokeTest {
    @Test
    public void signalPrekeysAndDirectDataChannelOfferInitializeOnAndroid() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();

        JSONObject bundle = new EncryptedSignalProtocolStore(context).publicBundle();
        assertEquals(1, bundle.getInt("version"));
        assertNotNull(bundle.getString("identityKey"));
        assertNotNull(bundle.getString("kyberPreKey"));

        P2pEngine engine = new P2pEngine(context, new FirebaseRestClient(context), new MessageStore(context));
        PeerConnectionFactory factory = engine.dataFactoryForSmokeTest();
        assertNotNull(factory);

        NoopObserver observer = new NoopObserver();
        PeerConnection peer = factory.createPeerConnection(
                new PeerConnection.RTCConfiguration(Collections.emptyList()), observer);
        assertNotNull("WebRTC failed to create a direct peer connection", peer);
        DataChannel channel = peer.createDataChannel("noir-direct-smoke", new DataChannel.Init());
        assertNotNull("WebRTC failed to create a direct DataChannel", channel);

        CountDownLatch offerCreated = new CountDownLatch(1);
        AtomicReference<String> offerFailure = new AtomicReference<>();
        AtomicReference<SessionDescription> offerResult = new AtomicReference<>();
        peer.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription offer) {
                offerResult.set(offer);
                if (offer == null || offer.description == null || offer.description.isEmpty()) {
                    offerFailure.set("WebRTC returned an empty SDP offer");
                }
                offerCreated.countDown();
            }
            @Override public void onSetSuccess() { }
            @Override public void onCreateFailure(String error) {
                offerFailure.set(error);
                offerCreated.countDown();
            }
            @Override public void onSetFailure(String error) {
                offerFailure.set(error);
                offerCreated.countDown();
            }
        }, new MediaConstraints());

        try {
            assertTrue("WebRTC did not create a direct SDP offer within 15 seconds",
                    offerCreated.await(15, TimeUnit.SECONDS));
            assertEquals("WebRTC offer creation failed: " + offerFailure.get(), null, offerFailure.get());

            CountDownLatch localDescriptionSet = new CountDownLatch(1);
            AtomicReference<String> localDescriptionFailure = new AtomicReference<>();
            peer.setLocalDescription(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription ignored) { }
                @Override public void onSetSuccess() { localDescriptionSet.countDown(); }
                @Override public void onCreateFailure(String error) {
                    localDescriptionFailure.set(error);
                    localDescriptionSet.countDown();
                }
                @Override public void onSetFailure(String error) {
                    localDescriptionFailure.set(error);
                    localDescriptionSet.countDown();
                }
            }, offerResult.get());

            assertTrue("WebRTC did not set the local SDP offer within 15 seconds",
                    localDescriptionSet.await(15, TimeUnit.SECONDS));
            assertEquals("WebRTC failed to set the local offer: " + localDescriptionFailure.get(),
                    null, localDescriptionFailure.get());
            assertTrue("WebRTC did not finish local ICE gathering within 15 seconds",
                    observer.iceGatheringComplete.await(15, TimeUnit.SECONDS));
            assertNotNull("WebRTC did not retain the local SDP offer", peer.getLocalDescription());
        } finally {
            channel.close();
            channel.dispose();
            peer.close();
            peer.dispose();
            factory.dispose();
        }
    }

    @Test
    public void directChatScreenAndForegroundServiceStartWithoutFirebase() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppKernel app = (AppKernel) context.getApplicationContext();
        P2pEngine engine = app.p2p();
        Field startedField = P2pEngine.class.getDeclaredField("started");
        startedField.setAccessible(true);
        AtomicBoolean started = (AtomicBoolean) startedField.get(engine);
        started.set(true);

        Intent chatIntent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_PEER_UID, "smoke-peer-36");
        try (ActivityScenario<ChatActivity> scenario = ActivityScenario.launch(chatIntent)) {
            scenario.onActivity(activity -> assertNotNull(
                    "Direct chat activity failed to create its content view",
                    activity.findViewById(android.R.id.content)));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        } finally {
            engine.stop();
            context.stopService(new Intent(context, ChatConnectionService.class));
        }
    }

    private static final class NoopObserver implements PeerConnection.Observer {
        private final CountDownLatch iceGatheringComplete = new CountDownLatch(1);

        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) { }
        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceGatheringComplete.countDown();
        }
        @Override public void onIceCandidate(IceCandidate candidate) { }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
        @Override public void onAddStream(MediaStream stream) { }
        @Override public void onRemoveStream(MediaStream stream) { }
        @Override public void onDataChannel(DataChannel channel) { }
        @Override public void onRenegotiationNeeded() { }
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) { }
        @Override public void onTrack(RtpTransceiver transceiver) { }
        @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) { }
        @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState state) { }
    }
}
