package com.beautifulquran.ui.reader

/** Keep the last heard word's leaf through an audio tail, until a seek or item change.
 * The wash continues to read the live word. */
internal fun mushafLocationWord(
    word: ActiveWord?,
    lastWord: ActiveWord?,
    playingAyah: Int?,
    inkId: Long,
): ActiveWord? = word ?: lastWord?.takeIf {
    !it.fromTap && it.ayah == playingAyah && it.activation == inkId
}
