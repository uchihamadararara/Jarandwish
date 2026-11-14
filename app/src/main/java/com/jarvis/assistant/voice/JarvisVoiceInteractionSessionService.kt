package com.jarvis.assistant.voice

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.background.JarvisConversationService

/** Routes the system assistant gesture (power-button hold) straight into JARVIS's live mic session. */
class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisVoiceInteractionSession(this)
    }
}

private class JarvisVoiceInteractionSession(
    service: VoiceInteractionSessionService
) : VoiceInteractionSession(service) {

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // No system assistant sheet. JARVIS draws its own edge aura + orb (home screen only).
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "onShow: assistant gesture received")
        try {
            // A second gesture while JARVIS is active returns it to standby.
            if (JarvisConversationService.isSessionOn.value) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, JarvisConversationService::class.java)
                        .setAction(JarvisConversationService.ACTION_ENTER_STANDBY)
                )
                finish()
                return
            }
            // Android 12+/14 blocks starting a microphone foreground service from the background, which is
            // exactly where a power-button gesture lands. So the visible (transparent) trampoline activity
            // is the PRIMARY path: while it is on screen the app counts as foreground and the mic works.
            // It starts the service and closes itself right away; nothing is drawn.
            try {
                startAssistantActivity(
                    Intent(context, JarvisVoiceAssistantActivity::class.java).apply {
                        action = Intent.ACTION_ASSIST
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
                Log.i(TAG, "onShow: trampoline activity launched")
            } catch (e: Exception) {
                Log.w(TAG, "Trampoline launch failed, starting service directly", e)
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, JarvisConversationService::class.java)
                        .setAction(JarvisConversationService.ACTION_START_CONVERSATION)
                        .putExtra(JarvisConversationService.EXTRA_SHOW_EDGE_OVERLAY, true)
                )
            }
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Assistant gesture could not start JARVIS", e)
            finish()
        }
    }

    private companion object {
        const val TAG = "JarvisVoiceAssist"
    }
}
