package org.jellyfin.mobile.player.ui

import android.view.View
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.extensions.hasFlag

class PlayerFullscreenHelper(private val window: Window) {
    private val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
    var isFullscreen: Boolean = false
        private set

    fun onWindowInsetsChanged(insets: WindowInsetsCompat) {
        isFullscreen = when {
            AndroidVersion.isAtLeastR -> {
                // Type.systemBars() doesn't work here because this would also check for the navigation bar
                // which doesn't exist on all devices
                !insets.isVisible(WindowInsetsCompat.Type.statusBars())
            }
            else -> {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility.hasFlag(View.SYSTEM_UI_FLAG_FULLSCREEN)
            }
        }
    }

    fun enableFullscreen() {
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Light icons on the always-dark player UI
        forceLightIcons()
    }

    /**
     * Forces light status bar icons regardless of fullscreen state. The player UI is
     * always dark, so icons must not follow the system theme while it is visible.
     */
    fun forceLightIcons() {
        windowInsetsController.isAppearanceLightStatusBars = false
        windowInsetsController.isAppearanceLightNavigationBars = false
    }

    fun disableFullscreen() {
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        // Restore icons based on the current theme (dark theme -> light icons)
        val darkTheme = window.context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        setSystemBarIconsByTheme(darkTheme)
    }

    private fun setSystemBarIconsByTheme(darkTheme: Boolean) {
        windowInsetsController.isAppearanceLightStatusBars = !darkTheme
        windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
    }

    fun toggleFullscreen() {
        if (isFullscreen) disableFullscreen() else enableFullscreen()
    }
}