package com.jarvis.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.0.4: Mute now REALLY releases the hardware microphone (OS mic indicator goes off) and
 * re-opens it on unmute. Previously the mic kept recording while the app only dropped the
 * audio, which caused the "mic on at OS level but muted in app" glitches.
 *
 * While muted, [onAudioChunk] is called exactly ONCE (empty chunk) so the UI/service can
 * reset levels - it is NOT spammed 50x per second any more.
 */
class AudioRecorder(
    private val onAudioChunk: (chunk: ByteArray, amplitude: Float, frequencyData: AudioFrequencyData) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // Ultra-low latency 20ms chunk: 16000 samples/sec * 0.02s * 2 bytes/sample = 640 bytes
        const val CHUNK_SIZE_BYTES = 640
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    val isMuted = AtomicBoolean(false)

    private fun bufferSizeBytes(): Int {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) return -1
        return maxOf(minBufferSize * 4, 32000)
    }

    private fun releaseEffects() {
        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            automaticGainControl?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects: ${e.message}")
        } finally {
            echoCanceler = null
            noiseSuppressor = null
            automaticGainControl = null
        }
    }

    private fun releaseRecord(record: AudioRecord?) {
        releaseEffects()
        if (record == null) return
        try {
            if (record.state == AudioRecord.STATE_INITIALIZED &&
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                record.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord stop failed: ${e.message}")
        }
        try { record.release() } catch (_: Exception) {}
    }

    /** Creates, configures and starts a new AudioRecord. Returns null on failure. */
    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        val bufferSize = bufferSizeBytes()
        if (bufferSize <= 0) {
            Log.e(TAG, "Unable to get valid min buffer size")
            return null
        }
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                record.release()
                return null
            }

            val sessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable AcousticEchoCanceler: ${e.message}")
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable NoiseSuppressor: ${e.message}")
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                try {
                    automaticGainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable AutomaticGainControl: ${e.message}")
                }
            }

            record.startRecording()
            record
        } catch (e: Exception) {
            Log.e(TAG, "Error opening AudioRecord: ${e.message}", e)
            null
        }
    }

    fun start(scope: CoroutineScope): Boolean {
        if (isRecording.get()) return true

        // If we start muted, the hardware mic is NOT opened at all (no OS mic indicator).
        var firstRecord: AudioRecord? = null
        if (!isMuted.get()) {
            firstRecord = openRecord()
            if (firstRecord == null) return false
        } else if (bufferSizeBytes() <= 0) {
            return false
        }

        audioRecord = firstRecord
        isRecording.set(true)

        recordingJob = scope.launch(Dispatchers.IO) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            val buffer = ByteArray(CHUNK_SIZE_BYTES)
            var currentRecord: AudioRecord? = firstRecord
            var mutedNotified = false
            var openFailures = 0

            while (isActive && isRecording.get()) {
                // ---- MUTED: hardware mic fully released ----
                if (isMuted.get()) {
                    if (currentRecord != null) {
                        releaseRecord(currentRecord)
                        currentRecord = null
                        audioRecord = null
                        Log.i(TAG, "Muted: hardware microphone released")
                    }
                    if (!mutedNotified) {
                        mutedNotified = true
                        onAudioChunk(ByteArray(0), 0f, AudioFrequencyData())
                    }
                    delay(80)
                    continue
                }

                // ---- UNMUTED: (re)open hardware mic if needed ----
                if (currentRecord == null) {
                    val fresh = openRecord()
                    if (fresh == null) {
                        openFailures++
                        Log.w(TAG, "Mic re-open failed (attempt $openFailures)")
                        delay(if (openFailures < 5) 300L else 1000L)
                        continue
                    }
                    openFailures = 0
                    currentRecord = fresh
                    audioRecord = fresh
                    Log.i(TAG, "Unmuted: hardware microphone opened")
                }
                mutedNotified = false

                // ---- READ LOOP ----
                var consecutiveErrors = 0
                var broken = false
                while (isActive && isRecording.get() && !isMuted.get()) {
                    try {
                        val readBytes = currentRecord!!.read(buffer, 0, buffer.size)
                        if (readBytes > 0) {
                            consecutiveErrors = 0
                            if (isMuted.get()) break // muted while reading: drop this chunk
                            val freq = AudioUtils.analyzeFrequency(buffer, readBytes, SAMPLE_RATE)
                            onAudioChunk(buffer.copyOf(readBytes), freq.amplitude, freq)
                        } else if (readBytes < 0) {
                            consecutiveErrors++
                            Log.w(TAG, "AudioRecord read error code: $readBytes (count: $consecutiveErrors)")
                            delay(40)
                            if (consecutiveErrors > 10) {
                                broken = true
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during AudioRecord read loop: ${e.message}", e)
                        delay(50)
                        consecutiveErrors++
                        if (consecutiveErrors > 10) {
                            broken = true
                            break
                        }
                    }
                }

                if (broken && isActive && isRecording.get()) {
                    Log.e(TAG, "AudioRecord broken - auto-recovering...")
                    releaseRecord(currentRecord)
                    currentRecord = null
                    audioRecord = null
                    delay(400)
                }
            }

            // Loop finished (stop() called or cancelled): make sure nothing is left open
            releaseRecord(currentRecord)
        }
        return true
    }

    fun stop() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        val record = audioRecord
        audioRecord = null
        releaseRecord(record)
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }
}
