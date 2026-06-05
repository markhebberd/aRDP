package nz.co.ardp.session

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.presentation.SessionActivity
import com.freerdp.freerdpcore.services.LibFreeRDP
import nz.co.ardp.R

class DynamicResolutionActivity : SessionActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingResize: Runnable? = null
    private var lastWidth = 0
    private var lastHeight = 0
    companion object {
        private const val MENU_FULLSCREEN = 99999
        private const val MENU_QUALITY = 99998
    }

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scheduleResize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved display prefs (don't reset them)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit()
            .putBoolean("ui.hide_action_bar", prefs.getBoolean("ui.hide_action_bar", false))
            .putBoolean("ui.hide_status_bar", prefs.getBoolean("ui.hide_status_bar", false))
            .putBoolean("ui.hide_navigation_bar", prefs.getBoolean("ui.hide_navigation_bar", false))
            .apply()
        super.onCreate(savedInstanceState)
        applyDisplaySettings()
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        // No initial corrective resize - MainActivity sets the correct resolution

        // TODO: foreground service for background persistence
    }

    private fun startKeepAliveService() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                return
            }
        }
        try {
            SessionKeepAliveService.start(this)
        } catch (e: Exception) {
            Log.w("DynamicResolution", "Could not start keep-alive service: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            try {
                SessionKeepAliveService.start(this)
            } catch (e: Exception) {
                Log.w("DynamicResolution", "Could not start keep-alive service: ${e.message}")
            }
        }
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

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            var inst = 0L
            for (s in GlobalApp.getSessions()) { inst = s.instance; break }
            if (inst != 0L) {
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vScroll != 0f) {
                    LibFreeRDP.sendCursorEvent(inst, 0, 0,
                        com.freerdp.freerdpcore.utils.Mouse.getScrollEvent(this, vScroll < 0))
                    return true
                }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("ui.hide_action_bar", false)) {
            // Briefly show action bar without changing resolution
            supportActionBar?.show()
            // Auto-hide after 3 seconds
            handler.postDelayed({
                if (PreferenceManager.getDefaultSharedPreferences(this)
                        .getBoolean("ui.hide_action_bar", false)) {
                    supportActionBar?.hide()
                }
            }, 3000L)
            return
        }
        super.onBackPressed()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menu.add(0, MENU_QUALITY, 99, "Quality")
        menu.add(0, MENU_FULLSCREEN, 100, "Display")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_FULLSCREEN) {
            showDisplayDialog()
            return true
        }
        if (item.itemId == MENU_QUALITY) {
            showQualityDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private var displayDialog: DisplaySettingsDialog? = null

    private fun showQualityDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val currentQuality = prefs.getString("rdp.quality", "high") ?: "high"
        val qualities = arrayOf("High (AVC444 + effects)", "Medium (GFX + no effects)", "Low (GFX minimal)")
        val keys = arrayOf("high", "medium", "low")
        val currentIndex = keys.indexOf(currentQuality).coerceAtLeast(0)

        android.app.AlertDialog.Builder(this)
            .setTitle("Connection Quality")
            .setSingleChoiceItems(qualities, currentIndex) { dialog, which ->
                val oldQuality = prefs.getString("rdp.quality", "high")
                val newQuality = keys[which]
                if (newQuality != oldQuality) {
                    prefs.edit().putString("rdp.quality", newQuality).apply()
                    dialog.dismiss()
                    reconnectWithQuality(newQuality, oldQuality ?: "high")
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun reconnectWithQuality(newQuality: String, oldQuality: String) {
        // Show confirmation with auto-revert
        val revertRunnable = Runnable {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putString("rdp.quality", oldQuality).apply()
            android.widget.Toast.makeText(this, "Reverted to previous quality", android.widget.Toast.LENGTH_SHORT).show()
        }

        android.widget.Toast.makeText(this,
            "Reconnecting with ${newQuality} quality...", android.widget.Toast.LENGTH_SHORT).show()

        // Disconnect current session and restart activity with new settings
        for (s in GlobalApp.getSessions()) {
            LibFreeRDP.disconnect(s.instance)
        }

        // Restart the session activity - MainActivity will read the quality pref
        handler.postDelayed({
            val intent = intent
            finish()
            startActivity(intent)
        }, 500L)
    }

    private fun showDisplayDialog() {
        displayDialog?.dismiss()
        displayDialog = DisplaySettingsDialog(this) {
            applyDisplaySettings()
        }.also { it.show() }
    }

    private fun applyDisplaySettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val hideStatus = prefs.getBoolean("ui.hide_status_bar", false)
        val hideNav = prefs.getBoolean("ui.hide_navigation_bar", false)
        val hideAction = prefs.getBoolean("ui.hide_action_bar", false)
        val useCutout = prefs.getBoolean("ui.use_cutout", false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)

        // Status bar
        if (hideStatus) controller.hide(WindowInsetsCompat.Type.statusBars())
        else controller.show(WindowInsetsCompat.Type.statusBars())

        // Navigation bar
        if (hideNav) controller.hide(WindowInsetsCompat.Type.navigationBars())
        else controller.show(WindowInsetsCompat.Type.navigationBars())

        if (hideStatus || hideNav) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Action bar
        if (hideAction) supportActionBar?.hide()
        else supportActionBar?.show()

        // Display cutout
        val lp = window.attributes
        if (useCutout) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            WindowCompat.setDecorFitsSystemWindows(window, !(hideStatus && hideNav))
        }
        window.attributes = lp

        handler.postDelayed({ sendResizeFromView() }, 500L)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDisplaySettings()
        scheduleResize()
        // Redraw display dialog if open
        if (displayDialog?.isShowing == true) {
            showDisplayDialog()
        }
    }

    private fun scheduleResize() {
        pendingResize?.let { handler.removeCallbacks(it) }
        pendingResize = Runnable { sendResizeFromView() }
        handler.postDelayed(pendingResize!!, 500L)
    }

    private fun sendResizeFromView() {
        val rootView = findViewById<View>(com.freerdp.freerdpcore.R.id.session_root_view)
            ?: window.decorView
        rootView.post {
            val w = rootView.width
            var h = rootView.height

            // session_root_view includes action bar - subtract when visible
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (!prefs.getBoolean("ui.hide_action_bar", false) && supportActionBar?.isShowing == true) {
                val abh = supportActionBar?.height ?: 0
                if (abh > 0) h -= abh
            }

            Log.d("DynamicResolution", "resize: ${w}x${h} (root=${rootView.height} ab=${supportActionBar?.height})")

            if (w <= 0 || h <= 0) return@post
            if (w == lastWidth && h == lastHeight) return@post
            lastWidth = w
            lastHeight = h
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
