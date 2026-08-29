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
    private lateinit var elapsed: TextView
    private lateinit var distance: TextView
    private lateinit var steps: TextView
    private lateinit var distanceSeek: SeekBar
    private lateinit var speedSeek: SeekBar
    private lateinit var selectedDistanceLabel: TextView
    private lateinit var selectedSpeedLabel: TextView
    private lateinit var estimatedTimeLabel: TextView
    private lateinit var historySection: LinearLayout
    private lateinit var historyContainer: LinearLayout

    private var selectedKm = 5
    private var selectedSpeedKmh = WalkState.DEFAULT_SPEED_KMH
    private var historySignature = ""
    private var lastErrorShown: String? = null

    private val healthPermissions = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class)
    )

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthPermissions)) ensureNotificationPermissionAndStart()
        else showMessage("Permita passos e distância no Health Connect.")
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
        selectedSpeedKmh = WalkState.preferredSpeedKmh(this)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(horizontalPad, topPad, horizontalPad, bottomPad)
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

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

        root.addView(TextView(this).apply {
            text = "VELOCIDADE"
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        selectedSpeedLabel = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(
            selectedSpeedLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        speedSeek = SeekBar(this).apply {
            max = WalkState.MAX_SPEED_KMH - WalkState.MIN_SPEED_KMH
            progress = selectedSpeedKmh - WalkState.MIN_SPEED_KMH
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        root.addView(
            speedSeek,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            endpointRow("1 km/h", "8 km/h"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = sectionPad
            }
        )

        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (WalkState.isRunning(this@MainActivity)) return
                selectedSpeedKmh = progress + WalkState.MIN_SPEED_KMH
                WalkState.setPreferredSpeedKmh(this@MainActivity, selectedSpeedKmh)
                updateSelectedConfigText()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        val selectorMetrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        selectedDistanceLabel = addSelectorMetric(selectorMetrics, "DISTÂNCIA", "$selectedKm km")
        estimatedTimeLabel = addSelectorMetric(selectorMetrics, "TEMPO ESTIMADO", "")
        root.addView(
            selectorMetrics,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * d).toInt()
            }
        )

        distanceSeek = SeekBar(this).apply {
            max = 19
            progress = selectedKm - 1
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        root.addView(
            distanceSeek,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            endpointRow("1 km", "20 km"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = sectionPad
            }
        )

        distanceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (WalkState.isRunning(this@MainActivity)) return
                selectedKm = progress + 1
                WalkState.setPreferredDistanceKm(this@MainActivity, selectedKm)
                updateSelectedConfigText()
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
        root.addView(
            metrics,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = sectionPad
            }
        )

        button = Button(this).apply {
            textSize = 18f
            minHeight = (56 * d).toInt()
            backgroundTintList = ColorStateList.valueOf(IDLE_BLUE)
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (WalkState.isRunning(this@MainActivity)) stopActivityRun() else prepareActivityRun()
            }
        }
        root.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

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
        historySection.addView(
            historyTitleRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = sectionPad
                bottomMargin = (8 * d).toInt()
            }
        )

        historyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historySection.addView(
            historyContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            historySection,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        root.addView(TextView(this).apply {
            text = appVersionLabel()
            textSize = 11f
            gravity = Gravity.CENTER
            alpha = 0.6f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = sectionPad
        })

        setContentView(scroll)
        updateSelectedConfigText()
        render()
    }

    private fun endpointRow(start: String, end: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(this@MainActivity).apply { text = start }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = end
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    private fun updateSelectedConfigText() {
        selectedSpeedLabel.text = "$selectedSpeedKmh km/h"
        selectedDistanceLabel.text = "$selectedKm km"
        estimatedTimeLabel.text = formatEstimatedDuration(
            WalkState.calculateDurationMs(selectedKm, selectedSpeedKmh)
        )
        if (!WalkState.isRunning(this)) button.text = "INICIAR $selectedKm KM"
    }

    private fun formatEstimatedDuration(durationMs: Long): String {
        val totalSeconds = ((durationMs + 500L) / 1_000L).coerceAtLeast(1L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L && seconds > 0L -> "$hours h $minutes min $seconds s"
            hours > 0L -> "$hours h $minutes min"
            seconds > 0L -> "$minutes min $seconds s"
            else -> "$minutes min"
        }
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

        elapsed.text = formatDuration(metrics.durationMs / 1_000L)
        distance.text = String.format(Locale.getDefault(), "%.2f km", metrics.distanceMeters / 1000.0)
        steps.text = String.format(Locale.getDefault(), "%,d", metrics.steps)

        if (running) {
            selectedKm = WalkState.targetDistanceKm(this)
            selectedSpeedKmh = WalkState.targetSpeedKmh(this)
            distanceSeek.progress = selectedKm - 1
            speedSeek.progress = selectedSpeedKmh - WalkState.MIN_SPEED_KMH
            distanceSeek.isEnabled = false
            speedSeek.isEnabled = false
            selectedSpeedLabel.text = "$selectedSpeedKmh km/h"
            selectedDistanceLabel.text = "$selectedKm km"
            estimatedTimeLabel.text = formatEstimatedDuration(WalkState.totalDurationMs(this))
            button.text = "CANCELAR ATIVIDADE"
            button.backgroundTintList = ColorStateList.valueOf(STOP_RED)
        } else {
            distanceSeek.isEnabled = true
            speedSeek.isEnabled = true
            button.backgroundTintList = ColorStateList.valueOf(IDLE_BLUE)
            updateSelectedConfigText()
        }
        button.setTextColor(Color.WHITE)

        val error = WalkState.error(this)
        if (error != null && error != lastErrorShown) {
            lastErrorShown = error
            showMessage("Erro: $error")
        }

        renderHistory()
    }

    private fun renderHistory() {
        val history = WalkState.history(this).sortedByDescending { it.endedAtMillis }.take(5)
        historySection.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE

        val signature = history.joinToString("|") {
            "${it.startedAtMillis}:${it.endedAtMillis}:${it.durationMs}:${it.distanceMeters}:${it.steps}:${it.speedKmh}:${it.goResult.storedValue}"
        }
        if (signature == historySignature) return
        historySignature = signature
        historyContainer.removeAllViews()

        val d = resources.displayMetrics.density
        history.forEachIndexed { index, entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            }
            card.addView(TextView(this).apply {
                text = String.format(
                    Locale.getDefault(),
                    "%d km/h • %.2f km • %s • %,d passos",
                    entry.speedKmh,
                    entry.distanceMeters / 1000.0,
                    formatDuration(entry.durationMs / 1_000L),
                    entry.steps
                )
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            })

            val resultButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            resultButtons.addView(
                goResultButton(entry, WalkState.GoResult.CREDITED, "GO CREDITOU"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (4 * d).toInt()
                }
            )
            resultButtons.addView(
                goResultButton(entry, WalkState.GoResult.NOT_CREDITED, "NÃO CREDITOU"),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (4 * d).toInt()
                }
            )
            card.addView(resultButtons)
            historyContainer.addView(
                card,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )

            if (index < history.lastIndex) {
                historyContainer.addView(View(this).apply {
                    setBackgroundColor(Color.argb(45, 128, 128, 128))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (1 * d).toInt().coerceAtLeast(1)
                ))
            }
        }
    }

    private fun goResultButton(
        entry: WalkState.HistoryEntry,
        result: WalkState.GoResult,
        label: String
    ): Button {
        val selected = entry.goResult == result
        val color = when {
            !selected -> SECONDARY_GRAY
            result == WalkState.GoResult.CREDITED -> GO_GREEN
            else -> STOP_RED
        }
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(color)
            setOnClickListener {
                val next = if (entry.goResult == result) WalkState.GoResult.UNKNOWN else result
                WalkState.setGoResult(this@MainActivity, entry.startedAtMillis, next)
                historySignature = "__refresh__"
                renderHistory()
            }
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        return if (totalSeconds >= 3600L) {
            String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d",
                totalSeconds / 3600L,
                (totalSeconds % 3600L) / 60L,
                totalSeconds % 60L
            )
        } else {
            String.format(
                Locale.getDefault(),
                "%02d:%02d",
                totalSeconds / 60L,
                totalSeconds % 60L
            )
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
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
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
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            startActivityRun()
        }
    }

    private fun startActivityRun() {
        WalkState.begin(this, selectedKm, selectedSpeedKmh)
        ContextCompat.startForegroundService(this, Intent(this, WalkService::class.java))
        render()
    }

    private fun stopActivityRun() {
        if (!WalkState.isRunning(this)) return
        startService(Intent(this, WalkService::class.java).apply { action = WalkService.ACTION_STOP })
    }

    private fun appVersionLabel(): String {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Throwable) {
            "?"
        }
        return "v$version"
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
        private val SECONDARY_GRAY = Color.rgb(97, 97, 97)
        private val STOP_RED = Color.rgb(198, 40, 40)
        private val GO_GREEN = Color.rgb(46, 125, 50)
    }
}
