package com.beautifulquran.domain

/** Manual wall-time corrections shared by the presentation and highlight clocks. */
object OutputLatency {
    /**
     * Extra correction for Media3's presentation clock. Automatic mode adds
     * none: subtracting a Bluetooth preset again makes the ink late. A manual
     * lab correction is in wall milliseconds, so scale it to the media clock.
     */
    fun mediaLagMs(additionalLagMs: Long?, playbackSpeed: Float): Long =
        ((additionalLagMs ?: 0L).coerceAtLeast(0L) * playbackSpeed).toLong()

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
