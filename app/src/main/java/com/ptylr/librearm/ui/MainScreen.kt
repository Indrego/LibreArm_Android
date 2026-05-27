package com.ptylr.librearm.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ptylr.librearm.R
import com.ptylr.librearm.health.HealthConnectManager
import com.ptylr.librearm.model.BatteryStatus
import com.ptylr.librearm.model.BpState
import com.ptylr.librearm.model.MeasurementMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private const val IOS_REPO_URL = "https://github.com/ptylr/LibreArm"
private const val ANDROID_REPO_URL = "https://github.com/agreenbhm/librearm_android"

@Composable
fun MainScreen(
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
    val batteryCritical = state.battery is BatteryStatus.Critical
    val batteryColor = when (state.battery) {
        is BatteryStatus.Critical, is BatteryStatus.Low -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    val averageMode = state.measurementMode == MeasurementMode.AVERAGE3

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TopBar()
            StatusRow(state, batteryColor)
            ReadingCard(state)

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
                Text(stringResource(if (state.isMeasuring) R.string.action_stop_measurement else R.string.action_start_measurement))
            }

            HealthSaveRow(
                autoSaveToHealth = autoSaveToHealth,
                healthAuthorized = healthAuthorized,
                healthAvailable = healthAvailable,
                healthRequestInFlight = healthRequestInFlight,
                isMeasuring = state.isMeasuring,
                onAutoSaveChange = onAutoSaveChange
            )

            AverageModeRow(
                averageMode = averageMode,
                isMeasuring = state.isMeasuring,
                onMeasurementModeChange = onMeasurementModeChange
            )

            DelaySliderRow(
                delaySeconds = state.delayBetweenRunsSeconds,
                isMeasuring = state.isMeasuring,
                averageMode = averageMode,
                onDelayChange = onDelayChange,
                onDelayChangeFinished = onDelayChangeFinished
            )

            if (!state.isConnected) {
                Button(onClick = onRetryConnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    Text(
                        stringResource(R.string.action_retry_connect),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        FooterCredits(onOpenLink = onOpenLink)
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.screen_subtitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun StatusRow(state: BpState, batteryColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            state.status.text(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            state.battery.text(),
            style = MaterialTheme.typography.bodySmall,
            color = batteryColor
        )
    }
}

@Composable
private fun ReadingCard(state: BpState) {
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
                        text = stringResource(R.string.reading_format, reading.sys.toInt(), reading.dia.toInt()),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    reading.map?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Speed, contentDescription = null)
                            Text(
                                stringResource(R.string.reading_map_format, it.toInt()),
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                    reading.hr?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = Color.Red
                            )
                            Text(
                                stringResource(R.string.reading_bpm_format, it.toInt()),
                                modifier = Modifier.padding(start = 4.dp)
                            )
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
                    stringResource(R.string.reading_none_yet),
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
}

@Composable
private fun HealthSaveRow(
    autoSaveToHealth: Boolean,
    healthAuthorized: Boolean,
    healthAvailable: HealthConnectManager.Availability,
    healthRequestInFlight: Boolean,
    isMeasuring: Boolean,
    onAutoSaveChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(stringResource(R.string.setting_save_to_health))
            if (!healthAuthorized) {
                Text(
                    when (healthAvailable) {
                        HealthConnectManager.Availability.Available -> stringResource(R.string.hc_toggle_to_request)
                        HealthConnectManager.Availability.NotInstalled -> stringResource(R.string.hc_install)
                        HealthConnectManager.Availability.NeedsUpdate -> stringResource(R.string.hc_update)
                        else -> stringResource(R.string.hc_unavailable)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Switch(
            checked = autoSaveToHealth,
            onCheckedChange = onAutoSaveChange,
            enabled = !isMeasuring && !healthRequestInFlight
        )
    }
}

@Composable
private fun AverageModeRow(
    averageMode: Boolean,
    isMeasuring: Boolean,
    onMeasurementModeChange: (MeasurementMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_average_3))
        Switch(
            checked = averageMode,
            onCheckedChange = {
                onMeasurementModeChange(if (it) MeasurementMode.AVERAGE3 else MeasurementMode.SINGLE)
            },
            enabled = !isMeasuring
        )
    }
}

@Composable
private fun DelaySliderRow(
    delaySeconds: Int,
    isMeasuring: Boolean,
    averageMode: Boolean,
    onDelayChange: (Int) -> Unit,
    onDelayChangeFinished: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.setting_delay_between_readings))
            Text(
                stringResource(R.string.delay_format, delaySeconds),
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Slider(
            value = delaySeconds.toFloat(),
            onValueChange = { onDelayChange(it.toInt()) },
            valueRange = 15f..60f,
            steps = 2,
            enabled = !isMeasuring && averageMode,
            onValueChangeFinished = {
                val options = listOf(15, 30, 45, 60)
                val closest = options.minByOrNull { abs(it - delaySeconds) } ?: 30
                onDelayChangeFinished(closest)
            }
        )
    }
}

@Composable
private fun FooterCredits(onOpenLink: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        HorizontalDivider()
        Text(
            stringResource(R.string.footer_credits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = { onOpenLink(IOS_REPO_URL) }) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(stringResource(R.string.link_ios_github), modifier = Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = { onOpenLink(ANDROID_REPO_URL) }) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(stringResource(R.string.link_android_github), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
fun KeepScreenOn(enabled: Boolean) {
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
