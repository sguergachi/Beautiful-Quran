package com.beautifulquran.domain

/**
 * Route-based output-latency presets for the karaoke clock.
 *
 * [HighlightEngine] is pure: it answers "which word at time *t*?". Bluetooth
 * (and similar) delay is a property of the *playback path*, not of the
 * segments — so the reader subtracts a small offset from
 * `player.positionMs` before [HighlightClock] / [HighlightEngine] see it.
 *
 * Android never exposes a reliable end-to-end "ms until the ear" number for
 * A2DP. These presets are intentionally coarse: good enough that ink lands
 * with the voice on typical headsets, without baking lag into timing data or
 * the engine. A later user nudge can sit on top if needed.
 */
object OutputLatency {

    /** Connected output kinds the monitor can report. Multiple may be present. */
    enum class OutputKind {
        /** Phone speaker, wired, USB — treat as near-zero extra lag. */
        LOCAL,
        /** Classic Bluetooth A2DP (and hearing-aid media). */
        BLUETOOTH_A2DP,
        /** LE Audio / BLE headset or speaker. */
        BLUETOOTH_LE,
    }

    /** The single route used for the preset table (highest-latency wins). */
    enum class Route {
        LOCAL,
        BLUETOOTH_A2DP,
        BLUETOOTH_LE,
    }

    const val LOCAL_MS = 0L
    /** Typical classic A2DP stack delay; devices vary ~100–300 ms. */
    const val A2DP_MS = 180L
    /** LE Audio is usually lower-latency than classic A2DP. */
    const val LE_MS = 80L

    /**
     * Pick one route from the set of currently available output kinds.
     * Prefer A2DP over LE over local so a connected BT headset is not
     * ignored when the built-in speaker is still listed as an output.
     */
    fun classify(kinds: Set<OutputKind>): Route = when {
        OutputKind.BLUETOOTH_A2DP in kinds -> Route.BLUETOOTH_A2DP
        OutputKind.BLUETOOTH_LE in kinds -> Route.BLUETOOTH_LE
        else -> Route.LOCAL
    }

    fun latencyMs(route: Route): Long = when (route) {
        Route.LOCAL -> LOCAL_MS
        Route.BLUETOOTH_A2DP -> A2DP_MS
        Route.BLUETOOTH_LE -> LE_MS
    }

    fun latencyMs(kinds: Set<OutputKind>): Long = latencyMs(classify(kinds))

    /**
     * Media-timeline position adjusted so the highlight tracks what the
     * listener *hears*, not the decoder playhead.
     */
    fun heardMs(mediaPositionMs: Long, latencyMs: Long): Long =
        (mediaPositionMs - latencyMs).coerceAtLeast(0L)

    /**
     * Karaoke query time fed to [HighlightEngine]: start from the heard
     * playhead, then advance by [leadMs] so word ink can run *ahead* of the
     * segment table (Ink Lab → Highlight lead). Default lead is 0.
     *
     * Net form (not sequential clamp) so lag and lead cancel cleanly:
     * `max(0, media − latency + lead)` once the lead is fully engaged.
     *
     * When [leadNotBeforeMs] is positive, encoded opening silence stays on the
     * heard clock. After the first word starts, lead **ramps** from 0 to
     * [leadMs] over the first [leadMs] of voiced audio so engagement is
     * continuous with silence. A hard `+lead` cliff at the gate is larger than
     * [HighlightClock]'s post-handoff settle step and freezes the clock through
     * word 1 — both words then light when settle ends on word 2.
     */
    fun highlightMs(
        mediaPositionMs: Long,
        latencyMs: Long,
        leadMs: Long = 0L,
        leadNotBeforeMs: Long = 0L,
    ): Long {
        val heardPositionMs = heardMs(mediaPositionMs, latencyMs)
        val lead = leadMs.coerceAtLeast(0L)
        val gate = leadNotBeforeMs.coerceAtLeast(0L)
        // No opening silence: full lead from the first sample (lab default path).
        if (gate <= 0L) {
            return (mediaPositionMs - latencyMs.coerceAtLeast(0L) + lead)
                .coerceAtLeast(0L)
        }
        if (heardPositionMs < gate) return heardPositionMs
        // Ramp lead after the gate so engagement is continuous with silence.
        val pastGate = heardPositionMs - gate
        val appliedLead = minOf(lead, pastGate)
        return (heardPositionMs + appliedLead).coerceAtLeast(0L)
    }
}
