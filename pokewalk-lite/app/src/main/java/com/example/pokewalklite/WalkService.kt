package com.example.pokewalklite

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class WalkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PokeWalk progress", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            job?.cancel()
            return START_NOT_STICKY
        }

        if (job?.isActive == true) return START_STICKY
        stopRequested = false
        val start = WalkState.ensureStarted(this)
        startAsForeground(0)
        job = scope.launch { runWalk(start) }
        return START_STICKY
    }

    private suspend fun runWalk(startMillis: Long) {
        val client = HealthConnectClient.getOrCreate(this)
        val sessionId = UUID.randomUUID().toString()
        val sessionStart = Instant.ofEpochMilli(startMillis)
        val plan = WalkState.stepPlan(this)
        val chunks = WalkState.chunkCount(this)

        try {
            val first = WalkState.completedChunks(this)
            for (index in first until chunks) {
                val intervalStart = sessionStart.plusSeconds(index * 60L)
                val intervalEnd = sessionStart.plusSeconds((index + 1) * 60L)
                val waitMs = intervalEnd.toEpochMilli() - System.currentTimeMillis()
                if (waitMs > 0) delay(waitMs)

                writeChunk(
                    client = client,
                    sessionId = sessionId,
                    index = index,
                    intervalStart = intervalStart,
                    intervalEnd = intervalEnd,
                    meters = WalkState.METERS_PER_MINUTE,
                    steps = plan[index].toLong()
                )

                WalkState.markChunkWritten(this, index + 1)
                updateNotification(index + 1)
            }

            WalkState.finish(this)
            val km = WalkState.targetDistanceKm(this)
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(chunks, String.format("%d,00 km concluídos", km))
            )
        } catch (cancelled: CancellationException) {
            if (!stopRequested) throw cancelled
        } catch (t: Throwable) {
            WalkState.fail(this, t.message ?: t.javaClass.simpleName)
        } finally {
            if (stopRequested) {
                withContext(NonCancellable) {
                    finalizeStoppedWalk(client, sessionStart, sessionId)
                }
            }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private suspend fun finalizeStoppedWalk(
        client: HealthConnectClient,
        sessionStart: Instant,
        sessionId: String
    ) {
        val totalDuration = WalkState.totalDurationMs(this)
        val durationMs = (System.currentTimeMillis() - sessionStart.toEpochMilli()).coerceIn(0L, totalDuration)
        val completed = WalkState.completedChunks(this)
        val chunks = WalkState.chunkCount(this)
        val plan = WalkState.stepPlan(this)

        if (completed < chunks) {
            val partialStart = sessionStart.plusSeconds(completed * 60L)
            val partialEndMillis = sessionStart.toEpochMilli() + durationMs
            val partialEnd = Instant.ofEpochMilli(partialEndMillis)
            val partialMs = (partialEndMillis - partialStart.toEpochMilli()).coerceIn(0L, 59_999L)

            if (partialMs > 0L) {
                val fraction = partialMs / 60_000.0
                val partialMeters = WalkState.METERS_PER_MINUTE * fraction
                val partialSteps = (plan[completed] * fraction).toLong().coerceAtLeast(0L)

                runCatching {
                    writeChunk(
                        client = client,
                        sessionId = sessionId,
                        index = completed,
                        intervalStart = partialStart,
                        intervalEnd = partialEnd,
                        meters = partialMeters,
                        steps = partialSteps
                    )
                }
            }
        }

        val metrics = WalkState.metricsAt(this, durationMs)
        WalkState.stop(this, metrics.durationMs, metrics.distanceMeters, metrics.steps)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(
                WalkState.completedChunks(this),
                String.format("Parado • %.2f km", metrics.distanceMeters / 1000.0)
            )
        )
    }

    private suspend fun writeChunk(
        client: HealthConnectClient,
        sessionId: String,
        index: Int,
        intervalStart: Instant,
        intervalEnd: Instant,
        meters: Double,
        steps: Long
    ) {
        val device = Device(type = Device.TYPE_PHONE)
        val zone = ZoneId.systemDefault()

        if (meters > 0.0) {
            client.insertRecords(
                listOf(
                    DistanceRecord(
                        distance = Length.meters(meters),
                        startTime = intervalStart,
                        startZoneOffset = zone.rules.getOffset(intervalStart),
                        endTime = intervalEnd,
                        endZoneOffset = zone.rules.getOffset(intervalEnd),
                        metadata = Metadata.activelyRecorded(
                            device = device,
                            clientRecordId = "$sessionId-distance-$index-${intervalEnd.toEpochMilli()}"
                        )
                    )
                )
            )
        }

        if (steps > 0L) {
            client.insertRecords(
                listOf(
                    StepsRecord(
                        count = steps,
                        startTime = intervalStart,
                        startZoneOffset = zone.rules.getOffset(intervalStart),
                        endTime = intervalEnd,
                        endZoneOffset = zone.rules.getOffset(intervalEnd),
                        metadata = Metadata.activelyRecorded(
                            device = device,
                            clientRecordId = "$sessionId-steps-$index-${intervalEnd.toEpochMilli()}"
                        )
                    )
                )
            )
        }
    }

    private fun notification(progress: Int, text: String): android.app.Notification {
        val max = WalkState.chunkCount(this).coerceAtLeast(1)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PokeWalk Lite")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(WalkState.isRunning(this))
            .setProgress(max, progress.coerceIn(0, max), false)
            .build()
    }

    private fun startAsForeground(progress: Int) {
        val km = WalkState.targetDistanceKm(this)
        val n = notification(progress, "$km km a 10 km/h")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else startForeground(NOTIFICATION_ID, n)
    }

    private fun updateNotification(completed: Int) {
        val km = completed * WalkState.METERS_PER_MINUTE / 1000.0
        val target = WalkState.targetDistanceKm(this).toDouble()
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(completed, String.format("%.2f / %.2f km", km, target))
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.pokewalklite.STOP_WALK"
        private const val CHANNEL_ID = "pokewalk_progress"
        private const val NOTIFICATION_ID = 5001
    }
}
