# Noir P2P Android release signing

`NoirP2P-release-cert.pem` is the public certificate for the Android release signing key. It is safe to publish and is included in the source archive so installers can verify that future APKs use the same signer.

The private PKCS#12 keystore and its password are deliberately **not** stored in this public repository, APK, or source ZIP. A public signing key lets anyone publish malicious APKs that Android would accept as updates. The release workflow reads these GitHub Actions repository secrets:

- `ANDROID_SIGNING_KEYSTORE_BASE64` — base64-encoded `NoirP2P-release.p12`;
- `ANDROID_SIGNING_STORE_PASSWORD`;
- `ANDROID_SIGNING_KEY_PASSWORD`.

The fixed alias is `noirp2p-release`. Keep an offline, encrypted backup of the keystore and passwords. Losing the private key prevents in-place updates for all installed release APKs. Do not regenerate or replace it once users have installed a release.

After the private backup files are available on a trusted machine, `./signing/configure-github-secrets.sh /path/to/NoirP2P-release.p12 /path/to/password.txt` submits the three secrets without printing their values. It requires GitHub Actions secret-write access; never put those files in this repository or its ZIP.

The workflow sets `versionCode` to its monotonically increasing GitHub Actions run number and `versionName` to `1.0.<run-number>`. It publishes the latest release and keeps each versioned APK under `handoff/releases/` instead of deleting older releases.
