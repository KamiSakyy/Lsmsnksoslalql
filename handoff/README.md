# MeowMessenger — Android Java + FCM prototype

This is a small **token-to-token prototype**: the Android app displays its FCM registration token; a sender pastes the recipient's token and sends plain text. A trusted Firebase callable function sends the notification through FCM. It has no message history, contact list, end-to-end encryption, or delivery guarantee.

## Security: the service-account key

Never put a Firebase Admin SDK service-account JSON/private key in Android code, an APK, Git, or the GitHub Actions workflow. The Cloud Function uses its Google-managed runtime identity instead. The Android app only uses the Firebase **client** configuration file `app/google-services.json`.

## Firebase setup

1. In Firebase Console, add an Android app with package name `com.meowmessenger.app`.
2. Download that app's `google-services.json` and place it at `handoff/app/google-services.json`.
3. In Firebase Authentication, enable **Anonymous** sign-in.
4. Deploy the trusted FCM sender from this directory using an authenticated Firebase CLI session:

   ```sh
   cd handoff
   npm --prefix functions install
   firebase deploy --only functions:sendMessage
   ```

   The function runs in `europe-west1` and uses the project runtime identity; it does not need a downloaded service-account key. Firebase may require a billing-enabled plan to deploy Cloud Functions. If deployment reports missing `cloudmessaging.messages.create`, grant the runtime service account the least-privilege Firebase Cloud Messaging permission.
5. Install the APK on two Android devices, open the app on both, copy one device's FCM token, paste it into the other, then send a message.

## Build APK with GitHub Actions

The workflow at `.github/workflows/android-build.yml` builds a debug APK and uploads it as an Actions artifact named `meowmessenger-debug-apk`. No GitHub secret is needed to build the client. The workflow requires the Android app's `google-services.json` to be present in the source tree.

## Important limits for the zero-bundle test

FCM is a server-to-device push channel, not a device-to-device send API. Sending from this app requires a small HTTPS call to the callable function; the function then sends the push using FCM. For this to work when the phone has access only to the Tinkoff Mobile self-service app, the carrier must also allow this app's HTTPS function endpoint and Google FCM/Play Services traffic. Zero-rating the operator app does not automatically whitelist either endpoint. If the SIM blocks both, the app cannot send or receive remotely; no Firebase setting or FCM key can bypass carrier filtering or billing. Test on the exact SIM/tariff.

FCM is best-effort and may delay/drop messages; pending messages can expire. The function currently sets a seven-day TTL. This prototype sends message text in the FCM payload without end-to-end encryption, as requested. Do not use it for sensitive data.
