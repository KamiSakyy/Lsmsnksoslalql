/**
 * FCMessenger relay: единственный серверный кусок.
 * Принимает зашифрованный конверт от отправителя и кладёт его
 * ЦЕЛИКОМ внутрь FCM data-пуша получателю.
 *
 * Сервер НЕ видит текст: шифрование сквозное (Tink ECIES на клиентах).
 */
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");

admin.initializeApp();

const KINDS = new Set(["msg", "ack", "read", "typing"]);
// Лимит FCM data-пейлоада — 4 КБ. Держим запас.
const MAX_BYTES = 3500;

exports.sendMessage = onCall({ maxInstances: 10 }, async (req) => {
  if (!req.auth) {
    throw new HttpsError("unauthenticated", "auth required");
  }
  const { to, kind, id, ts, ct } = req.data || {};
  if (typeof to !== "string" || !to) {
    throw new HttpsError("invalid-argument", "bad 'to'");
  }
  if (!KINDS.has(kind)) {
    throw new HttpsError("invalid-argument", "bad 'kind'");
  }
  if (typeof id !== "string" || !id) {
    throw new HttpsError("invalid-argument", "bad 'id'");
  }
  const data = {
    from: req.auth.uid,
    kind,
    id,
    ts: String(ts || Date.now()),
    ct: typeof ct === "string" ? ct : "",
  };
  if (Buffer.byteLength(JSON.stringify(data), "utf8") > MAX_BYTES) {
    throw new HttpsError("invalid-argument", "payload too big (4KB FCM limit)");
  }

  const snap = await admin.firestore().doc(`users/${to}`).get();
  const token = snap.get("token");
  if (!token) {
    throw new HttpsError("failed-precondition", "recipient has no FCM token");
  }

  try {
    const fcmId = await admin.messaging().send({
      token,
      data,
      android: { priority: "high" }, // высокий приоритет: будит даже в Doze
    });
    return { ok: true, fcmId };
  } catch (e) {
    // Протухший токен — чистим, клиент перезальёт свежий через onNewToken
    if (
      e.code === "messaging/registration-token-not-registered" ||
      e.code === "messaging/invalid-registration-token"
    ) {
      await admin.firestore().doc(`users/${to}`).update({ token: "" });
      throw new HttpsError("failed-precondition", "stale token, ask peer to reopen app");
    }
    throw new HttpsError("internal", e.message || "fcm send failed");
  }
});
