const { initializeApp } = require('firebase-admin/app');
const { getMessaging } = require('firebase-admin/messaging');
const { HttpsError, onCall } = require('firebase-functions/v2/https');

// Uses the Cloud Functions runtime service identity. No service-account JSON
// belongs in the Android APK, this source tree, or the build workflow.
initializeApp();

exports.sendMessage = onCall(
  { region: 'europe-west1', maxInstances: 10 },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError('unauthenticated', 'Sign in before sending a message.');
    }

    const recipientToken = request.data && request.data.recipientToken;
    const text = request.data && request.data.text;

    if (typeof recipientToken !== 'string' || recipientToken.length < 20 || recipientToken.length > 4096) {
      throw new HttpsError('invalid-argument', 'A valid recipient FCM token is required.');
    }
    if (typeof text !== 'string' || text.trim().length === 0 || text.length > 2000) {
      throw new HttpsError('invalid-argument', 'Message text must be between 1 and 2000 characters.');
    }
    if (Buffer.byteLength(text, 'utf8') > 3000) {
      throw new HttpsError('invalid-argument', 'Message is too large for an FCM payload.');
    }

    try {
      const messageId = await getMessaging().send({
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
      return { messageId };
    } catch (error) {
      console.error('FCM send failed:', error && error.code ? error.code : 'unknown');
      throw new HttpsError('failed-precondition', 'FCM не принял сообщение или токен устройства недействителен.');
    }
  }
);
