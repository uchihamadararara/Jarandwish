package com.jarvis.assistant.wellbeing

import android.app.KeyguardManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService

/**
 * Safety checks for the wellbeing feature.
 *  - isCallOrBusy: any sign of a call (phone, WhatsApp, Instagram, Meet...), camera in use, navigation.
 *    When in doubt it answers "busy", so Jarvis never locks the screen during a call.
 *  - isUsingPhone: real usage (screen on + unlocked + touches / media), not just "screen on".
 */
object PhoneActivity {

    /** Updated by the accessibility service on clicks, typing and scrolls. */
    @Volatile
    var lastInteractionAt: Long = 0L

    @Volatile
    private var lastBusyAt: Long = 0L

    private const val CALL_RECENT_MS = 2 * 60_000L
    private const val ACTIVE_WINDOW_MS = 90_000L

    private val NAVIGATION_PACKAGES = setOf("com.google.android.apps.maps", "com.waze")

    private val unavailableCameras = java.util.Collections.synchronizedSet(HashSet<String>())
    private var cameraCallback: CameraManager.AvailabilityCallback? = null

    fun registerCamera(context: Context) {
        if (cameraCallback != null) return
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cb = object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    unavailableCameras.remove(cameraId)
                }

                override fun onCameraUnavailable(cameraId: String) {
                    unavailableCameras.add(cameraId)
                }
            }
            cm.registerAvailabilityCallback(cb, Handler(Looper.getMainLooper()))
            cameraCallback = cb
        } catch (_: Exception) {
        }
    }

    fun unregisterCamera(context: Context) {
        try {
            val cb = cameraCallback ?: return
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).unregisterAvailabilityCallback(cb)
        } catch (_: Exception) {
        }
        cameraCallback = null
        unavailableCameras.clear()
    }

    fun isScreenOnUnlocked(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            pm.isInteractive && !km.isKeyguardLocked
        } catch (_: Exception) {
            false
        }
    }

    /**
     * @param ignoreCommunicationMode set true only while Jarvis's own voice session is running,
     * because Jarvis itself switches the audio mode to "communication" when a headset is used.
     */
    fun isCallOrBusy(context: Context, ignoreCommunicationMode: Boolean): Boolean {
        val now = System.currentTimeMillis()
        var busy = false
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mode = am.mode
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE) busy = true
            if (mode == AudioManager.MODE_IN_COMMUNICATION && !ignoreCommunicationMode) busy = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mode == AudioManager.MODE_CALL_SCREENING) busy = true
        } catch (_: Exception) {
            busy = true // cannot tell: assume busy
        }
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            if (tm != null && tm.callState != TelephonyManager.CALL_STATE_IDLE) busy = true
        } catch (_: Exception) {
        }
        if (unavailableCameras.isNotEmpty()) busy = true // another app (video call) is using a camera
        try {
            if (JarvisAccessibilityService.foregroundAppPackage.value in NAVIGATION_PACKAGES) busy = true
        } catch (_: Exception) {
        }
        if (busy) lastBusyAt = now
        return busy || (now - lastBusyAt < CALL_RECENT_MS)
    }

    fun isUsingPhone(context: Context): Boolean {
        if (!isScreenOnUnlocked(context)) return false
        val now = System.currentTimeMillis()
        if (now - lastInteractionAt < ACTIVE_WINDOW_MS) return true
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.isMusicActive) return true // video / music playing
        } catch (_: Exception) {
        }
        return ScreenTimeReader.recentUserInteraction(context, 2 * 60_000L)
    }
}
