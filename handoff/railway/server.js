const express = require('express');
const admin = require('firebase-admin');

const app = express();
const port = Number(process.env.PORT) || 8080;
const projectId = process.env.FIREBASE_PROJECT_ID || 'meowmessenger';
const serviceAccountJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
const recentSendsByUid = new Map();

app.disable('x-powered-by');
app.use(express.json({ limit: '8kb' }));

function initializeFirebaseAdmin() {
  if (admin.apps.length > 0) return true;
  if (!serviceAccountJson) return false;

  try {
    const serviceAccount = JSON.parse(serviceAccountJson);
    if (serviceAccount.project_id !== projectId) {
      console.error('Firebase service-account project_id does not match FIREBASE_PROJECT_ID.');
      return false;
    }
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
      projectId,
    });
    return true;
  } catch (error) {
    console.error('Firebase Admin initialization failed:', error.message);
    return false;
  }
}

function takeRateLimit(uid) {
  const now = Date.now();
  const windowStart = now - 60_000;
  const recent = (recentSendsByUid.get(uid) || []).filter((timestamp) => timestamp > windowStart);
  if (recent.length >= 20) return false;
  recent.push(now);
  recentSendsByUid.set(uid, recent);
  return true;
}

function sendError(res, status, message) {
  return res.status(status).json({ ok: false, error: message });
}

app.get('/health', (_request, response) => {
  response.status(200).json({
    ok: true,
    service: 'meowmessenger-railway',
    firebaseConfigured: Boolean(serviceAccountJson),
  });
});

app.post('/api/send', async (request, response) => {
  if (!initializeFirebaseAdmin()) {
    return sendError(response, 503, 'Railway Firebase variables are not configured.');
  }

  const authorization = request.get('authorization') || '';
  const match = authorization.match(/^Bearer\s+(.+)$/i);
  if (!match) {
    return sendError(response, 401, 'Firebase ID token is required.');
  }

  let decodedToken;
  try {
    decodedToken = await admin.auth().verifyIdToken(match[1]);
  } catch (_error) {
    return sendError(response, 401, 'Firebase ID token is invalid or expired.');
  }

  const recipientToken = request.body && request.body.recipientToken;
  const text = request.body && request.body.text;
  if (typeof recipientToken !== 'string' || recipientToken.length < 20 || recipientToken.length > 4096) {
    return sendError(response, 400, 'A valid recipient FCM token is required.');
  }
  if (typeof text !== 'string' || text.trim().length === 0 || text.length > 2000) {
    return sendError(response, 400, 'Message text must be between 1 and 2000 characters.');
  }
  if (Buffer.byteLength(text, 'utf8') > 3000) {
    return sendError(response, 400, 'Message is too large for an FCM payload.');
  }
  if (!takeRateLimit(decodedToken.uid)) {
    return sendError(response, 429, 'Rate limit reached. Try again in a minute.');
  }

  try {
    const messageId = await admin.messaging().send({
      token: recipientToken,
      notification: {
        title: 'MeowMessenger',
        body: text,
      },
      data: {
        text,
      },
      android: {
        priority: 'high',
        ttl: 7 * 24 * 60 * 60 * 1000,
      },
    });
    return response.status(200).json({ ok: true, messageId });
  } catch (error) {
    console.error('FCM send failed:', error && error.code ? error.code : 'unknown');
    if (error && (error.code === 'messaging/registration-token-not-registered'
        || error.code === 'messaging/invalid-registration-token')) {
      return sendError(response, 400, 'Recipient FCM token is invalid or expired.');
    }
    return sendError(response, 502, 'FCM could not accept the message.');
  }
});

app.listen(port, '0.0.0.0', () => {
  console.log(`MeowMessenger Railway API listening on ${port}`);
});
