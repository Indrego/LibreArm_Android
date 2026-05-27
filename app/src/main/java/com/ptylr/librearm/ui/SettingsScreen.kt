package com.ptylr.librearm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ptylr.librearm.R
import com.ptylr.librearm.health.HealthConnectManager
import com.ptylr.librearm.model.MeasurementMode
import kotlin.math.abs

@Composable
fun SettingsScreen(
    isMeasuring: Boolean,
    measurementMode: MeasurementMode,
    delaySeconds: Int,
    autoSaveToHealth: Boolean,
    healthAuthorized: Boolean,
    healthAvailable: HealthConnectManager.Availability,
    healthRequestInFlight: Boolean,
    onMeasurementModeChange: (MeasurementMode) -> Unit,
    onDelayChange: (Int) -> Unit,
    onDelayChangeFinished: (Int) -> Unit,
    onAutoSaveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val averageMode = measurementMode == MeasurementMode.AVERAGE3
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Section(title = stringResource(R.string.settings_section_measurement)) {
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

        Section(title = stringResource(R.string.settings_section_storage)) {
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
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}
