package com.vaada.audio

import org.junit.Assert.*
import org.junit.Test

class EndpointerTest {
    private val frame = 32L                      // ms per frame (512 samples @ 16 kHz)
    private val quiet = 0.003f
    private val loud = 0.08f

    /** Feeds `ms` of constant level; returns the first end reason, or null. */
    private fun Endpointer.feed(level: Float, ms: Long): EndReason? {
        var t = 0L
        while (t < ms) { onFrame(level, frame)?.let { return it }; t += frame }
        return null
    }

    @Test fun endsAfterTrailingSilenceFollowingSpeech() {
        val e = Endpointer()
        assertNull(e.feed(quiet, 300))
        assertNull(e.feed(loud, 1200))
        assertEquals(EndReason.SILENCE, e.feed(quiet, 2000))
        assertTrue(e.speechDetected)
    }

    @Test fun doesNotCutAShortNaturalPauseBetweenWords() {
        val e = Endpointer()
        e.feed(loud, 800)
        assertNull(e.feed(quiet, 700))            // a 0.7 s pause is inside the 1.1 s trailing window
        assertNull(e.feed(loud, 800))
        assertEquals(EndReason.SILENCE, e.feed(quiet, 2000))
    }

    @Test fun speechInTheVeryFirstFramesDoesNotPoisonTheNoiseFloor() {
        // The old code seeded the noise floor from the first frames, so speaking immediately meant speech was never detected.
        val e = Endpointer()
        assertNull(e.feed(loud, 1000))
        assertTrue("speech must be detected", e.speechDetected)
        assertEquals(EndReason.SILENCE, e.feed(quiet, 2000))
        assertTrue(e.noiseFloor <= EndpointConfig().maxNoiseFloor)
    }

    @Test fun silenceOnlyEndsWithNoSpeechAndIsNotAnUtterance() {
        val e = Endpointer()
        assertEquals(EndReason.NO_SPEECH, e.feed(quiet, 10_000))
        assertFalse(e.speechDetected)
    }

    @Test fun aSingleClickIsNotSpeech() {
        val e = Endpointer()
        e.feed(quiet, 200)
        e.onFrame(0.5f, frame)                   // one loud frame < onsetFrames
        assertEquals(EndReason.NO_SPEECH, e.feed(quiet, 10_000))
        assertFalse(e.speechDetected)
    }

    @Test fun aShortBurstThenQuietIsDiscardedAsNoise() {
        val e = Endpointer()
        e.feed(loud, 100)                         // < minSpeechMs of voiced audio
        assertEquals(EndReason.NO_SPEECH, e.feed(quiet, 10_000))
        assertFalse(e.speechDetected)
    }

    @Test fun continuousSpeechStopsAtMaxDuration() {
        val e = Endpointer(EndpointConfig(maxUtteranceMs = 5000))
        assertEquals(EndReason.MAX_DURATION, e.feed(loud, 10_000))
        assertTrue(e.elapsedMs in 5000..5100)
    }

    @Test fun steadyBackgroundNoiseRaisesTheThresholdSoItIsNotMistakenForSpeech() {
        val e = Endpointer()
        val fan = 0.02f                           // above the absolute minimum threshold, but constant
        e.feed(fan, 1500)
        assertTrue("threshold should have adapted above the fan level", e.threshold > fan)
        assertFalse(e.speechDetected)
        e.feed(0.2f, 600)                         // speech well above the fan
        assertTrue(e.speechDetected)
    }

    @Test fun quietEndOfAWordStillCountsThanksToHysteresis() {
        val e = Endpointer()
        e.feed(quiet, 200)
        e.feed(loud, 600)
        val tail = EndpointConfig().minThreshold * 0.9f   // just under the start threshold, above the release threshold
        assertNull("a fading word tail must not end the utterance", e.feed(tail, 1500))
        assertTrue(e.voicedMs >= 1100)
    }

    @Test fun speechStartIsRecordedForFirstWordDiagnostics() {
        val e = Endpointer()
        e.feed(quiet, 320)
        e.feed(loud, 800)
        assertTrue(e.speechStartedAtMs in 256..352)
    }
}
