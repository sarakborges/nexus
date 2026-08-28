package com.example.pokewalklite

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

class WalkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var notificationTicker: Job? = null
    @Volatile private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    ACTIVE_CHANNEL_ID,
                    "Caminhada em andamento",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Contador permanente das caminhadas do PokeWalk"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    "Resultado das caminhadas",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notificação simples quando uma caminhada termina"
                }
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
        startAsForeground(start)
        startNotificationTicker(start)
        job = scope.launch { runWalk(start) }
        return START_STICKY
    }

    private fun startNotificationTicker(startMillis: Long) {
        notificationTicker?.cancel()
        notificationTicker = scope.launch {
            while (isActive && WalkState.isRunning(this@WalkService)) {
                getSystemService(NotificationManager::class.java).notify(
                    ACTIVE_NOTIFICATION_ID,
                    liveNotification(startMillis)
                )
                delay(1_000L)
            }
        }
    }

    private fun liveNotification(startMillis: Long): android.app.Notification {
        val totalMs = WalkState.totalDurationMs(this).coerceAtLeast(1L)
        val elapsedMs = (System.currentTimeMillis() - startMillis).coerceIn(0L, totalMs)
        val metrics = WalkState.metricsAt(this, elapsedMs)
        val elapsedSeconds = (elapsedMs / 1_000L).toInt()
        val totalSeconds = (totalMs / 1_000L).toInt().coerceAtLeast(1)
        val text = String.format(
            Locale.getDefault(),
            "%s • %.2f km • %,d passos",
            formatDuration(metrics.durationMs),
            metrics.distanceMeters / 1000.0,
            metrics.steps
        )

        return NotificationCompat.Builder(this, ACTIVE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PokeWalk Lite • caminhando")
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setProgress(totalSeconds, elapsedSeconds.coerceIn(0, totalSeconds), false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun resultNotification(title: String, metrics: WalkState.Metrics): android.app.Notification {
        val text = String.format(
            Locale.getDefault(),
            "%s • %.2f km • %,d passos",
            formatDuration(metrics.durationMs),
            metrics.distanceMeters / 1000.0,
            metrics.steps
        )
        return NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.checkbox_on_background)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private suspend fun runWalk(startMillis: Long) {
        val client = HealthConnectClient.getOrCreate(this)
        val sessionId = UUID.randomUUID().toString()
        val sessionStart = Instant.ofEpochMilli(startMillis)
        val plan = WalkState.stepPlan(this)
        val chunks = WalkState.chunkCount(this)
        var resultTitle: String? = null
        var resultMetrics: WalkState.Metrics? = null

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
            }

            WalkState.finish(this)
            resultTitle = "Caminhada concluída"
            resultMetrics = WalkState.finalMetrics(this)
        } catch (cancelled: CancellationException) {
            if (!stopRequested) throw cancelled
        } catch (t: Throwable) {
            WalkState.fail(this, t.message ?: t.javaClass.simpleName)
        } finally {
            if (stopRequested) {
                resultMetrics = withContext(NonCancellable) {
                    finalizeStoppedWalk(client, sessionStart, sessionId)
                }
                resultTitle = "Caminhada interrompida"
            }

            notificationTicker?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (resultTitle != null && resultMetrics != null) {
                getSystemService(NotificationManager::class.java).notify(
                    RESULT_NOTIFICATION_ID,
                    resultNotification(resultTitle, resultMetrics)
                )
            }
            stopSelf()
        }
    }

    private suspend fun finalizeStoppedWalk(
        client: HealthConnectClient,
        sessionStart: Instant,
        sessionId: String
    ): WalkState.Metrics {
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
        return metrics
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

    private fun startAsForeground(startMillis: Long) {
        val notification = liveNotification(startMillis)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ACTIVE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(ACTIVE_NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notificationTicker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.pokewalklite.STOP_WALK"
        private const val ACTIVE_CHANNEL_ID = "pokewalk_active_v4"
        private const val RESULT_CHANNEL_ID = "pokewalk_results_v4"
        private const val ACTIVE_NOTIFICATION_ID = 5001
        private const val RESULT_NOTIFICATION_ID = 5002
    }
}
