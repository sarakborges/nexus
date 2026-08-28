package com.example.pokewalklite

import android.content.Context

object WalkState {
    const val TOTAL_DURATION_MS = 30L * 60L * 1000L
    const val TOTAL_DISTANCE_METERS = 5000.0
    const val TOTAL_STEPS = 6500L
    const val CHUNK_COUNT = 30

    private const val PREFS = "pokewalk_state"
    private const val KEY_RUNNING = "running"
    private const val KEY_START_TIME = "start_time"
    private const val KEY_COMPLETED_CHUNKS = "completed_chunks"
    private const val KEY_FINISHED = "finished"
    private const val KEY_ERROR = "error"

    fun begin(context: Context, startTimeMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_START_TIME, startTimeMillis)
            .putInt(KEY_COMPLETED_CHUNKS, 0)
            .putBoolean(KEY_FINISHED, false)
            .remove(KEY_ERROR)
            .apply()
    }

    fun ensureStarted(context: Context): Long {
        val p = prefs(context)
        val existing = p.getLong(KEY_START_TIME, 0L)
        if (p.getBoolean(KEY_RUNNING, false) && existing > 0L) return existing
        val now = System.currentTimeMillis()
        begin(context, now)
        return now
    }

    fun markChunkWritten(context: Context, completedChunks: Int) {
        prefs(context).edit()
            .putInt(KEY_COMPLETED_CHUNKS, completedChunks.coerceIn(0, CHUNK_COUNT))
            .apply()
    }

    fun finish(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_FINISHED, true)
            .putInt(KEY_COMPLETED_CHUNKS, CHUNK_COUNT)
            .remove(KEY_ERROR)
            .apply()
    }

    fun fail(context: Context, message: String) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_FINISHED, false)
            .putString(KEY_ERROR, message)
            .apply()
    }

    fun isRunning(context: Context): Boolean = prefs(context).getBoolean(KEY_RUNNING, false)
    fun startTimeMillis(context: Context): Long = prefs(context).getLong(KEY_START_TIME, 0L)
    fun completedChunks(context: Context): Int = prefs(context).getInt(KEY_COMPLETED_CHUNKS, 0)
    fun isFinished(context: Context): Boolean = prefs(context).getBoolean(KEY_FINISHED, false)
    fun error(context: Context): String? = prefs(context).getString(KEY_ERROR, null)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
