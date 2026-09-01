package org.jellyfin.mobile.player.interaction

import android.annotation.SuppressLint
import android.content.Intent
import android.media.session.MediaSession
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import org.jellyfin.mobile.player.PlayerViewModel

@SuppressLint("MissingOnPlayFromSearch")
class PlayerMediaSessionCallback(private val viewModel: PlayerViewModel) : MediaSession.Callback() {
    override fun onPlay() {
        viewModel.play()
    }

    override fun onPause() {
        viewModel.pause()
    }

    override fun onSeekTo(pos: Long) {
        viewModel.playerOrNull?.seekTo(pos)
    }

    override fun onRewind() {
        viewModel.rewind()
    }

    override fun onFastForward() {
        viewModel.fastForward()
    }

    override fun onSkipToPrevious() {
        viewModel.skipToPrevious()
    }

    override fun onSkipToNext() {
        viewModel.skipToNext()
    }

    override fun onStop() {
        viewModel.stop()
    }

    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        val keyEvent = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            ?: return super.onMediaButtonEvent(mediaButtonIntent)

        if (keyEvent.action != KeyEvent.ACTION_DOWN || keyEvent.repeatCount != 0) {
            return super.onMediaButtonEvent(mediaButtonIntent)
        }
        return when (keyEvent.keyCode) {
            // Some remotes send the SKIP keys instead of the dedicated seek keys
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                viewModel.fastForward()
                true
            }

            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                viewModel.rewind()
                true
            }

            else -> super.onMediaButtonEvent(mediaButtonIntent)
        }
    }
}
