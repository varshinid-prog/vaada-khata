package com.vaada.audio

import kotlin.math.max
import kotlin.math.min

/**
 * Tunable endpointing parameters. All of them are exposed so they can be adjusted from diagnostics without touching logic.
 * Defaults are starting values to be tuned on the CMF Phone 2 Pro in a real shop, not measured optima.
 */
data class EndpointConfig(
    val minThreshold: Float = 0.012f,        // absolute floor for "speech" RMS (0..1), so a silent room never triggers on hiss
    val noiseMultiplier: Float = 2.0f,       // speech must exceed noiseFloor * this ...
    val noiseMargin: Float = 0.006f,         // ... plus this
    val hysteresis: Float = 0.7f,            // once speaking, a frame still counts as speech above threshold * this (keeps word tails)
    val maxNoiseFloor: Float = 0.02f,        // a loud start can never raise the floor above this (the old code let it), so speech stays detectable
    val onsetFrames: Int = 2,                // consecutive loud frames needed to call it speech (rejects clicks and thumps)
    val minSpeechMs: Long = 250,             // less voiced audio than this is noise, not an utterance
    val trailingSilenceMs: Long = 1100,      // quiet needed after speech to finish
    val maxUtteranceMs: Long = 15000,        // hard cap (gateway/Gnani limit is 30 s)
    val noSpeechTimeoutMs: Long = 4000,      // give up if nothing was said
    val preRollMs: Long = 300,               // audio kept from before the handoff (wake-word tail), see AudioCaptureManager
)

enum class EndReason { SILENCE, MAX_DURATION, NO_SPEECH, STOPPED, ERROR }

/** Pure frame-by-frame voice activity / endpoint logic (no Android, no wall clock: time is counted from sample counts). */
class Endpointer(private val cfg: EndpointConfig = EndpointConfig()) {
    var noiseFloor = 0f; private set
    var threshold = cfg.minThreshold; private set
    var speechStartedAtMs = -1L; private set
    var elapsedMs = 0L; private set
    var voicedMs = 0L; private set
    val speechDetected: Boolean get() = inSpeech || voicedMs >= cfg.minSpeechMs
    private var inSpeech = false
    private var loudRun = 0
    private var runStartMs = 0L
    private var runVoicedMs = 0L
    private var silenceMs = 0L
    private var frames = 0

    /** Feed one frame. Returns the reason to stop now, or null to keep listening. */
    fun onFrame(rms: Float, frameMs: Long): EndReason? {
        frames++
        elapsedMs += frameMs
        val start = max(cfg.minThreshold, noiseFloor * cfg.noiseMultiplier + cfg.noiseMargin)
        threshold = start
        val loud = rms >= (if (inSpeech) start * cfg.hysteresis else start)

        if (!inSpeech) {
            updateNoise(rms)
            if (loud) {
                if (loudRun == 0) { runStartMs = elapsedMs - frameMs; runVoicedMs = 0 }
                loudRun++; runVoicedMs += frameMs
                if (loudRun >= cfg.onsetFrames) {
                    inSpeech = true; silenceMs = 0
                    if (speechStartedAtMs < 0) speechStartedAtMs = runStartMs
                    voicedMs += runVoicedMs
                }
            } else {
                loudRun = 0
            }
        } else if (loud) {
            voicedMs += frameMs; silenceMs = 0
        } else {
            silenceMs += frameMs
            if (silenceMs >= cfg.trailingSilenceMs) {
                if (voicedMs >= cfg.minSpeechMs) return EndReason.SILENCE
                inSpeech = false; loudRun = 0; voicedMs = 0; speechStartedAtMs = -1   // it was only a short burst of noise
            }
        }
        if (elapsedMs >= cfg.maxUtteranceMs) return EndReason.MAX_DURATION
        if (!inSpeech && voicedMs == 0L && elapsedMs >= cfg.noSpeechTimeoutMs) return EndReason.NO_SPEECH
        return null
    }

    /** Noise floor follows the quiet frames only: down fast, up slowly, and never above [EndpointConfig.maxNoiseFloor]. */
    private fun updateNoise(rms: Float) {
        val capped = min(rms, cfg.maxNoiseFloor)
        noiseFloor = when {
            frames == 1 -> capped
            frames <= 3 -> min(noiseFloor, capped)         // quietest of the first frames: speech has no lower dips than the room
            capped < noiseFloor -> noiseFloor * 0.7f + capped * 0.3f
            else -> noiseFloor * 0.98f + capped * 0.02f
        }
    }
}
