# Third-party notices

## libsignal

This project integrates `org.signal:libsignal-android:0.105.0` and `org.signal:libsignal-client:0.105.0` from Signal's official Maven repository. libsignal is licensed under **AGPL-3.0-only**. See the upstream source and license:

- https://github.com/signalapp/libsignal/tree/v0.105.0
- https://www.gnu.org/licenses/agpl-3.0.txt

The Android artifact includes JNI/native code. The project's public repository currently has no declared top-level license; adding this dependency may create source-distribution and licensing obligations for a combined application. Obtain legal review and choose/declare a compatible project license before distributing a release APK. This notice is not legal advice.

## Android Keystore

Signal identity/prekey/session records and local message bodies are encrypted at rest with per-install AES-GCM keys held by Android Keystore. Those keys are not embedded in the APK or stored in a `.so` file. Android Keystore may be software-backed; hardware-backed protection is device-dependent.
