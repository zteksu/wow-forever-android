package app.gamenative.ui.screen.wow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Play-session stats for the summary the launcher shows after quitting: how long the session lasted, the average
 * and 1% low frame rates (from the game's frame counter), how much battery it used and how hot the battery got.
 * The battery is followed through Android's battery broadcasts while the game runs, so nothing is polled.
 */
object GameSessionStats {
    data class Summary(
        val endedAt: Long,
        val durationSec: Long,
        val avgFps: Float,
        /** The frame rate the slowest 1% of frames ran at, or 0 if unknown. */
        val lowFps: Float,
        val batteryStart: Int,
        val batteryEnd: Int,
        val charged: Boolean,
        /** Peak battery temperature in °C, or null if the device doesn't report it. */
        val peakTempC: Float?,
    ) {
        val batteryUsed: Int get() = (batteryStart - batteryEnd).coerceAtLeast(0)

        /** Battery used per hour, once a session is long enough for that to mean something. */
        val batteryPerHour: Int? get() = if (charged || durationSec < 600) null else (batteryUsed * 3600 / durationSec).toInt()

        fun toJson(): JSONObject = JSONObject()
            .put("endedAt", endedAt)
            .put("durationSec", durationSec)
            .put("avgFps", avgFps.toDouble())
            .put("lowFps", lowFps.toDouble())
            .put("batteryStart", batteryStart)
            .put("batteryEnd", batteryEnd)
            .put("charged", charged)
            .apply { peakTempC?.let { put("peakTempC", it.toDouble()) } }

        companion object {
            fun fromJson(json: JSONObject) = Summary(
                endedAt = json.optLong("endedAt"),
                durationSec = json.optLong("durationSec"),
                avgFps = json.optDouble("avgFps").toFloat(),
                lowFps = json.optDouble("lowFps").toFloat(),
                batteryStart = json.optInt("batteryStart"),
                batteryEnd = json.optInt("batteryEnd"),
                charged = json.optBoolean("charged"),
                peakTempC = if (json.has("peakTempC")) json.optDouble("peakTempC").toFloat() else null,
            )
        }
    }

    private const val PREFS = "game_sessions"
    private const val KEY_HISTORY = "history"
    private const val KEY_UNSEEN = "unseen"
    private const val HISTORY_SIZE = 50

    // The session in progress; one at a time, like the game itself.
    private var startedAt = 0L
    private var batteryStart = -1
    private var charged = false
    private var peakTenthsC = Int.MIN_VALUE
    private var receiver: BroadcastReceiver? = null

    val isRunning: Boolean get() = startedAt != 0L

    /** Starts following a session; does nothing if one is already running (it's called again on resume). */
    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        startedAt = System.currentTimeMillis()
        batteryStart = batteryPercent(app)
        charged = false
        peakTenthsC = Int.MIN_VALUE
        val battery = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
        }
        receiver = battery
        // The broadcast is sticky, so registering also delivers the current reading.
        runCatching { app.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
            .onSuccess { it?.let(::onBattery) }
            .onFailure { Timber.w(it, "Couldn't follow the battery for the session summary") }
    }

    @Synchronized
    private fun onBattery(intent: Intent) {
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (temp != Int.MIN_VALUE && temp > peakTenthsC) peakTenthsC = temp
        if (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) charged = true
    }

    /** Ends the session with the frame counter's numbers and saves its summary for the launcher. */
    @Synchronized
    fun finish(context: Context, avgFps: Float, slowFrameMs: Int) {
        if (!isRunning) return
        val app = context.applicationContext
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        val now = System.currentTimeMillis()
        val summary = Summary(
            endedAt = now,
            durationSec = (now - startedAt) / 1000,
            avgFps = avgFps,
            lowFps = if (slowFrameMs > 0) 1000f / slowFrameMs else 0f,
            batteryStart = batteryStart,
            batteryEnd = batteryPercent(app),
            charged = charged,
            peakTempC = peakTenthsC.takeIf { it != Int.MIN_VALUE }?.let { it / 10f },
        )
        startedAt = 0L
        // Under a minute is a launch that failed or was quit straight away, not a session worth a summary.
        if (summary.durationSec < 60) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = (listOf(summary) + history(app)).take(HISTORY_SIZE)
        prefs.edit()
            .putString(KEY_HISTORY, JSONArray(history.map { it.toJson() }).toString())
            .putBoolean(KEY_UNSEEN, true)
            .apply()
    }

    /** Past sessions, newest first. */
    fun history(context: Context): List<Summary> = runCatching {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HISTORY, null) ?: return emptyList()
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Summary::fromJson) }
    }.getOrDefault(emptyList())

    /** The last session, once, if the launcher hasn't shown it yet. */
    fun takeUnseen(context: Context): Summary? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_UNSEEN, false)) return null
        prefs.edit().putBoolean(KEY_UNSEEN, false).apply()
        return history(context).firstOrNull()
    }

    /** Total play time over the sessions that ended in the last [days] days. */
    fun playTimeSec(history: List<Summary>, days: Int, now: Long = System.currentTimeMillis()): Long =
        history.filter { now - it.endedAt <= days * 86_400_000L }.sumOf { it.durationSec }

    private fun batteryPercent(context: Context): Int = runCatching {
        context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)
}
