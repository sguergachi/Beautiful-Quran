package com.beautifulquran.playback

/** Developer comparison choices; [DEFAULT] is what the reader uses. */
enum class TarjiDetectorMode(val label: String) {
    Current("Current"), Cycles("Cycles"), Spectrum("Spectrum"), Recording("Recording");

    companion object {
        /** What the reader uses unless a developer picks another: Recording, for now
         * (it keeps only the dramatic moments — docs/tarji-detection/hani-drama.md). */
        val DEFAULT = Recording
    }
}

/** One reusable measurement from the existing PCM extractor; invalid F0 is never carried. */
internal data class TarjiFrame(
    var hop: Int = -1,
    var hopMs: Double = 20.0,
    var hopRms: Float = 0f,
    var rms80: Float = 0f,
    var level: Float = 0f,
    var voiced: Boolean = false,
    var holdMs: Float = 0f,
    var holdStartHop: Int = -1,
    var holdPitchHz: Float = 0f,
    var holdClarity: Float = 0f,
    var f0Hz: Float = Float.NaN,
    var f0Valid: Boolean = false,
    var pitchQuality: Float = 0f,
    var pitchLeadHops: Float = 0f,
)
