package com.beautifulquran.playback

/**
 * Makes the first [leadBytes] of a verse slice decode as silence.
 *
 * A verse cut from a surah MP3 begins on a frame header, but its first real
 * frame draws part of its data from the bit reservoir the frames before it
 * filled. So the slice starts with those frames (the "lead"). Left alone they
 * decode to the tail of the previous verse. Here each lead granule's
 * `part2_3_length` and `big_values` (the first 21 of its 59 side-info bits) are
 * cleared: the decoder finds no spectrum to read and emits silence, yet the
 * frame's bytes still reach the reservoir.
 *
 * Needs MPEG-1 Layer III stereo frames without CRC; the verse index is built
 * from files that are verified to be exactly that. This is the same transform
 * as `silence_lead` in tools/build_tawfeek_verse_index.py, which the build
 * uses to measure what the player will hear.
 */
internal object Mp3LeadSilencer {
    private val BITRATES_KBPS =
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private const val SAMPLE_RATE = 44_100
    private const val SIDE_INFO_FIRST_GRANULE_BIT = 20
    private const val GRANULE_BITS = 59
    private const val CLEARED_BITS = 21

    /** Length in bytes of the lead frame that starts at [offset], or -1 if it is not a usable frame. */
    fun frameLength(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        val header = ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        val sync = header ushr 21
        val version = (header ushr 19) and 3
        val layer = (header ushr 17) and 3
        val protectionBit = (header ushr 16) and 1
        val bitrate = BITRATES_KBPS[(header ushr 12) and 15]
        val rate = (header ushr 10) and 3
        val padding = (header ushr 9) and 1
        val mono = ((header ushr 6) and 3) == 3
        if (sync != 0x7FF || version != 3 || layer != 1 || rate != 0 || bitrate == 0 ||
            protectionBit == 0 || mono
        ) {
            return -1
        }
        return 144 * bitrate * 1000 / SAMPLE_RATE + padding
    }

    /** Silences, in place, every frame of [lead], which holds exactly the slice's first [leadBytes] bytes. */
    fun silence(lead: ByteArray, leadBytes: Int) {
        var pos = 0
        while (pos < leadBytes) {
            val length = frameLength(lead, pos)
            require(length > 0 && pos + length <= leadBytes) { "lead of $leadBytes bytes is not whole MP3 frames" }
            for (granule in 0 until 4) {
                val first = SIDE_INFO_FIRST_GRANULE_BIT + granule * GRANULE_BITS
                for (bit in first until first + CLEARED_BITS) {
                    val index = pos + 4 + bit / 8
                    lead[index] = (lead[index].toInt() and (1 shl (7 - bit % 8)).inv()).toByte()
                }
            }
            pos += length
        }
    }
}
