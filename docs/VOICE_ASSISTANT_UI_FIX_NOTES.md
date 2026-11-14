# JARVIS 1.8.10 – power-button assistant

- Power-button hold now starts the live mic session directly from the voice-interaction session (no full-screen activity). The old activity is only an invisible fallback.
- Aura (screen-edge glow) + liquid orb at the bottom react to voice level and conversation state (listening / thinking / speaking).
- The aura/orb is shown ONLY while the home screen is in front. Over other apps and inside JARVIS it is removed; it returns when you go back to home. The session keeps running (voice only) meanwhile.
- Needs: Display over other apps, Accessibility service (to detect home screen), mic + notification permission.
- Android/ColorOS: Settings -> power button long-press must be set to the Digital assistant (JARVIS), otherwise the system never calls JARVIS. Log tag `JarvisVoiceAssist` prints "onShow" when the gesture reaches the app.
- Not device-tested / not compiled in the authoring environment; build in Termux and test on device.
