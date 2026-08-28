package com.example.pokewalklite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.random.Random

object WalkState {
    const val SPEED_KMH = 10.0
    const val MINUTES_PER_KM = 6
    const val METERS_PER_MINUTE = 10000.0 / 60.0
    private const val BASE_STEPS_PER_MINUTE = 217

    private const val PREFS = "pokewalk_state"
    private const val KEY_RUNNING = "running"
    private const val KEY_START_TIME = "start_time"
    private const val KEY_COMPLETED_CHUNKS = "completed_chunks"
    private const val KEY_FINISHED = "finished"
    private const val KEY_STOPPED = "stopped"
    private const val KEY_ERROR = "error"
    private const val KEY_TARGET_KM = "target_km"
    private const val KEY_PREFERRED_KM = "preferred_km"
    private const val KEY_STEP_PLAN = "step_plan"
    private const val KEY_FINAL_DURATION = "final_duration"
    private const val KEY_FINAL_DISTANCE = "final_distance"
    private const val KEY_FINAL_STEPS = "final_steps"
    private const val KEY_HISTORY = "history"
    private const val KEY_HISTORY_SAVED = "history_saved"

    data class Metrics(
        val durationMs: Long,
        val distanceMeters: Double,
        val steps: Long
    )

    data class HistoryEntry(
        val endedAtMillis: Long,
        val durationMs: Long,
        val distanceMeters: Double,
        val steps: Long
    )

    fun begin(context: Context, distanceKm: Int, startTimeMillis: Long = System.currentTimeMillis()) {
        val km = distanceKm.coerceIn(1, 20)
        val chunks = km * MINUTES_PER_KM
        val plan = List(chunks) { (BASE_STEPS_PER_MINUTE + Random.nextInt(-5, 6)).coerceAtLeast(1) }
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_START_TIME, startTimeMillis)
            .putInt(KEY_COMPLETED_CHUNKS, 0)
            .putBoolean(KEY_FINISHED, false)
            .putBoolean(KEY_STOPPED, false)
            .putInt(KEY_TARGET_KM, km)
            .putInt(KEY_PREFERRED_KM, km)
            .putString(KEY_STEP_PLAN, plan.joinToString(","))
            .putLong(KEY_FINAL_DURATION, 0L)
            .putString(KEY_FINAL_DISTANCE, "0")
            .putLong(KEY_FINAL_STEPS, 0L)
            .putBoolean(KEY_HISTORY_SAVED, false)
            .remove(KEY_ERROR)
            .apply()
    }

    fun ensureStarted(context: Context): Long {
        val p = prefs(context)
        val existing = p.getLong(KEY_START_TIME, 0L)
        if (p.getBoolean(KEY_RUNNING, false) && existing > 0L) return existing
        val now = System.currentTimeMillis()
        begin(context, preferredDistanceKm(context), now)
        return now
    }

    fun setPreferredDistanceKm(context: Context, km: Int) {
        prefs(context).edit().putInt(KEY_PREFERRED_KM, km.coerceIn(1, 20)).apply()
    }

    fun preferredDistanceKm(context: Context): Int = prefs(context).getInt(KEY_PREFERRED_KM, 5).coerceIn(1, 20)
    fun targetDistanceKm(context: Context): Int = prefs(context).getInt(KEY_TARGET_KM, preferredDistanceKm(context)).coerceIn(1, 20)
    fun targetDistanceMeters(context: Context): Double = targetDistanceKm(context) * 1000.0
    fun chunkCount(context: Context): Int = targetDistanceKm(context) * MINUTES_PER_KM
    fun totalDurationMs(context: Context): Long = chunkCount(context) * 60_000L

    fun stepPlan(context: Context): List<Int> {
        val raw = prefs(context).getString(KEY_STEP_PLAN, "").orEmpty()
        val parsed = raw.split(',').mapNotNull { it.toIntOrNull() }
        val expected = chunkCount(context)
        if (parsed.size == expected) return parsed
        return List(expected) { BASE_STEPS_PER_MINUTE }
    }

    fun metricsAt(context: Context, elapsedMs: Long): Metrics {
        val totalDuration = totalDurationMs(context)
        val safeElapsed = elapsedMs.coerceIn(0L, totalDuration)
        val plan = stepPlan(context)
        val minutePosition = safeElapsed / 60_000.0
        val fullMinutes = floor(minutePosition).toInt().coerceIn(0, plan.size)
        val fraction = (minutePosition - fullMinutes).coerceIn(0.0, 1.0)
        val completeSteps = plan.take(fullMinutes).sumOf { it.toLong() }
        val partialSteps = if (fullMinutes < plan.size) (plan[fullMinutes] * fraction).roundToLong() else 0L
        val meters = (safeElapsed / 60_000.0 * METERS_PER_MINUTE).coerceAtMost(targetDistanceMeters(context))
        return Metrics(safeElapsed, meters, completeSteps + partialSteps)
    }

    fun markChunkWritten(context: Context, completedChunks: Int) {
        prefs(context).edit()
            .putInt(KEY_COMPLETED_CHUNKS, completedChunks.coerceIn(0, chunkCount(context)))
            .apply()
    }

    fun finish(context: Context) {
        val duration = totalDurationMs(context)
        val distance = targetDistanceMeters(context)
        val steps = stepPlan(context).sumOf { it.toLong() }
        saveResult(context, duration, distance, steps, finished = true, stopped = false)
    }

    fun stop(context: Context, durationMs: Long, distanceMeters: Double, steps: Long) {
        saveResult(
            context,
            durationMs.coerceIn(0L, totalDurationMs(context)),
            distanceMeters.coerceIn(0.0, targetDistanceMeters(context)),
            steps.coerceAtLeast(0L),
            finished = false,
            stopped = true
        )
    }

    private fun saveResult(context: Context, durationMs: Long, distanceMeters: Double, steps: Long, finished: Boolean, stopped: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_FINISHED, finished)
            .putBoolean(KEY_STOPPED, stopped)
            .putLong(KEY_FINAL_DURATION, durationMs)
            .putString(KEY_FINAL_DISTANCE, distanceMeters.toString())
            .putLong(KEY_FINAL_STEPS, steps)
            .remove(KEY_ERROR)
            .apply()
        addHistoryOnce(context, HistoryEntry(System.currentTimeMillis(), durationMs, distanceMeters, steps))
    }

    private fun addHistoryOnce(context: Context, entry: HistoryEntry) {
        val p = prefs(context)
        if (p.getBoolean(KEY_HISTORY_SAVED, false)) return
        val array = JSONArray(p.getString(KEY_HISTORY, "[]"))
        val updated = JSONArray()
        updated.put(JSONObject().apply {
            put("endedAt", entry.endedAtMillis)
            put("duration", entry.durationMs)
            put("distance", entry.distanceMeters)
            put("steps", entry.steps)
        })
        for (i in 0 until minOf(array.length(), 4)) updated.put(array.getJSONObject(i))
        p.edit()
            .putString(KEY_HISTORY, updated.toString())
            .putBoolean(KEY_HISTORY_SAVED, true)
            .apply()
    }

    fun history(context: Context): List<HistoryEntry> {
        return try {
            val array = JSONArray(prefs(context).getString(KEY_HISTORY, "[]"))
            buildList {
                for (i in 0 until minOf(array.length(), 5)) {
                    val item = array.getJSONObject(i)
                    add(
                        HistoryEntry(
                            endedAtMillis = item.optLong("endedAt"),
                            durationMs = item.optLong("duration"),
                            distanceMeters = item.optDouble("distance"),
                            steps = item.optLong("steps")
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun finalMetrics(context: Context): Metrics = Metrics(
        durationMs = prefs(context).getLong(KEY_FINAL_DURATION, 0L),
        distanceMeters = prefs(context).getString(KEY_FINAL_DISTANCE, "0")?.toDoubleOrNull() ?: 0.0,
        steps = prefs(context).getLong(KEY_FINAL_STEPS, 0L)
    )

    fun fail(context: Context, message: String) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_FINISHED, false)
            .putBoolean(KEY_STOPPED, false)
            .putString(KEY_ERROR, message)
            .apply()
    }

    fun isRunning(context: Context): Boolean = prefs(context).getBoolean(KEY_RUNNING, false)
    fun startTimeMillis(context: Context): Long = prefs(context).getLong(KEY_START_TIME, 0L)
    fun completedChunks(context: Context): Int = prefs(context).getInt(KEY_COMPLETED_CHUNKS, 0).coerceIn(0, chunkCount(context))
    fun isFinished(context: Context): Boolean = prefs(context).getBoolean(KEY_FINISHED, false)
    fun isStopped(context: Context): Boolean = prefs(context).getBoolean(KEY_STOPPED, false)
    fun hasResult(context: Context): Boolean = isFinished(context) || isStopped(context)
    fun error(context: Context): String? = prefs(context).getString(KEY_ERROR, null)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
