package com.ptylr.librearm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ptylr.librearm.R
import com.ptylr.librearm.health.HealthConnectManager

@Composable
internal fun ReadPermissionRequired(
    healthAvailable: HealthConnectManager.Availability,
    previouslyDenied: Boolean,
    onGrantClick: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val installNeeded = healthAvailable == HealthConnectManager.Availability.NotInstalled ||
        healthAvailable == HealthConnectManager.Availability.NeedsUpdate

    val titleRes = when {
        installNeeded -> R.string.history_read_permission_install_title
        else -> R.string.history_read_permission_title
    }
    val bodyRes = when {
        installNeeded -> R.string.history_read_permission_install_body
        previouslyDenied -> R.string.history_read_permission_denied_body
        else -> R.string.history_read_permission_body
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.HealthAndSafety,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        when {
            installNeeded -> Button(onClick = onInstallHealthConnect) {
                Text(stringResource(R.string.history_read_permission_install))
            }
            previouslyDenied -> Button(onClick = onOpenHealthConnect) {
                Text(stringResource(R.string.history_read_permission_open_hc))
            }
            else -> Button(onClick = onGrantClick) {
                Text(stringResource(R.string.history_read_permission_grant))
            }
        }
    }
}
