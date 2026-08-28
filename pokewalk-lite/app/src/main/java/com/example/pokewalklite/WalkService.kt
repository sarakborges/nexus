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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class WalkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PokeWalk progress", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_STICKY
        val start = WalkState.ensureStarted(this)
        startAsForeground(0)
        job = scope.launch { runWalk(start) }
        return START_STICKY
    }

    private suspend fun runWalk(startMillis: Long) {
        val client = HealthConnectClient.getOrCreate(this)
        val sessionId = UUID.randomUUID().toString()
        val sessionStart = Instant.ofEpochMilli(startMillis)
        val metersPerMinute = WalkState.TOTAL_DISTANCE_METERS / WalkState.CHUNK_COUNT
        val baseSteps = (WalkState.TOTAL_STEPS / WalkState.CHUNK_COUNT).toInt()
        val extraSteps = (WalkState.TOTAL_STEPS % WalkState.CHUNK_COUNT).toInt()

        try {
            val first = WalkState.completedChunks(this)
            for (index in first until WalkState.CHUNK_COUNT) {
                val intervalStart = sessionStart.plusSeconds(index * 60L)
                val intervalEnd = sessionStart.plusSeconds((index + 1) * 60L)
                val waitMs = intervalEnd.toEpochMilli() - System.currentTimeMillis()
                if (waitMs > 0) delay(waitMs)

                val device = Device(type = Device.TYPE_PHONE)
                val stepsThisMinute = baseSteps + if (index < extraSteps) 1 else 0
                val distance = DistanceRecord(
                    distance = Length.meters(metersPerMinute),
                    startTime = intervalStart,
                    startZoneOffset = ZoneId.systemDefault().rules.getOffset(intervalStart),
                    endTime = intervalEnd,
                    endZoneOffset = ZoneId.systemDefault().rules.getOffset(intervalEnd),
                    metadata = Metadata.activelyRecorded(
                        device = device,
                        clientRecordId = "$sessionId-distance-$index"
                    )
                )
                val steps = StepsRecord(
                    count = stepsThisMinute.toLong(),
                    startTime = intervalStart,
                    startZoneOffset = ZoneId.systemDefault().rules.getOffset(intervalStart),
                    endTime = intervalEnd,
                    endZoneOffset = ZoneId.systemDefault().rules.getOffset(intervalEnd),
                    metadata = Metadata.activelyRecorded(
                        device = device,
                        clientRecordId = "$sessionId-steps-$index"
                    )
                )

                client.insertRecords(listOf(distance, steps))
                WalkState.markChunkWritten(this, index + 1)
                updateNotification(index + 1)
            }
            WalkState.finish(this)
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(WalkState.CHUNK_COUNT, "5,00 km concluídos")
            )
        } catch (t: Throwable) {
            WalkState.fail(this, t.message ?: t.javaClass.simpleName)
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun notification(progress: Int, text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("PokeWalk Lite")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(progress < WalkState.CHUNK_COUNT)
        .setProgress(WalkState.CHUNK_COUNT, progress, false)
        .build()

    private fun startAsForeground(progress: Int) {
        val n = notification(progress, "5 km a 10 km/h")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else startForeground(NOTIFICATION_ID, n)
    }

    private fun updateNotification(completed: Int) {
        val km = completed * WalkState.TOTAL_DISTANCE_METERS / WalkState.CHUNK_COUNT / 1000.0
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(completed, String.format("%.2f / 5.00 km", km))
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "pokewalk_progress"
        private const val NOTIFICATION_ID = 5001
    }
}
