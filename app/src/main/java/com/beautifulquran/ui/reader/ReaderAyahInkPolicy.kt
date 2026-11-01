package com.beautifulquran.ui.reader

/** A word start focuses only its requested verse; natural handoffs can prepare the next verse. */
internal fun readerAyahInkPolicyActive(
    ayah: Int,
    inkAyah: Int?,
    fadeLedAyah: Int?,
    awaitingWordStart: Boolean,
): Boolean = ayah == inkAyah || !awaitingWordStart && ayah == fadeLedAyah
