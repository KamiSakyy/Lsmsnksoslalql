package com.tsuyu.messenger.p2p;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Свой STUN-клиент по RFC 5389 — ни одной сторонней библиотеки, только сокеты Android.
 *
 * <p>Задача одна: узнать, как нас видит интернет (публичный адрес и порт). Это нужно,
 * чтобы два телефона нашли друг друга напрямую через обычный мобильный интернет.
 */
public final class Stun {

    /** Публичные STUN-серверы: это просто справочные серверы, а не посредники для трафика. */
    public static final String[] SERVERS = {
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
            "stun2.l.google.com:19302",
            "stun.cloudflare.com:3478",
            "stun.nextcloud.com:443",
            "stun.sipgate.net:3478",
            "stun.ekiga.net:3478",
            "stun.syncthing.net:3478",
    };

    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final int BINDING_REQUEST = 0x0001;
    private static final int BINDING_RESPONSE = 0x0101;
    private static final int ATTR_XOR_MAPPED = 0x0020;
    private static final int ATTR_MAPPED = 0x0001;

    private Stun() { }

    /** Публичная точка: адрес и порт, которые видит интернет. */
    public static final class Endpoint {
        public final String host;
        public final int port;

        public Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String value() {
            return host + ":" + port;
        }

        public InetSocketAddress address() {
            try {
                return new InetSocketAddress(InetAddress.getByName(host), port);
            } catch (Exception ignored) {
                return null;
            }
        }

        public static Endpoint parse(String value) {
            if (value == null) return null;
            int colon = value.lastIndexOf(':');
            if (colon <= 0) return null;
            try {
                return new Endpoint(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /**
     * Спрашивает серверы по очереди, пока кто-нибудь не ответит: свой публичный адрес
     * узнаём через тот же сокет, которым потом будем говорить с собеседником.
     */
    public static Endpoint discover(DatagramSocket socket) {
        if (socket == null || socket.isClosed()) return null;
        byte[] transaction = new byte[12];
        new SecureRandom().nextBytes(transaction);
        byte[] request = bindingRequest(transaction);
        for (String server : SERVERS) {
            try {
                String host = server;
                int port = 3478;
                int colon = server.lastIndexOf(':');
                if (colon > 0) {
                    host = server.substring(0, colon);
                    port = Integer.parseInt(server.substring(colon + 1));
                }
                InetAddress address = InetAddress.getByName(host);
                socket.setSoTimeout(1200);
                socket.send(new DatagramPacket(request, request.length, address, port));
                byte[] buffer = new byte[512];
                DatagramPacket answer = new DatagramPacket(buffer, buffer.length);
                socket.receive(answer);
                Endpoint endpoint = parseResponse(answer, transaction);
                if (endpoint != null) {
                    socket.setSoTimeout(0);
                    return endpoint;
                }
            } catch (Exception ignored) {
                // Сервер молчит или недоступен — пробуем следующий.
            }
        }
        try {
            socket.setSoTimeout(0);
        } catch (Exception ignored) { }
        return null;
    }

    /** Свой адрес в локальной сети — пригодится, если телефоны в одной Wi-Fi сети. */
    public static List<Endpoint> localEndpoints() {
        List<Endpoint> found = new ArrayList<>();
        try {
            for (java.net.NetworkInterface network : java.util.Collections.list(
                    java.net.NetworkInterface.getNetworkInterfaces())) {
                if (network.isLoopback() || !network.isUp()) continue;
                for (java.net.InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress host = address.getAddress();
                    if (host == null || host.isLoopback()) continue;
                    if (host instanceof java.net.Inet6Address) continue;
                    found.add(new Endpoint(host.getHostAddress(), 0));
                }
            }
        } catch (Exception ignored) { }
        return found;
    }

    private static byte[] bindingRequest(byte[] transaction) {
        byte[] message = new byte[20];
        message[0] = 0x00;
        message[1] = (byte) BINDING_REQUEST;
        // длина 0, магическое число и 12 байт транзакции
        message[4] = (byte) (MAGIC_COOKIE >> 24);
        message[5] = (byte) (MAGIC_COOKIE >> 16);
        message[6] = (byte) (MAGIC_COOKIE >> 8);
        message[7] = (byte) MAGIC_COOKIE;
        System.arraycopy(transaction, 0, message, 8, 12);
        return message;
    }

    private static Endpoint parseResponse(DatagramPacket answer, byte[] transaction) {
        byte[] data = answer.getData();
        int length = answer.getLength();
        if (length < 20) return null;
        int type = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
        if (type != BINDING_RESPONSE) return null;
        for (int i = 0; i < 12; i++) {
            if (data[8 + i] != transaction[i]) return null;
        }
        int attributesLength = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
        int offset = 20;
        int end = Math.min(length, 20 + attributesLength);
        while (offset + 4 <= end) {
            int attribute = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
            int size = ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
            int value = offset + 4;
            if (value + size > end) break;
            if (attribute == ATTR_XOR_MAPPED && size >= 8 && (data[value + 1] & 0xff) == 0x01) {
                int port = (((data[value + 2] & 0xff) << 8) | (data[value + 3] & 0xff)) ^ (MAGIC_COOKIE >> 16);
                StringBuilder host = new StringBuilder();
                for (int i = 0; i < 4; i++) {
                    if (i > 0) host.append('.');
                    host.append((data[value + 4 + i] & 0xff) ^ ((MAGIC_COOKIE >> (24 - i * 8)) & 0xff));
                }
                return new Endpoint(host.toString(), port);
            }
            if (attribute == ATTR_MAPPED && size >= 8 && (data[value + 1] & 0xff) == 0x01) {
                int port = ((data[value + 2] & 0xff) << 8) | (data[value + 3] & 0xff);
                StringBuilder host = new StringBuilder();
                for (int i = 0; i < 4; i++) {
                    if (i > 0) host.append('.');
                    host.append(data[value + 4 + i] & 0xff);
                }
                return new Endpoint(host.toString(), port);
            }
            offset = value + ((size + 3) & ~3);
        }
        return null;
    }
}
