package com.ptylr.librearm.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.ptylr.librearm.BpViewModel
import com.ptylr.librearm.R
import com.ptylr.librearm.ble.BlePermissions
import com.ptylr.librearm.health.HealthConnectManager
import com.ptylr.librearm.model.MeasurementMode
import com.ptylr.librearm.prefs.Preferences
import java.time.Instant
import kotlinx.coroutines.launch

@Composable
fun LibreArmApp(
    viewModel: BpViewModel,
    healthManager: HealthConnectManager,
    preferences: Preferences,
    onOpenUrl: (String) -> Unit,
    onLaunchInstallIntent: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    val hcPermissionMissingMessage = stringResource(R.string.toast_hc_permission_missing)
    val readingInvalidMessage = stringResource(R.string.toast_reading_invalid)

    var autoSaveToHealth by rememberSaveable { mutableStateOf(preferences.autoSaveToHealth) }
    var healthGranted by remember { mutableStateOf(false) }
    var healthAvailable by remember { mutableStateOf(HealthConnectManager.Availability.Unknown) }
    var healthRequestInFlight by remember { mutableStateOf(false) }

    val blePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) viewModel.startConnect()
    }

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = HealthConnectManager.createRequestPermissionActivityContract()
    ) { granted ->
        val writeGranted = granted.containsAll(healthManager.writePermissions)
        healthGranted = writeGranted
        healthRequestInFlight = false
        autoSaveToHealth = writeGranted
        preferences.autoSaveToHealth = writeGranted
    }

    val notificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* battery alerts are best-effort */ }

    LaunchedEffect(Unit) {
        if (!BlePermissions.areGranted(context)) {
            blePermissionsLauncher.launch(BlePermissions.required)
        } else {
            viewModel.startConnect()
        }
        healthGranted = healthManager.hasWritePermissions()
        healthAvailable = healthManager.availability()
        if (preferences.averageMode) {
            viewModel.setMeasurementMode(MeasurementMode.AVERAGE3)
        }
        viewModel.setDelayBetweenRuns(preferences.delayBetweenRunsSeconds)
        if (!healthGranted && autoSaveToHealth) {
            autoSaveToHealth = false
            preferences.autoSaveToHealth = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(autoSaveToHealth, healthGranted) {
        viewModel.setOnFinalReading { reading ->
            if (!autoSaveToHealth || !healthGranted) return@setOnFinalReading
            scope.launch {
                when (healthManager.saveReading(reading, Instant.now().toEpochMilli())) {
                    HealthConnectManager.SaveResult.Saved -> Unit
                    HealthConnectManager.SaveResult.MissingPermissions -> {
                        Toast.makeText(context, hcPermissionMissingMessage, Toast.LENGTH_SHORT).show()
                    }
                    is HealthConnectManager.SaveResult.InvalidData -> {
                        Toast.makeText(context, readingInvalidMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    KeepScreenOn(enabled = state.isMeasuring)

    MainScreen(
        state = state,
        autoSaveToHealth = autoSaveToHealth,
        healthAuthorized = healthGranted,
        healthAvailable = healthAvailable,
        healthRequestInFlight = healthRequestInFlight,
        onAutoSaveChange = { enabled ->
            if (!enabled) {
                autoSaveToHealth = false
                preferences.autoSaveToHealth = false
                return@MainScreen
            }
            if (healthAvailable != HealthConnectManager.Availability.Available) {
                autoSaveToHealth = false
                preferences.autoSaveToHealth = false
                onLaunchInstallIntent()
                return@MainScreen
            }
            healthRequestInFlight = true
            scope.launch {
                val writeGranted = healthManager.hasWritePermissions()
                healthGranted = writeGranted
                if (writeGranted) {
                    autoSaveToHealth = true
                    preferences.autoSaveToHealth = true
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
        onMeasurementModeChange = { mode ->
            viewModel.setMeasurementMode(mode)
            preferences.averageMode = mode == MeasurementMode.AVERAGE3
        },
        onDelayChange = { viewModel.setDelayBetweenRuns(it) },
        onDelayChangeFinished = { snapped ->
            viewModel.setDelayBetweenRuns(snapped)
            preferences.delayBetweenRunsSeconds = snapped
        },
        onOpenLink = onOpenUrl
    )
}

