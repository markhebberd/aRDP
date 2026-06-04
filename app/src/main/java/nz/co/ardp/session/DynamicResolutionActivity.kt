package nz.co.ardp.session

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.preference.PreferenceManager
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.presentation.SessionActivity
import com.freerdp.freerdpcore.services.LibFreeRDP

class DynamicResolutionActivity : SessionActivity() {

    companion object {
        private const val TAG = "DynamicResolution"
        private const val RESIZE_DEBOUNCE_MS = 500L
        private const val INITIAL_RESIZE_DELAY_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingResize: Runnable? = null
    private var lastWidth = 0
    private var lastHeight = 0

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scheduleResize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set fullscreen prefs BEFORE super.onCreate (which calls hideSystemBars)
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean("ui.hide_action_bar", true)
            .putBoolean("ui.hide_status_bar", true)
            .putBoolean("ui.hide_navigation_bar", true)
            .apply()

        super.onCreate(savedInstanceState)
        Log.d(TAG, "DynamicResolutionActivity created")

        // Watch for any layout changes (freeform window resize, split-screen, etc.)
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)

        // Initial corrective resize after connection establishes
        handler.postDelayed({ sendResizeFromView() }, INITIAL_RESIZE_DELAY_MS)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Orientation change, screen size change, multi-window transitions
        scheduleResize()
    }

    private fun scheduleResize() {
        pendingResize?.let { handler.removeCallbacks(it) }
        pendingResize = Runnable { sendResizeFromView() }
        handler.postDelayed(pendingResize!!, RESIZE_DEBOUNCE_MS)
    }

    private fun sendResizeFromView() {
        val decorView = window.decorView
        decorView.post {
            val visibleRect = Rect()
            decorView.getWindowVisibleDisplayFrame(visibleRect)
            val width = visibleRect.width()
            val height = visibleRect.height()

            if (width <= 0 || height <= 0) return@post
            if (width == lastWidth && height == lastHeight) return@post

            lastWidth = width
            lastHeight = height

            Log.i(TAG, "Sending resize: ${width}x${height}")

            val sessions = GlobalApp.getSessions()
            for (session in sessions) {
                LibFreeRDP.sendResizeEvent(session.instance, width, height)
            }
        }
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        pendingResize?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
