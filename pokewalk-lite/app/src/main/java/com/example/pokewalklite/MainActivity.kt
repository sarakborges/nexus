package com.example.pokewalklite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
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
    private lateinit var walkModeButton: Button
    private lateinit var runModeButton: Button
    private lateinit var elapsed: TextView
    private lateinit var distance: TextView
    private lateinit var steps: TextView
    private lateinit var distanceSeek: SeekBar
    private lateinit var selectedDistanceLabel: TextView
    private lateinit var estimatedTimeLabel: TextView
    private lateinit var historySection: LinearLayout
    private lateinit var historyTable: TableLayout

    private var selectedKm = 5
    private var selectedMode = WalkState.ActivityMode.WALK
    private var historySignature = ""
    private var lastErrorShown: String? = null

    private val healthPermissions = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    )

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthPermissions)) ensureNotificationPermissionAndStart()
        else showMessage("Permita passos, distância e exercícios no Health Connect.")
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ensureActivityPermissionAndStart()
        else showMessage("Permita notificações para iniciar a atividade.")
    }

    private val activityRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startActivityRun()
        else showMessage("Permissão de atividade necessária.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val horizontalPad = (24 * d).toInt()
        val topPad = (64 * d).toInt()
        val bottomPad = (40 * d).toInt()
        val sectionPad = (24 * d).toInt()
        selectedKm = WalkState.preferredDistanceKm(this)
        selectedMode = WalkState.preferredMode(this)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(horizontalPad, topPad, horizontalPad, bottomPad)
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(
                horizontalPad,
                bars.top + topPad,
                horizontalPad,
                bars.bottom + bottomPad
            )
            insets
        }

        root.addView(TextView(this).apply {
            text = "PokeWalk Lite"
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = sectionPad
        })

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        walkModeButton = Button(this).apply {
            text = "CAMINHADA"
            setTextColor(Color.WHITE)
            setOnClickListener { selectMode(WalkState.ActivityMode.WALK) }
        }
        runModeButton = Button(this).apply {
            text = "CORRIDA"
            setTextColor(Color.WHITE)
            setOnClickListener { selectMode(WalkState.ActivityMode.RUN) }
        }
        modeRow.addView(walkModeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = (6 * d).toInt()
        })
        modeRow.addView(runModeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (6 * d).toInt()
        })
        root.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = sectionPad
        })

        val selectorMetrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        selectedDistanceLabel = addSelectorMetric(selectorMetrics, "DISTÂNCIA", "$selectedKm km")
        estimatedTimeLabel = addSelectorMetric(selectorMetrics, "TEMPO ESTIMADO", "")
        root.addView(selectorMetrics, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (8 * d).toInt()
        })

        distanceSeek = SeekBar(this).apply {
            max = 19
            progress = selectedKm - 1
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        root.addView(distanceSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val endpoints = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        endpoints.addView(TextView(this).apply { text = "1 km" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        endpoints.addView(TextView(this).apply {
            text = "20 km"
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(endpoints, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = sectionPad
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
            bottomMargin = sectionPad
        })

        button = Button(this).apply {
            textSize = 18f
            minHeight = (56 * d).toInt()
            backgroundTintList = ColorStateList.valueOf(IDLE_BLUE)
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (WalkState.isRunning(this@MainActivity)) stopActivityRun() else prepareActivityRun()
            }
        }
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        historySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val historyTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        historyTitleRow.addView(TextView(this).apply {
            text = "Últimas atividades"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        historyTitleRow.addView(Button(this).apply {
            text = "LIMPAR"
            setOnClickListener {
                WalkState.clearHistory(this@MainActivity)
                historySignature = "__refresh__"
                renderHistory()
            }
        })
        historySection.addView(historyTitleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = sectionPad
            bottomMargin = (8 * d).toInt()
        })

        historyTable = TableLayout(this).apply {
            isStretchAllColumns = true
            addView(historyHeader())
        }
        historySection.addView(historyTable, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(historySection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(scroll)
        updateSelectedDistanceText()
        updateModeButtons()
        render()
    }

    private fun selectMode(mode: WalkState.ActivityMode) {
        if (WalkState.isRunning(this)) return
        selectedMode = mode
        WalkState.setPreferredMode(this, mode)
        updateModeButtons()
        updateSelectedDistanceText()
    }

    private fun updateModeButtons() {
        val running = WalkState.isRunning(this)
        walkModeButton.isEnabled = !running
        runModeButton.isEnabled = !running
        walkModeButton.backgroundTintList = ColorStateList.valueOf(
            if (selectedMode == WalkState.ActivityMode.WALK) IDLE_BLUE else MODE_IDLE_GRAY
        )
        runModeButton.backgroundTintList = ColorStateList.valueOf(
            if (selectedMode == WalkState.ActivityMode.RUN) IDLE_BLUE else MODE_IDLE_GRAY
        )
    }

    private fun addSelectorMetric(parent: LinearLayout, label: String, initial: String): TextView {
        val value = TextView(this).apply {
            text = initial
            textSize = 24f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(value)
        }
        parent.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return value
    }

    private fun updateSelectedDistanceText() {
        selectedDistanceLabel.text = "$selectedKm km"
        estimatedTimeLabel.text = formatEstimatedDuration(selectedKm * WalkState.MINUTES_PER_KM)
        if (!WalkState.isRunning(this)) updateStartButtonText()
    }

    private fun updateStartButtonText() {
        button.text = if (selectedMode == WalkState.ActivityMode.RUN) {
            "CORRER $selectedKm KM"
        } else {
            "CAMINHAR $selectedKm KM"
        }
    }

    private fun formatEstimatedDuration(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0 -> "$minutes min"
            minutes == 0 -> "$hours h"
            else -> "$hours h $minutes min"
        }
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
        addView(tableCell("TIPO", true))
        addView(tableCell("TEMPO", true))
        addView(tableCell("DIST.", true))
        addView(tableCell("PASSOS", true))
    }

    private fun tableCell(value: String, bold: Boolean = false): TextView = TextView(this).apply {
        val d = resources.displayMetrics.density
        text = value
        textSize = if (bold) 11f else 14f
        gravity = Gravity.CENTER
        setPadding((3 * d).toInt(), (10 * d).toInt(), (3 * d).toInt(), (10 * d).toInt())
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
        elapsed.text = formatDuration(sec)
        distance.text = String.format(Locale.getDefault(), "%.2f km", metrics.distanceMeters / 1000.0)
        steps.text = String.format(Locale.getDefault(), "%,d", metrics.steps)

        if (running) {
            selectedKm = WalkState.targetDistanceKm(this)
            selectedMode = WalkState.targetMode(this)
            distanceSeek.progress = selectedKm - 1
            distanceSeek.isEnabled = false
            selectedDistanceLabel.text = "$selectedKm km"
            estimatedTimeLabel.text = formatEstimatedDuration(selectedKm * WalkState.MINUTES_PER_KM)
            button.text = if (selectedMode == WalkState.ActivityMode.RUN) "CANCELAR CORRIDA" else "CANCELAR CAMINHADA"
            button.backgroundTintList = ColorStateList.valueOf(STOP_RED)
            button.setTextColor(Color.WHITE)
        } else {
            distanceSeek.isEnabled = true
            button.backgroundTintList = ColorStateList.valueOf(IDLE_BLUE)
            button.setTextColor(Color.WHITE)
            updateStartButtonText()
        }
        updateModeButtons()

        val error = WalkState.error(this)
        if (error != null && error != lastErrorShown) {
            lastErrorShown = error
            showMessage("Erro: $error")
        }

        renderHistory()
    }

    private fun renderHistory() {
        val history = WalkState.history(this)
            .sortedByDescending { it.endedAtMillis }
            .take(5)
        historySection.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE

        val signature = history.joinToString("|") {
            "${it.endedAtMillis}:${it.durationMs}:${it.distanceMeters}:${it.steps}:${it.mode.storedValue}"
        }
        if (signature == historySignature) return
        historySignature = signature

        while (historyTable.childCount > 1) historyTable.removeViewAt(1)
        history.forEach { entry ->
            val sec = entry.durationMs / 1000L
            historyTable.addView(TableRow(this).apply {
                addView(tableCell(entry.mode.shortLabel))
                addView(tableCell(formatDuration(sec)))
                addView(tableCell(String.format(Locale.getDefault(), "%.2f km", entry.distanceMeters / 1000.0)))
                addView(tableCell(String.format(Locale.getDefault(), "%,d", entry.steps)))
            })
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        return if (totalSeconds >= 3600L) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", totalSeconds / 3600L, (totalSeconds % 3600L) / 60L, totalSeconds % 60L)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
        }
    }

    private fun prepareActivityRun() {
        if (WalkState.isRunning(this)) return

        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            showMessage("Health Connect indisponível ou desatualizado.")
            return
        }

        val client = HealthConnectClient.getOrCreate(this)
        scope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(healthPermissions)) ensureNotificationPermissionAndStart()
            else healthPermissionLauncher.launch(healthPermissions)
        }
    }

    private fun ensureNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            showMessage("Ative as notificações do PokeWalk nas configurações do Android.")
            return
        }

        ensureActivityPermissionAndStart()
    }

    private fun ensureActivityPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else startActivityRun()
    }

    private fun startActivityRun() {
        WalkState.begin(this, selectedKm, selectedMode)
        ContextCompat.startForegroundService(this, Intent(this, WalkService::class.java))
        render()
    }

    private fun stopActivityRun() {
        if (!WalkState.isRunning(this)) return
        startService(Intent(this, WalkService::class.java).apply { action = WalkService.ACTION_STOP })
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val IDLE_BLUE = Color.rgb(25, 118, 210)
        private val MODE_IDLE_GRAY = Color.rgb(97, 97, 97)
        private val STOP_RED = Color.rgb(198, 40, 40)
    }
}
