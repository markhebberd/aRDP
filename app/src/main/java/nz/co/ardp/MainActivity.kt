package nz.co.ardp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.freerdp.freerdpcore.data.AppDatabase
import com.freerdp.freerdpcore.data.BookmarkConverter
import com.freerdp.freerdpcore.domain.BookmarkBase
import com.freerdp.freerdpcore.domain.ConnectionReference
import nz.co.ardp.session.DynamicResolutionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.co.ardp.connection.ConnectionConfig
import nz.co.ardp.connection.ConnectionStore
import nz.co.ardp.debug.LogCapture
import nz.co.ardp.ui.screens.ConnectionListScreen
import nz.co.ardp.ui.screens.ConnectionListViewModel
import nz.co.ardp.ui.screens.EditConnectionScreen
import nz.co.ardp.ui.screens.LogViewerScreen
import nz.co.ardp.ui.theme.ARdpTheme

class MainActivity : ComponentActivity() {

    private lateinit var store: ConnectionStore
    private lateinit var logCapture: LogCapture

    private var editTarget: ConnectionConfig? = null
    private var connectAfterSave = false
    private var pendingLogView by mutableStateOf(false)
    private var sessionLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = ConnectionStore(applicationContext)
        logCapture = LogCapture(applicationContext)
        // Clear stale log from previous session/crash
        logCapture.logFile.delete()

        setContent {
            ARdpTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()
                val listViewModel: ConnectionListViewModel = viewModel()

                NavHost(navController, startDestination = "connections") {
                    composable("connections") {
                        ConnectionListScreen(
                            viewModel = listViewModel,
                            onConnect = { config ->
                                scope.launch { launchSession(config) }
                            },
                            onEdit = { config ->
                                editTarget = config
                                connectAfterSave = false
                                navController.navigate("edit")
                            },
                            onAdd = {
                                editTarget = null
                                connectAfterSave = true
                                navController.navigate("edit")
                            },
                        )

                        // After returning from session, show logs if available
                        if (pendingLogView) {
                            pendingLogView = false
                            navController.navigate("logs")
                        }
                    }

                    composable("edit") {
                        EditConnectionScreen(
                            initial = editTarget,
                            onSave = { config ->
                                scope.launch {
                                    store.save(config)
                                    if (connectAfterSave) {
                                        launchSession(config)
                                    }
                                }
                                navController.popBackStack()
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable("logs") {
                        LogViewerScreen(
                            logText = logCapture.readLog(),
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (sessionLaunched) {
            sessionLaunched = false
            logCapture.stop()
            pendingLogView = true
        }
    }

    private suspend fun launchSession(config: ConnectionConfig) {
        val bookmark = BookmarkBase().apply {
            label = config.name.ifBlank { config.hostname }
            hostname = config.hostname
            port = config.port
            username = config.username
            password = config.password
            domain = config.domain
            // Use visible display area, subtract action bar height
            val dm = resources.displayMetrics
            val rect = android.graphics.Rect()
            window.decorView.getWindowVisibleDisplayFrame(rect)
            var w = if (rect.width() > 0) rect.width() else dm.widthPixels
            var h = if (rect.height() > 0) rect.height() else dm.heightPixels
            // Subtract action bar unless user has hidden it
            val hideAb = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(applicationContext)
                .getBoolean("ui.hide_action_bar", false)
            if (!hideAb) {
                val tv = android.util.TypedValue()
                if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                    h -= android.util.TypedValue.complexToDimensionPixelSize(tv.data, dm)
                }
            }
            screenSettings.setResolution(BookmarkBase.ScreenSettings.CUSTOM)
            screenSettings.width = w
            screenSettings.height = h

            // Apply quality settings
            val quality = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(applicationContext)
                .getString("rdp.quality", "high") ?: "high"
            when (quality) {
                "high" -> {
                    performanceFlags.setGfx(true)
                    performanceFlags.setH264(true)
                    performanceFlags.setRemoteFX(false)
                    performanceFlags.setWallpaper(true)
                    performanceFlags.setFontSmoothing(true)
                    performanceFlags.setDesktopComposition(true)
                    performanceFlags.setTheming(true)
                    performanceFlags.setMenuAnimations(true)
                    performanceFlags.setFullWindowDrag(true)
                }
                "medium" -> {
                    performanceFlags.setGfx(true)
                    performanceFlags.setH264(false)
                    performanceFlags.setRemoteFX(false)
                    performanceFlags.setWallpaper(false)
                    performanceFlags.setFontSmoothing(true)
                    performanceFlags.setDesktopComposition(false)
                    performanceFlags.setTheming(true)
                    performanceFlags.setMenuAnimations(false)
                    performanceFlags.setFullWindowDrag(false)
                }
                "low" -> {
                    performanceFlags.setGfx(true)
                    performanceFlags.setH264(false)
                    performanceFlags.setRemoteFX(false)
                    performanceFlags.setWallpaper(false)
                    performanceFlags.setFontSmoothing(false)
                    performanceFlags.setDesktopComposition(false)
                    performanceFlags.setTheming(false)
                    performanceFlags.setMenuAnimations(false)
                    performanceFlags.setFullWindowDrag(false)
                }
            }

            debugSettings.setDebugLevel("INFO")
        }

        // Save to freeRDPCore's database to get a bookmark ID
        val bookmarkId = withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(applicationContext)
            val entity = BookmarkConverter.toEntity(bookmark)
            val existing = db.bookmarkDao().getAll()
                .firstOrNull { it.hostname == config.hostname && it.username == config.username }
            if (existing != null) {
                entity.id = existing.id
                db.bookmarkDao().update(entity)
                existing.id
            } else {
                db.bookmarkDao().insert(entity)
            }
        }

        // Start log capture BEFORE launching session
        sessionLaunched = true
        logCapture.start()

        // Launch SessionActivity with a bookmark reference
        val conRef = ConnectionReference.getBookmarkReference(bookmarkId)
        val intent = Intent(this, DynamicResolutionActivity::class.java).apply {
            putExtra("conRef", conRef)
        }
        startActivity(intent)
    }
}
