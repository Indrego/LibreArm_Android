package com.ptylr.librearm

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ptylr.librearm.health.HealthConnectManager
import com.ptylr.librearm.model.BpState
import com.ptylr.librearm.model.MeasurementMode
import com.ptylr.librearm.ui.HypertensionGraphView
import com.ptylr.librearm.ui.theme.LibreArmTheme
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: BpViewModel by viewModels()
    private lateinit var healthManager: HealthConnectManager
    private val prefs by lazy { getSharedPreferences("librearm_prefs", MODE_PRIVATE) }
    private val prefKeyHealth = "pref_auto_health"
    private val prefKeyAverage = "pref_average_three"
    private val prefKeyDelay = "pref_delay_between_runs"

    private val blePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthManager = HealthConnectManager(this)

        setContent {
            LibreArmTheme {
                val state by viewModel.state.collectAsState()
                val savedHealthPref = prefs.getBoolean(prefKeyHealth, false)
                val savedAveragePref = prefs.getBoolean(prefKeyAverage, false)
                val savedDelayPref = prefs.getInt(prefKeyDelay, 30)
                var autoSaveToHealth by rememberSaveable { mutableStateOf(savedHealthPref) }
                var healthGranted by remember { mutableStateOf(false) }
                var healthAvailable by remember { mutableStateOf(HealthConnectManager.Availability.Unknown) }
                var healthRequestInFlight by remember { mutableStateOf(false) }
                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    if (result.values.all { it }) {
                        viewModel.startConnect()
                    }
                }

                val healthPermissionLauncher = rememberLauncherForActivityResult(
                    contract = HealthConnectManager.createRequestPermissionActivityContract()
                ) { grantedPermissions ->
                    val granted = grantedPermissions.containsAll(healthManager.permissions)
                    healthGranted = granted
                    healthRequestInFlight = false
                    autoSaveToHealth = granted
                    prefs.edit().putBoolean(prefKeyHealth, autoSaveToHealth).apply()
                }

                val notificationsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { /* battery alerts are best-effort */ }

                LaunchedEffect(Unit) {
                    if (!hasBlePermissions()) {
                        permissionsLauncher.launch(blePermissions)
                    } else {
                        viewModel.startConnect()
                    }
                    healthGranted = healthManager.hasPermissions()
                    healthAvailable = healthManager.availability()
                    if (savedAveragePref) {
                        viewModel.setMeasurementMode(MeasurementMode.AVERAGE3)
                    }
                    viewModel.setDelayBetweenRuns(savedDelayPref)
                    if (!healthGranted && autoSaveToHealth) {
                        autoSaveToHealth = false
                        prefs.edit().putBoolean(prefKeyHealth, false).apply()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                LaunchedEffect(autoSaveToHealth, healthGranted) {
                    viewModel.setOnFinalReading { reading ->
                        if (autoSaveToHealth && healthGranted) {
                            lifecycleScope.launch {
                                when (healthManager.saveReading(reading, Date().time)) {
                                    HealthConnectManager.SaveResult.Saved -> Unit
                                    HealthConnectManager.SaveResult.MissingPermissions -> {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Health Connect permission missing",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    is HealthConnectManager.SaveResult.InvalidData -> {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Reading looks invalid; not saved",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                }

                KeepScreenOn(enabled = state.isMeasuring)

                LibreArmScreen(
                    state = state,
                    autoSaveToHealth = autoSaveToHealth,
                    healthAuthorized = healthGranted,
                    healthAvailable = healthAvailable,
                    healthRequestInFlight = healthRequestInFlight,
                    onAutoSaveChange = { enabled ->
                        if (!enabled) {
                            autoSaveToHealth = false
                            prefs.edit().putBoolean(prefKeyHealth, false).apply()
                            return@LibreArmScreen
                        }

                        if (healthAvailable != HealthConnectManager.Availability.Available) {
                            autoSaveToHealth = false
                            prefs.edit().putBoolean(prefKeyHealth, false).apply()
                            runCatching { startActivity(healthManager.installIntent()) }
                            return@LibreArmScreen
                        }

                        healthRequestInFlight = true
                        lifecycleScope.launch {
                            val alreadyGranted = healthManager.hasPermissions()
                            healthGranted = alreadyGranted
                            if (alreadyGranted) {
                                autoSaveToHealth = true
                                prefs.edit().putBoolean(prefKeyHealth, true).apply()
                                healthRequestInFlight = false
                            } else {
                                healthPermissionLauncher.launch(healthManager.permissions)
                            }
                        }
                    },
                    onStartStop = {
                        if (state.isMeasuring) viewModel.cancelMeasurement() else viewModel.startMeasurement()
                    },
                    onRetryConnect = { viewModel.startConnect() },
                    onMeasurementModeChange = {
                        viewModel.setMeasurementMode(it)
                        prefs.edit().putBoolean(prefKeyAverage, it == MeasurementMode.AVERAGE3).apply()
                    },
                    onDelayChange = { viewModel.setDelayBetweenRuns(it) },
                    onDelayChangeFinished = { snapped ->
                        viewModel.setDelayBetweenRuns(snapped)
                        prefs.edit().putInt(prefKeyDelay, snapped).apply()
                    },
                    onOpenLink = { openUrl(it) }
                )
            }
        }
    }

    private fun hasBlePermissions(): Boolean {
        return blePermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openUrl(url: String) {
        kotlin.runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}

@Composable
private fun LibreArmScreen(
    state: BpState,
    autoSaveToHealth: Boolean,
    healthAuthorized: Boolean,
    healthAvailable: HealthConnectManager.Availability,
    healthRequestInFlight: Boolean,
    onAutoSaveChange: (Boolean) -> Unit,
    onStartStop: () -> Unit,
    onRetryConnect: () -> Unit,
    onMeasurementModeChange: (MeasurementMode) -> Unit,
    onDelayChange: (Int) -> Unit,
    onDelayChangeFinished: (Int) -> Unit,
    onOpenLink: (String) -> Unit
) {
    val batteryCritical = (state.batteryLevelPct ?: 100) <= 10
    val batteryLow = (state.batteryLevelPct ?: 100) <= 20
    val batteryColor = when {
        batteryCritical || batteryLow -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    val averageMode = state.measurementMode == MeasurementMode.AVERAGE3

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LibreArm", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Blood Pressure",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    state.batteryStatusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = batteryColor
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val reading = state.lastReading
                    if (reading != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${reading.sys.toInt()}/${reading.dia.toInt()} mmHg",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            reading.map?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Speed, contentDescription = null)
                                    Text("${it.toInt()} MAP", modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                            reading.hr?.let {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = null,
                                        tint = Color.Red
                                    )
                                    Text("${it.toInt()} bpm", modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                        }
                        HypertensionGraphView(
                            systolic = reading.sys,
                            diastolic = reading.dia,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )
                    } else {
                        Text(
                            "No reading yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        HypertensionGraphView(
                            systolic = 120.0,
                            diastolic = 80.0,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .alpha(0.3f)
                        )
                    }
                }
            }

            Button(
                onClick = onStartStop,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isMeasuring || (state.canMeasure && !batteryCritical),
                colors = if (state.isMeasuring) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (state.isMeasuring) "Stop Measurement" else "Start Measurement")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Text("Save to Health Connect")
                    if (!healthAuthorized) {
                        Text(
                            when (healthAvailable) {
                                HealthConnectManager.Availability.Available -> "Toggle to request access"
                                HealthConnectManager.Availability.NotInstalled -> "Install Health Connect to save readings"
                                HealthConnectManager.Availability.NeedsUpdate -> "Update Health Connect to continue"
                                else -> "Health Connect not available"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Switch(
                    checked = autoSaveToHealth,
                    onCheckedChange = onAutoSaveChange,
                    enabled = !state.isMeasuring && !healthRequestInFlight
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Average (3 readings)")
                Switch(
                    checked = averageMode,
                    onCheckedChange = {
                        onMeasurementModeChange(if (it) MeasurementMode.AVERAGE3 else MeasurementMode.SINGLE)
                    },
                    enabled = !state.isMeasuring
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Delay between readings (seconds)")
                    Text(
                        "${state.delayBetweenRunsSeconds}s",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Slider(
                    value = state.delayBetweenRunsSeconds.toFloat(),
                    onValueChange = { onDelayChange(it.toInt()) },
                    valueRange = 15f..60f,
                    steps = 2,
                    enabled = !state.isMeasuring && averageMode,
                    onValueChangeFinished = {
                        val options = listOf(15, 30, 45, 60)
                        val closest = options.minByOrNull { abs(it - state.delayBetweenRunsSeconds) } ?: 30
                        onDelayChangeFinished(closest)
                    }
                )
            }

            if (!state.isConnected) {
                Button(
                    onClick = onRetryConnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    Text("Retry Connect", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HorizontalDivider()
            Text(
                text = "Original iOS app by Paul Taylor — Android port by agreenbhm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { onOpenLink("https://github.com/ptylr/LibreArm") }) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("iOS GitHub", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = { onOpenLink("https://github.com/agreenbhm/librearm_android") }) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Android GitHub", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(key1 = enabled) {
        val window = (context as? Activity)?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
