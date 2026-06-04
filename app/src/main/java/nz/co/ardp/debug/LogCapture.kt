package nz.co.ardp.debug

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class LogCapture(private val context: Context) {

    private var process: Process? = null
    private var captureThread: Thread? = null

    val logFile: File get() = File(context.filesDir, "freerdp_trace.log")

    fun start() {
        stop()
        // Clear previous log
        logFile.writeText("")

        captureThread = Thread({
            try {
                val pid = android.os.Process.myPid().toString()
                val proc = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "time", "--pid=$pid", "*:D")
                )
                process = proc
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                logFile.bufferedWriter().use { writer ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        // Capture FreeRDP-related and connection-related logs
                        if (l.contains("KEY") || l.contains("FreeRDP") || l.contains("freerdp") ||
                            l.contains("LibFreeRDP") || l.contains("client.android") ||
                            l.contains("DynamicResolution") || l.contains("disp") ||
                            l.contains("DISP") || l.contains("Display control") ||
                            l.contains("resize") || l.contains("Resize") ||
                            l.contains("GFX") || l.contains("gfx") ||
                            l.contains("Session") || l.contains("channel") ||
                            l.contains("Graphics") || l.contains("SettingsChanged") ||
                            l.contains("AndroidRuntime") || l.contains("FATAL")
                        ) {
                            writer.write(l)
                            writer.newLine()
                            writer.flush()
                        }
                    }
                }
            } catch (_: Exception) {}
        }, "log-capture").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        process?.destroy()
        process = null
        captureThread?.interrupt()
        captureThread = null
    }

    fun readLog(): String {
        return if (logFile.exists()) logFile.readText() else "(no log captured)"
    }
}
