package com.tsuyu.messenger.p2p;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Прямой канал между двумя телефонами: обычные UDP-сокеты, «пробивка» NAT, свои
 * подтверждения и собственное шифрование AES-256-GCM. Ни одной сторонней библиотеки.
 *
 * <p>Как соединяемся: узнаём свои публичные адреса через STUN, публикуем их в общей базе
 * и одновременно посылаем друг другу короткие пакеты. Как только чужой пакет дошёл —
 * канал открыт, и голос с видео идут напрямую, минуя какие-либо серверы.
 */
public final class PeerLink {

    public interface Listener {
        void onConnected();
        void onAudio(int sequence, byte[] pcm);
        void onVideo(int sequence, byte[] accessUnit);
        void onClosed(String reason);
    }

    public static final int HELLO = 1;
    public static final int PING = 2;
    public static final int PONG = 3;
    public static final int AUDIO = 4;
    public static final int VIDEO = 5;
    public static final int BYE = 8;

    private static final long PUNCH_INTERVAL_MS = 250L;
    private static final long KEEPALIVE_INTERVAL_MS = 2000L;
    private static final long SILENCE_TIMEOUT_MS = 9000L;
    private static final int MAX_PACKET = 1500;

    private final String callId;
    private final byte[] ownNonce = new byte[16];
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger lastSeenSequence = new AtomicInteger();
    private final List<InetSocketAddress> targets = Collections.synchronizedList(new ArrayList<>());
    private final SecureRandom random = new SecureRandom();

    private volatile DatagramSocket socket;
    private volatile Listener listener;
    private volatile InetSocketAddress peer;
    private volatile SecretKeySpec key;
    private volatile byte[] ivPrefix = new byte[8];
    private volatile byte[] peerNonce;
    private volatile long lastPacketAt;
    private volatile boolean announced;
    private Thread receiveThread;
    private Thread keepAliveThread;

    public PeerLink(String callId) {
        this.callId = callId == null ? "call" : callId;
        random.nextBytes(ownNonce);
        random.nextBytes(ivPrefix);
    }

    public String ownNonceHex() {
        return hex(ownNonce);
    }

    public String localPort() {
        DatagramSocket current = socket;
        return current == null || current.isClosed() ? null : String.valueOf(current.getLocalPort());
    }

    public boolean isConnected() {
        return connected.get();
    }

    /** Открываем сокет и запускаем приём. Возвращает true, если сокет готов. */
    public boolean open(Listener target) {
        if (running.get()) return true;
        listener = target;
        try {
            socket = new DatagramSocket();
            socket.setReuseAddress(true);
            running.set(true);
        } catch (Exception error) {
            return false;
        }
        receiveThread = new Thread(this::receiveLoop, "tsuyu-p2p-rx");
        receiveThread.setDaemon(true);
        receiveThread.start();
        keepAliveThread = new Thread(this::keepAliveLoop, "tsuyu-p2p-keepalive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
        return true;
    }

    /** Свой публичный адрес по мнению сети; null, если STUN недоступен. */
    public Stun.Endpoint discoverOwnEndpoint() {
        DatagramSocket current = socket;
        if (current == null) return null;
        return Stun.discover(current);
    }

    /** Адреса собеседника: публичный из базы плюс возможные локальные. */
    public void setPeerEndpoints(List<String> values) {
        if (values == null) return;
        List<InetSocketAddress> next = new ArrayList<>();
        for (String value : values) {
            Stun.Endpoint endpoint = Stun.Endpoint.parse(value);
            if (endpoint == null) continue;
            InetSocketAddress address = endpoint.address();
            if (address != null && !address.isUnresolved() && !next.contains(address)) next.add(address);
        }
        synchronized (targets) {
            targets.clear();
            targets.addAll(next);
        }
    }

    /** Публичный ключ обмена: пока известны не оба nonce, шифрование не включается. */
    public void setPeerNonce(String nonceHex) {
        if (nonceHex == null || key != null) return;
        byte[] parsed = unhex(nonceHex);
        if (parsed == null || parsed.length != 16) return;
        peerNonce = parsed;
        buildKey();
    }

    /** Ключ выводится из идентификатора звонка и двух случайных чисел — оба конца считают его сами. */
    private void buildKey() {
        if (peerNonce == null || key != null) return;
        try {
            byte[] first = ownNonce;
            byte[] second = peerNonce;
            if (compare(first, second) > 0) {
                byte[] swap = first;
                first = second;
                second = swap;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("tsuyu-p2p".getBytes("UTF-8"));
            digest.update(callId.getBytes("UTF-8"));
            digest.update(first);
            digest.update(second);
            byte[] material = digest.digest();
            byte[] keyBytes = new byte[32];
            System.arraycopy(material, 0, keyBytes, 0, 32);
            key = new SecretKeySpec(keyBytes, "AES");
            byte[] prefix = new byte[8];
            System.arraycopy(material, 0, prefix, 0, 8);
            ivPrefix = prefix;
        } catch (Exception ignored) { }
    }

    /** Отправка полезной нагрузки: тип, шифрование, счётчик. */
    public void send(int type, byte[] payload) {
        DatagramSocket current = socket;
        InetSocketAddress destination = peer;
        if (current == null || current.isClosed()) return;
        if (destination == null) return;
        int number = sequence.incrementAndGet();
        try {
            byte[] body = payload == null ? new byte[0] : payload;
            if (type == HELLO || type == PING || type == PONG) {
                byte[] packet = new byte[5 + body.length];
                packet[0] = (byte) type;
                writeInt(packet, 1, number);
                System.arraycopy(body, 0, packet, 5, body.length);
                current.send(new DatagramPacket(packet, packet.length, destination));
                return;
            }
            SecretKeySpec secret = key;
            if (secret == null) return;
            byte[] iv = new byte[12];
            System.arraycopy(ivPrefix, 0, iv, 0, 8);
            writeInt(iv, 8, number);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret, new GCMParameterSpec(128, iv));
            cipher.updateAAD(new byte[]{(byte) type});
            byte[] encrypted = cipher.doFinal(body);
            byte[] packet = new byte[5 + encrypted.length];
            packet[0] = (byte) type;
            writeInt(packet, 1, number);
            System.arraycopy(encrypted, 0, packet, 5, encrypted.length);
            current.send(new DatagramPacket(packet, packet.length, destination));
        } catch (Exception ignored) {
            // Потеря пакета в UDP — обычное дело, следующий дойдёт.
        }
    }

    public void close(String reason) {
        if (!running.getAndSet(false)) return;
        try {
            send(BYE, null);
        } catch (Exception ignored) { }
        DatagramSocket current = socket;
        socket = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
        connected.set(false);
        Listener target = listener;
        if (target != null) target.onClosed(reason == null ? "" : reason);
    }

    // ------------------------------------------------------------------
    // Приём и «пробивка»
    // ------------------------------------------------------------------

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET + 200];
        while (running.get()) {
            DatagramSocket current = socket;
            if (current == null) break;
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                current.receive(packet);
                handle(packet);
            } catch (Exception error) {
                if (!running.get()) break;
                // Сокет закрылся или сеть мигнула — выходим из цикла, движок решит, что делать.
                if (socket == null) break;
            }
        }
    }

    private void handle(DatagramPacket packet) {
        if (packet.getLength() < 5) return;
        byte[] data = packet.getData();
        int type = data[0] & 0xff;
        int number = readInt(data, 1);
        InetSocketAddress from = new InetSocketAddress(packet.getAddress(), packet.getPort());
        lastPacketAt = System.currentTimeMillis();
        if (type == HELLO) {
            // Собеседник проявился: запоминаем адрес и сразу отвечаем тем же.
            if (!from.equals(peer)) {
                peer = from;
                if (from.getAddress() != null) {
                    // Отвечаем один раз, чтобы канал открылся с двух сторон.
                    answerHello(from);
                }
            }
            markConnected();
            return;
        }
        if (type == PING) {
            if (!from.equals(peer)) peer = from;
            send(PONG, null);
            markConnected();
            return;
        }
        if (type == PONG || type == BYE) {
            if (type == BYE) {
                Listener target = listener;
                if (target != null) target.onClosed("peer");
                close("peer");
            }
            return;
        }
        if (type != AUDIO && type != VIDEO) return;
        if (!from.equals(peer)) {
            if (peer == null) peer = from;
            else return;
        }
        SecretKeySpec secret = key;
        if (secret == null) return;
        if (number <= lastSeenSequence.get()) return; // защита от повторов
        byte[] iv = new byte[12];
        System.arraycopy(ivPrefix, 0, iv, 0, 8);
        writeInt(iv, 8, number);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, iv));
            cipher.updateAAD(new byte[]{(byte) type});
            byte[] plain = cipher.doFinal(data, 5, packet.getLength() - 5);
            lastSeenSequence.set(number);
            markConnected();
            Listener target = listener;
            if (target == null) return;
            if (type == AUDIO) target.onAudio(number, plain);
            else target.onVideo(number, plain);
        } catch (Exception ignored) {
            // Чужой или испорченный пакет: молча отбрасываем.
        }
    }

    private void answerHello(InetSocketAddress address) {
        DatagramSocket current = socket;
        if (current == null || current.isClosed()) return;
        try {
            byte[] packet = new byte[5 + 16];
            packet[0] = (byte) HELLO;
            writeInt(packet, 1, sequence.incrementAndGet());
            System.arraycopy(ownNonce, 0, packet, 5, 16);
            current.send(new DatagramPacket(packet, packet.length, address));
        } catch (Exception ignored) { }
    }

    private void markConnected() {
        if (connected.compareAndSet(false, true)) {
            Listener target = listener;
            if (target != null) target.onConnected();
        }
    }

    /** Пока канал не открыт — долбим все известные адреса собеседника. */
    private void keepAliveLoop() {
        long nextPunch = 0L;
        long nextPing = 0L;
        while (running.get()) {
            long now = System.currentTimeMillis();
            try {
                if (!connected.get()) {
                    if (now >= nextPunch) {
                        nextPunch = now + PUNCH_INTERVAL_MS;
                        List<InetSocketAddress> snapshot;
                        synchronized (targets) {
                            snapshot = new ArrayList<>(targets);
                        }
                        announced = true;
                        for (InetSocketAddress address : snapshot) {
                            DatagramSocket current = socket;
                            if (current == null || current.isClosed()) break;
                            byte[] packet = new byte[5 + 16];
                            packet[0] = (byte) HELLO;
                            writeInt(packet, 1, sequence.incrementAndGet());
                            System.arraycopy(ownNonce, 0, packet, 5, 16);
                            try {
                                current.send(new DatagramPacket(packet, packet.length, address));
                            } catch (Exception ignored) { }
                        }
                    }
                } else {
                    if (now >= nextPing) {
                        nextPing = now + KEEPALIVE_INTERVAL_MS;
                        send(PING, null);
                    }
                    if (lastPacketAt > 0 && now - lastPacketAt > SILENCE_TIMEOUT_MS) {
                        connected.set(false);
                        String reason = "нет пакетов";
                        Listener target = listener;
                        if (target != null) target.onClosed(reason);
                        running.set(false);
                        break;
                    }
                }
            } catch (Exception ignored) { }
            try {
                Thread.sleep(120L);
            } catch (InterruptedException stop) {
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Мелочи
    // ------------------------------------------------------------------

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >> 24);
        target[offset + 1] = (byte) (value >> 16);
        target[offset + 2] = (byte) (value >> 8);
        target[offset + 3] = (byte) value;
    }

    private static int readInt(byte[] source, int offset) {
        return ((source[offset] & 0xff) << 24) | ((source[offset + 1] & 0xff) << 16)
                | ((source[offset + 2] & 0xff) << 8) | (source[offset + 3] & 0xff);
    }

    private static int compare(byte[] first, byte[] second) {
        for (int i = 0; i < Math.min(first.length, second.length); i++) {
            int left = first[i] & 0xff;
            int right = second[i] & 0xff;
            if (left != right) return left - right;
        }
        return first.length - second.length;
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        return out.toString();
    }

    private static byte[] unhex(String value) {
        if (value == null || value.length() % 2 != 0) return null;
        byte[] out = new byte[value.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException error) {
            return null;
        }
        return out;
    }
}
