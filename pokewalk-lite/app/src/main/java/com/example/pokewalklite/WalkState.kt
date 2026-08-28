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

    enum class ActivityMode(
        val storedValue: String,
        val label: String,
        val shortLabel: String
    ) {
        WALK("walk", "Caminhada", "CAM."),
        RUN("run", "Corrida", "COR.");

        companion object {
            fun fromStored(value: String?): ActivityMode =
                entries.firstOrNull { it.storedValue == value } ?: WALK
        }
    }

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
        val speedRecords: Int = 0,
        val exerciseRecords: Int = 0,
        val averageSpeedKmh: Double = 0.0,
        val origin: String = "",
        val recordingMethod: Int = 0,
        val error: String? = null
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
    private const val KEY_TARGET_MODE = "target_mode"
    private const val KEY_PREFERRED_MODE = "preferred_mode"
    private const val KEY_TARGET_SPEED = "target_speed_kmh"
    private const val KEY_PREFERRED_SPEED = "preferred_speed_kmh"
    private const val KEY_STEP_PLAN = "step_plan"
    private const val KEY_DISTANCE_PLAN = "distance_plan"
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
        val mode: ActivityMode,
        val speedKmh: Int,
        val goResult: GoResult = GoResult.UNKNOWN,
        val diagnostic: Diagnostic = Diagnostic()
    )

    fun begin(
        context: Context,
        distanceKm: Int,
        mode: ActivityMode = preferredMode(context),
        speedKmh: Int = preferredSpeedKmh(context),
        startTimeMillis: Long = System.currentTimeMillis()
    ) {
        val km = distanceKm.coerceIn(1, 20)
        val speed = speedKmh.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
        val durationMs = calculateDurationMs(km, speed)
        val chunks = calculateChunkCount(durationMs)
        val distancePlan = buildDistancePlan(km * 1000.0, speed, durationMs, chunks)
        val stepPlan = buildStepPlan(speed, durationMs, distancePlan)

        prefs(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_START_TIME, startTimeMillis)
            .putInt(KEY_COMPLETED_CHUNKS, 0)
            .putBoolean(KEY_FINISHED, false)
            .putBoolean(KEY_STOPPED, false)
            .putInt(KEY_TARGET_KM, km)
            .putInt(KEY_PREFERRED_KM, km)
            .putString(KEY_TARGET_MODE, mode.storedValue)
            .putString(KEY_PREFERRED_MODE, mode.storedValue)
            .putInt(KEY_TARGET_SPEED, speed)
            .putInt(KEY_PREFERRED_SPEED, speed)
            .putString(KEY_STEP_PLAN, stepPlan.joinToString(","))
            .putString(KEY_DISTANCE_PLAN, distancePlan.joinToString(",") { "%.6f".format(java.util.Locale.US, it) })
            .putLong(KEY_FINAL_DURATION, 0L)
            .putString(KEY_FINAL_DISTANCE, "0")
            .putLong(KEY_FINAL_STEPS, 0L)
            .putBoolean(KEY_HISTORY_SAVED, false)
            .remove(KEY_ERROR)
            .apply()
    }

    private fun buildDistancePlan(
        targetMeters: Double,
        speedKmh: Int,
        durationMs: Long,
        chunks: Int
    ): List<Double> {
        var factor = 1.0 + Random.nextDouble(-0.025, 0.025)
        val baseMetersPerMs = speedKmh * 1000.0 / 3_600_000.0
        val raw = MutableList(chunks) { index ->
            factor = (factor + Random.nextDouble(-0.018, 0.018)).coerceIn(0.94, 1.06)
            baseMetersPerMs * chunkDurationMs(durationMs, chunks, index) * factor
        }
        val rawTotal = raw.sum().coerceAtLeast(0.000001)
        val scale = targetMeters / rawTotal
        val normalized = raw.map { it * scale }.toMutableList()
        if (normalized.isNotEmpty()) {
            normalized[normalized.lastIndex] += targetMeters - normalized.sum()
        }
        return normalized
    }

    private fun buildStepPlan(
        speedKmh: Int,
        durationMs: Long,
        distancePlan: List<Double>
    ): List<Int> {
        val chunks = distancePlan.size
        val baseCadence = 60.0 + speedKmh * 14.0
        val baseMetersPerMs = speedKmh * 1000.0 / 3_600_000.0
        return distancePlan.mapIndexed { index, meters ->
            val chunkMs = chunkDurationMs(durationMs, chunks, index)
            val expectedMeters = (baseMetersPerMs * chunkMs).coerceAtLeast(0.000001)
            val paceFactor = meters / expectedMeters
            val cadence = (baseCadence * paceFactor + Random.nextInt(-5, 6)).coerceAtLeast(20.0)
            (cadence * chunkMs / 60_000.0).roundToInt().coerceAtLeast(1)
        }
    }

    fun ensureStarted(context: Context): Long {
        val p = prefs(context)
        val existing = p.getLong(KEY_START_TIME, 0L)
        if (p.getBoolean(KEY_RUNNING, false) && existing > 0L) return existing
        val now = System.currentTimeMillis()
        begin(context, preferredDistanceKm(context), preferredMode(context), preferredSpeedKmh(context), now)
        return now
    }

    fun setPreferredDistanceKm(context: Context, km: Int) {
        prefs(context).edit().putInt(KEY_PREFERRED_KM, km.coerceIn(1, 20)).apply()
    }

    fun setPreferredMode(context: Context, mode: ActivityMode) {
        prefs(context).edit().putString(KEY_PREFERRED_MODE, mode.storedValue).apply()
    }

    fun setPreferredSpeedKmh(context: Context, speedKmh: Int) {
        prefs(context).edit().putInt(KEY_PREFERRED_SPEED, speedKmh.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)).apply()
    }

    fun preferredDistanceKm(context: Context): Int = prefs(context).getInt(KEY_PREFERRED_KM, 5).coerceIn(1, 20)
    fun targetDistanceKm(context: Context): Int = prefs(context).getInt(KEY_TARGET_KM, preferredDistanceKm(context)).coerceIn(1, 20)
    fun preferredMode(context: Context): ActivityMode = ActivityMode.fromStored(prefs(context).getString(KEY_PREFERRED_MODE, null))
    fun targetMode(context: Context): ActivityMode = ActivityMode.fromStored(prefs(context).getString(KEY_TARGET_MODE, preferredMode(context).storedValue))
    fun preferredSpeedKmh(context: Context): Int = prefs(context).getInt(KEY_PREFERRED_SPEED, DEFAULT_SPEED_KMH).coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
    fun targetSpeedKmh(context: Context): Int = prefs(context).getInt(KEY_TARGET_SPEED, preferredSpeedKmh(context)).coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
    fun targetDistanceMeters(context: Context): Double = targetDistanceKm(context) * 1000.0

    fun calculateDurationMs(distanceKm: Int, speedKmh: Int): Long =
        (distanceKm.coerceIn(1, 20) * 3_600_000.0 / speedKmh.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH))
            .roundToLong()
            .coerceAtLeast(1_000L)

    private fun calculateChunkCount(durationMs: Long): Int = ceil(durationMs / 60_000.0).toInt().coerceAtLeast(1)

    fun chunkCount(context: Context): Int = calculateChunkCount(totalDurationMs(context))
    fun totalDurationMs(context: Context): Long = calculateDurationMs(targetDistanceKm(context), targetSpeedKmh(context))

    private fun chunkDurationMs(totalDurationMs: Long, chunks: Int, index: Int): Long {
        if (index !in 0 until chunks) return 0L
        val start = index * 60_000L
        return (totalDurationMs - start).coerceIn(0L, 60_000L)
    }

    fun chunkDurationMs(context: Context, index: Int): Long =
        chunkDurationMs(totalDurationMs(context), chunkCount(context), index)

    fun chunkStartOffsetMs(index: Int): Long = index.coerceAtLeast(0) * 60_000L

    fun chunkEndOffsetMs(context: Context, index: Int): Long =
        (chunkStartOffsetMs(index) + chunkDurationMs(context, index)).coerceAtMost(totalDurationMs(context))

    fun fullChunksElapsed(context: Context, elapsedMs: Long): Int {
        val safe = elapsedMs.coerceIn(0L, totalDurationMs(context))
        return if (safe >= totalDurationMs(context)) chunkCount(context)
        else (safe / 60_000L).toInt().coerceIn(0, chunkCount(context))
    }

    fun stepPlan(context: Context): List<Int> {
        val raw = prefs(context).getString(KEY_STEP_PLAN, "").orEmpty()
        val parsed = raw.split(',').mapNotNull { it.toIntOrNull() }
        val expected = chunkCount(context)
        if (parsed.size == expected) return parsed
        val speed = targetSpeedKmh(context)
        val duration = totalDurationMs(context)
        val fallbackDistance = constantDistancePlan(context)
        return buildStepPlan(speed, duration, fallbackDistance)
    }

    fun distancePlan(context: Context): List<Double> {
        val raw = prefs(context).getString(KEY_DISTANCE_PLAN, "").orEmpty()
        val parsed = raw.split(',').mapNotNull { it.toDoubleOrNull() }
        val expected = chunkCount(context)
        if (parsed.size == expected && parsed.all { it >= 0.0 }) return parsed
        return constantDistancePlan(context)
    }

    private fun constantDistancePlan(context: Context): List<Double> {
        val chunks = chunkCount(context)
        val duration = totalDurationMs(context)
        val total = targetDistanceMeters(context)
        val base = targetSpeedKmh(context) * 1000.0 / 3_600_000.0
        val result = MutableList(chunks) { index -> base * chunkDurationMs(duration, chunks, index) }
        if (result.isNotEmpty()) result[result.lastIndex] += total - result.sum()
        return result
    }

    fun distanceForChunk(context: Context, index: Int): Double = distancePlan(context).getOrElse(index) { 0.0 }
    fun stepsForChunk(context: Context, index: Int): Int = stepPlan(context).getOrElse(index) { 0 }

    fun speedForChunkKmh(context: Context, index: Int): Double {
        val duration = chunkDurationMs(context, index)
        if (duration <= 0L) return targetSpeedKmh(context).toDouble()
        return distanceForChunk(context, index) * 3_600_000.0 / duration / 1000.0
    }

    fun metricsAt(context: Context, elapsedMs: Long): Metrics {
        val totalDuration = totalDurationMs(context)
        val safeElapsed = elapsedMs.coerceIn(0L, totalDuration)
        val distances = distancePlan(context)
        val steps = stepPlan(context)
        val fullChunks = fullChunksElapsed(context, safeElapsed)
        var meters = distances.take(fullChunks).sum()
        var stepCount = steps.take(fullChunks).sumOf { it.toLong() }

        if (fullChunks < chunkCount(context) && safeElapsed < totalDuration) {
            val chunkStart = chunkStartOffsetMs(fullChunks)
            val elapsedInChunk = (safeElapsed - chunkStart).coerceAtLeast(0L)
            val chunkDuration = chunkDurationMs(context, fullChunks).coerceAtLeast(1L)
            val fraction = (elapsedInChunk.toDouble() / chunkDuration).coerceIn(0.0, 1.0)
            meters += distances.getOrElse(fullChunks) { 0.0 } * fraction
            stepCount += (steps.getOrElse(fullChunks) { 0 } * fraction).roundToLong()
        }

        return Metrics(
            safeElapsed,
            meters.coerceIn(0.0, targetDistanceMeters(context)),
            stepCount.coerceAtLeast(0L)
        )
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

    private fun saveResult(
        context: Context,
        durationMs: Long,
        distanceMeters: Double,
        steps: Long,
        finished: Boolean,
        stopped: Boolean
    ) {
        val mode = targetMode(context)
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
                mode = mode,
                speedKmh = targetSpeedKmh(context),
                diagnostic = Diagnostic(status = DIAGNOSTIC_PENDING)
            )
        )
    }

    private fun addHistoryOnce(context: Context, entry: HistoryEntry) {
        val p = prefs(context)
        if (p.getBoolean(KEY_HISTORY_SAVED, false)) return
        val existing = history(context)
        val newest = (listOf(entry) + existing)
            .sortedByDescending { it.endedAtMillis }
            .take(5)
        writeHistory(context, newest)
        p.edit().putBoolean(KEY_HISTORY_SAVED, true).apply()
    }

    fun updateDiagnostic(context: Context, startedAtMillis: Long, diagnostic: Diagnostic) {
        val updated = history(context).map {
            if (it.startedAtMillis == startedAtMillis) it.copy(diagnostic = diagnostic) else it
        }
        writeHistory(context, updated)
    }

    fun setGoResult(context: Context, startedAtMillis: Long, result: GoResult) {
        val updated = history(context).map {
            if (it.startedAtMillis == startedAtMillis) it.copy(goResult = result) else it
        }
        writeHistory(context, updated)
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
                put("mode", entry.mode.storedValue)
                put("speedKmh", entry.speedKmh)
                put("goResult", entry.goResult.storedValue)
                put("diagnostic", JSONObject().apply {
                    put("status", entry.diagnostic.status)
                    put("checkedAt", entry.diagnostic.checkedAtMillis)
                    put("confirmedDistance", entry.diagnostic.confirmedDistanceMeters)
                    put("confirmedSteps", entry.diagnostic.confirmedSteps)
                    put("distanceRecords", entry.diagnostic.distanceRecords)
                    put("stepsRecords", entry.diagnostic.stepsRecords)
                    put("speedRecords", entry.diagnostic.speedRecords)
                    put("exerciseRecords", entry.diagnostic.exerciseRecords)
                    put("averageSpeedKmh", entry.diagnostic.averageSpeedKmh)
                    put("origin", entry.diagnostic.origin)
                    put("recordingMethod", entry.diagnostic.recordingMethod)
                    if (entry.diagnostic.error != null) put("error", entry.diagnostic.error)
                })
            })
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().putString(KEY_HISTORY, "[]").apply()
    }

    fun history(context: Context): List<HistoryEntry> {
        return try {
            val array = JSONArray(prefs(context).getString(KEY_HISTORY, "[]"))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val diagnosticObject = item.optJSONObject("diagnostic")
                    val diagnostic = if (diagnosticObject != null) {
                        Diagnostic(
                            status = diagnosticObject.optString("status", DIAGNOSTIC_UNAVAILABLE),
                            checkedAtMillis = diagnosticObject.optLong("checkedAt"),
                            confirmedDistanceMeters = diagnosticObject.optDouble("confirmedDistance"),
                            confirmedSteps = diagnosticObject.optLong("confirmedSteps"),
                            distanceRecords = diagnosticObject.optInt("distanceRecords"),
                            stepsRecords = diagnosticObject.optInt("stepsRecords"),
                            speedRecords = diagnosticObject.optInt("speedRecords"),
                            exerciseRecords = diagnosticObject.optInt("exerciseRecords"),
                            averageSpeedKmh = diagnosticObject.optDouble("averageSpeedKmh"),
                            origin = diagnosticObject.optString("origin", ""),
                            recordingMethod = diagnosticObject.optInt("recordingMethod"),
                            error = diagnosticObject.optString("error", "").takeIf { it.isNotBlank() }
                        )
                    } else {
                        Diagnostic(status = DIAGNOSTIC_UNAVAILABLE)
                    }
                    add(
                        HistoryEntry(
                            startedAtMillis = item.optLong("startedAt", item.optLong("endedAt") - item.optLong("duration")),
                            endedAtMillis = item.optLong("endedAt"),
                            durationMs = item.optLong("duration"),
                            distanceMeters = item.optDouble("distance"),
                            steps = item.optLong("steps"),
                            mode = ActivityMode.fromStored(item.optString("mode", ActivityMode.WALK.storedValue)),
                            speedKmh = item.optInt("speedKmh", DEFAULT_SPEED_KMH).coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH),
                            goResult = GoResult.fromStored(item.optString("goResult", GoResult.UNKNOWN.storedValue)),
                            diagnostic = diagnostic
                        )
                    )
                }
            }.sortedByDescending { it.endedAtMillis }.take(5)
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
