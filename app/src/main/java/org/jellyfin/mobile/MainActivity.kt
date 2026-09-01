package org.jellyfin.mobile

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.cast.Chromecast
import org.jellyfin.mobile.player.cast.IChromecast
import org.jellyfin.mobile.player.ui.PlayerFragment
import org.jellyfin.mobile.setup.ConnectFragment
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.BluetoothPermissionHelper
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.PermissionRequestHelper
import org.jellyfin.mobile.utils.SmartOrientationListener
import org.jellyfin.mobile.utils.extensions.replaceFragment
import org.jellyfin.mobile.utils.isWebViewSupported
import org.jellyfin.mobile.webapp.RemotePlayerService
import org.jellyfin.mobile.webapp.WebViewFragment
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.fragment.android.setupKoinFragmentFactory
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

@Suppress("TooManyFunctions")
class MainActivity : AppCompatActivity() {
    private val activityEventHandler: ActivityEventHandler = get()
    val mainViewModel: MainViewModel by viewModel()
    val bluetoothPermissionHelper: BluetoothPermissionHelper = BluetoothPermissionHelper(this, get())
    val chromecast: IChromecast = Chromecast()
    private val permissionRequestHelper: PermissionRequestHelper by inject()
    private val webappFunctionChannel: WebappFunctionChannel by inject()
    private val appPreferences: AppPreferences by inject()

    var serviceBinder: RemotePlayerService.ServiceBinder? = null
        private set
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            serviceBinder = binder as? RemotePlayerService.ServiceBinder
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            serviceBinder = null
        }
    }

    private val orientationListener: OrientationEventListener by lazy { SmartOrientationListener(this) }

    /**
     * Passes back press events onto the currently visible [Fragment] if it implements the [BackPressInterceptor] interface.
     *
     * If the current fragment does not implement [BackPressInterceptor] or has decided not to intercept the event
     * (see result of [BackPressInterceptor.onInterceptBackPressed]), the topmost backstack entry will be popped.
     *
     * If there is no topmost backstack entry, the event will be passed onto the dispatcher's fallback handler.
     */
    private val onBackPressedCallback: OnBackPressedCallback.() -> Unit = callback@{
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentFragment is BackPressInterceptor && currentFragment.onInterceptBackPressed()) {
            // Top fragment handled back press
            return@callback
        }

        // This is the same default action as in Activity.onBackPressed
        if (!supportFragmentManager.isStateSaved && supportFragmentManager.popBackStackImmediate()) {
            // Removed fragment from back stack
            return@callback
        }

        // Let the system handle the back press
        isEnabled = false
        // Make sure that we *really* call the fallback handler
        assert(!onBackPressedDispatcher.hasEnabledCallbacks()) {
            "MainActivity should be the lowest onBackPressCallback"
        }
        onBackPressedDispatcher.onBackPressed()
        isEnabled = true // re-enable callback in case activity isn't finished
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        setupKoinFragmentFactory()
        appliedNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        // Persist the startup value; it is fresh at this point (no recreation).
        appPreferences.systemDarkTheme = appliedNightMode == Configuration.UI_MODE_NIGHT_YES
        applyEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Check WebView support
        if (!isWebViewSupported()) {
            AlertDialog.Builder(this).apply {
                setTitle(R.string.dialog_web_view_not_supported)
                setMessage(R.string.dialog_web_view_not_supported_message)
                setCancelable(false)
                if (AndroidVersion.isAtLeastN) {
                    setNeutralButton(R.string.dialog_button_open_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_WEBVIEW_SETTINGS))
                        Toast.makeText(context, R.string.toast_reopen_after_change, Toast.LENGTH_LONG).show()
                        finishAfterTransition()
                    }
                }
                setNegativeButton(R.string.dialog_button_close_app) { _, _ ->
                    finishAfterTransition()
                }
            }.show()
            return
        }

        // Bind player service
        bindService(Intent(this, RemotePlayerService::class.java), serviceConnection, Service.BIND_AUTO_CREATE)

        // Subscribe to activity events
        with(activityEventHandler) { subscribe() }

        // Load UI
        lifecycleScope.launch {
            mainViewModel.serverState.collectLatest { state ->
                lifecycle.withStarted {
                    handleServerState(state)
                }
            }
        }

        // Handle back presses
        onBackPressedDispatcher.addCallback(this, onBackPressed = onBackPressedCallback)

        // Setup Chromecast
        chromecast.initializePlugin(this)
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onResume() {
        super.onResume()
        applySystemBarAppearance()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val darkTheme = nightMode == Configuration.UI_MODE_NIGHT_YES
        // Persist the fresh value from the system callback; resources.configuration
        // can lag behind after recreate on some ROMs.
        appPreferences.systemDarkTheme = darkTheme
        if (appliedNightMode != -1 && appliedNightMode != nightMode) {
            // Recreate to apply the DayNight theme resources. uiMode is NOT in
            // configChanges, so the system relaunches the activity on theme
            // changes; the WebViewFragment always loads fresh (no restoreState),
            // so no reload loop and no connect-page kickback on fast toggles.
            recreate()
            return
        }
        appliedNightMode = nightMode
        applySystemBarAppearance()
    }

    /**
     * Updates the status bar icon appearance based on the current theme. The activity handles
     * uiMode changes itself (see manifest configChanges), so the icons need to be updated
     * manually here. Both the compat API and the legacy flag are applied, the latter last,
     * because some OEM ROMs only honor the legacy flag.
     */
    fun applySystemBarAppearance() {
        val darkTheme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        applySystemBarAppearance(darkTheme)
    }

    private fun applySystemBarAppearance(darkTheme: Boolean) {
        // Compat API (API 30+).
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
        // Legacy flags — some OEM ROMs (e.g. Huawei/Honor) only honor these for
        // status/navigation bar icon tinting. Applied last so they win on those ROMs.
        var legacyFlags = window.decorView.systemUiVisibility
        legacyFlags = if (darkTheme) {
            legacyFlags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            legacyFlags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = legacyFlags
    }

    /**
     * Applies edge-to-edge with auto system bar styles. The activity declares uiMode in
     * configChanges, so it is not recreated on theme changes and the styles need to be
     * re-applied to keep the status bar icon appearance in sync.
     */
    private fun applyEdgeToEdge() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
    }

    /**
     * Fallback media button handling for web player playback.
     *
     * Media buttons (KEYCODE_MEDIA_*) from Bluetooth remotes and wired headsets are routed by
     * the system to the active media session. Gamepad style keys (KEYCODE_BUTTON_*) and DPAD
     * keys from Bluetooth remotes that act as a gamepad are dispatched to the focused activity
     * instead and handled here.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        Timber.d(
            "dispatchKeyEvent: key=%d action=%d repeat=%d",
            keyCode,
            event.action,
            event.repeatCount,
        )
        // Standard DPAD up/down keys adjust volume
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) adjustVolume(direction = 1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) adjustVolume(direction = -1)
                return true
            }
        }
        val seekCommand = SEEK_COMMAND_BY_KEYCODE[keyCode]
        val mediaCommand = if (seekCommand == null) MEDIA_COMMAND_BY_KEYCODE[keyCode] else null
        if (seekCommand == null && mediaCommand == null) return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN &&
            event.eventTime - lastMediaButtonTime >= MEDIA_BUTTON_REPEAT_INTERVAL_MS
        ) {
            lastMediaButtonTime = event.eventTime
            if (!handleMediaButton(seekCommand, mediaCommand)) return super.dispatchKeyEvent(event)
        }
        return true
    }

    /**
     * Handles Bluetooth remotes that report their direction controls as relative axes on a
     * pointer/mouse device (e.g. Magicsee R1 sends AXIS_X/AXIS_Y on source SOURCE_MOUSE):
     * - X axis: left/right -> rewind / fast forward
     * - Y axis: up/down -> volume down / volume up
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        Timber.d(
            "onGenericMotionEvent: action=%d source=0x%x device=%d " +
                "x=%.2f y=%.2f vscroll=%.2f hscroll=%.2f hatX=%.2f hatY=%.2f",
            event.actionMasked,
            event.source,
            event.deviceId,
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y),
            event.getAxisValue(MotionEvent.AXIS_VSCROLL),
            event.getAxisValue(MotionEvent.AXIS_HSCROLL),
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
        val supportedSources = InputDevice.SOURCE_JOYSTICK or
            InputDevice.SOURCE_GAMEPAD or
            InputDevice.SOURCE_MOUSE
        if (event.actionMasked != MotionEvent.ACTION_MOVE || event.source and supportedSources == 0) {
            return super.onGenericMotionEvent(event)
        }
        // Only handle axis deltas (pointer devices report relative y/x on AXIS_Y/AXIS_X)
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        if (x == 0f && y == 0f) return super.onGenericMotionEvent(event)

        val time = event.eventTime
        if (time - lastJoystickTime < JOYSTICK_REPEAT_INTERVAL_MS) return true
        lastJoystickTime = time

        val handled = when {
            // Horizontal: seek
            x > JOYSTICK_AXIS_THRESHOLD -> {
                handleMediaButton(Constants.PLAYBACK_MANAGER_COMMAND_FAST_FORWARD, null)
                true
            }

            x < -JOYSTICK_AXIS_THRESHOLD -> {
                handleMediaButton(Constants.PLAYBACK_MANAGER_COMMAND_REWIND, null)
                true
            }

            // Vertical: volume
            y < -JOYSTICK_AXIS_THRESHOLD -> {
                adjustVolume(direction = 1)
                true
            }

            y > JOYSTICK_AXIS_THRESHOLD -> {
                adjustVolume(direction = -1)
                true
            }

            else -> false
        }
        return if (handled) true else super.onGenericMotionEvent(event)
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        Timber.d("adjustVolume: direction=%d", direction)
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    /**
     * Routes a media/seek key to the active player.
     *
     * @return true if the key was consumed
     */
    private fun handleMediaButton(seekCommand: String?, mediaCommand: String?): Boolean {
        // Route to the native player when it is showing
        val playerFragment = supportFragmentManager.fragments.filterIsInstance<PlayerFragment>()
            .firstOrNull { it.isVisible }
        if (playerFragment != null) {
            if (seekCommand == Constants.PLAYBACK_MANAGER_COMMAND_FAST_FORWARD) {
                playerFragment.onFastForward()
            } else if (seekCommand == Constants.PLAYBACK_MANAGER_COMMAND_REWIND) {
                playerFragment.onRewind()
            }
            return true
        }
        // Forward to the web player when it is active
        if (webappFunctionChannel.webPlayerActive) {
            webappFunctionChannel.playbackCommand(seekCommand ?: mediaCommand ?: return true)
            return true
        }
        // No player active: only consume media keys, let seek keys fall through
        return mediaCommand != null
    }

    private fun handleServerState(state: ServerState) {
        with(supportFragmentManager) {
            val currentFragment = findFragmentById(R.id.fragment_container)
            when (state) {
                ServerState.Pending -> {
                    // TODO add loading indicator
                }
                is ServerState.Unset -> {
                    if (currentFragment !is ConnectFragment) {
                        replaceFragment<ConnectFragment>()
                    }
                }
                is ServerState.Available -> {
                    if (currentFragment !is WebViewFragment || currentFragment.server != state.server) {
                        replaceFragment<WebViewFragment>(
                            Bundle().apply {
                                putParcelable(Constants.FRAGMENT_WEB_VIEW_EXTRA_SERVER, state.server)
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        permissionRequestHelper.handleRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        for (fragment in supportFragmentManager.fragments) {
            if (fragment is PlayerFragment && fragment.isVisible) {
                fragment.onUserLeaveHint()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    override fun onDestroy() {
        unbindService(serviceConnection)
        chromecast.destroy()
        super.onDestroy()
    }

    companion object {
        private const val MEDIA_BUTTON_REPEAT_INTERVAL_MS = 300L
        private const val JOYSTICK_REPEAT_INTERVAL_MS = 400L
        private const val JOYSTICK_AXIS_THRESHOLD = 0.5f

        /**
         * Keys that seek back/forward. Gamepad style keys (KEYCODE_BUTTON_*) and DPAD keys are
         * used by Bluetooth remotes that act as a gamepad, SKIP keys are sent by some remotes
         * instead of the dedicated FAST_FORWARD/REWIND media keys.
         */
        private val SEEK_COMMAND_BY_KEYCODE = mapOf<Int, String>(
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to Constants.PLAYBACK_MANAGER_COMMAND_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD to Constants.PLAYBACK_MANAGER_COMMAND_FAST_FORWARD,
            KeyEvent.KEYCODE_BUTTON_R1 to Constants.PLAYBACK_MANAGER_COMMAND_FAST_FORWARD,
            KeyEvent.KEYCODE_BUTTON_R2 to Constants.PLAYBACK_MANAGER_COMMAND_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_RIGHT to Constants.PLAYBACK_MANAGER_COMMAND_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND to Constants.PLAYBACK_MANAGER_COMMAND_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD to Constants.PLAYBACK_MANAGER_COMMAND_REWIND,
            KeyEvent.KEYCODE_BUTTON_L1 to Constants.PLAYBACK_MANAGER_COMMAND_REWIND,
            KeyEvent.KEYCODE_BUTTON_L2 to Constants.PLAYBACK_MANAGER_COMMAND_REWIND,
            KeyEvent.KEYCODE_DPAD_LEFT to Constants.PLAYBACK_MANAGER_COMMAND_REWIND,
        )

        private val MEDIA_COMMAND_BY_KEYCODE = mapOf(
            KeyEvent.KEYCODE_MEDIA_PLAY to Constants.PLAYBACK_MANAGER_COMMAND_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE to Constants.PLAYBACK_MANAGER_COMMAND_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK to Constants.PLAYBACK_MANAGER_COMMAND_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to Constants.PLAYBACK_MANAGER_COMMAND_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT to Constants.PLAYBACK_MANAGER_COMMAND_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to Constants.PLAYBACK_MANAGER_COMMAND_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP to Constants.PLAYBACK_MANAGER_COMMAND_STOP,
        )
    }

    private var lastMediaButtonTime = 0L
    private var lastJoystickTime = 0L
    private var appliedNightMode = -1
}
