package com.example.pokewalklite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private lateinit var button: Button
    private lateinit var status: TextView
    private lateinit var elapsed: TextView
    private lateinit var distance: TextView
    private lateinit var steps: TextView

    private val healthPermissions = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class)
    )

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthPermissions)) ensureActivityPermissionAndStart()
        else {
            status.text = "Permita passos e distância no Health Connect."
            button.isEnabled = true
        }
    }

    private val activityRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startWalk()
        else {
            status.text = "Permissão de atividade necessária."
            button.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "PokeWalk Lite"
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "5 km • 10 km/h • 30 min"
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, pad)
        })

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        elapsed = addMetric(metrics, "TEMPO", "00:00")
        distance = addMetric(metrics, "DISTÂNCIA", "0,00 km")
        steps = addMetric(metrics, "PASSOS", "0")
        root.addView(metrics, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pad })

        button = Button(this).apply {
            text = "ADICIONAR 5 KM"
            textSize = 18f
            minHeight = (56 * d).toInt()
            setOnClickListener { prepareWalk() }
        }
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        status = TextView(this).apply {
            text = "Pronto."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        root.addView(status)
        setContentView(root)
        render()
    }

    private fun addMetric(parent: LinearLayout, label: String, initial: String): TextView {
        val value = TextView(this).apply {
            text = initial
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(value)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
            })
        }
        parent.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return value
    }

    override fun onResume() {
        super.onResume()
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                render()
                delay(1_000L)
            }
        }
    }

    override fun onPause() {
        ticker?.cancel()
        ticker = null
        super.onPause()
    }

    private fun render() {
        val running = WalkState.isRunning(this)
        val start = WalkState.startTimeMillis(this)
        val finished = WalkState.isFinished(this)
        val elapsedMs = when {
            start <= 0L -> 0L
            finished -> WalkState.TOTAL_DURATION_MS
            else -> (System.currentTimeMillis() - start).coerceIn(0L, WalkState.TOTAL_DURATION_MS)
        }
        val progress = elapsedMs.toDouble() / WalkState.TOTAL_DURATION_MS
        val meters = WalkState.TOTAL_DISTANCE_METERS * progress
        val stepCount = (WalkState.TOTAL_STEPS * progress).roundToLong()
        val sec = elapsedMs / 1000L

        elapsed.text = String.format(Locale.getDefault(), "%02d:%02d", sec / 60L, sec % 60L)
        distance.text = String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)
        steps.text = String.format(Locale.getDefault(), "%,d", stepCount)
        button.isEnabled = !running
        button.text = if (running) "CAMINHANDO…" else "ADICIONAR 5 KM"
        status.text = when {
            running -> "Caminhando • Health Connect: ${WalkState.completedChunks(this)}/30 gravações"
            WalkState.error(this) != null -> "Erro: ${WalkState.error(this)}"
            finished -> "Concluído: 5,00 km em 30:00."
            else -> "Pronto."
        }
    }

    private fun prepareWalk() {
        if (WalkState.isRunning(this)) return
        button.isEnabled = false
        status.text = "Verificando Health Connect…"

        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            status.text = "Health Connect indisponível ou desatualizado."
            button.isEnabled = true
            return
        }

        val client = HealthConnectClient.getOrCreate(this)
        scope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(healthPermissions)) ensureActivityPermissionAndStart()
            else healthPermissionLauncher.launch(healthPermissions)
        }
    }

    private fun ensureActivityPermissionAndStart() {
        if (android.os.Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else startWalk()
    }

    private fun startWalk() {
        WalkState.begin(this)
        ContextCompat.startForegroundService(this, Intent(this, WalkService::class.java))
        render()
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
