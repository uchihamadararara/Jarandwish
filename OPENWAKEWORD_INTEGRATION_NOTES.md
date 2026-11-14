# JARVIS 2.0.10 Agentic + OpenWakeWord integration

This is the full JARVIS 2.0.10 Agentic Task Upgrade Android project with the supplied OpenWakeWord Android Kotlin library integrated as a local Gradle module (`:wakeword`).

## Wake words
- `Jarvis_20260313_215039.onnx` -> Jarvis
- `Hey_Jarvis_trial26_128x3_240000_20260919_101237.onnx` -> Hey Jarvis
- Both detections call the existing `onWakeWordDetected()` path in `JarvisConversationService`.
- Detection is on-device. The shared OpenWakeWord `melspectrogram.onnx` and `embedding_model.onnx` front-end assets are included.

## Removed
- Vosk Android dependency and JNA dependency that was only used by the Vosk stack.
- Vosk model manager, acoustic model assets, speaker model assets, and speaker verification manager.
- Owner voice enrollment activity and manifest entry. Old owner-voice preference reads are hard-disabled so legacy saved settings cannot block assistant commands.

## Build
Run from the project root:

```bash
bash gradlew :app:assembleDebug
```

The original project targets Android SDK 34, minSdk 26, Java 17 and Gradle 8.9. Ensure Android SDK 34 and required build tools are installed in Termux/Android SDK.

## Testing
1. Install the debug APK and grant microphone permission.
2. Start JARVIS standby mode.
3. Say `Jarvis`, then repeat with `Hey Jarvis`.
4. Check Logcat for `OpenWakeWord detected` and the model label.
5. Adjust thresholds in `JarvisConversationService.startWakeListening()` if needed. Both currently start at 0.50; actual sensitivity/false-positive behavior must be measured on the target phone.

Build and microphone tests were not completed in this environment because the Gradle 8.9 distribution could not be downloaded (network DNS unavailable). Do not treat the archive as a verified APK.
