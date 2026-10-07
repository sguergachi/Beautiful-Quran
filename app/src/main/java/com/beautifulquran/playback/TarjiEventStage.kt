package com.beautifulquran.playback

import kotlin.math.ceil
import kotlin.math.max

/** A detector changes admission and gain; it never supplies visual phase. */
internal class TarjiDecision {
    var gain = 0f
    var reverberating = false
    var eventStartHop = -1
    var rateHz = 0f
    var usesAmplitude = true
    fun clear() {
        gain = 0f
        reverberating = false
        eventStartHop = -1
        rateHz = 0f
        usesAmplitude = true
    }
}

/**
 * Experimental event lifecycle. Retains the current hold, volume, level-step,
 * climax and dry-down rules without changing the shipped detector's lifecycle.
 * Every experimental open already requires confirmed repeated modulation.
 */
internal class TarjiEventStage {
    val decision = TarjiDecision()
    private var hold = -1
    private var peak = 0f
    private var eventRate = 0f
    private var under = 0
    private var pulseUnder = 0
    private var steadyGap = 0
    private var eventHops = 0
    private var transitionGrace = 0
    private var ended = false
    var minimumHop = 0

    fun clear() {
        decision.clear()
        hold = -1
        resetEvent()
        steadyGap = 0
        ended = false
    }

    private fun resetEvent() {
        peak = 0f
        eventRate = 0f
        under = 0
        pulseUnder = 0
        eventHops = 0
        transitionGrace = 0
    }

    fun next(frame: TarjiFrame, knobs: Tarji, am: TarjiEvidence, fm: TarjiEvidence,
             volumeFloor: Float = knobs.minVolume) {
        if (hold != frame.holdStartHop) { clear(); hold = frame.holdStartHop }
        val wasActive = decision.reverberating
        val loud = volumeFloor <= 0f || frame.level >= volumeFloor
        val loudKeep = volumeFloor <= 0f || frame.level >= 0.7f * volumeFloor
        val amOpen = loud && frame.voiced && am.open && am.levelBalance >= 0.35f
        val fmOpen = loud && frame.voiced && fm.open
        val amKeep = loudKeep && frame.voiced && am.keep
        val fmKeep = loudKeep && frame.voiced && fm.keep
        val coherent = amKeep || fmKeep
        val gapBefore = steadyGap
        steadyGap = if (coherent) 0 else steadyGap + 1
        if (ended && coherent && gapBefore >= 10) { resetEvent(); ended = false }
        decision.usesAmplitude = when {
            !wasActive -> amOpen || !fmOpen
            decision.usesAmplitude && !amKeep && fmKeep -> false
            !decision.usesAmplitude && !fmKeep && amKeep -> true
            else -> decision.usesAmplitude
        }
        val rate = if (decision.usesAmplitude) am.rateHz else fm.rateHz
        val ratio = if (rate > 0f && eventRate > 0f) max(rate / eventRate, eventRate / rate) else 1f
        if (wasActive && ratio <= 3f) eventRate += 0.1f * (rate - eventRate)
        peak = max(peak, frame.level.takeIf { peak > 0f } ?: 0f)
        under = if (peak > 0f && frame.level < 0.52f * peak) under + 1 else 0
        var climaxOver = under >= 24
        val cadenceBridge = eventRate > 0f && ratio <= 3f && coherent
        if (climaxOver && cadenceBridge) {
            peak = frame.level
            under = 0
            climaxOver = false
            transitionGrace = ceil(1000.0 / (knobs.minTremoloHz.coerceAtLeast(1.5f) * frame.hopMs)).toInt() + 8
        }
        val held = frame.holdMs >= knobs.holdMinMs && frame.holdMs > 0f && frame.hop + 1 >= minimumHop
        val next = held && !ended && if (wasActive) coherent else amOpen || fmOpen
        pulseUnder = if (coherent || transitionGrace > 0 || (cadenceBridge && under > 0)) 0
            else if (peak > 0f || next) pulseUnder + 1 else 0
        if (transitionGrace > 0) transitionGrace--
        val gapLimit = if (eventHops < 50 && eventRate > 0f) {
            max(10, ceil(2000.0 / (eventRate * frame.hopMs)).toInt())
        } else 10
        if (climaxOver || pulseUnder >= gapLimit || frame.holdMs <= 0f) ended = true
        decision.reverberating = next && !ended
        if (decision.reverberating && peak == 0f) {
            peak = frame.level
            eventRate = rate
            eventHops = 0
        }
        if (decision.reverberating && !wasActive) decision.eventStartHop = frame.hop + 1
        if (!decision.reverberating) decision.eventStartHop = -1
        decision.rateHz = rate
        val target = if (decision.reverberating) {
            eventHops++
            val level = if (peak > 0f) frame.level / peak else 1f
            (eventHops / 50f).coerceIn(0f, 1f) * ((level - 0.52f) / 0.23f).coerceIn(0f, 1f)
        } else 0f
        val tau = if (decision.reverberating) knobs.attackMs else if (ended) 50f else knobs.releaseMs
        decision.gain += (frame.hopMs / tau.coerceAtLeast(1f)).toFloat().coerceAtMost(1f) * (target - decision.gain)
    }
}

/** Selected causal experiment, sharing feature extraction and lifecycle. No PCM clock ownership. */
internal class TarjiExperimentalDetector {
    val modulation = TarjiModulation()
    val stage = TarjiEventStage()
    val decision get() = stage.decision
    fun clear(minimumHop: Int = 0) {
        modulation.clear()
        stage.clear()
        stage.minimumHop = minimumHop
    }
    fun next(frame: TarjiFrame, mode: TarjiDetectorMode, knobs: Tarji) {
        modulation.next(frame, mode, knobs)
        stage.next(frame, knobs, modulation.am, modulation.fm)
    }
}
