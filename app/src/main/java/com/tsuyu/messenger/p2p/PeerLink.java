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
    private final List<InetSocketAddress> targets = Collections.synchronizedList(new ArrayList<>());
    private final SecureRandom random = new SecureRandom();

    private volatile DatagramSocket socket;
    private volatile Listener listener;
    private volatile InetSocketAddress peer;
    private volatile SecretKeySpec key;
    /** Своя метка для исходящих пакетов и метка собеседника — для входящих (у каждого она своя). */
    private volatile byte[] ivOut = new byte[8];
    private volatile byte[] ivIn = new byte[8];
    private volatile byte[] peerNonce;
    private volatile long lastPacketAt;
    /** Ответы STUN приходят в этот «ящик»: приём P2P и знакомство с сетью делят один порт. */
    private final java.util.concurrent.BlockingQueue<byte[]> stunInbox =
            new java.util.concurrent.LinkedBlockingQueue<>();
    /** Недавно принятые номера — чтобы повтор не сбивал буфер переупорядочивания. */
    private final java.util.LinkedHashSet<Integer> recentSequences = new java.util.LinkedHashSet<>();
    private final AtomicInteger highestSequence = new AtomicInteger();
    private volatile boolean announced;
    private Thread receiveThread;
    private Thread keepAliveThread;

    public PeerLink(String callId) {
        this.callId = callId == null ? "call" : callId;
        random.nextBytes(ownNonce);
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
        return Stun.discover(current, stunInbox);
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
            ivOut = directionStamp(ownNonce, peerNonce);
            ivIn = directionStamp(peerNonce, ownNonce);
        } catch (Exception ignored) { }
    }

    /**
     * Метка направления: у каждой стороны она своя (своё число идёт первым), поэтому один и
     * тот же счётчик в двух направлениях никогда не даёт одинаковый «номер шифрования».
     */
    private byte[] directionStamp(byte[] mine, byte[] theirs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("tsuyu-p2p-iv".getBytes("UTF-8"));
            digest.update(callId.getBytes("UTF-8"));
            digest.update(mine);
            digest.update(theirs);
            byte[] full = digest.digest();
            byte[] stamp = new byte[8];
            System.arraycopy(full, 0, stamp, 0, 8);
            return stamp;
        } catch (Exception ignored) {
            return new byte[8];
        }
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
            System.arraycopy(ivOut, 0, iv, 0, 8);
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
        if (type == 0x0101) { // ответ STUN на запрос «как меня видит интернет»
            byte[] copy = new byte[packet.getLength()];
            System.arraycopy(data, 0, copy, 0, copy.length);
            stunInbox.offer(copy);
            return;
        }
        int number = readInt(data, 1);
        InetSocketAddress from = new InetSocketAddress(packet.getAddress(), packet.getPort());
        lastPacketAt = System.currentTimeMillis();
        if (type == HELLO) {
            // В «привете» сразу приходит и его число для ключа — не ждём базы.
            if (packet.getLength() >= 21) {
                byte[] nonce = new byte[16];
                System.arraycopy(data, 5, nonce, 0, 16);
                setPeerNonce(hex(nonce));
            }
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
            if (type == BYE) close("peer");
            return;
        }
        if (type != AUDIO && type != VIDEO) return;
        if (!from.equals(peer)) {
            if (peer == null) peer = from;
            else return;
        }
        SecretKeySpec secret = key;
        if (secret == null) return;
        if (!acceptSequence(number)) return; // повтор или слишком старый пакет
        byte[] iv = new byte[12];
        System.arraycopy(ivIn, 0, iv, 0, 8);
        writeInt(iv, 8, number);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, iv));
            cipher.updateAAD(new byte[]{(byte) type});
            byte[] plain = cipher.doFinal(data, 5, packet.getLength() - 5);
            markConnected();
            Listener target = listener;
            if (target == null) return;
            if (type == AUDIO) target.onAudio(number, plain);
            else target.onVideo(number, plain);
        } catch (Exception ignored) {
            // Чужой или испорченный пакет: молча отбрасываем.
        }
    }

    /**
     * Пускаем пакет, если он новый и не слишком старый: лёгкий беспорядок в сети не мешает,
     * а повтор или чужой «отравленный» номер отбрасывается. Возвращает true — можно читать.
     */
    private boolean acceptSequence(int number) {
        synchronized (recentSequences) {
            if (recentSequences.contains(number)) return false;
            int highest = highestSequence.get();
            if (highest > 0 && number < highest - 400) return false;
            recentSequences.add(number);
            if (recentSequences.size() > 1024) {
                java.util.Iterator<Integer> oldest = recentSequences.iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            if (number > highest) highestSequence.set(number);
            return true;
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
