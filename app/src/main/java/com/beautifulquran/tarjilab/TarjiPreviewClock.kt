package com.beautifulquran.tarjilab

/** Maps AudioTrack's unsigned frames-played counter onto a static loop.
 * The counter is independent of the initial buffer offset and playback speed. */
internal class TarjiPreviewClock(
    private val startFrame: Int,
    private val initialHead: Int,
    private val loopStart: Int,
    private val loopEnd: Int,
) {
    fun frameAt(head: Int): Int {
        val played = (head.toLong() - initialHead.toLong()) and 0xFFFF_FFFFL
        val length = (loopEnd - loopStart).coerceAtLeast(1)
        return loopStart + ((startFrame - loopStart + played) % length).toInt()
    }
}
