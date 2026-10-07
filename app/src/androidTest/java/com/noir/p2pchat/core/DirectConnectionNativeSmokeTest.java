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
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    public void twoDirectPeersOpenDataChannelAndExchangeBytesOnAndroid() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO);
        P2pEngine engine = new P2pEngine(context, new FirebaseRestClient(context), new MessageStore(context));
        PeerConnectionFactory factory = engine.dataFactoryForSmokeTest();
        PeerObserver offererObserver = new PeerObserver();
        PeerObserver answererObserver = new PeerObserver();
        PeerConnection offerer = null;
        PeerConnection answerer = null;
        DataChannel outgoingChannel = null;
        DataChannel incomingChannel = null;
        ChannelObserver outgoingObserver = new ChannelObserver();

        try {
            offerer = factory.createPeerConnection(rtcConfiguration(engine), offererObserver);
            answerer = factory.createPeerConnection(rtcConfiguration(engine), answererObserver);
            assertNotNull("WebRTC failed to create the offerer", offerer);
            assertNotNull("WebRTC failed to create the answerer", answerer);

            outgoingChannel = offerer.createDataChannel("noir-loopback-smoke", new DataChannel.Init());
            assertNotNull("WebRTC failed to create the outgoing DataChannel", outgoingChannel);
            outgoingObserver.attach(outgoingChannel);

            SessionDescription offer = createDescription(offerer, true);
            setDescription(offerer, true, offer);
            assertTrue("Offerer did not finish local ICE gathering",
                    offererObserver.iceGatheringComplete.await(15, TimeUnit.SECONDS));
            offer = offerer.getLocalDescription();
            assertNotNull("Offerer did not retain gathered ICE candidates", offer);

            setDescription(answerer, false, offer);
            SessionDescription answer = createDescription(answerer, false);
            setDescription(answerer, true, answer);
            assertTrue("Answerer did not finish local ICE gathering",
                    answererObserver.iceGatheringComplete.await(15, TimeUnit.SECONDS));
            answer = answerer.getLocalDescription();
            assertNotNull("Answerer did not retain gathered ICE candidates", answer);
            setDescription(offerer, false, answer);

            assertTrue("Offerer did not receive an open DataChannel; offerer "
                            + offererObserver.diagnostics() + " " + describeLocalSdp(offerer)
                            + "; answerer " + answererObserver.diagnostics() + " " + describeLocalSdp(answerer)
                            + "; interfaces " + describeNetworkInterfaces(),
                    outgoingObserver.open.await(20, TimeUnit.SECONDS));
            assertTrue("Answerer did not receive the negotiated DataChannel",
                    answererObserver.dataChannelCreated.await(15, TimeUnit.SECONDS));
            incomingChannel = answererObserver.dataChannel.get();
            assertNotNull("Answerer DataChannel callback returned null", incomingChannel);
            assertTrue("Answerer DataChannel did not open",
                    answererObserver.channelObserver.open.await(20, TimeUnit.SECONDS));

            byte[] payload = "noir-direct-smoke".getBytes(StandardCharsets.UTF_8);
            assertTrue("WebRTC rejected a message on the open DataChannel",
                    outgoingChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(payload), true)));
            assertTrue("Answerer did not receive the DataChannel payload",
                    answererObserver.channelObserver.message.await(15, TimeUnit.SECONDS));
            assertEquals("noir-direct-smoke", answererObserver.channelObserver.receivedText.get());
        } finally {
            if (outgoingChannel != null) {
                outgoingChannel.close();
                outgoingChannel.dispose();
            }
            if (incomingChannel != null) {
                incomingChannel.close();
                incomingChannel.dispose();
            }
            if (offerer != null) {
                offerer.close();
                offerer.dispose();
            }
            if (answerer != null) {
                answerer.close();
                answerer.dispose();
            }
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

    private static String describeNetworkInterfaces() {
        StringBuilder details = new StringBuilder();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "none";
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (details.length() > 0) details.append(';');
                details.append(networkInterface.getName()).append("{up=").append(networkInterface.isUp())
                        .append(",loopback=").append(networkInterface.isLoopback()).append(",addresses=");
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) details.append(addresses.nextElement().getHostAddress()).append(',');
                details.append('}');
            }
        } catch (Exception error) {
            return error.getClass().getSimpleName() + ":" + error.getMessage();
        }
        return details.length() == 0 ? "none" : details.toString();
    }

    private static String describeLocalSdp(PeerConnection peer) {
        SessionDescription description = peer.getLocalDescription();
        if (description == null || description.description == null) return "local SDP=null";
        int candidates = 0;
        for (String line : description.description.split("\\r?\\n")) {
            if (line.startsWith("a=candidate:")) candidates++;
        }
        return "local SDP{application=" + description.description.contains("m=application")
                + ", candidates=" + candidates + "}";
    }

    private static PeerConnection.RTCConfiguration rtcConfiguration(P2pEngine engine) {
        PeerConnection.RTCConfiguration configuration =
                new PeerConnection.RTCConfiguration(engine.iceServersForSmokeTest());
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        return configuration;
    }

    private static SessionDescription createDescription(PeerConnection peer, boolean offer) throws Exception {
        CountDownLatch created = new CountDownLatch(1);
        AtomicReference<SessionDescription> description = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        SdpObserver observer = new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription result) {
                description.set(result);
                created.countDown();
            }
            @Override public void onSetSuccess() { }
            @Override public void onCreateFailure(String error) {
                failure.set(error);
                created.countDown();
            }
            @Override public void onSetFailure(String error) {
                failure.set(error);
                created.countDown();
            }
        };
        if (offer) peer.createOffer(observer, new MediaConstraints());
        else peer.createAnswer(observer, new MediaConstraints());
        assertTrue("WebRTC did not create an SDP description within 15 seconds",
                created.await(15, TimeUnit.SECONDS));
        assertEquals("WebRTC SDP creation failed: " + failure.get(), null, failure.get());
        assertNotNull("WebRTC returned a null SDP description", description.get());
        return description.get();
    }

    private static void setDescription(PeerConnection peer, boolean local, SessionDescription description)
            throws Exception {
        CountDownLatch set = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        SdpObserver observer = new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription ignored) { }
            @Override public void onSetSuccess() { set.countDown(); }
            @Override public void onCreateFailure(String error) {
                failure.set(error);
                set.countDown();
            }
            @Override public void onSetFailure(String error) {
                failure.set(error);
                set.countDown();
            }
        };
        if (local) peer.setLocalDescription(observer, description);
        else peer.setRemoteDescription(observer, description);
        assertTrue("WebRTC did not set an SDP description within 15 seconds",
                set.await(15, TimeUnit.SECONDS));
        assertEquals("WebRTC failed to set SDP: " + failure.get(), null, failure.get());
    }

    private static final class ChannelObserver implements DataChannel.Observer {
        private final CountDownLatch open = new CountDownLatch(1);
        private final CountDownLatch message = new CountDownLatch(1);
        private final AtomicReference<String> receivedText = new AtomicReference<>();
        private volatile DataChannel channel;

        void attach(DataChannel dataChannel) {
            channel = dataChannel;
            dataChannel.registerObserver(this);
            if (dataChannel.state() == DataChannel.State.OPEN) open.countDown();
        }

        String stateDescription() {
            DataChannel current = channel;
            return current == null ? "none" : String.valueOf(current.state());
        }

        @Override public void onBufferedAmountChange(long previousAmount) { }
        @Override public void onStateChange() {
            DataChannel current = channel;
            if (current != null && current.state() == DataChannel.State.OPEN) open.countDown();
        }
        @Override public void onMessage(DataChannel.Buffer buffer) {
            ByteBuffer data = buffer.data.slice();
            byte[] payload = new byte[data.remaining()];
            data.get(payload);
            receivedText.set(new String(payload, StandardCharsets.UTF_8));
            message.countDown();
        }
    }

    private static final class PeerObserver extends NoopObserver {
        private final CountDownLatch dataChannelCreated = new CountDownLatch(1);
        private final AtomicReference<DataChannel> dataChannel = new AtomicReference<>();
        private final AtomicReference<PeerConnection.IceConnectionState> iceState =
                new AtomicReference<>(PeerConnection.IceConnectionState.NEW);
        private final AtomicInteger gatheredCandidates = new AtomicInteger();
        private final ChannelObserver channelObserver = new ChannelObserver();

        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            iceState.set(state);
        }
        @Override public void onIceCandidate(IceCandidate candidate) {
            gatheredCandidates.incrementAndGet();
        }
        @Override public void onDataChannel(DataChannel channel) {
            dataChannel.set(channel);
            channelObserver.attach(channel);
            dataChannelCreated.countDown();
        }

        String diagnostics() {
            return "ICE=" + iceState.get() + ", gatheredCandidates=" + gatheredCandidates.get()
                    + ", remoteDataChannel=" + (dataChannel.get() != null)
                    + ", remoteChannelState=" + channelObserver.stateDescription();
        }
    }

    private static class NoopObserver implements PeerConnection.Observer {
        final CountDownLatch iceGatheringComplete = new CountDownLatch(1);

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
