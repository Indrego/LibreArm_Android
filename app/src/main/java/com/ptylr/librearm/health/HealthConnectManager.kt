package com.ptylr.librearm.health

import androidx.activity.result.contract.ActivityResultContract
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import com.ptylr.librearm.model.BpReading
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToInt

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

class HealthConnectManager(private val context: Context) {
    private val client: HealthConnectClient = HealthConnectClient.getOrCreate(context)

    val writePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class)
    )

    val permissions: Set<String> = writePermissions

    suspend fun hasWritePermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(writePermissions)
    }

    suspend fun saveReading(reading: BpReading, timestampMillis: Long): SaveResult {
        if (!hasWritePermissions()) return SaveResult.MissingPermissions
        // Upstream validation in BpValidation.isValid ensures finite, plausible values.
        return runCatching {
            val instant = Instant.ofEpochMilli(timestampMillis)
            val zoneOffset: ZoneOffset = ZoneOffset.systemDefault().rules.getOffset(instant)
            val metadata = Metadata.autoRecorded(QARDIO_DEVICE)

            val bpRecord = BloodPressureRecord(
                time = instant,
                zoneOffset = zoneOffset,
                systolic = Pressure.millimetersOfMercury(reading.sys),
                diastolic = Pressure.millimetersOfMercury(reading.dia),
                metadata = metadata
            )

            val records = mutableListOf<androidx.health.connect.client.records.Record>(bpRecord)
            reading.hr?.let { bpm ->
                val hrRecord = HeartRateRecord(
                    startTime = instant,
                    startZoneOffset = zoneOffset,
                    endTime = instant,
                    endZoneOffset = zoneOffset,
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = instant,
                            beatsPerMinute = bpm.roundToInt().toLong()
                        )
                    ),
                    metadata = metadata
                )
                records.add(hrRecord)
            }
            client.insertRecords(records)
            SaveResult.Saved
        }.getOrElse { SaveResult.InvalidData(it.message ?: "Unable to save") }
    }

    fun availability(): Availability {
        val status = HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PACKAGE)
        return when (status) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NeedsUpdate
            HealthConnectClient.SDK_UNAVAILABLE -> Availability.NotInstalled
            else -> Availability.Unknown
        }
    }

    fun installIntent(): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = "https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE".toUri()
            setPackage("com.android.vending")
        }
    }

    enum class Availability { Available, NotInstalled, NeedsUpdate, Unknown }

    sealed interface SaveResult {
        data object Saved : SaveResult
        data object MissingPermissions : SaveResult
        data class InvalidData(val reason: String) : SaveResult
    }

    companion object {
        private val QARDIO_DEVICE = Device(
            manufacturer = "Qardio",
            model = "QardioArm",
            type = Device.TYPE_UNKNOWN
        )

        fun createRequestPermissionActivityContract(): ActivityResultContract<Set<String>, Set<String>> =
            PermissionController.createRequestPermissionResultContract()
    }
}
