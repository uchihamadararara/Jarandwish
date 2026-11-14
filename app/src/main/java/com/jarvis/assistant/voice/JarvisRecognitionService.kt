package com.jarvis.assistant.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Declaration-only recognizer. Many ROMs (ColorOS included) expect a default-assistant app to declare
 * a recognition service next to its VoiceInteractionService. JARVIS does its own speech handling via
 * Gemini Live, so this just reports "not available" if some app ever asks it to transcribe.
 */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {}
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
