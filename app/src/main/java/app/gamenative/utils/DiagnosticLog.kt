package app.gamenative.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import app.gamenative.BuildConfig
import app.gamenative.CrashHandler
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * Persistent, in-app diagnostics. Timber INFO+ lines are appended to filesDir/logs/app.log so they
 * survive crashes and process kills, and [buildReport] bundles them with the last crash report,
 * Android's recorded exit reasons for earlier sessions, and the live logcat for this process.
 */
object DiagnosticLog {
    private const val MAX_LOG_BYTES = 512 * 1024L
    private const val REPORT_LOG_LINES = 1500
    private const val REPORT_LOGCAT_LINES = 500
    private const val MAX_EXIT_REASONS = 8
    private const val MAX_CLIPBOARD_CHARS = 100_000

    private val lineTime = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTime = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private fun logDir(context: Context) = File(context.filesDir, "logs")

    fun init(context: Context) {
        val dir = logDir(context.applicationContext).apply { mkdirs() }
        Timber.plant(FileTree(File(dir, "app.log")))
        Timber.i("==== Session start: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) pid=${Process.myPid()} ====")
    }

    private class FileTree(private val file: File) : Timber.Tree() {
        private val rotated = File(file.parentFile, "app.log.1")

        override fun isLoggable(tag: String?, priority: Int) = priority >= Log.INFO

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val level = when (priority) {
                Log.INFO -> 'I'
                Log.WARN -> 'W'
                Log.ERROR -> 'E'
                Log.ASSERT -> 'A'
                else -> 'V'
            }
            val line = buildString {
                append(synchronized(lineTime) { lineTime.format(Date()) })
                append(' ').append(level).append(' ')
                if (tag != null) append(tag).append(": ")
                append(message)
                if (t != null && t.message.orEmpty() !in message) append('\n').append(Log.getStackTraceString(t))
                append('\n')
            }
            synchronized(this) {
                runCatching {
                    if (file.length() > MAX_LOG_BYTES) {
                        rotated.delete()
                        file.renameTo(rotated)
                    }
                    FileWriter(file, true).use { it.write(line) }
                }
            }
        }
    }

    fun buildReport(context: Context): String = buildString {
        appendLine("${BuildConfig.GAME_NAME} diagnostics - ${fileTime.format(Date())}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} ($soc)")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine()

        appendLine("---------- Previous exits (newest first) ----------")
        appendLine(exitReasons(context))
        appendLine()

        appendLine("---------- Last crash report ----------")
        val crash = context.getExternalFilesDir(null)
            ?.let { File(it, "crash_logs").listFiles() }
            ?.maxByOrNull { it.lastModified() }
        appendLine(crash?.let { "${it.name}\n${it.readText()}" } ?: "(none)")
        appendLine()

        appendLine("---------- App log (last $REPORT_LOG_LINES lines) ----------")
        val dir = logDir(context)
        val lines = listOf(File(dir, "app.log.1"), File(dir, "app.log"))
            .filter { it.isFile }
            .flatMap { it.readLines() }
        appendLine(lines.takeLast(REPORT_LOG_LINES).joinToString("\n").ifEmpty { "(empty)" })
        appendLine()

        if (BuildConfig.MPQ_CLIENT) {
            // XServerScreen writes the Wine launch log to Download/ for the MPQ client.
            val launchLog = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "${BuildConfig.GAME_NAME}-launch.log",
            )
            appendLine("---------- Game launch log (last $REPORT_LOG_LINES lines) ----------")
            appendLine(
                runCatching { launchLog.readLines().takeLast(REPORT_LOG_LINES).joinToString("\n") }
                    .getOrElse { "(unavailable: ${it.message})" },
            )
            appendLine()
        }

        appendLine("---------- Logcat (this session) ----------")
        appendLine(CrashHandler.getAppLogs(REPORT_LOGCAT_LINES))
    }

    private fun exitReasons(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "(requires Android 11+)"
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS)
            if (exits.isEmpty()) return "(none recorded)"
            exits.joinToString("\n") { info ->
                buildString {
                    append(fileTime.format(Date(info.timestamp)))
                    append("  ").append(exitReasonName(info.reason))
                    append("  process=").append(info.processName)
                    append(" pid=").append(info.pid)
                    append(" status=").append(info.status)
                    info.description?.takeIf { it.isNotBlank() }?.let { append("  \"").append(it).append('"') }
                }
            }
        }.getOrElse { "(unavailable: ${it.message})" }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH (native)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$reason"
    }

    fun copyToClipboard(context: Context, report: String) {
        val text = if (report.length > MAX_CLIPBOARD_CHARS) {
            report.take(MAX_CLIPBOARD_CHARS) + "\n(truncated; use Share for the full log)"
        } else {
            report
        }
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("${BuildConfig.GAME_NAME} log", text))
    }

    fun share(context: Context, report: String) {
        runCatching {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "${BuildConfig.RELEASE_APK_PREFIX}-log-${fileTime.format(Date())}.txt").apply { writeText(report) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Timber.w(it, "Sharing log failed") }
    }
}
