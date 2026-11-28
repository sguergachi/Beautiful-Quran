package com.beautifulquran.playback

import com.beautifulquran.data.VerseClip
import com.beautifulquran.data.model.Reciter
import com.beautifulquran.data.parseVerseClipUrl
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tawfeek As-Sawaigh is streamed as byte ranges of his surah recordings. These
 * pin the pieces that make a range behave as a verse file.
 */
class VerseClipTest {

    /** One synthetic MPEG-1 Layer III stereo frame, no CRC, 128 kbps; [pad] adds the padding byte. */
    private fun frame(pad: Int, seed: Int): ByteArray {
        val length = 417 + pad
        val out = ByteArray(length)
        out[0] = 0xFF.toByte(); out[1] = 0xFB.toByte(); out[2] = (0x90 or (pad shl 1)).toByte(); out[3] = 0
        for (i in 4 until length) {
            out[i] = (if (i < 36) (((seed * 31 + i * 7) and 0xFF) or 0x81) else ((seed + i) and 0xFF)).toByte()
        }
        return out
    }

    private fun threeLeadFramesAndOne(): Pair<ByteArray, Int> {
        var data = ByteArray(0)
        for (pad in intArrayOf(0, 1, 0)) data += frame(pad, data.size % 251)
        val lead = data.size
        return (data + frame(0, 9)) to lead
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun silencerMatchesTheBuildToolsTransformByteForByte() {
        // The digests come from tools/build_tawfeek_verse_index.py's silence_lead on the same bytes.
        val (data, lead) = threeLeadFramesAndOne()
        assertEquals(1252, lead)
        assertEquals("81944a7dd6b99dabcd6b668373cf105b8a02b25618cf55e931f13ab954e4e40c", sha256(data))
        val out = data.copyOf(lead)
        Mp3LeadSilencer.silence(out, lead)
        assertEquals(
            "2c4fe229aebe82d3ec614cf42b26b6ba9ae7d537fd7673ac170e69dc52a49a01",
            sha256(out + data.copyOfRange(lead, data.size)),
        )
    }

    @Test fun silencerClearsOnlySpectrumLengthsAndLeavesHeaderAndReservoirBytes() {
        val (data, lead) = threeLeadFramesAndOne()
        val out = data.copyOf(lead)
        Mp3LeadSilencer.silence(out, lead)
        var pos = 0
        while (pos < lead) {
            val length = Mp3LeadSilencer.frameLength(data, pos)
            assertArrayEquals("header of frame at $pos", data.copyOfRange(pos, pos + 4), out.copyOfRange(pos, pos + 4))
            assertArrayEquals(
                "audio data of frame at $pos is the reservoir and must survive",
                data.copyOfRange(pos + 36, pos + length),
                out.copyOfRange(pos + 36, pos + length),
            )
            // Granule 0 channel 0 starts at side-info bit 20: bits 20..40 are zero, bit 41 is untouched.
            for (bit in 20..40) assertEquals(0, (out[pos + 4 + bit / 8].toInt() shr (7 - bit % 8)) and 1)
            pos += length
        }
    }

    @Test fun silencerRejectsFramesItCannotMakeSilent() {
        val (data, lead) = threeLeadFramesAndOne()
        assertThrows(IllegalArgumentException::class.java) { Mp3LeadSilencer.silence(data.copyOf(lead - 1), lead - 1) }
        val mono = data.copyOf(lead).also { it[3] = (it[3].toInt() or 0xC0).toByte() }
        assertThrows(IllegalArgumentException::class.java) { Mp3LeadSilencer.silence(mono, lead) }
        val crc = data.copyOf(lead).also { it[1] = (it[1].toInt() and 0xFE).toByte() }
        assertThrows(IllegalArgumentException::class.java) { Mp3LeadSilencer.silence(crc, lead) }
    }

    /** A fake surah recording: [file], with the verse at [clip]. Records every range requested. */
    private class FakeRecording(val file: ByteArray, private val reportedTotal: Long? = file.size.toLong()) : RangeSource {
        val requests = mutableListOf<Pair<Long, Long>>()
        private var pos = 0L
        private var end = 0L
        var closed = 0

        override fun open(position: Long, length: Long) {
            requests += position to length
            pos = position
            end = position + length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= end || pos >= file.size) return -1
            val n = minOf(length.toLong(), end - pos, file.size - pos, 100L).toInt() // short reads, like a socket
            System.arraycopy(file, pos.toInt(), buffer, offset, n)
            pos += n
            return n
        }

        override fun totalBytes(): Long? = reportedTotal
        override fun close() { closed++ }
    }

    private fun verse(): Triple<ByteArray, VerseClip, ByteArray> {
        val (slice, lead) = threeLeadFramesAndOne()
        val before = ByteArray(5000) { 7 }
        val after = ByteArray(300) { 9 }
        val file = before + slice + after
        val clip = VerseClip("https://example.invalid/002.mp3", file.size.toLong(), 5000, 5000L + slice.size, lead, 3 * 1152)
        // Build the expected served bytes the plain way: the gapless tag, then a
        // copy of the range with its lead silenced.
        val body = slice.copyOf()
        val head = slice.copyOf(lead).also { Mp3LeadSilencer.silence(it, lead) }
        System.arraycopy(head, 0, body, 0, lead)
        return Triple(file, clip, gaplessHeaderFor(clip) + body)
    }

    /** Bytes of the gapless tag ahead of every verse's range. */
    private val H = gaplessId3Tag(0, 0).size

    @Test fun theGaplessTagTrimsTheLeadAndTheDecoderPadding() {
        val clip = VerseClip("https://example.invalid/002.mp3", 1, 0, 1, 0, 2 * 1152)
        val tag = String(gaplessHeaderFor(clip), Charsets.ISO_8859_1)
        assertTrue(tag.startsWith("ID3\u0003"))
        assertTrue(tag.contains("COMM"))
        // Delay in decoder output: the platform decoder already withheld 529 of the 2304.
        assertTrue(tag, tag.endsWith("iTunSMPB\u0000 00000000 000006EF 00000211 0000000000000000"))
        assertEquals(H, tag.length)
    }

    @Test fun aRequestInsideTheTagServesItThenTheVerse() {
        val (file, clip, served) = verse()
        val source = FakeRecording(file)
        val stream = VerseClipStream(source, clip)
        assertEquals(served.size - 5L, stream.open(5, -1))
        assertArrayEquals(served.copyOfRange(5, served.size), readAll(stream))
        assertEquals(listOf(5000L to clip.length), source.requests)
    }

    @Test fun aRequestForTheTagAloneTouchesNoRecording() {
        val (file, clip, served) = verse()
        val source = FakeRecording(file)
        val stream = VerseClipStream(source, clip)
        assertEquals(H.toLong(), stream.open(0, H.toLong()))
        assertArrayEquals(served.copyOf(H), readAll(stream))
        assertTrue(source.requests.isEmpty())
    }

    private fun readAll(stream: VerseClipStream, bufferSize: Int = 64): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        while (true) {
            val n = stream.read(buffer, 0, buffer.size)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    @Test fun wholeVerseIsItsRangeWithTheLeadSilenced() {
        val (file, clip, served) = verse()
        val source = FakeRecording(file)
        val stream = VerseClipStream(source, clip)
        assertEquals(served.size.toLong(), stream.open(0, -1))
        assertArrayEquals(served, readAll(stream))
        assertEquals(listOf(5000L to clip.length), source.requests)
        stream.close()
        assertEquals(1, source.closed)
    }

    @Test fun aRequestInsideTheLeadStartsFromTheFirstFrameAndServesTheSilencedBytes() {
        val (file, clip, served) = verse()
        val source = FakeRecording(file)
        val stream = VerseClipStream(source, clip)
        val at = H + 300
        assertEquals(served.size - at.toLong(), stream.open(at.toLong(), -1))
        assertArrayEquals(served.copyOfRange(at, served.size), readAll(stream))
        assertEquals(5000L, source.requests.single().first)
    }

    @Test fun aRequestPastTheLeadFetchesOnlyWhatItAsksFor() {
        val (file, clip, served) = verse()
        val source = FakeRecording(file)
        val stream = VerseClipStream(source, clip)
        assertEquals(200, stream.open(H + 1300L, 200))
        assertArrayEquals(served.copyOfRange(H + 1300, H + 1500), readAll(stream))
        assertEquals(listOf(6300L to 200L), source.requests)
    }

    @Test fun aShortRequestInsideTheLeadStillFetchesWholeLeadFramesAndServesJustWhatWasAsked() {
        val (file, clip, served) = verse()
        val source = FakeRecording(file)
        val stream = VerseClipStream(source, clip)
        assertEquals(50, stream.open(H + 10L, 50))
        assertArrayEquals(served.copyOfRange(H + 10, H + 60), readAll(stream))
        assertEquals(listOf(5000L to clip.leadBytes.toLong()), source.requests)
    }

    @Test fun anOpenThatOutrunsTheVerseIsClampedToItsEnd() {
        val (file, clip, served) = verse()
        val stream = VerseClipStream(FakeRecording(file), clip)
        assertEquals(10, stream.open(stream.length - 10, 1000))
        assertArrayEquals(served.copyOfRange(served.size - 10, served.size), readAll(stream))
        assertEquals(0, stream.open(stream.length, -1))
        assertEquals(-1, stream.read(ByteArray(8), 0, 8))
    }

    @Test fun aRecordingOfAnotherSizeIsRefusedBeforeAnyAudioIsServed() {
        val (file, clip, _) = verse()
        val wrong = VerseClipStream(FakeRecording(file, reportedTotal = file.size + 1L), clip)
        assertThrows(IOException::class.java) { wrong.open(0, -1) }
        val unknown = VerseClipStream(FakeRecording(file, reportedTotal = null), clip)
        assertThrows(IOException::class.java) { unknown.open(0, -1) }
    }

    @Test fun positionsOutsideTheVerseAreErrors() {
        val (file, clip, _) = verse()
        val stream = VerseClipStream(FakeRecording(file), clip)
        assertThrows(IOException::class.java) { stream.open(-1, -1) }
        assertThrows(IOException::class.java) { stream.open(stream.length + 1, -1) }
    }

    @Test fun aTruncatedRecordingFailsRatherThanEndingEarly() {
        val (file, clip, _) = verse()
        val short = file.copyOf((clip.endByte - 100).toInt())
        val stream = VerseClipStream(FakeRecording(short, reportedTotal = file.size.toLong()), clip)
        stream.open(0, -1)
        assertThrows(java.io.EOFException::class.java) { readAll(stream) }
    }

    @Test fun verseClipUrlsParseAndEveryAyahOnesDoNot() {
        val ref = parseVerseClipUrl("https://verse-clips.invalid/data/Tawfeek_As-Sawaigh_128kbps/002255.mp3")!!
        assertEquals(Triple("Tawfeek_As-Sawaigh_128kbps", 2, 255), Triple(ref.slug, ref.surah, ref.ayah))
        assertNull(parseVerseClipUrl("https://everyayah.com/data/Alafasy_128kbps/002255.mp3"))
        assertNull(parseVerseClipUrl("https://verse-clips.invalid/data/x/2255.mp3"))
    }

    @Test fun contentRangeTotalIsReadFromTheHeader() {
        assertEquals(110510228L, parseContentRangeTotal("bytes 0-1023/110510228"))
        assertNull(parseContentRangeTotal("bytes 0-1023/*"))
        assertNull(parseContentRangeTotal(null))
        assertNull(parseContentRangeTotal("garbage"))
    }

    @Test fun aRangeSourcedReciterHasPerVerseUrlsOnTheVerseClipHostAndOthersStayOnEveryAyah() {
        val range = Reciter(20, "Tawfeek_As-Sawaigh_128kbps", "Tawfeek As-Sawaigh", "Murattal", true, rangeSourced = true)
        val every = Reciter(1, "Alafasy_128kbps", "Mishary", "Murattal", true)
        assertEquals("https://verse-clips.invalid/data/Tawfeek_As-Sawaigh_128kbps/002005.mp3", range.audioUrl(2, 5))
        assertEquals("https://everyayah.com/data/Alafasy_128kbps/002005.mp3", every.audioUrl(2, 5))
        assertTrue(parseEveryayahUri(range.audioUrl(114, 6))?.let { it.slug == range.slug && it.surah == 114 && it.ayah == 6 } == true)
        assertEquals(range.audioUrl(1, 1), range.basmalahAudioUrl())
    }

    @Test
    fun `stale cache clean-up only touches the reciter's own verse urls`() {
        val keys = listOf(
            "https://verse-clips.invalid/data/Tawfeek_As-Sawaigh_128kbps/109003.mp3",
            "https://verse-clips.invalid/data/Other_128kbps/109003.mp3",
            "https://everyayah.com/data/Tawfeek_As-Sawaigh_128kbps/109003.mp3",
        )
        assertEquals(
            listOf(keys[0]),
            com.beautifulquran.data.verseClipKeys(keys, "Tawfeek_As-Sawaigh_128kbps"),
        )
    }
}
