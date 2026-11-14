package com.jarvis.assistant.vision

import com.jarvis.assistant.JarvisApp

/**
 * v2.0.6 "Smart Vision": during a COMPLEX screen task Jarvis automatically looks at the screen
 * through the Accessibility screenshot API (no screen-share dialog, no MediaProjection).
 *
 * Privacy / resource rules (hard guarantees):
 *  - frames live ONLY in RAM (downscaled JPEG bytes), never written to files, gallery or MediaStore;
 *  - the hardware bitmap is closed/recycled right after compression, sent bytes are zero-filled;
 *  - vision is active ONLY while a complex task is running ([TemporaryVisualTaskSession]) and the
 *    user setting is on - it switches off automatically when the task ends, is cancelled, or the AI
 *    session stops.
 */
object SmartVision {
    fun isActive(): Boolean = try {
        TemporaryVisualTaskSession.isActive() && JarvisApp.instance.preferences.isSmartVisionEnabled
    } catch (_: Throwable) {
        false
    }
}
