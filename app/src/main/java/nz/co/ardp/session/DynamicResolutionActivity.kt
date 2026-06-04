package nz.co.ardp.session

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.presentation.SessionActivity
import com.freerdp.freerdpcore.services.LibFreeRDP

class DynamicResolutionActivity : SessionActivity() {

    companion object {
        private const val TAG = "DynamicResolution"
        private const val RESIZE_DEBOUNCE_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingResize: Runnable? = null
    private var lastWidth = 0
    private var lastHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "DynamicResolutionActivity created")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Debounce resize during rapid changes
        pendingResize?.let { handler.removeCallbacks(it) }

        pendingResize = Runnable {
            // Use the root view's actual dimensions after layout
            val rootView = window.decorView.findViewById<View>(android.R.id.content)
            rootView?.post {
                val width = rootView.width
                val height = rootView.height

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
        handler.postDelayed(pendingResize!!, RESIZE_DEBOUNCE_MS)
    }

    override fun onDestroy() {
        pendingResize?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
