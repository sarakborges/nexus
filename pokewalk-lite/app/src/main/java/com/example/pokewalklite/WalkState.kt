package com.example.pokewalklite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

object WalkState {
    const val MIN_SPEED_KMH = 1
    const val MAX_SPEED_KMH = 8
    const val DEFAULT_SPEED_KMH = 7

    enum class GoResult(val storedValue: String) {
        UNKNOWN("unknown"),
        CREDITED("credited"),
        NOT_CREDITED("not_credited");

        companion object {
            fun fromStored(value: String?): GoResult =
                entries.firstOrNull { it.storedValue == value } ?: UNKNOWN
        }
    }

    data class Diagnostic(
        val status: String = DIAGNOSTIC_PENDING,
        val checkedAtMillis: Long = 0L,
        val confirmedDistanceMeters: Double = 0.0,
        val confirmedSteps: Long = 0L,
        val distanceRecords: Int = 0,
        val stepsRecords: Int = 0,
        val origin: String = "",
        val recordingMethod: Int = 0,
        val error: String? = null
    )

    data class Metrics(
        val durationMs: Long,
        val distanceMeters: Double,
        val steps: Long
    )

    data class HistoryEntry(
        val startedAtMillis: Long,
        val endedAtMillis: Long,
        val durationMs: Long,
        val distanceMeters: Double,
        val steps: Long,
        val speedKmh: Int,
        val goResult: GoResult = GoResult.UNKNOWN,
        val diagnostic: Diagnostic = Diagnostic()
    )

    private const val PREFS = "pokewalk_state"
    private const val KEY_RUNNING = "running"
    private const val KEY_START_TIME = "start_time"
    private const val KEY_COMPLETED_CHUNKS = "completed_chunks"
    private const val KEY_FINISHED = "finished"
    private const val KEY_STOPPED = "stopped"
    private const val KEY_ERROR = "error"
    private const val KEY_TARGET_KM = "target_km"
    private const val KEY_PREFERRED_KM = "preferred_km"
    private const val KEY_TARGET_SPEED = "target_speed_kmh"
    private const val KEY_PREFERRED_SPEED = "preferred_speed_kmh"
    private const val KEY_STEP_PLAN = "step_plan"
    private const val KEY_FINAL_DURATION = "final_duration"
    private const val KEY_FINAL_DISTANCE = "final_distance"
    private const val KEY_FINAL_STEPS = "final_steps"
    private const val KEY_HISTORY = "history"
    private const val KEY_HISTORY_SAVED = "history_saved"

    const val DIAGNOSTIC_PENDING = "pending"
    const val DIAGNOSTIC_CONFIRMED = "confirmed"
    const val DIAGNOSTIC_MISMATCH = "mismatch"
    const val DIAGNOSTIC_ERROR = "error"
    const val DIAGNOSTIC_UNAVAILABLE = "unavailable"

    fun begin(
        context: Context,
        distanceKm: Int,
        speedKmh: Int = preferredSpeedKmh(context),
        startTimeMillis: Long = System.currentTimeMillis()
    ) {
        val km = distanceKm.coerceIn(1, 20)
        val speed = speedKmh.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
        val durationMs = calculateDurationMs(km, speed)
        val chunks = calculateChunkCount(durationMs)
        val stepPlan = buildStepPlan(speed, durationMs, chunks)

        prefs(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_START_TIME, startTimeMillis)
            .putInt(KEY_COMPLETED_CHUNKS, 0)
            .putBoolean(KEY_FINISHED, false)
            .putBoolean(KEY_STOPPED, false)
            .putInt(KEY_TARGET_KM, km)
            .putInt(KEY_PREFERRED_KM, km)
            .putInt(KEY_TARGET_SPEED, speed)
            .putInt(KEY_PREFERRED_SPEED, speed)
            .putString(KEY_STEP_PLAN, stepPlan.joinToString(","))
            .putLong(KEY_FINAL_DURATION, 0L)
            .putString(KEY_FINAL_DISTANCE, "0")
            .putLong(KEY_FINAL_STEPS, 0L)
            .putBoolean(KEY_HISTORY_SAVED, false)
            .remove(KEY_ERROR)
            .apply()
    }

    private fun buildStepPlan(speedKmh: Int, durationMs: Long, chunks: Int): List<Int> {
        val baseCadencePerMinute = 60 + speedKmh * 14
        return List(chunks) { index ->
            val chunkMs = chunkDurationMs(durationMs, chunks, index)
            val cadence = (baseCadencePerMinute + Random.nextInt(-5, 6)).coerceAtLeast(20)
            (cadence * chunkMs / 60_000.0).roundToInt().coerceAtLeast(1)
        }
    }

    fun setPreferredDistanceKm(context: Context, km: Int) {
        prefs(context).edit().putInt(KEY_PREFERRED_KM, km.coerceIn(1, 20)).apply()
    }

    fun setPreferredSpeedKmh(context: Context, speedKmh: Int) {
        prefs(context).edit()
            .putInt(KEY_PREFERRED_SPEED, speedKmh.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH))
            .apply()
    }

    fun preferredDistanceKm(context: Context): Int =
        prefs(context).getInt(KEY_PREFERRED_KM, 5).coerceIn(1, 20)

    fun targetDistanceKm(context: Context): Int =
        prefs(context).getInt(KEY_TARGET_KM, preferredDistanceKm(context)).coerceIn(1, 20)

    fun preferredSpeedKmh(context: Context): Int =
        prefs(context).getInt(KEY_PREFERRED_SPEED, DEFAULT_SPEED_KMH)
            .coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)

    fun targetSpeedKmh(context: Context): Int =
        prefs(context).getInt(KEY_TARGET_SPEED, preferredSpeedKmh(context))
            .coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)

    fun targetDistanceMeters(context: Context): Double = targetDistanceKm(context) * 1000.0

    fun calculateDurationMs(distanceKm: Int, speedKmh: Int): Long =
        (distanceKm.coerceIn(1, 20) * 3_600_000.0 /
            speedKmh.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH))
            .roundToLong()
            .coerceAtLeast(1_000L)

    private fun calculateChunkCount(durationMs: Long): Int =
        ceil(durationMs / 60_000.0).toInt().coerceAtLeast(1)

    fun totalDurationMs(context: Context): Long =
        calculateDurationMs(targetDistanceKm(context), targetSpeedKmh(context))

    fun chunkCount(context: Context): Int = calculateChunkCount(totalDurationMs(context))

    private fun chunkDurationMs(totalDurationMs: Long, chunks: Int, index: Int): Long {
        if (index !in 0 until chunks) return 0L
        val start = index * 60_000L
        return (totalDurationMs - start).coerceIn(0L, 60_000L)
    }

    fun chunkDurationMs(context: Context, index: Int): Long =
        chunkDurationMs(totalDurationMs(context), chunkCount(context), index)

    fun chunkStartOffsetMs(index: Int): Long = index.coerceAtLeast(0) * 60_000L

    fun chunkEndOffsetMs(context: Context, index: Int): Long =
        (chunkStartOffsetMs(index) + chunkDurationMs(context, index))
            .coerceAtMost(totalDurationMs(context))

    fun fullChunksElapsed(context: Context, elapsedMs: Long): Int {
        val safe = elapsedMs.coerceIn(0L, totalDurationMs(context))
        return if (safe >= totalDurationMs(context)) {
            chunkCount(context)
        } else {
            (safe / 60_000L).toInt().coerceIn(0, chunkCount(context))
        }
    }

    fun distanceForChunk(context: Context, index: Int): Double {
        val duration = chunkDurationMs(context, index)
        if (duration <= 0L) return 0.0
        val meters = targetSpeedKmh(context) * 1000.0 * duration / 3_600_000.0
        if (index != chunkCount(context) - 1) return meters

        val previousMeters = (0 until index).sumOf { i ->
            targetSpeedKmh(context) * 1000.0 * chunkDurationMs(context, i) / 3_600_000.0
        }
        return (targetDistanceMeters(context) - previousMeters).coerceAtLeast(0.0)
    }

    fun stepPlan(context: Context): List<Int> {
        val expected = chunkCount(context)
        val raw = prefs(context).getString(KEY_STEP_PLAN, "").orEmpty()
        val parsed = raw.split(',').mapNotNull { it.toIntOrNull() }
        if (parsed.size == expected) return parsed
        return buildStepPlan(targetSpeedKmh(context), totalDurationMs(context), expected)
    }

    fun stepsForChunk(context: Context, index: Int): Int =
        stepPlan(context).getOrElse(index) { 0 }

    fun metricsAt(context: Context, elapsedMs: Long): Metrics {
        val totalDuration = totalDurationMs(context)
        val safeElapsed = elapsedMs.coerceIn(0L, totalDuration)
        val chunks = chunkCount(context)
        val plan = stepPlan(context)
        val full = fullChunksElapsed(context, safeElapsed)

        var meters = (0 until full).sumOf { distanceForChunk(context, it) }
        var steps = plan.take(full).sumOf { it.toLong() }

        if (full < chunks && safeElapsed < totalDuration) {
            val start = chunkStartOffsetMs(full)
            val elapsedInChunk = (safeElapsed - start).coerceAtLeast(0L)
            val duration = chunkDurationMs(context, full).coerceAtLeast(1L)
            val fraction = (elapsedInChunk.toDouble() / duration).coerceIn(0.0, 1.0)
            meters += distanceForChunk(context, full) * fraction
            steps += (plan.getOrElse(full) { 0 } * fraction).roundToLong()
        }

        return Metrics(
            durationMs = safeElapsed,
            distanceMeters = meters.coerceIn(0.0, targetDistanceMeters(context)),
            steps = steps.coerceAtLeast(0L)
        )
    }

    fun markChunkWritten(context: Context, completedChunks: Int) {
        prefs(context).edit()
            .putInt(KEY_COMPLETED_CHUNKS, completedChunks.coerceIn(0, chunkCount(context)))
            .apply()
    }

    fun finish(context: Context) {
        saveResult(
            context = context,
            durationMs = totalDurationMs(context),
            distanceMeters = targetDistanceMeters(context),
            steps = stepPlan(context).sumOf { it.toLong() },
            finished = true,
            stopped = false
        )
    }

    fun stop(context: Context, durationMs: Long, distanceMeters: Double, steps: Long) {
        saveResult(
            context = context,
            durationMs = durationMs.coerceIn(0L, totalDurationMs(context)),
            distanceMeters = distanceMeters.coerceIn(0.0, targetDistanceMeters(context)),
            steps = steps.coerceAtLeast(0L),
            finished = false,
            stopped = true
        )
    }

    private fun saveResult(
        context: Context,
        durationMs: Long,
        distanceMeters: Double,
        steps: Long,
        finished: Boolean,
        stopped: Boolean
    ) {
        val startedAt = startTimeMillis(context)
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_FINISHED, finished)
            .putBoolean(KEY_STOPPED, stopped)
            .putLong(KEY_FINAL_DURATION, durationMs)
            .putString(KEY_FINAL_DISTANCE, distanceMeters.toString())
            .putLong(KEY_FINAL_STEPS, steps)
            .remove(KEY_ERROR)
            .apply()

        addHistoryOnce(
            context,
            HistoryEntry(
                startedAtMillis = startedAt,
                endedAtMillis = System.currentTimeMillis(),
                durationMs = durationMs,
                distanceMeters = distanceMeters,
                steps = steps,
                speedKmh = targetSpeedKmh(context),
                diagnostic = Diagnostic(status = DIAGNOSTIC_PENDING)
            )
        )
    }

    private fun addHistoryOnce(context: Context, entry: HistoryEntry) {
        val p = prefs(context)
        if (p.getBoolean(KEY_HISTORY_SAVED, false)) return
        writeHistory(context, (listOf(entry) + history(context)).sortedByDescending { it.endedAtMillis }.take(5))
        p.edit().putBoolean(KEY_HISTORY_SAVED, true).apply()
    }

    fun updateDiagnostic(context: Context, startedAtMillis: Long, diagnostic: Diagnostic) {
        writeHistory(context, history(context).map {
            if (it.startedAtMillis == startedAtMillis) it.copy(diagnostic = diagnostic) else it
        })
    }

    fun setGoResult(context: Context, startedAtMillis: Long, result: GoResult) {
        writeHistory(context, history(context).map {
            if (it.startedAtMillis == startedAtMillis) it.copy(goResult = result) else it
        })
    }

    fun pendingDiagnostic(context: Context): HistoryEntry? =
        history(context).firstOrNull { it.diagnostic.status == DIAGNOSTIC_PENDING }

    private fun writeHistory(context: Context, entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.sortedByDescending { it.endedAtMillis }.take(5).forEach { entry ->
            array.put(JSONObject().apply {
                put("startedAt", entry.startedAtMillis)
                put("endedAt", entry.endedAtMillis)
                put("duration", entry.durationMs)
                put("distance", entry.distanceMeters)
                put("steps", entry.steps)
                put("speedKmh", entry.speedKmh)
                put("goResult", entry.goResult.storedValue)
                put("diagnostic", JSONObject().apply {
                    put("status", entry.diagnostic.status)
                    put("checkedAt", entry.diagnostic.checkedAtMillis)
                    put("confirmedDistance", entry.diagnostic.confirmedDistanceMeters)
                    put("confirmedSteps", entry.diagnostic.confirmedSteps)
                    put("distanceRecords", entry.diagnostic.distanceRecords)
                    put("stepsRecords", entry.diagnostic.stepsRecords)
                    put("origin", entry.diagnostic.origin)
                    put("recordingMethod", entry.diagnostic.recordingMethod)
                    if (!entry.diagnostic.error.isNullOrBlank()) put("error", entry.diagnostic.error)
                })
            })
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun history(context: Context): List<HistoryEntry> = try {
        val array = JSONArray(prefs(context).getString(KEY_HISTORY, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val d = item.optJSONObject("diagnostic")
                val diagnostic = if (d != null) {
                    Diagnostic(
                        status = d.optString("status", DIAGNOSTIC_UNAVAILABLE),
                        checkedAtMillis = d.optLong("checkedAt"),
                        confirmedDistanceMeters = d.optDouble("confirmedDistance"),
                        confirmedSteps = d.optLong("confirmedSteps"),
                        distanceRecords = d.optInt("distanceRecords"),
                        stepsRecords = d.optInt("stepsRecords"),
                        origin = d.optString("origin", ""),
                        recordingMethod = d.optInt("recordingMethod"),
                        error = d.optString("error", "").takeIf { it.isNotBlank() }
                    )
                } else {
                    Diagnostic(status = DIAGNOSTIC_UNAVAILABLE)
                }

                add(
                    HistoryEntry(
                        startedAtMillis = item.optLong(
                            "startedAt",
                            item.optLong("endedAt") - item.optLong("duration")
                        ),
                        endedAtMillis = item.optLong("endedAt"),
                        durationMs = item.optLong("duration"),
                        distanceMeters = item.optDouble("distance"),
                        steps = item.optLong("steps"),
                        speedKmh = item.optInt("speedKmh", DEFAULT_SPEED_KMH)
                            .coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH),
                        goResult = GoResult.fromStored(item.optString("goResult", GoResult.UNKNOWN.storedValue)),
                        diagnostic = diagnostic
                    )
                )
            }
        }.sortedByDescending { it.endedAtMillis }.take(5)
    } catch (_: Throwable) {
        emptyList()
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().putString(KEY_HISTORY, "[]").apply()
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
    fun completedChunks(context: Context): Int =
        prefs(context).getInt(KEY_COMPLETED_CHUNKS, 0).coerceIn(0, chunkCount(context))
    fun isFinished(context: Context): Boolean = prefs(context).getBoolean(KEY_FINISHED, false)
    fun isStopped(context: Context): Boolean = prefs(context).getBoolean(KEY_STOPPED, false)
    fun hasResult(context: Context): Boolean = isFinished(context) || isStopped(context)
    fun error(context: Context): String? = prefs(context).getString(KEY_ERROR, null)

    fun recordPrefix(startedAtMillis: Long): String = "pokewalk-$startedAtMillis"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
