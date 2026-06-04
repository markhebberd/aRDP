package nz.co.ardp.session

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
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

        private const val VK_LSHIFT = 0xA0
        private const val VK_LCONTROL = 0xA2
        private const val VK_LMENU = 0xA4
        private const val VK_LWIN = 0x5B

        // Android keycode -> Windows VK code
        private val KEY_MAP = mapOf(
            KeyEvent.KEYCODE_A to 0x41, KeyEvent.KEYCODE_B to 0x42,
            KeyEvent.KEYCODE_C to 0x43, KeyEvent.KEYCODE_D to 0x44,
            KeyEvent.KEYCODE_E to 0x45, KeyEvent.KEYCODE_F to 0x46,
            KeyEvent.KEYCODE_G to 0x47, KeyEvent.KEYCODE_H to 0x48,
            KeyEvent.KEYCODE_I to 0x49, KeyEvent.KEYCODE_J to 0x4A,
            KeyEvent.KEYCODE_K to 0x4B, KeyEvent.KEYCODE_L to 0x4C,
            KeyEvent.KEYCODE_M to 0x4D, KeyEvent.KEYCODE_N to 0x4E,
            KeyEvent.KEYCODE_O to 0x4F, KeyEvent.KEYCODE_P to 0x50,
            KeyEvent.KEYCODE_Q to 0x51, KeyEvent.KEYCODE_R to 0x52,
            KeyEvent.KEYCODE_S to 0x53, KeyEvent.KEYCODE_T to 0x54,
            KeyEvent.KEYCODE_U to 0x55, KeyEvent.KEYCODE_V to 0x56,
            KeyEvent.KEYCODE_W to 0x57, KeyEvent.KEYCODE_X to 0x58,
            KeyEvent.KEYCODE_Y to 0x59, KeyEvent.KEYCODE_Z to 0x5A,
            KeyEvent.KEYCODE_0 to 0x30, KeyEvent.KEYCODE_1 to 0x31,
            KeyEvent.KEYCODE_2 to 0x32, KeyEvent.KEYCODE_3 to 0x33,
            KeyEvent.KEYCODE_4 to 0x34, KeyEvent.KEYCODE_5 to 0x35,
            KeyEvent.KEYCODE_6 to 0x36, KeyEvent.KEYCODE_7 to 0x37,
            KeyEvent.KEYCODE_8 to 0x38, KeyEvent.KEYCODE_9 to 0x39,
            KeyEvent.KEYCODE_TAB to 0x09, KeyEvent.KEYCODE_ENTER to 0x0D,
            KeyEvent.KEYCODE_ESCAPE to 0x1B, KeyEvent.KEYCODE_DEL to 0x08,
            KeyEvent.KEYCODE_FORWARD_DEL to 0x2E,
            KeyEvent.KEYCODE_F1 to 0x70, KeyEvent.KEYCODE_F2 to 0x71,
            KeyEvent.KEYCODE_F3 to 0x72, KeyEvent.KEYCODE_F4 to 0x73,
            KeyEvent.KEYCODE_F5 to 0x74, KeyEvent.KEYCODE_F6 to 0x75,
            KeyEvent.KEYCODE_F7 to 0x76, KeyEvent.KEYCODE_F8 to 0x77,
            KeyEvent.KEYCODE_F9 to 0x78, KeyEvent.KEYCODE_F10 to 0x79,
            KeyEvent.KEYCODE_F11 to 0x7A, KeyEvent.KEYCODE_F12 to 0x7B,
            KeyEvent.KEYCODE_DPAD_LEFT to 0x25,    // VK_LEFT
            KeyEvent.KEYCODE_DPAD_UP to 0x26,      // VK_UP
            KeyEvent.KEYCODE_DPAD_RIGHT to 0x27,   // VK_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN to 0x28,    // VK_DOWN
            KeyEvent.KEYCODE_INSERT to 0x2D,       // VK_INSERT
            KeyEvent.KEYCODE_MOVE_HOME to 0x24,    // VK_HOME
            KeyEvent.KEYCODE_MOVE_END to 0x23,     // VK_END
            KeyEvent.KEYCODE_PAGE_UP to 0x21,      // VK_PRIOR
            KeyEvent.KEYCODE_PAGE_DOWN to 0x22,    // VK_NEXT
            KeyEvent.KEYCODE_SPACE to 0x20,        // VK_SPACE
            KeyEvent.KEYCODE_MINUS to 0xBD,        // VK_OEM_MINUS
            KeyEvent.KEYCODE_EQUALS to 0xBB,       // VK_OEM_PLUS
            KeyEvent.KEYCODE_LEFT_BRACKET to 0xDB, // VK_OEM_4
            KeyEvent.KEYCODE_RIGHT_BRACKET to 0xDD,// VK_OEM_6
            KeyEvent.KEYCODE_BACKSLASH to 0xDC,    // VK_OEM_5
            KeyEvent.KEYCODE_SEMICOLON to 0xBA,    // VK_OEM_1
            KeyEvent.KEYCODE_APOSTROPHE to 0xDE,   // VK_OEM_7
            KeyEvent.KEYCODE_COMMA to 0xBC,        // VK_OEM_COMMA
            KeyEvent.KEYCODE_PERIOD to 0xBE,       // VK_OEM_PERIOD
            KeyEvent.KEYCODE_SLASH to 0xBF,        // VK_OEM_2
            KeyEvent.KEYCODE_GRAVE to 0xC0,        // VK_OEM_3
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingResize: Runnable? = null
    private var lastWidth = 0
    private var lastHeight = 0

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scheduleResize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean("ui.hide_action_bar", true)
            .putBoolean("ui.hide_status_bar", true)
            .putBoolean("ui.hide_navigation_bar", true)
            .apply()

        super.onCreate(savedInstanceState)
        Log.d(TAG, "DynamicResolutionActivity created")

        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        handler.postDelayed({ sendResizeFromView() }, INITIAL_RESIZE_DELAY_MS)
    }

    private fun getSessionInstance(): Long? {
        val sessions = GlobalApp.getSessions()
        return sessions.firstOrNull()?.instance
    }

    // Track which modifiers WE sent down so we always release them
    private var shiftSentDown = false
    private var ctrlSentDown = false
    private var altSentDown = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val inst = getSessionInstance() ?: return super.dispatchKeyEvent(event)

        // Ctrl+Esc -> send as Win key (Start menu)
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE &&
            event.metaState and KeyEvent.META_CTRL_MASK != 0
        ) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        LibFreeRDP.sendKeyEvent(inst, VK_LWIN, true)
                        LibFreeRDP.sendKeyEvent(inst, VK_LWIN, false)
                    }
                }
            }
            return true
        }

        // Intercept Ctrl/Alt combos that Android would steal
        val hasShift = event.metaState and KeyEvent.META_SHIFT_MASK != 0
        val hasCtrl = event.metaState and KeyEvent.META_CTRL_MASK != 0
        val hasAlt = event.metaState and KeyEvent.META_ALT_MASK != 0

        if (hasCtrl || hasAlt) {
            val vk = KEY_MAP[event.keyCode]
            if (vk != null) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            if (hasCtrl) {
                                LibFreeRDP.sendKeyEvent(inst, VK_LCONTROL, true)
                                ctrlSentDown = true
                            }
                            if (hasAlt) {
                                LibFreeRDP.sendKeyEvent(inst, VK_LMENU, true)
                                altSentDown = true
                            }
                            if (hasShift) {
                                LibFreeRDP.sendKeyEvent(inst, VK_LSHIFT, true)
                                shiftSentDown = true
                            }
                            LibFreeRDP.sendKeyEvent(inst, vk, true)
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        LibFreeRDP.sendKeyEvent(inst, vk, false)
                        if (shiftSentDown) {
                            LibFreeRDP.sendKeyEvent(inst, VK_LSHIFT, false)
                            shiftSentDown = false
                        }
                        if (altSentDown) {
                            LibFreeRDP.sendKeyEvent(inst, VK_LMENU, false)
                            altSentDown = false
                        }
                        if (ctrlSentDown) {
                            LibFreeRDP.sendKeyEvent(inst, VK_LCONTROL, false)
                            ctrlSentDown = false
                        }
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Intercept scroll BEFORE the view hierarchy to invert direction
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val inst = getSessionInstance()
            if (inst != null) {
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vScroll != 0f) {
                    val down = vScroll < 0
                    LibFreeRDP.sendCursorEvent(inst, 0, 0,
                        com.freerdp.freerdpcore.utils.Mouse.getScrollEvent(this, down))
                    return true
                }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
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
