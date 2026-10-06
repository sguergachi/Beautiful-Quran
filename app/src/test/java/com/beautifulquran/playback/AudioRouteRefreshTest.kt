package com.beautifulquran.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class AudioRouteRefreshTest {
    @Test
    fun `Bluetooth disconnect releases the old clock without a seek or play command`() = runBlocking {
        for (state in listOf(Player.STATE_READY, Player.STATE_BUFFERING)) {
            val commands = mutableListOf<String>()
            val route = MutableStateFlow<Int?>(2)
            val watch = launch(start = CoroutineStart.UNDISPATCHED) {
                refreshAudioOutputOnRouteChange(recordingPlayer(state, commands), route)
            }
            assertEquals(emptyList<String>(), commands)

            route.value = 1
            yield()
            assertEquals(listOf("stop", "prepare"), commands)

            watch.cancelAndJoin()
            route.value = 2
            yield()
            assertEquals(listOf("stop", "prepare"), commands)
        }
    }

    @Test
    fun `speaker Bluetooth and LE transitions each receive a fresh output`() = runBlocking {
        val commands = mutableListOf<String>()
        val route = MutableStateFlow<Int?>(1)
        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
            refreshAudioOutputOnRouteChange(recordingPlayer(Player.STATE_READY, commands), route)
        }

        for (deviceId in listOf(3, 2, 1)) {
            route.value = deviceId
            yield()
        }
        assertEquals(List(3) { listOf("stop", "prepare") }.flatten(), commands)

        route.value = 1
        yield()
        assertEquals(6, commands.size)
        watch.cancelAndJoin()
    }

    @Test
    fun `a route change never starts an idle or completed playlist`() = runBlocking {
        for (state in listOf(Player.STATE_IDLE, Player.STATE_ENDED)) {
            val commands = mutableListOf<String>()
            val route = MutableStateFlow<Int?>(2)
            val watch = launch(start = CoroutineStart.UNDISPATCHED) {
                refreshAudioOutputOnRouteChange(recordingPlayer(state, commands), route)
            }

            route.value = 1
            yield()
            assertEquals(emptyList<String>(), commands)
            watch.cancelAndJoin()
        }
    }

    @Test
    fun `initial route discovery never restarts playback but changing connected outputs does`() = runBlocking {
        val commands = mutableListOf<String>()
        val route = MutableStateFlow<Int?>(null)
        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
            refreshAudioOutputOnRouteChange(recordingPlayer(Player.STATE_READY, commands), route)
        }
        route.value = 2 // Actual initial Bluetooth output; no capacity/preset warmup.
        yield()
        assertEquals(emptyList<String>(), commands)
        route.value = 1 // Headset stays connected, but AudioTrack switches to speaker.
        yield()
        assertEquals(listOf("stop", "prepare"), commands)
        route.value = 4 // Another speaker/USB device: equal latency kinds still refresh.
        yield()
        assertEquals(listOf("stop", "prepare", "stop", "prepare"), commands)
        route.value = null // Not playing: absence of a route is not a switch.
        yield()
        assertEquals(4, commands.size)
        watch.cancelAndJoin()
    }

    /** Any seek, play, or pause call fails: a route change must keep the listener's place and intent. */
    private fun recordingPlayer(state: Int, commands: MutableList<String>): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> state
                "stop", "prepare" -> {
                    commands += method.name
                    null
                }
                else -> error("Unexpected player command: ${method.name}")
            }
        } as Player
}
