package com.beautifulquran.data

/**
 * How a gathered verse's `﴿N﴾` answers the tap that gathered it. Developer
 * A/B (Settings → Developer → Gather mark); readers get [BRUSH_CIRCLE].
 * Every effect is page ink, never gold: gold never marks interaction.
 * Persisted by ordinal — append new entries, never reorder.
 */
enum class GatherMarkEffect(val label: String, val paintMs: Int) {
    /** The ink-brush loop the gather ordinal and Settings choices use. */
    BRUSH_CIRCLE("Brush circle", 380),
    /** A wet drop lands behind the number, spreads, and dries to a pale stain with a darker rim. */
    INK_DROP("Ink drop", 460),
    /** A tapered qalam stroke under the number, written in the verse's direction, flicking up at the end. */
    QALAM_SWASH("Qalam swash", 420),
    /** An illuminator's shamsa: sixteen tapered rays, long and short, drawn round the mark in turn. */
    SHAMSA_RAYS("Shamsa rays", 480),
    /** The illuminated roundel's pearl-and-bead border, each bead pressed in after the last. */
    PEARL_RING("Pearl ring", 460),
    /** The illuminator's tāj: a teardrop and two palmette volutes crowning the medallion. */
    TAJ_CROWN("Tāj crown", 520),
}
