package com.beautifulquran.domain

/** Number of ajzāʾ the mushaf is divided into. */
const val MUSHAF_JUZ_COUNT = 30

/**
 * The 30 juzʾ openings in the Hafs mushaf, as flat `surah, ayah` pairs
 * in order. Juzʾ 1 opens the book, so the table also anchors the fallback.
 */
private val JUZ_STARTS = intArrayOf(
    1, 1,
    2, 142,
    2, 253,
    3, 93,
    4, 24,
    4, 148,
    5, 82,
    6, 111,
    7, 88,
    8, 41,
    9, 93,
    11, 6,
    12, 53,
    15, 1,
    17, 1,
    18, 75,
    21, 1,
    23, 1,
    25, 21,
    27, 56,
    29, 46,
    33, 31,
    36, 28,
    39, 32,
    41, 47,
    46, 1,
    51, 31,
    58, 1,
    67, 1,
    78, 1,
)

/**
 * The juzʾ (1..30) an ayah belongs to — the last opening at or before it.
 * The printed page carries this in its running head beside the surah name.
 */
fun juzOf(surahId: Int, ayah: Int): Int {
    var juz = 1
    for (index in 0 until MUSHAF_JUZ_COUNT) {
        val startSurah = JUZ_STARTS[index * 2]
        val startAyah = JUZ_STARTS[index * 2 + 1]
        val started = startSurah < surahId || (startSurah == surahId && startAyah <= ayah)
        if (!started) break
        juz = index + 1
    }
    return juz
}
