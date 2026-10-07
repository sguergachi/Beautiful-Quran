package com.beautifulquran.playback

/** Developer comparison choices. The current detector remains the default. */
enum class TarjiDetectorMode(val label: String) {
    Current("Current"), Cycles("Cycles"), Spectrum("Spectrum"), Recording("Recording"),
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
