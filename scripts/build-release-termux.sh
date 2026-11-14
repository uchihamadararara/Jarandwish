#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_BIN="${GRADLE_BIN:-$HOME/jarvis-build/gradle-8.9/bin/gradle}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

AAPT2_OVERRIDE="/data/data/com.termux/files/usr/bin/aapt2"
if [ -x "$AAPT2_OVERRIDE" ]; then
  mkdir -p "$HOME/.gradle"
  touch "$HOME/.gradle/gradle.properties"
  if ! grep -Eq '^[[:space:]]*android\.aapt2FromMavenOverride=' "$HOME/.gradle/gradle.properties"; then
    printf '\nandroid.aapt2FromMavenOverride=%s\n' "$AAPT2_OVERRIDE" >> "$HOME/.gradle/gradle.properties"
  fi
fi

if [ ! -x "$GRADLE_BIN" ]; then
  echo "Gradle 8.9 not found at: $GRADLE_BIN" >&2
  echo "Set GRADLE_BIN to the existing Gradle 8.9 executable." >&2
  exit 2
fi
if [ ! -d "$ANDROID_HOME/platforms" ]; then
  echo "Android SDK platforms not found under $ANDROID_HOME" >&2
  exit 2
fi

export JARVIS_RELEASE_STORE_FILE="${JARVIS_RELEASE_STORE_FILE:-$HOME/jarvis-release.jks}"
export JARVIS_RELEASE_KEY_ALIAS="${JARVIS_RELEASE_KEY_ALIAS:-jarvis}"
export JARVIS_DEVELOPER_NAME="${JARVIS_DEVELOPER_NAME:-Tech GPTx}"
export JARVIS_COPYRIGHT_OWNER="${JARVIS_COPYRIGHT_OWNER:-Tech GPTx}"

if [ ! -f "$JARVIS_RELEASE_STORE_FILE" ]; then
  echo "Release keystore not found: $JARVIS_RELEASE_STORE_FILE" >&2
  exit 2
fi

JARVIS_RELEASE_STORE_PASSWORD="${JARVIS_RELEASE_STORE_PASSWORD:-devildinesh}"
echo "Keystore password loaded."
export JARVIS_RELEASE_STORE_PASSWORD
export JARVIS_RELEASE_KEY_PASSWORD="$JARVIS_RELEASE_STORE_PASSWORD"

CERT_LINE="$(keytool -list -v -keystore "$JARVIS_RELEASE_STORE_FILE" -alias "$JARVIS_RELEASE_KEY_ALIAS" -storepass "$JARVIS_RELEASE_STORE_PASSWORD" 2>/dev/null | grep -m1 'SHA256:' || true)"
if [ -z "$CERT_LINE" ]; then
  unset JARVIS_RELEASE_STORE_PASSWORD JARVIS_RELEASE_KEY_PASSWORD
  echo "Could not read SHA-256 certificate fingerprint. Check the keystore password and alias." >&2
  exit 2
fi
export JARVIS_RELEASE_CERT_SHA256="$(printf '%s' "$CERT_LINE" | sed 's/.*SHA256:[[:space:]]*//' | tr -d ':[:space:]')"

cd "$PROJECT_DIR"
"$GRADLE_BIN" :app:assembleRelease --no-daemon
printf '\nRelease APK: %s\n' "$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
unset JARVIS_RELEASE_STORE_PASSWORD JARVIS_RELEASE_KEY_PASSWORD
