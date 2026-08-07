package com.beautifulquran.playback

import android.media.audiofx.Visualizer
import kotlin.math.sqrt

/**
 * Live voice level of the reciter for the glint resonance shimmer.
 *
 * Attaches an [Visualizer] to the player's audio session and exposes a
 * thread-safe [level] (0..1 RMS of the waveform) plus a slow [resting]
 * EMA so the gold can track vibrato and breath as deviation from the
 * sustained note — not absolute loudness.
 *
 * Pure RMS helpers stay unit-testable without Android.
 */
class VoiceEnergy {

    @Volatile
    var level: Float = 0f
        private set

    /** Slow center of the sustained note; updated on each capture. */
    @Volatile
    var resting: Float = 0f
        private set

    private var visualizer: Visualizer? = null
    private var attachedSession: Int = Visualizer.ERROR

    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == Visualizer.ERROR) {
            release()
            return
        }
        if (attachedSession == audioSessionId && visualizer != null) return
        release()
        try {
            val v = Visualizer(audioSessionId)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            val rate = (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(8_000)
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (waveform == null || waveform.isEmpty()) return
                        val rms = rms(waveform)
                        level = rms
                        // Slow EMA so vibrato/breath read as deviation, not
                        // absolute volume of the reciter or device gain.
                        resting = resting * RESTING_EMA + rms * (1f - RESTING_EMA)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) = Unit
                },
                rate,
                true,
                false,
            )
            v.enabled = true
            visualizer = v
            attachedSession = audioSessionId
        } catch (_: RuntimeException) {
            // Some devices / output routes refuse Visualizer; glint falls back
            // to the free-running shimmer in [InkEngine.glintResonance].
            release()
        }
    }

    fun release() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: RuntimeException) {
            // already gone
        }
        visualizer = null
        attachedSession = Visualizer.ERROR
        level = 0f
    }

    companion object {
        private const val RESTING_EMA = 0.92f

        /**
         * The live probe owned by [PlayerController]. Draw-phase glint reads
         * this without composition so voice energy never forces a recompose.
         */
        @Volatile
        var active: VoiceEnergy? = null

        /**
         * RMS of an 8-bit unsigned PCM waveform (Visualizer format: 128 =
         * silence). Returns 0..1.
         */
        fun rms(waveform: ByteArray): Float {
            if (waveform.isEmpty()) return 0f
            var sum = 0.0
            for (b in waveform) {
                val v = (b.toInt() and 0xFF) - 128
                sum += v * v
            }
            return (sqrt(sum / waveform.size) / 128.0).toFloat().coerceIn(0f, 1f)
        }
    }
}
