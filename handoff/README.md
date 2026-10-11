# Tsuyu Android release handoff

The CI release workflow adds one APK and one standalone source archive for each app version:

- `tsuyu-android-v1.1.apk`
- `tsuyu-android-source-v1.1.zip`

New releases advance the user-facing version by `0.1` (`1.1`, `1.2`, `1.3`, …). The workflow checks the next version against the latest GitHub release and refuses to replace an existing handoff version. APK `versionCode` remains monotonically increasing for Android updates. The existing `tsuyu-android-source-v1.zip` is retained as-is.

APK builds continue to use the existing application ID and persistent release signing key, and the CI size check remains capped at 30,000,000 bytes.
