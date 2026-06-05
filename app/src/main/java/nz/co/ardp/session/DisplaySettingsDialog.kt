package nz.co.ardp.session

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager

class DisplaySettingsDialog(
    private val activity: Activity,
    private val onChanged: () -> Unit,
) : Dialog(activity) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(activity)

    private val COLOR_ACTIVE = Color.parseColor("#4CAF50")
    private val COLOR_HIDDEN = Color.parseColor("#424242")
    private val COLOR_DESKTOP = Color.parseColor("#1565C0")
    private val COLOR_BORDER = Color.parseColor("#BDBDBD")
    private val COLOR_CUTOUT = Color.parseColor("#FF9800")
    private val COLOR_CUTOUT_HIDDEN = Color.parseColor("#5D4037")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("Tap areas to toggle")

        val dp = { px: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, px.toFloat(), context.resources.displayMetrics
            ).toInt()
        }

        // Detect actual inset positions
        val statusTop = true // status bar is always conceptually at "top" of current orientation
        var navLeft = false
        var navBottom = false
        var navRight = false
        var cutoutLeft = false
        var cutoutTop = false
        var cutoutRight = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = activity.window.decorView.rootWindowInsets
            if (insets != null) {
                val nav = insets.getInsets(WindowInsets.Type.navigationBars())
                navLeft = nav.left > 0
                navBottom = nav.bottom > 0
                navRight = nav.right > 0

                val cutout = insets.displayCutout
                if (cutout != null) {
                    cutoutLeft = cutout.safeInsetLeft > 0
                    cutoutTop = cutout.safeInsetTop > 0
                    cutoutRight = cutout.safeInsetRight > 0
                }
            }
        } else {
            // Fallback: nav at bottom, cutout at top
            navBottom = true
            cutoutTop = true
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        // Phone frame
        val phoneFrame = FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(dp(2), COLOR_BORDER)
                cornerRadius = dp(12).toFloat()
                setColor(Color.BLACK)
            }
        }

        val phoneLayout = buildLayout(dp, navLeft, navBottom, navRight,
            cutoutLeft, cutoutTop, cutoutRight)

        phoneFrame.addView(phoneLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })

        val isLandscape = activity.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        container.addView(phoneFrame, LinearLayout.LayoutParams(
            if (isLandscape) dp(300) else dp(200),
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // Done button
        container.addView(TextView(context).apply {
            text = "Done"
            setTextColor(Color.parseColor("#2196F3"))
            textSize = 16f
            setPadding(dp(16), dp(12), dp(16), dp(4))
            gravity = Gravity.CENTER
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        setContentView(container)
    }

    private fun buildLayout(
        dp: (Int) -> Int,
        navLeft: Boolean, navBottom: Boolean, navRight: Boolean,
        cutoutLeft: Boolean, cutoutTop: Boolean, cutoutRight: Boolean,
    ): LinearLayout {
        val outer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Top row: cutout if at top
        if (cutoutTop) {
            outer.addView(makeCutoutArea(dp, "Notch"), lp(dp))
        }

        // Status bar (always at top of content)
        outer.addView(makeArea(dp, "Status Bar", "ui.hide_status_bar", dp(22)), lp(dp))

        // Action bar
        outer.addView(makeArea(dp, "Action Bar", "ui.hide_action_bar", dp(32)), lp(dp))

        // Middle: optional left nav | desktop | optional right nav
        val middle = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        if (cutoutLeft) {
            middle.addView(makeCutoutArea(dp, "N"), LinearLayout.LayoutParams(
                dp(20), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        if (navLeft) {
            middle.addView(makeArea(dp, "Nav", "ui.hide_navigation_bar", dp(80)),
                LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.MATCH_PARENT))
        }

        middle.addView(TextView(context).apply {
            text = "Remote\nDesktop"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_DESKTOP)
            minimumHeight = dp(if (navBottom) 100 else 80)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        if (navRight) {
            middle.addView(makeArea(dp, "Nav", "ui.hide_navigation_bar", dp(80)),
                LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        if (cutoutRight) {
            middle.addView(makeCutoutArea(dp, "N"), LinearLayout.LayoutParams(
                dp(20), LinearLayout.LayoutParams.MATCH_PARENT))
        }

        outer.addView(middle, lp(dp))

        // Bottom: nav bar if at bottom
        if (navBottom) {
            outer.addView(makeArea(dp, "Navigation Bar", "ui.hide_navigation_bar", dp(28)), lp(dp))
        }

        // Bottom cutout (unlikely but handle it)
        if (!cutoutTop && !cutoutLeft && !cutoutRight) {
            // No cutout detected anywhere - still show the option at bottom
            outer.addView(makeCutoutArea(dp, "Notch Area (not detected)"), lp(dp))
        }

        return outer
    }

    private fun makeArea(dp: (Int) -> Int, label: String, prefKey: String, height: Int): TextView {
        val isHidden = prefs.getBoolean(prefKey, false)
        return TextView(context).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            minimumHeight = height
            setPadding(dp(2), dp(1), dp(2), dp(1))
            applyStyle(this, isHidden, COLOR_ACTIVE, COLOR_HIDDEN)
            setOnClickListener {
                val newState = !prefs.getBoolean(prefKey, false)
                prefs.edit().putBoolean(prefKey, newState).apply()
                applyStyle(this, newState, COLOR_ACTIVE, COLOR_HIDDEN)
                onChanged()
            }
        }
    }

    private fun makeCutoutArea(dp: (Int) -> Int, label: String): TextView {
        val isUsed = prefs.getBoolean("ui.use_cutout", false)
        return TextView(context).apply {
            text = label
            textSize = 9f
            gravity = Gravity.CENTER
            minimumHeight = dp(18)
            setPadding(dp(2), dp(1), dp(2), dp(1))
            applyStyle(this, !isUsed, COLOR_CUTOUT, COLOR_CUTOUT_HIDDEN)
            setOnClickListener {
                val newState = !prefs.getBoolean("ui.use_cutout", false)
                prefs.edit().putBoolean("ui.use_cutout", newState).apply()
                applyStyle(this, !newState, COLOR_CUTOUT, COLOR_CUTOUT_HIDDEN)
                onChanged()
            }
        }
    }

    private fun applyStyle(view: TextView, dimmed: Boolean, activeColor: Int, hiddenColor: Int) {
        if (dimmed) {
            view.setBackgroundColor(hiddenColor)
            view.setTextColor(Color.parseColor("#9E9E9E"))
        } else {
            view.setBackgroundColor(activeColor)
            view.setTextColor(Color.WHITE)
        }
    }

    private fun lp(dp: (Int) -> Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(1) }
}
