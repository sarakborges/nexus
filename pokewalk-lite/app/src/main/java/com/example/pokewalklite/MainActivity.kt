package com.example.pokewalklite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TableLayout
import android.widget.TableRow
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

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private lateinit var button: Button
    private lateinit var status: TextView
    private lateinit var elapsed: TextView
    private lateinit var distance: TextView
    private lateinit var steps: TextView
    private lateinit var distanceSeek: SeekBar
    private lateinit var selectedDistanceLabel: TextView
    private lateinit var subtitle: TextView
    private lateinit var historyTable: TableLayout

    private var selectedKm = 5
    private var historySignature = ""
    private var defaultButtonTint: ColorStateList? = null
    private lateinit var defaultButtonTextColors: ColorStateList

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
            render()
        }
    }

    private val activityRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startWalk()
        else {
            status.text = "Permissão de atividade necessária."
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        selectedKm = WalkState.preferredDistanceKm(this)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "PokeWalk Lite"
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })

        subtitle = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, pad)
        }
        root.addView(subtitle)

        val selectorTitle = TextView(this).apply {
            text = "DISTÂNCIA"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(selectorTitle)

        selectedDistanceLabel = TextView(this).apply {
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        root.addView(selectedDistanceLabel)

        distanceSeek = SeekBar(this).apply {
            max = 19
            progress = selectedKm - 1
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        root.addView(distanceSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val endpoints = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        endpoints.addView(TextView(this).apply { text = "1 km" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        endpoints.addView(TextView(this).apply {
            text = "20 km"
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(endpoints, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = pad
        })

        distanceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (WalkState.isRunning(this@MainActivity)) return
                selectedKm = progress + 1
                WalkState.setPreferredDistanceKm(this@MainActivity, selectedKm)
                updateSelectedDistanceText()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        elapsed = addMetric(metrics, "TEMPO", "00:00")
        distance = addMetric(metrics, "DISTÂNCIA", "0,00 km")
        steps = addMetric(metrics, "PASSOS", "0")
        root.addView(metrics, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = pad
        })

        button = Button(this).apply {
            textSize = 18f
            minHeight = (56 * d).toInt()
            setOnClickListener {
                if (WalkState.isRunning(this@MainActivity)) stopWalk() else prepareWalk()
            }
        }
        defaultButtonTint = button.backgroundTintList
        defaultButtonTextColors = button.textColors
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        status = TextView(this).apply {
            text = "Pronto."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "Últimas caminhadas"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (8 * d).toInt(), 0, (12 * d).toInt())
        })

        historyTable = TableLayout(this).apply {
            isStretchAllColumns = true
            addView(historyHeader())
        }
        root.addView(historyTable, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(scroll)
        updateSelectedDistanceText()
        render()
    }

    private fun updateSelectedDistanceText() {
        selectedDistanceLabel.text = "$selectedKm km"
        subtitle.text = "10 km/h • ${selectedKm * WalkState.MINUTES_PER_KM} min"
        if (!WalkState.isRunning(this)) button.text = "CAMINHAR $selectedKm KM"
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

    private fun historyHeader(): TableRow = TableRow(this).apply {
        addView(tableCell("TEMPO", true))
        addView(tableCell("DISTÂNCIA", true))
        addView(tableCell("PASSOS", true))
    }

    private fun tableCell(value: String, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = if (bold) 12f else 15f
        gravity = Gravity.CENTER
        setPadding(4, 12, 4, 12)
        if (bold) setTypeface(typeface, Typeface.BOLD)
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
        val metrics = when {
            running -> {
                val elapsedMs = (System.currentTimeMillis() - WalkState.startTimeMillis(this))
                    .coerceIn(0L, WalkState.totalDurationMs(this))
                WalkState.metricsAt(this, elapsedMs)
            }
            WalkState.hasResult(this) -> WalkState.finalMetrics(this)
            else -> WalkState.Metrics(0L, 0.0, 0L)
        }

        val sec = metrics.durationMs / 1000L
        elapsed.text = if (sec >= 3600L) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", sec / 3600L, (sec % 3600L) / 60L, sec % 60L)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", sec / 60L, sec % 60L)
        }
        distance.text = String.format(Locale.getDefault(), "%.2f km", metrics.distanceMeters / 1000.0)
        steps.text = String.format(Locale.getDefault(), "%,d", metrics.steps)

        if (running) {
            selectedKm = WalkState.targetDistanceKm(this)
            distanceSeek.progress = selectedKm - 1
            distanceSeek.isEnabled = false
            selectedDistanceLabel.text = "$selectedKm km"
            subtitle.text = "10 km/h • ${selectedKm * WalkState.MINUTES_PER_KM} min"
            button.text = "PARAR DE CAMINHAR"
            button.backgroundTintList = ColorStateList.valueOf(Color.rgb(198, 40, 40))
            button.setTextColor(Color.WHITE)
        } else {
            distanceSeek.isEnabled = true
            button.backgroundTintList = defaultButtonTint
            button.setTextColor(defaultButtonTextColors)
            button.text = "CAMINHAR $selectedKm KM"
        }

        status.text = when {
            running -> "Caminhando • Health Connect: ${WalkState.completedChunks(this)}/${WalkState.chunkCount(this)} gravações"
            WalkState.error(this) != null -> "Erro: ${WalkState.error(this)}"
            WalkState.isStopped(this) -> "Caminhada interrompida."
            WalkState.isFinished(this) -> "Concluído."
            else -> "Pronto."
        }

        renderHistory()
    }

    private fun renderHistory() {
        val history = WalkState.history(this)
        val signature = history.joinToString("|") { "${it.endedAtMillis}:${it.durationMs}:${it.distanceMeters}:${it.steps}" }
        if (signature == historySignature) return
        historySignature = signature

        while (historyTable.childCount > 1) historyTable.removeViewAt(1)
        if (history.isEmpty()) {
            historyTable.addView(TableRow(this).apply {
                addView(TextView(this@MainActivity).apply {
                    text = "Nenhuma caminhada ainda."
                    gravity = Gravity.CENTER
                    setPadding(4, 18, 4, 18)
                }, TableRow.LayoutParams().apply { span = 3 })
            })
            return
        }

        history.forEach { entry ->
            val sec = entry.durationMs / 1000L
            val time = if (sec >= 3600L) {
                String.format(Locale.getDefault(), "%02d:%02d:%02d", sec / 3600L, (sec % 3600L) / 60L, sec % 60L)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", sec / 60L, sec % 60L)
            }
            historyTable.addView(TableRow(this).apply {
                addView(tableCell(time))
                addView(tableCell(String.format(Locale.getDefault(), "%.2f km", entry.distanceMeters / 1000.0)))
                addView(tableCell(String.format(Locale.getDefault(), "%,d", entry.steps)))
            })
        }
    }

    private fun prepareWalk() {
        if (WalkState.isRunning(this)) return
        status.text = "Verificando Health Connect…"

        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            status.text = "Health Connect indisponível ou desatualizado."
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
        WalkState.begin(this, selectedKm)
        ContextCompat.startForegroundService(this, Intent(this, WalkService::class.java))
        render()
    }

    private fun stopWalk() {
        if (!WalkState.isRunning(this)) return
        status.text = "Parando…"
        startService(Intent(this, WalkService::class.java).apply { action = WalkService.ACTION_STOP })
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
