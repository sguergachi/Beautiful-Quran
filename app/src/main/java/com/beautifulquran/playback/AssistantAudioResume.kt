package com.beautifulquran.playback

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/**
 * Restores recitation after Assistant builds that report their brief speech as
 * a permanent audio-focus loss. Media3 already handles transient loss itself;
 * this only arms on its explicit audio-focus-loss pause reason and only resumes
 * after a real [AudioAttributes.USAGE_ASSISTANT] player has gone quiet.
 */
internal class AssistantAudioResume(
    private val player: Player,
    private val audioManager: AudioManager,
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val interruption = AssistantInterruptionTracker()
    private val expireFocusLoss = Runnable { interruption.expireUnconfirmedFocusLoss() }
    private val resume = Runnable {
        if (
            interruption.takeResume() &&
            !player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        ) {
            player.play()
        }
    }
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<android.media.AudioPlaybackConfiguration>) {
            val assistantActive = configs.any {
                it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANT
            }
            interruption.onAssistantPlaybackChanged(assistantActive)
            handler.removeCallbacks(resume)
            if (!assistantActive && interruption.canResume) {
                handler.postDelayed(resume, ASSISTANT_END_DEBOUNCE_MS)
            }
        }
    }

    init {
        player.addListener(this)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
            interruption.onFocusLost()
            handler.removeCallbacks(expireFocusLoss)
            handler.postDelayed(expireFocusLoss, ASSISTANT_MATCH_WINDOW_MS)
        } else {
            cancel()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) cancel()
    }

    fun release() {
        cancel()
        player.removeListener(this)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
    }

    private fun cancel() {
        interruption.cancel()
        handler.removeCallbacks(expireFocusLoss)
        handler.removeCallbacks(resume)
    }

    private companion object {
        const val ASSISTANT_MATCH_WINDOW_MS = 5_000L
        const val ASSISTANT_END_DEBOUNCE_MS = 750L
    }
}

/** Pure state behind [AssistantAudioResume], kept separate for JVM coverage. */
internal class AssistantInterruptionTracker {
    private var focusLost = false
    private var assistantActive = false
    private var confirmed = false

    val canResume: Boolean
        get() = confirmed && !assistantActive

    fun onFocusLost() {
        focusLost = true
        if (assistantActive) confirmed = true
    }

    fun onAssistantPlaybackChanged(active: Boolean) {
        assistantActive = active
        if (active && focusLost) confirmed = true
    }

    fun expireUnconfirmedFocusLoss() {
        if (!confirmed) focusLost = false
    }

    fun takeResume(): Boolean = canResume.also { shouldResume ->
        if (shouldResume) cancel()
    }

    fun cancel() {
        focusLost = false
        assistantActive = false
        confirmed = false
    }
}
