# MeowMessenger

Android Java + Firebase Cloud Messaging prototype. Source and setup notes are in [`handoff/`](handoff/README.md).

**Security:** never put a Firebase Admin SDK service-account JSON/private key in the Android app or Git. The Android app uses `google-services.json` (client configuration); FCM sends are performed by the trusted Cloud Function in `handoff/functions/`.
