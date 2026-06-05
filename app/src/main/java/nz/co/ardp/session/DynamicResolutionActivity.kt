package nz.co.ardp.session

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.preference.PreferenceManager
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.presentation.SessionActivity
import com.freerdp.freerdpcore.services.LibFreeRDP
import android.content.res.Configuration.KEYBOARD_QWERTY

class DynamicResolutionActivity : SessionActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingResize: Runnable? = null
    private var lastWidth = 0
    private var lastHeight = 0

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scheduleResize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        updateActionBarVisibility()
        super.onCreate(savedInstanceState)
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        handler.postDelayed({ sendResizeFromView() }, 3000L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.d("KEY", "dispatchKeyEvent: keyCode=${event.keyCode} action=${event.action} scan=${event.scanCode}")

        var inst = 0L
        for (s in GlobalApp.getSessions()) { inst = s.instance; break }
        Log.d("KEY", "session instance: $inst")
        if (inst == 0L) return super.dispatchKeyEvent(event)

        // Arrow keys - send directly, ScrollView2D steals these otherwise
        val vk = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x25
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27
            KeyEvent.KEYCODE_DPAD_UP -> 0x26
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x28
            KeyEvent.KEYCODE_MOVE_HOME -> 0x24
            KeyEvent.KEYCODE_MOVE_END -> 0x23
            KeyEvent.KEYCODE_PAGE_UP -> 0x21
            KeyEvent.KEYCODE_PAGE_DOWN -> 0x22
            else -> 0
        }
        if (vk != 0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.isCtrlPressed) LibFreeRDP.sendKeyEvent(inst, 0xA2, true)
                if (event.isShiftPressed) LibFreeRDP.sendKeyEvent(inst, 0xA0, true)
                if (event.isAltPressed) LibFreeRDP.sendKeyEvent(inst, 0xA4, true)
                LibFreeRDP.sendKeyEvent(inst, vk, true)
                LibFreeRDP.sendKeyEvent(inst, vk, false)
                if (event.isAltPressed) LibFreeRDP.sendKeyEvent(inst, 0xA4, false)
                if (event.isShiftPressed) LibFreeRDP.sendKeyEvent(inst, 0xA0, false)
                if (event.isCtrlPressed) LibFreeRDP.sendKeyEvent(inst, 0xA2, false)
            }
            return true
        }

        // Ctrl/Alt combos - intercept before Android steals Ctrl+C/V/X
        if (event.isCtrlPressed || event.isAltPressed) {
            val comboVk = when (event.keyCode) {
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x41 + (event.keyCode - KeyEvent.KEYCODE_A)
                KeyEvent.KEYCODE_ESCAPE -> 0x1B
                else -> 0
            }
            if (comboVk != 0 && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.isCtrlPressed) LibFreeRDP.sendKeyEvent(inst, 0xA2, true)
                if (event.isAltPressed) LibFreeRDP.sendKeyEvent(inst, 0xA4, true)
                if (event.isShiftPressed) LibFreeRDP.sendKeyEvent(inst, 0xA0, true)
                LibFreeRDP.sendKeyEvent(inst, comboVk, true)
                LibFreeRDP.sendKeyEvent(inst, comboVk, false)
                if (event.isShiftPressed) LibFreeRDP.sendKeyEvent(inst, 0xA0, false)
                if (event.isAltPressed) LibFreeRDP.sendKeyEvent(inst, 0xA4, false)
                if (event.isCtrlPressed) LibFreeRDP.sendKeyEvent(inst, 0xA2, false)
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL) {
            var inst = 0L
            for (s in GlobalApp.getSessions()) { inst = s.instance; break }
            if (inst != 0L) {
                val vScroll = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vScroll != 0f) {
                    LibFreeRDP.sendCursorEvent(inst, 0, 0,
                        com.freerdp.freerdpcore.utils.Mouse.getScrollEvent(this, vScroll < 0))
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateActionBarVisibility()
        scheduleResize()
    }

    private fun hasHardwareKeyboard(): Boolean {
        val config = resources.configuration
        return config.keyboard == Configuration.KEYBOARD_QWERTY &&
            config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    private fun updateActionBarVisibility() {
        val hide = hasHardwareKeyboard()
        Log.d("DynamicResolution", "keyboard=${resources.configuration.keyboard} hidden=${resources.configuration.hardKeyboardHidden} hideActionBar=$hide")
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean("ui.hide_action_bar", hide)
            .putBoolean("ui.hide_status_bar", false)
            .putBoolean("ui.hide_navigation_bar", false)
            .apply()
    }

    private fun scheduleResize() {
        pendingResize?.let { handler.removeCallbacks(it) }
        pendingResize = Runnable { sendResizeFromView() }
        handler.postDelayed(pendingResize!!, 500L)
    }

    private fun sendResizeFromView() {
        val decorView = window.decorView
        decorView.post {
            val r = Rect()
            decorView.getWindowVisibleDisplayFrame(r)
            if (r.width() <= 0 || r.height() <= 0) return@post
            if (r.width() == lastWidth && r.height() == lastHeight) return@post
            lastWidth = r.width()
            lastHeight = r.height()
            Log.i("DynamicResolution", "Resize: ${lastWidth}x${lastHeight}")
            for (s in GlobalApp.getSessions()) {
                LibFreeRDP.sendResizeEvent(s.instance, lastWidth, lastHeight)
            }
        }
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
