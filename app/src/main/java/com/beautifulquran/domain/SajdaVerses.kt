package com.beautifulquran.domain

/**
 * The fifteen verses of prostration (sajdat al-tilāwa) in the Hafs reading, as
 * flat `surah, ayah` pairs: the ayah where the reader prostrates, which the
 * printed mushaf marks with ۩. Al-Ḥajj carries two; the second Ṣād verse is
 * counted here as in the Madani mushaf.
 */
private val SAJDA_VERSES = intArrayOf(
    7, 206,
    13, 15,
    16, 50,
    17, 109,
    19, 58,
    22, 18,
    22, 77,
    25, 60,
    27, 26,
    32, 15,
    38, 24,
    41, 38,
    53, 62,
    84, 21,
    96, 19,
)

/** Ayah numbers in [surahId] at which the reader prostrates. */
fun sajdaAyahsOf(surahId: Int): Set<Int> = buildSet {
    for (index in 0 until SAJDA_VERSES.size / 2) {
        if (SAJDA_VERSES[index * 2] == surahId) add(SAJDA_VERSES[index * 2 + 1])
    }
}
