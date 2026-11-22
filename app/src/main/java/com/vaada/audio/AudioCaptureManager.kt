package com.vaada.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.vaada.models.AudioCaptureMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class AudioCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE = 16000 // 16 kHz
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)

    var onAmplitudeUpdate: ((Float) -> Unit)? = null
    var onCaptureCompleted: ((AudioCaptureMetadata, File?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Endpointing parameters used for the next capture (diagnostics can change them). */
    @Volatile var endpointConfig: EndpointConfig = EndpointConfig()

    val isCapturing: Boolean
        get() = isRecording.get()

    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope, handoffStartTimeMs: Long? = null, preRoll: ShortArray? = null) {
        val tCaptureInitStart = System.currentTimeMillis()
        if (isRecording.get()) {
            Log.w(TAG, "Capture already in progress")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            val error = "Unable to determine minimum buffer size for AudioRecord"
            Log.e(TAG, error)
            onError?.invoke(error)
            return
        }

        val bufferSize = max(minBufferSize * 2, 4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                val error = "AudioRecord failed to initialize (state != STATE_INITIALIZED)"
                Log.e(TAG, error)
                releaseAudioRecord()
                onError?.invoke(error)
                return
            }

            val tBeforeStart = System.currentTimeMillis()
            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                // another app (a call, another recorder) holds the microphone
                releaseAudioRecord()
                onError?.invoke("Microphone is busy or unavailable")
                return
            }
            val tRecordStarted = System.currentTimeMillis()
            isRecording.set(true)

            Log.d(
                "HandoffProfiler",
                "AudioCaptureManager: AudioRecord created in ${tBeforeStart - tCaptureInitStart}ms, startRecording took ${tRecordStarted - tBeforeStart}ms (Total T2->T3: ${tRecordStarted - tCaptureInitStart}ms)"
            )

            recordingJob = scope.launch(Dispatchers.IO) {
                runCaptureLoop(bufferSize, handoffStartTimeMs, tRecordStarted, preRoll, endpointConfig)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord capture", e)
            releaseAudioRecord()
            isRecording.set(false)
            onError?.invoke(e.message ?: "Unknown audio recording error")
        }
    }

    private fun runCaptureLoop(
        bufferSize: Int, handoffStartTimeMs: Long?, tRecordStarted: Long, preRoll: ShortArray?, cfg: EndpointConfig
    ) {
        val frame = ShortArray(bufferSize / 2)
        val pcm = ByteArrayOutputStream()
        val endpointer = Endpointer(cfg)
        val startTime = System.currentTimeMillis()
        var maxAmplitude = 0
        var squareSum = 0.0
        var totalSamples = 0L
        var firstFrame = true
        var reason = EndReason.STOPPED

        // Feeds one block of PCM to the buffer, the level meter and the endpointer. Returns the reason to stop, if any.
        fun consume(buf: ShortArray, n: Int): EndReason? {
            var sq = 0.0
            for (i in 0 until n) {
                val v = buf[i].toInt()
                if (Math.abs(v) > maxAmplitude) maxAmplitude = Math.abs(v)
                sq += v.toDouble() * v
                pcm.write(v and 0xFF); pcm.write((v shr 8) and 0xFF)
            }
            squareSum += sq; totalSamples += n
            val rms = min((sqrt(sq / n) / 32767.0).toFloat(), 1f)
            onAmplitudeUpdate?.invoke(rms)
            return endpointer.onFrame(rms, n * 1000L / SAMPLE_RATE)
        }

        try {
            // Audio that arrived after the wake word fired but before this recorder existed (see WakeWordDetector.audioSinceTrigger).
            if (preRoll != null && preRoll.isNotEmpty()) {
                var off = 0
                while (off < preRoll.size) {
                    val n = min(frame.size, preRoll.size - off)
                    System.arraycopy(preRoll, off, frame, 0, n)
                    consume(frame, n)?.let { /* a tail alone never ends a capture */ }
                    off += n
                }
                Log.i(TAG, "Pre-roll prepended: ${preRoll.size * 1000L / SAMPLE_RATE} ms")
            }
            while (isRecording.get()) {
                val read = audioRecord?.read(frame, 0, frame.size) ?: AudioRecord.ERROR_INVALID_OPERATION
                if (read < 0) {   // ERROR_INVALID_OPERATION, ERROR_BAD_VALUE, ERROR_DEAD_OBJECT, ERROR: never spin on a dead recorder
                    Log.e(TAG, "AudioRecord.read failed with code $read")
                    reason = EndReason.ERROR
                    break
                }
                if (read == 0) continue
                if (firstFrame) {
                    firstFrame = false
                    val tFirst = System.currentTimeMillis()
                    val recordToRead = if (tRecordStarted > 0) tFirst - tRecordStarted else 0L
                    if (handoffStartTimeMs != null) {
                        Log.i("HandoffProfiler", ">>> HANDOFF: wake->first audio = ${tFirst - handoffStartTimeMs} ms (record start->first read ${recordToRead} ms)")
                    } else {
                        Log.i("HandoffProfiler", "PTT first audio frame in ${recordToRead} ms")
                    }
                }
                val end = consume(frame, read)
                if (end != null) { reason = end; break }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during audio capture loop", e)
            reason = EndReason.ERROR
        } finally {
            val bytes = pcm.toByteArray()
            val speech = endpointer.speechDetected
            val avgRms = if (totalSamples > 0) (sqrt(squareSum / totalSamples) / 32767.0).toFloat() else 0f
            Log.i(
                TAG,
                "Capture end: reason=$reason speech=$speech speechStart=${endpointer.speechStartedAtMs}ms voiced=${endpointer.voicedMs}ms " +
                    "total=${endpointer.elapsedMs}ms noiseFloor=%.4f threshold=%.4f peak=$maxAmplitude avgRms=%.4f".format(
                        endpointer.noiseFloor, endpointer.threshold, avgRms)
            )
            // Only a recording that really contains speech is written out and handed on; silence is never uploaded.
            val outputFile = if (speech && reason != EndReason.ERROR) try {
                File(context.cacheDir, "last_utterance.pcm").also { f -> FileOutputStream(f).use { it.write(bytes) } }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist PCM utterance to cache file", e); null
            } else null

            val metadata = AudioCaptureMetadata(
                sampleRate = SAMPLE_RATE, channels = 1, audioFormat = "PCM_16BIT",
                durationMs = endpointer.elapsedMs, totalBytes = bytes.size, maxAmplitude = maxAmplitude, averageRms = avgRms,
                endReason = reason.name, speechDetected = speech, speechStartMs = endpointer.speechStartedAtMs,
                noiseFloor = endpointer.noiseFloor, speechThreshold = endpointer.threshold
            )
            isRecording.set(false)
            releaseAudioRecord()
            onCaptureCompleted?.invoke(metadata, outputFile)
        }
    }

    fun stopCapture() {
        if (!isRecording.get()) return
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null
    }

    private fun releaseAudioRecord() {
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        } finally {
            try {
                audioRecord?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioRecord", e)
            }
            audioRecord = null
        }
    }

    fun release() {
        stopCapture()
        releaseAudioRecord()
    }
}
