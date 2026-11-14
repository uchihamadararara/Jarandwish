# JARVIS: Termux build and setup notes

This archive includes the assistant-gesture, listening-edge-animation, wake-word and text-entry fixes. The project was prepared for the existing Termux ARM64 setup; device-specific runtime behaviour still needs to be tested on the phone.

## Build on the existing Termux setup

1. Extract this ZIP to `~/jarvis-build/jarvis` (replace the old source folder only after keeping any local changes you need).
2. Confirm these existing paths are present:
   - `~/android-sdk`
   - `~/jarvis-build/gradle-8.9/bin/gradle`
   - `~/jarvis-release.jks` with alias `jarvis`
3. Run:

```bash
cd ~/jarvis-build/jarvis
bash scripts/build-release-termux.sh
```

The script uses the locally installed ARM64 AAPT2 override at `/data/data/com.termux/files/usr/bin/aapt2` when available. This avoids the earlier Termux build failure caused by Gradle downloading an x86_64 AAPT2 binary. It asks for the keystore password interactively and does not store that password in this archive.

The expected output is:

`app/build/outputs/apk/release/app-release.apk`

The script uses the existing Gradle 8.9 installation from the prior successful build. If that installation is in a different location, set `GRADLE_BIN` before running the script.

## Required phone setup

- Set JARVIS as the default digital assistant in Android Settings. The power-button assistant gesture is controlled by Android/OEM settings; an app cannot independently intercept the hardware power key.
- Grant microphone and notification permissions.
- Enable JARVIS under Accessibility so it can inspect supported UI elements and type into focused fields in other apps.
- Enable **Display over other apps** for JARVIS to show the animated edge glow over other apps. If this permission is off, voice conversation can still start, but the system-wide edge animation is skipped.
- Configure the Gemini API key in JARVIS settings.
- For reliable background wake-word listening, allow unrestricted background/battery use and avoid force-stopping the app.

## Fixes included

- Removed the unsupported `supportsLaunchFromKeyguard` XML attribute that previously caused Android resource packaging to fail.
- Replaced the unavailable `Intent.ACTION_VOICE_ASSIST` Kotlin symbol with the Android action string, avoiding the prior unresolved-reference build error.
- Power-button assistant launches no longer wait on the battery-optimization dialog before starting the foreground voice session; required microphone/notification permission prompts remain.
- Added a non-touchable animated neon edge overlay while a live voice session is active; it is hidden on standby/session stop.
- Relaxed overly strict Vosk confidence cutoffs slightly while keeping wake-word phrase and word-confidence checks.
- Improved text entry to fall back to the newly focused editable field after tapping a grounded target, and added view focus/text event reporting to the accessibility service.
- Expanded common voice stop phrases including “OK”, “thanks”, “thank you”, and natural variants. The existing short debounce remains so a longer command beginning with “OK” is less likely to be cut off.

## Important limitation

A successful APK build does not prove the power-button gesture, wake-word detection, third-party app typing, or overlay animation works on every OEM/app. Test these on the target phone after setting JARVIS as the default assistant and granting the required permissions. Some third-party fields do not expose editable accessibility nodes, so typing cannot be guaranteed in every app.
