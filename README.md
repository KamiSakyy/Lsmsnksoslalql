# MeowMessenger

Android Java + Firebase Cloud Messaging prototype with a Railway HTTPS relay. Source, Docker deployment, and the Railway one-click link are in [`handoff/README.md`](handoff/README.md).

**Security:** never commit Firebase Admin SDK credentials or put them in the APK. The Railway service reads its credential from a Railway environment variable; the Android app uses only Firebase client configuration.
