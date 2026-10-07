package com.noir.p2pchat.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the same first-use crypto and DataChannel native paths that used to terminate the app. */
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

        PeerConnection peer = factory.createPeerConnection(
                new PeerConnection.RTCConfiguration(Collections.emptyList()), new NoopObserver());
        assertNotNull("WebRTC failed to create a direct peer connection", peer);
        DataChannel channel = peer.createDataChannel("noir-direct-smoke", new DataChannel.Init());
        assertNotNull("WebRTC failed to create a direct DataChannel", channel);

        CountDownLatch offerCreated = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        peer.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription offer) {
                if (offer == null || offer.description == null || offer.description.isEmpty()) {
                    failure.set("WebRTC returned an empty SDP offer");
                }
                offerCreated.countDown();
            }
            @Override public void onSetSuccess() { }
            @Override public void onCreateFailure(String error) {
                failure.set(error);
                offerCreated.countDown();
            }
            @Override public void onSetFailure(String error) {
                failure.set(error);
                offerCreated.countDown();
            }
        }, new MediaConstraints());

        try {
            assertTrue("WebRTC did not create a direct SDP offer within 15 seconds",
                    offerCreated.await(15, TimeUnit.SECONDS));
            assertEquals("WebRTC offer creation failed: " + failure.get(), null, failure.get());
        } finally {
            channel.close();
            channel.dispose();
            peer.close();
            peer.dispose();
            factory.dispose();
        }
    }

    private static final class NoopObserver implements PeerConnection.Observer {
        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) { }
        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
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
