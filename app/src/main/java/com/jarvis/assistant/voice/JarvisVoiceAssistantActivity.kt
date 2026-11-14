package com.jarvis.assistant.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.background.JarvisConversationService

/**
 * Invisible fallback only. If the assistant session cannot start the service directly, this
 * transparent activity gives the app a visible window for a moment, starts the mic session
 * and closes itself. It draws nothing, so the home screen stays visible.
 */
class JarvisVoiceAssistantActivity : AppCompatActivity() {

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            start()
        }
    }

    private fun start() {
        if (!JarvisConversationService.isSessionOn.value) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, JarvisConversationService::class.java)
                    .setAction(JarvisConversationService.ACTION_START_CONVERSATION)
                    .putExtra(JarvisConversationService.EXTRA_SHOW_EDGE_OVERLAY, true)
            )
        }
        // Stay alive for a moment so the app still counts as foreground while the mic service
        // calls startForeground(); then close without any animation.
        window.decorView.postDelayed({
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            }
            finish()
        }, 600L)
    }
}
