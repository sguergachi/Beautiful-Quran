package com.beautifulquran.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beautifulquran.QuranApp
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays range-sourced verses back to back through the real player, decoder and
 * seam processing, and writes the PCM the sink was handed to
 * `files/seam-capture/<label>.pcm` (16-bit, interleaved) with a `.txt` of where each
 * file's PCM began. `tools/check_verse_seams.py` lines it up against the surah
 * recording: every seam must continue the recording, with no hole and nothing
 * heard twice.
 *
 * Needs the network (the recordings stream from their public host).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VerseClipSeamCaptureTest {

    private class Capture : BaseAudioProcessor() {
        val pcm = ByteArrayOutputStream()
        val marks = StringBuilder()
        var format: AudioProcessor.AudioFormat? = null

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            format = inputAudioFormat
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val bytes = ByteArray(inputBuffer.remaining())
            inputBuffer.get(bytes)
            synchronized(this) { pcm.write(bytes) }
            replaceOutputBuffer(bytes.size).put(bytes).flip()
        }

        override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
            synchronized(this) { marks.append("flush ").append(pcm.size()).append('\n') }
        }
    }

    @Test
    fun captureAlKafirun() = capture("109", surah = 109, ayahs = 1..6)

    @Test
    fun captureAlIkhlas() = capture("112", surah = 112, ayahs = 1..4)

    private fun capture(label: String, surah: Int, ayahs: IntRange) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as QuranApp
        val reciter = kotlinx.coroutines.runBlocking { app.repository.reciters() }.first { it.rangeSourced }
        val capture = Capture()
        val seams = VerseSeamAudioProcessor().also { it.sampleExactSeams = true }
        val done = CountDownLatch(1)
        var player: ExoPlayer? = null
        val main = Handler(Looper.getMainLooper())
        main.post {
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(seams, capture))
                    .build()
            }
            val upstream = VerseClipDataSource.Factory(DefaultHttpDataSource.Factory(), app.repository.verseClips)
            player = ExoPlayer.Builder(context, renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(upstream))
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                    false,
                )
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) done.countDown()
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            synchronized(capture) {
                                capture.marks.append("item ").append(mediaItem?.mediaId).append(' ')
                                    .append(capture.pcm.size()).append('\n')
                            }
                        }
                    })
                    setMediaItems(ayahs.map { MediaItem.Builder().setMediaId("$surah:$it").setUri(reciter.audioUrl(surah, it)).build() })
                    volume = 0f
                    prepare()
                    play()
                }
        }
        val finished = done.await(240, TimeUnit.SECONDS)
        main.post { player?.release() }
        val dir = File(context.filesDir, "seam-capture").also { it.mkdirs() }
        synchronized(capture) {
            File(dir, "$label.pcm").writeBytes(capture.pcm.toByteArray())
            File(dir, "$label.txt").writeText(
                "format ${capture.format}\nslug ${reciter.slug}\nsurah $surah\nayahs ${ayahs.first} ${ayahs.last}\n" + capture.marks,
            )
        }
        assertTrue("playback did not end", finished)
    }
}
