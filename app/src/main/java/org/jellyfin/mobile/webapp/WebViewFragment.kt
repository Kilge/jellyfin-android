package org.jellyfin.mobile.webapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.IntentCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.bridge.ExternalPlayer
import org.jellyfin.mobile.bridge.MediaSegments
import org.jellyfin.mobile.bridge.NativeInterface
import org.jellyfin.mobile.bridge.NativePlayer
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.databinding.FragmentWebviewBinding
import org.jellyfin.mobile.setup.ConnectFragment
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.FRAGMENT_WEB_VIEW_EXTRA_SERVER
import org.jellyfin.mobile.utils.Constants.SUPPORTED_WEB_PLAYER_PLAYBACK_ACTIONS
import org.jellyfin.mobile.utils.applyDefault
import org.jellyfin.mobile.utils.applyWindowInsetsAsMargins
import org.jellyfin.mobile.utils.dip
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.jellyfin.mobile.utils.extensions.replaceFragment
import org.jellyfin.mobile.utils.fadeIn
import org.jellyfin.mobile.utils.isOutdated
import org.jellyfin.mobile.utils.requestNoBatteryOptimizations
import org.jellyfin.mobile.utils.runOnUiThread
import org.jellyfin.mobile.utils.setPlaybackState
import org.json.JSONObject
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import timber.log.Timber

@Suppress("TooManyFunctions")
class WebViewFragment : Fragment(), BackPressInterceptor, JellyfinWebChromeClient.FileChooserListener {
    val appPreferences: AppPreferences by inject()
    private val mainViewModel: MainViewModel by activityViewModel()
    private val webappFunctionChannel: WebappFunctionChannel by inject()
    private lateinit var assetsPathHandler: AssetsPathHandler
    private lateinit var jellyfinWebViewClient: JellyfinWebViewClient
    private val nativePlayer: NativePlayer by inject()
    private lateinit var externalPlayer: ExternalPlayer
    private val mediaSegments: MediaSegments by inject()

    lateinit var server: ServerEntity
        private set
    private var connected = false
    private val timeoutRunnable = Runnable {
        handleError()
    }
    private val showLoadingContainerRunnable = Runnable {
        webViewBinding?.loadingContainer?.isVisible = true
    }

    /**
     * Media session for web player playback. The web player (video.js in the WebView) does not
     * create its own Android media session, so without an active session media buttons from
     * Bluetooth remotes and wired headsets would be dropped by the system. When active, all
     * media buttons are forwarded to the web player through the [webappFunctionChannel].
     */
    private var webPlayerMediaSession: MediaSession? = null
    private var lastMediaButtonTime = 0L
    private var webViewRestoreState: Bundle? = null

    // UI
    private var webViewBinding: FragmentWebviewBinding? = null

    // External file access
    private var fileChooserActivityLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileChooserCallback?.onReceiveValue(FileChooserParams.parseResult(result.resultCode, result.data))
    }
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webViewRestoreState = savedInstanceState
        server = requireNotNull(requireArguments().getParcelableCompat(FRAGMENT_WEB_VIEW_EXTRA_SERVER)) {
            "Server entity has not been supplied!"
        }

        assetsPathHandler = AssetsPathHandler(requireContext())
        jellyfinWebViewClient = object : JellyfinWebViewClient(
            lifecycleScope,
            server,
            assetsPathHandler,
            mainViewModel,
        ) {
            override fun onConnectedToWebapp() {
                val webViewBinding = webViewBinding ?: return
                val webView = webViewBinding.webView
                webView.removeCallbacks(timeoutRunnable)
                webView.removeCallbacks(showLoadingContainerRunnable)
                connected = true
                runOnUiThread {
                    webViewBinding.loadingContainer.isVisible = false
                    webView.fadeIn()
                }
                requestNoBatteryOptimizations(webViewBinding.root)
            }

            override fun onErrorReceived() {
                handleError()
            }
        }
        externalPlayer = ExternalPlayer(requireContext(), this, requireActivity().activityResultRegistry)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentWebviewBinding.inflate(inflater, container, false).also { binding ->
            webViewBinding = binding
        }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val webView = webViewBinding!!.webView

        // Apply window insets
        webView.applyWindowInsetsAsMargins()

        // Setup exclusion rects for gestures
        if (AndroidVersion.isAtLeastQ) {
            @Suppress("MagicNumber")
            webView.doOnNextLayout {
                // Maximum allowed exclusion rect height is 200dp,
                // offsetting 100dp from the center in both directions
                // uses the maximum available space
                val verticalCenter = webView.measuredHeight / 2
                val offset = webView.resources.dip(100)

                // Arbitrary, currently 2x minimum touch target size
                val exclusionWidth = webView.resources.dip(96)

                webView.systemGestureExclusionRects = listOf(
                    Rect(
                        0,
                        verticalCenter - offset,
                        exclusionWidth,
                        verticalCenter + offset,
                    ),
                )
            }
        }

        // Setup WebView
        webView.initialize()
        setupRemoteInputHandling(webView)

        webViewBinding!!.useDifferentServerButton.setOnClickListener {
            webView.removeCallbacks(timeoutRunnable)
            webView.stopLoading()
            webViewBinding!!.loadingContainer.isVisible = false
            onSelectServer(error = false)
        }

        // Process JS functions called from other components (e.g. the PlayerActivity)
        lifecycleScope.launch {
            for (function in webappFunctionChannel) {
                webView.evaluateJavascript(function, null)
            }
        }

        webPlayerMediaSession = MediaSession(requireContext(), "org.jellyfin.mobile.webapp.WebViewFragment").apply {
            setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
            setCallback(webPlayerMediaSessionCallback)
            setPlaybackState(
                isPlaying = false,
                position = PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                playbackActions = SUPPORTED_WEB_PLAYER_PLAYBACK_ACTIONS,
            )
        }

        // Poll the web player state to keep the media session and the activity's
        // media button routing in sync.
        pollWebPlayerState()
    }

    private fun pollWebPlayerState() {
        lifecycleScope.launch {
            while (isActive) {
                val currentWebView = webViewBinding?.webView ?: break
                currentWebView.evaluateJavascript(WEB_PLAYER_STATE_QUERY) { result ->
                    val state = parseWebPlayerState(result)
                    webappFunctionChannel.updateWebPlayerActive(state != null)
                    val session = webPlayerMediaSession ?: return@evaluateJavascript
                    if (state != null) {
                        session.setPlaybackState(state.playing, state.position, SUPPORTED_WEB_PLAYER_PLAYBACK_ACTIONS)
                    } else {
                        session.setPlaybackState(
                            isPlaying = false,
                            position = PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                            playbackActions = SUPPORTED_WEB_PLAYER_PLAYBACK_ACTIONS,
                        )
                    }
                }
                delay(WEB_PLAYER_STATE_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Intercepts directional input from Bluetooth remotes before the WebView consumes it.
     * DPAD keys and scroll axes are translated to volume (up/down) and seek (left/right).
     */
    private fun setupRemoteInputHandling(webView: WebView) {
        setupRemoteKeyHandling(webView)
        setupRemoteMotionHandling(webView)
    }

    private fun setupRemoteKeyHandling(webView: WebView) {
        webView.setOnKeyListener { _, keyCode, keyEvent ->
            Timber.d("webView key: code=%d action=%d", keyCode, keyEvent.action)
            if (keyEvent.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    adjustVolume(1)
                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    adjustVolume(-1)
                    true
                }

                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                -> {
                    seekFromWebKey(Constants.PLAYBACK_MANAGER_COMMAND_REWIND)
                    true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                -> {
                    seekFromWebKey(Constants.PLAYBACK_MANAGER_COMMAND_FAST_FORWARD)
                    true
                }

                else -> false
            }
        }
    }

    private fun setupRemoteMotionHandling(webView: WebView) {
        webView.setOnGenericMotionListener { _, motionEvent ->
            if (motionEvent.actionMasked != MotionEvent.ACTION_MOVE) return@setOnGenericMotionListener false
            val vscroll = motionEvent.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hscroll = motionEvent.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val y = motionEvent.getAxisValue(MotionEvent.AXIS_Y)
            val x = motionEvent.getAxisValue(MotionEvent.AXIS_X)
            Timber.d(
                "webView motion: source=0x%x x=%.2f y=%.2f hscroll=%.2f vscroll=%.2f",
                motionEvent.source,
                x,
                y,
                hscroll,
                vscroll,
            )
            when {
                // Vertical scroll / Y axis: volume
                vscroll < -AXIS_THRESHOLD || y < -AXIS_THRESHOLD -> {
                    adjustVolume(1)
                    true
                }

                vscroll > AXIS_THRESHOLD || y > AXIS_THRESHOLD -> {
                    adjustVolume(-1)
                    true
                }

                // Horizontal scroll / X axis: seek
                hscroll > AXIS_THRESHOLD || x > AXIS_THRESHOLD -> {
                    seekFromWebKey(Constants.PLAYBACK_MANAGER_COMMAND_FAST_FORWARD)
                    true
                }

                hscroll < -AXIS_THRESHOLD || x < -AXIS_THRESHOLD -> {
                    seekFromWebKey(Constants.PLAYBACK_MANAGER_COMMAND_REWIND)
                    true
                }

                else -> false
            }
        }
    }

    private fun seekFromWebKey(command: String) {
        // The command wrapper is safe to call without an active player; the
        // playback manager no-ops when no player is loaded.
        webappFunctionChannel.playbackCommand(command)
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    private fun parseWebPlayerState(result: String): WebPlayerState? {
        val trimmed = result.trim().removeSurrounding("\"")
        if (trimmed.isBlank() || trimmed == "null") return null
        return runCatching {
            val json = JSONObject(trimmed)
            WebPlayerState(
                playing = json.optBoolean("playing"),
                position = json.optLong("position"),
            )
        }.getOrNull()
    }

    private data class WebPlayerState(val playing: Boolean, val position: Long)

    override fun onResume() {
        super.onResume()
        webPlayerMediaSession?.isActive = true
    }

    override fun onPause() {
        super.onPause()
        webPlayerMediaSession?.isActive = false
    }

    @SuppressLint("MissingOnPlayFromSearch")
    private val webPlayerMediaSessionCallback: MediaSession.Callback = object : MediaSession.Callback() {
        override fun onPlay() {
            webappFunctionChannel.playbackCommand(Constants.PLAYBACK_MANAGER_COMMAND_PLAY)
        }

        override fun onPause() {
            webappFunctionChannel.playbackCommand(Constants.PLAYBACK_MANAGER_COMMAND_PAUSE)
        }

        override fun onSkipToPrevious() {
            webappFunctionChannel.playbackCommand(Constants.PLAYBACK_MANAGER_COMMAND_PREVIOUS)
        }

        override fun onSkipToNext() {
            webappFunctionChannel.playbackCommand(Constants.PLAYBACK_MANAGER_COMMAND_NEXT)
        }

        override fun onRewind() {
            webappFunctionChannel.playbackCommand(Constants.PLAYBACK_MANAGER_COMMAND_REWIND)
        }

        override fun onFastForward() {
            webappFunctionChannel.playbackCommand(Constants.PLAYBACK_MANAGER_COMMAND_FAST_FORWARD)
        }

        override fun onStop() {
            webappFunctionChannel.playbackCommand(Constants.PLAYBACK_MANAGER_COMMAND_STOP)
        }

        override fun onSeekTo(pos: Long) {
            webappFunctionChannel.seekTo(pos)
        }

        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val keyEvent = IntentCompat.getParcelableExtra(
                mediaButtonIntent,
                Intent.EXTRA_KEY_EVENT,
                KeyEvent::class.java,
            )
                ?: return super.onMediaButtonEvent(mediaButtonIntent)

            val command = when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> Constants.PLAYBACK_MANAGER_COMMAND_PLAY
                KeyEvent.KEYCODE_MEDIA_PAUSE -> Constants.PLAYBACK_MANAGER_COMMAND_PAUSE
                KeyEvent.KEYCODE_HEADSETHOOK -> Constants.PLAYBACK_MANAGER_COMMAND_PLAY_PAUSE
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Constants.PLAYBACK_MANAGER_COMMAND_PLAY_PAUSE
                KeyEvent.KEYCODE_MEDIA_NEXT -> Constants.PLAYBACK_MANAGER_COMMAND_NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Constants.PLAYBACK_MANAGER_COMMAND_PREVIOUS
                KeyEvent.KEYCODE_MEDIA_STOP -> Constants.PLAYBACK_MANAGER_COMMAND_STOP
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                -> Constants.PLAYBACK_MANAGER_COMMAND_FAST_FORWARD

                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                -> Constants.PLAYBACK_MANAGER_COMMAND_REWIND

                else -> null
            }

            if (keyEvent.action != KeyEvent.ACTION_DOWN) return command != null
            // Throttle auto-repeat events so holding a button seeks at a steady pace
            if (command != null && keyEvent.eventTime - lastMediaButtonTime >= MEDIA_BUTTON_REPEAT_INTERVAL_MS) {
                lastMediaButtonTime = keyEvent.eventTime
                webappFunctionChannel.playbackCommand(command)
            }
            return command != null
        }
    }

    override fun onInterceptBackPressed(): Boolean {
        return connected && webappFunctionChannel.goBack()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Preserve the web page state across activity recreation (e.g. theme changes)
        webViewBinding?.webView?.saveState(outState)
        // Also remember the current URL so a theme-change recreation can reload
        // the same page instead of landing back on the home page (the fresh
        // loadUrl in initialize() would otherwise start from the server root).
        webViewBinding?.webView?.url?.let { outState.putString(KEY_SAVED_WEBVIEW_URL, it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webPlayerMediaSession?.release()
        webPlayerMediaSession = null
        webViewBinding = null
    }

    private fun WebView.initialize() {
        if (!appPreferences.ignoreWebViewChecks && isOutdated()) { // Check WebView version
            showOutdatedWebViewDialog(this)
            return
        }
        webViewClient = jellyfinWebViewClient
        webChromeClient = JellyfinWebChromeClient(this@WebViewFragment)
        settings.applyDefault()
        addJavascriptInterface(NativeInterface(requireContext()), "NativeInterface")
        addJavascriptInterface(nativePlayer, "NativePlayer")
        addJavascriptInterface(externalPlayer, "ExternalPlayer")
        addJavascriptInterface(mediaSegments, "MediaSegments")

        // Load fresh (no restoreState — that caused a reload loop after theme
        // relaunches). Prefer the URL the user was on so a theme-change recreation
        // lands back on the same page instead of the home page. The login session
        // lives in the WebView's localStorage, so the reload stays authenticated.
        val savedUrl = webViewRestoreState?.getString(KEY_SAVED_WEBVIEW_URL)
        loadUrl(savedUrl ?: "${server.hostname.trimEnd('/')}/")
        // No connection timeout: on a fast theme toggle the re-load would be
        // interrupted and the timeout would kick the user back to the connect
        // page. Real connection failures are handled by onErrorReceived.
        postDelayed(showLoadingContainerRunnable, Constants.SHOW_PROGRESS_BAR_DELAY)
    }

    private fun showOutdatedWebViewDialog(webView: WebView) {
        AlertDialog.Builder(requireContext()).apply {
            setTitle(R.string.dialog_web_view_outdated)
            setMessage(R.string.dialog_web_view_outdated_message)
            setCancelable(false)

            val webViewPackage = WebViewCompat.getCurrentWebViewPackage(context)
            if (webViewPackage != null) {
                val marketUri = Uri.Builder().apply {
                    scheme("market")
                    authority("details")
                    appendQueryParameter("id", webViewPackage.packageName)
                }.build()
                val referrerUri = Uri.Builder().apply {
                    scheme("android-app")
                    authority(context.packageName)
                }.build()

                val marketIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = marketUri
                    putExtra(Intent.EXTRA_REFERRER, referrerUri)
                }

                // Only show button if the intent can be resolved
                if (marketIntent.resolveActivity(context.packageManager) != null) {
                    setNegativeButton(R.string.dialog_button_check_for_updates) { _, _ ->
                        startActivity(marketIntent)
                        requireActivity().finishAfterTransition()
                    }
                }
            }
            if (AndroidVersion.isAtLeastN) {
                setPositiveButton(R.string.dialog_button_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_WEBVIEW_SETTINGS))
                    Toast.makeText(context, R.string.toast_reopen_after_change, Toast.LENGTH_LONG).show()
                    requireActivity().finishAfterTransition()
                }
            }
            setNeutralButton(R.string.dialog_button_ignore) { _, _ ->
                appPreferences.ignoreWebViewChecks = true
                // Re-initialize
                webView.initialize()
            }
        }.show()
    }

    private fun onSelectServer(error: Boolean = false) = runOnUiThread {
        val activity = activity
        if (activity != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val extras = when {
                error -> Bundle().apply {
                    putBoolean(Constants.FRAGMENT_CONNECT_EXTRA_ERROR, true)
                }
                else -> null
            }
            parentFragmentManager.replaceFragment<ConnectFragment>(extras)
        }
    }

    private fun handleError() {
        connected = false
        onSelectServer(error = true)
    }

    override fun onShowFileChooser(intent: Intent, filePathCallback: ValueCallback<Array<Uri>>) {
        fileChooserCallback = filePathCallback
        fileChooserActivityLauncher.launch(intent)
    }

    companion object {
        private const val KEY_SAVED_WEBVIEW_URL = "saved_webview_url"
        private const val MEDIA_BUTTON_REPEAT_INTERVAL_MS = 300L
        private const val WEB_PLAYER_STATE_POLL_INTERVAL_MS = 2000L
        private const val AXIS_THRESHOLD = 0.5f

        private const val WEB_PLAYER_STATE_QUERY =
            "(function () {" +
                "var pm = window.NavigationHelper && window.NavigationHelper.playbackManager;" +
                "var player = pm && pm.getCurrentPlayer();" +
                "if (!player) return 'null';" +
                "try {" +
                "return JSON.stringify({" +
                "playing: !player.paused()," +
                "position: Math.round((player.currentTime() || 0) * 1000)" +
                "});" +
                "} catch (e) { return 'null'; }" +
                "})()"
    }
}
