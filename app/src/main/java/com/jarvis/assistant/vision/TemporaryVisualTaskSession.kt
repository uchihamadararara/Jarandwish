package com.jarvis.assistant.vision

import android.util.Log
import java.util.Arrays

/**
 * Owns compressed screenshot bytes for one complex automation task. Data stays in RAM;
 * this class never writes screenshots to files, MediaStore, or persistent storage.
 */
object TemporaryVisualTaskSession {
    private const val TAG = "TemporaryVisualTask"
    private const val MAX_FRAME_BYTES = 512 * 1024

    @Volatile private var activeTaskId: String? = null
    private var beforeFrame: ByteArray? = null
    private var afterFrame: ByteArray? = null

    @Synchronized fun begin(taskId: String) {
        clearFramesLocked()
        activeTaskId = taskId
        Log.d(TAG, "Temporary visual session started")
    }

    @Synchronized fun isActive(): Boolean = activeTaskId != null

    @Synchronized fun recordBefore(bytes: ByteArray?) {
        if (activeTaskId == null || bytes == null) return
        clearBytes(beforeFrame)
        beforeFrame = boundedCopy(bytes)
        clearBytes(bytes)
    }

    @Synchronized fun recordAfter(bytes: ByteArray?) {
        if (activeTaskId == null || bytes == null) return
        clearBytes(afterFrame)
        afterFrame = boundedCopy(bytes)
        clearBytes(bytes)
    }

    /** Returns a caller-owned copy for immediate in-memory analysis only. */
    @Synchronized fun beforeSnapshot(): ByteArray? = beforeFrame?.copyOf()
    @Synchronized fun afterSnapshot(): ByteArray? = afterFrame?.copyOf()

    @Synchronized fun end(taskId: String? = null) {
        if (taskId != null && activeTaskId != taskId) return
        clearFramesLocked()
        activeTaskId = null
        Log.d(TAG, "Temporary visual session cleared")
    }

    @Synchronized private fun clearFramesLocked() {
        clearBytes(beforeFrame)
        clearBytes(afterFrame)
        beforeFrame = null
        afterFrame = null
    }

    private fun boundedCopy(bytes: ByteArray): ByteArray {
        // Captures are expected to be downscaled JPEGs. Refuse oversized data rather than
        // retain a large image buffer during an automation task.
        if (bytes.size > MAX_FRAME_BYTES) {
            Log.w(TAG, "Skipped oversized temporary screenshot (${bytes.size} bytes)")
            return ByteArray(0)
        }
        return bytes.copyOf()
    }

    private fun clearBytes(bytes: ByteArray?) {
        if (bytes != null) Arrays.fill(bytes, 0.toByte())
    }
}
