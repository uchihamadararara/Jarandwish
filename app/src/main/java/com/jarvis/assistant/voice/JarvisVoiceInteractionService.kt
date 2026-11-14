package com.jarvis.assistant.voice

import android.service.voice.VoiceInteractionService
import android.util.Log

/** Enables JARVIS to be selected as Android's default digital assistant. */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(TAG, "JARVIS voice interaction service ready")
    }

    companion object {
        private const val TAG = "JarvisVoiceAssist"
    }
}
