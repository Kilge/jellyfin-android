package org.jellyfin.mobile.webapp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelIterator

/**
 * Allows to call functions within the webapp
 */
class WebappFunctionChannel {
    private val internalChannel = Channel<String>()

    /**
     * Whether the web player currently has an active player (set by WebViewFragment polling).
     */
    @Volatile
    var webPlayerActive: Boolean = false
        private set

    operator fun iterator(): ChannelIterator<String> = internalChannel.iterator()

    fun call(action: String) = internalChannel.trySend(action).isSuccess

    // Web component helpers
    fun callPlaybackManagerAction(action: String) = call("$PLAYBACK_MANAGER.$action();")
    fun setVolume(volume: Int) = call(
        "$PLAYBACK_MANAGER.sendCommand({" +
            "Name: 'SetVolume', Arguments: { Volume: $volume }" +
            "});",
    )

    fun seekTo(pos: Long) = call("$PLAYBACK_MANAGER.seekMs($pos);")
    fun goBack() = call("$NAVIGATION_HELPER.goBack();")

    /**
     * Invokes a playback manager command through the injected [NativeShell] wrapper, which
     * guards against the web app not being ready or no player being active.
     */
    fun playbackCommand(action: String) = call("$NATIVE_SHELL.playbackCommand('$action');")

    /**
     * Updates [webPlayerActive] with the result of a playback state poll.
     */
    fun updateWebPlayerActive(active: Boolean) {
        webPlayerActive = active
    }

    companion object {
        private const val NAVIGATION_HELPER = "window.NavigationHelper"
        private const val PLAYBACK_MANAGER = "$NAVIGATION_HELPER.playbackManager"
        private const val NATIVE_SHELL = "window.NativeShell"
    }
}
