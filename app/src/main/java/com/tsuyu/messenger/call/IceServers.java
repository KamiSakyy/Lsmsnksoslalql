package com.tsuyu.messenger.call;

import org.webrtc.PeerConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * Бесплатные релеи для P2P: несколько STUN и больше десятка TURN-«туннелей» на портах
 * 80/443 (UDP, TCP и TLS). Если один релей недоступен, ICE сам поднимает следующий,
 * поэтому звонок не рвётся ни в мобильной сети, ни за корпоративным файрволом.
 */
public final class IceServers {

    /** TURN: url, username, password. Все — публичные бесплатные сервисы. */
    private static final String[][] TURN = {
            {"turn:openrelay.metered.ca:80", "openrelayproject", "openrelayproject"},
            {"turn:openrelay.metered.ca:443", "openrelayproject", "openrelayproject"},
            {"turn:openrelay.metered.ca:443?transport=tcp", "openrelayproject", "openrelayproject"},
            {"turns:openrelay.metered.ca:443?transport=tcp", "openrelayproject", "openrelayproject"},
            {"turn:global.relay.metered.ca:80", "f6507426c0f4f89d0bda02e2", "7YF0907XexAfvkbL"},
            {"turn:global.relay.metered.ca:443", "f6507426c0f4f89d0bda02e2", "7YF0907XexAfvkbL"},
            {"turn:global.relay.metered.ca:443?transport=tcp", "f6507426c0f4f89d0bda02e2", "7YF0907XexAfvkbL"},
            {"turns:global.relay.metered.ca:443?transport=tcp", "f6507426c0f4f89d0bda02e2", "7YF0907XexAfvkbL"},
            {"turn:freestun.net:3478", "free", "free"},
            {"turn:freestun.net:3478?transport=tcp", "free", "free"},
            {"turns:freestun.net:5349", "free", "free"},
    };

    /** Публичные STUN: нужны, чтобы в 95 % случаев вообще не платить релеем. */
    private static final String[] STUN = {
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302",
            "stun:stun3.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
            "stun:stun.nextcloud.com:443",
            "stun:stun.sipgate.net:3478",
            "stun:stun.ekiga.net:3478",
            "stun:stun.syncthing.net:3478",
            "stun:stun.miwifi.com:3478",
    };

    private IceServers() { }

    public static List<PeerConnection.IceServer> list() {
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        for (String url : STUN) {
            servers.add(PeerConnection.IceServer.builder(url).createIceServer());
        }
        for (String[] turn : TURN) {
            servers.add(PeerConnection.IceServer.builder(turn[0])
                    .setUsername(turn[1])
                    .setPassword(turn[2])
                    .createIceServer());
        }
        return servers;
    }

    public static int count() {
        return STUN.length + TURN.length;
    }
}
