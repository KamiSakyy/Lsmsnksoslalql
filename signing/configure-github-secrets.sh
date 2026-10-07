#!/usr/bin/env bash
set -euo pipefail

REPOSITORY="${GH_REPO:-KamiSakyy/Lsmsnksoslalql}"
KEYSTORE_PATH="${1:?Usage: $0 /path/to/NoirP2P-release.p12 /path/to/password.txt}"
PASSWORD_FILE="${2:?Usage: $0 /path/to/NoirP2P-release.p12 /path/to/password.txt}"

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required." >&2
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "Reconnect GitHub in Arena or run gh auth login before setting repository secrets." >&2
  exit 1
fi

# Use process substitution/stdin so neither password is echoed to the terminal or stored in this script.
base64 < "$KEYSTORE_PATH" | tr -d '\n' | gh secret set ANDROID_SIGNING_KEYSTORE_BASE64 --repo "$REPOSITORY"
gh secret set ANDROID_SIGNING_STORE_PASSWORD --repo "$REPOSITORY" < "$PASSWORD_FILE"
gh secret set ANDROID_SIGNING_KEY_PASSWORD --repo "$REPOSITORY" < "$PASSWORD_FILE"
echo "Release signing secrets were submitted to GitHub Actions for $REPOSITORY."
