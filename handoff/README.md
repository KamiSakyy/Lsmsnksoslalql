# MeowMessenger — Android Java + Railway + FCM

A minimal plain-text test messenger. The Android app gets an FCM token and can send `Привет!` to a recipient token. Message delivery is through Firebase Cloud Messaging; there is no message history or end-to-end encryption.

## Deploy the Railway API

[![Deploy on Railway](https://railway.app/button.svg)](https://railway.app/new/template?templateUrl=https%3A%2F%2Fgithub.com%2FKamiSakyy%2FLsmsnksoslalql%2Ftree%2Farena%2Fd87d04d9-lsmsnksoslalql)

The repository-root `Dockerfile` and `railway.json` configure the API service. After Railway creates the service:

1. In **Variables**, add `FIREBASE_PROJECT_ID=meowmessenger`.
2. Add `FIREBASE_SERVICE_ACCOUNT_JSON` with a **new, rotated** service-account JSON that has permission to send FCM messages. Keep it only in Railway Variables; never put it in Git, the Docker image, the Android APK, or GitHub Actions.
3. Generate a public Railway domain. In the Android app, paste that `https://…up.railway.app` URL into **Адрес Railway API**, save it, and use **Проверить подключение**.
4. In Firebase Console, enable **Authentication → Anonymous**. Install the APK, copy a recipient's FCM token, and send a message. For a one-device smoke test, paste that same device's token into the recipient field and send `Привет!`.

The old Admin SDK key was posted in a public repository/chat. Revoke it before creating a replacement. The `google-services.json` under `app/` is the Android client configuration, not an Admin credential.

## What “FCM only” means here

FCM is a server-to-device push service; an Android app cannot safely send an arbitrary FCM message directly to another device. The app first sends a small HTTPS request to this Railway API. Railway verifies the Firebase Auth ID token and asks Firebase Admin SDK to send the push through FCM. No Cloud Function is used.

If the phone has network access **only inside the Tinkoff Mobile operator app**, that allowance does not automatically cover this Android app, Firebase Auth, the Railway URL, or Google Play Services/FCM. For outgoing messages, the app must be able to reach the Railway HTTPS endpoint; for incoming pushes, FCM traffic must also be allowed. The carrier controls this. If those endpoints are blocked at zero balance, code and a Railway server cannot bypass that restriction. Test on the exact SIM/tariff.

Messages are sent as plain text in the FCM payload and can be stored temporarily by FCM (the API sets a seven-day TTL). Do not send sensitive information. The Railway endpoint applies a basic per-user rate limit; this prototype is not production hardened.

## Build APK

GitHub Actions builds `app-debug.apk` and publishes the artifact as `meowmessenger-debug-apk`. No GitHub secret is required for the Android build. The Railway service credential is entered in Railway, not GitHub.
