package com.ptylr.librearm.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.ptylr.librearm.model.BatteryStatus
import com.ptylr.librearm.model.BpReading
import com.ptylr.librearm.model.BpState
import com.ptylr.librearm.model.BpStatus
import com.ptylr.librearm.model.MeasurementMode
import com.ptylr.librearm.model.levelOrNull
import com.ptylr.librearm.notifications.BatteryNotifier
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * BLE client for QardioArm blood pressure cuff.
 * Mirrors the iOS BPClient behaviors: connection management, session debouncing,
 * average-of-3 mode with adjustable delay, battery monitoring with low/critical
 * thresholds, strict reading validation, and final reading callback.
 */
class BpClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val batteryNotifier: BatteryNotifier? = null
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _state = MutableStateFlow(BpState())
    val state: StateFlow<BpState> = _state

    var onFinalReading: ((BpReading) -> Unit)? = null

    private val gattQueue = GattOperationQueue()

    private var gatt: BluetoothGatt? = null
    private var measurementCharacteristic: BluetoothGattCharacteristic? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null

    private var connectTimeoutJob: Job? = null
    private var finalizeJob: Job? = null
    private var countdownJob: Job? = null

    private var sessionActive = false
    private var hasFiredFinal = false
    private var remainingRuns = 0
    private val accumulatedReadings = mutableListOf<BpReading>()

    private var lastBatteryStage: BatteryStage = BatteryStage.UNKNOWN

    private val completionDebounceSeconds = 1.5

    // UUIDs
    private val bpsService = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
    private val measurement = UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")
    private val control = UUID.fromString("583CB5B3-875D-40ED-9098-C39EB0C1983D")
    private val batteryService = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val batteryLevel = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private val startCommand = byteArrayOf(0xF1.toByte(), 0x01)
    private val cancelCommand = byteArrayOf(0xF1.toByte(), 0x02)

    fun setMeasurementMode(mode: MeasurementMode) {
        _state.update { it.copy(measurementMode = mode) }
    }

    fun setDelayBetweenRuns(seconds: Int) {
        _state.update { it.copy(delayBetweenRunsSeconds = seconds) }
    }

    @SuppressLint("MissingPermission")
    fun startConnect(timeoutSeconds: Long = 30) {
        if (!BlePermissions.areGranted(context)) {
            _state.update { it.copy(status = BpStatus.BluetoothPermissionRequired) }
            return
        }

        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _state.update { it.copy(status = BpStatus.BluetoothUnavailable) }
            return
        }

        resetSessionForScan()
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(bpsService))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        btAdapter.bluetoothLeScanner.startScan(filters, settings, scanCallback)
        _state.update { it.copy(status = BpStatus.Searching) }

        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(TimeUnit.SECONDS.toMillis(timeoutSeconds))
            if (!_state.value.isConnected) {
                stopScan()
                _state.update { it.copy(status = BpStatus.NotConnectedTimeout) }
            }
        }
    }

    fun startMeasurement() {
        if (!_state.value.canMeasure || _state.value.isMeasuring) return

        val batteryPct = _state.value.battery.levelOrNull
        if (batteryPct != null && batteryPct <= CRITICAL_BATTERY) {
            _state.update { it.copy(status = BpStatus.BatteryCriticalBlocked(batteryPct)) }
            return
        }

        sessionActive = true
        hasFiredFinal = false
        accumulatedReadings.clear()
        finalizeJob?.cancel()
        countdownJob?.cancel()

        if (_state.value.measurementMode == MeasurementMode.AVERAGE3) {
            remainingRuns = 3
            _state.update { it.copy(status = BpStatus.MeasuringRun(current = 1, total = 3), isMeasuring = true) }
        } else {
            remainingRuns = 0
            _state.update { it.copy(status = BpStatus.Measuring, isMeasuring = true) }
        }

        scope.launch {
            // Each op suspends on the queue, so battery read + start write are serialized
            // and the start-command write is never silently dropped.
            readBatteryLevelQueued()
            performSingleRunStart()
        }
    }

    fun cancelMeasurement() {
        scope.launch {
            controlCharacteristic?.let { queueWriteCharacteristic(it, cancelCommand) }
        }
        remainingRuns = 0
        accumulatedReadings.clear()
        sessionActive = false
        hasFiredFinal = true
        finalizeJob?.cancel()
        countdownJob?.cancel()
        _state.update { it.copy(status = BpStatus.Ready, isMeasuring = false) }
    }

    @SuppressLint("MissingPermission")
    fun cleanup() {
        stopScan()
        finalizeJob?.cancel()
        countdownJob?.cancel()
        connectTimeoutJob?.cancel()
        gattQueue.reset()
        gatt?.close()
        gatt = null
    }

    private fun resetSessionForScan() {
        stopScan()
        hasFiredFinal = false
        sessionActive = false
        remainingRuns = 0
        accumulatedReadings.clear()
        finalizeJob?.cancel()
        countdownJob?.cancel()
        connectTimeoutJob?.cancel()
        _state.update {
            it.copy(
                isConnected = false,
                canMeasure = false,
                isMeasuring = false,
                lastReading = null
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private suspend fun performSingleRunStart() {
        val char = controlCharacteristic ?: return
        queueWriteCharacteristic(char, startCommand)
    }

    @SuppressLint("MissingPermission")
    private suspend fun readBatteryLevelQueued() {
        val char = batteryCharacteristic ?: return
        queueReadCharacteristic(char)
    }

    @SuppressLint("MissingPermission")
    private suspend fun queueWriteCharacteristic(
        char: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        val g = gatt ?: return false
        return gattQueue.submit {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = value
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(char)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun queueReadCharacteristic(char: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        return gattQueue.submit { g.readCharacteristic(char) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun queueEnableNotifications(char: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(char, true)) return false
        val descriptor = char.getDescriptor(UUID.fromString(CLIENT_CONFIG_UUID)) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return gattQueue.submit {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, value) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        }
    }

    private fun updateBatteryStatus(level: Int?) {
        if (level == null) {
            _state.update { it.copy(battery = BatteryStatus.Unavailable) }
            return
        }

        val newStatus: BatteryStatus = when {
            level <= CRITICAL_BATTERY -> BatteryStatus.Critical(level)
            level <= LOW_BATTERY -> BatteryStatus.Low(level)
            else -> BatteryStatus.Normal(level)
        }
        _state.update { it.copy(battery = newStatus) }

        val newStage = when (newStatus) {
            is BatteryStatus.Critical -> BatteryStage.CRITICAL
            is BatteryStatus.Low -> BatteryStage.LOW
            else -> BatteryStage.NORMAL
        }

        if (newStage != lastBatteryStage) {
            when (newStage) {
                BatteryStage.CRITICAL -> if (lastBatteryStage != BatteryStage.CRITICAL) {
                    batteryNotifier?.notifyBattery(level, isCritical = true)
                }
                BatteryStage.LOW -> if (lastBatteryStage == BatteryStage.NORMAL || lastBatteryStage == BatteryStage.UNKNOWN) {
                    batteryNotifier?.notifyBattery(level, isCritical = false)
                }
                else -> Unit
            }
            lastBatteryStage = newStage
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            stopScan()
            connectTimeoutJob?.cancel()
            _state.update { it.copy(status = BpStatus.Connecting) }
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@BpClient.gatt = gatt
                _state.update { it.copy(isConnected = true, status = BpStatus.Discovering) }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gattQueue.reset()
                measurementCharacteristic = null
                controlCharacteristic = null
                batteryCharacteristic = null
                lastBatteryStage = BatteryStage.UNKNOWN
                // Close the platform GATT client so its internal resources are released.
                // Without this, every power-cycle / out-of-range leaks a GATT object.
                gatt.close()
                this@BpClient.gatt = null
                _state.update {
                    it.copy(
                        isConnected = false,
                        canMeasure = false,
                        isMeasuring = false,
                        status = BpStatus.Disconnected,
                        battery = BatteryStatus.Unavailable
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val bp = gatt.getService(bpsService)
            if (bp == null) {
                _state.update { it.copy(status = BpStatus.BloodPressureServiceNotFound) }
                return
            }
            val battery = gatt.getService(batteryService)
            scope.launch {
                setupBpCharacteristics(gatt, bp)
                if (battery != null) {
                    setupBatteryCharacteristic(gatt, battery)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // Unsolicited notification — do not touch the op queue.
            dispatchCharacteristicChanged(characteristic, value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Required for API < 33; new overload is preferred.")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                dispatchCharacteristicChanged(characteristic, value)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            if (success && characteristic.uuid == batteryLevel) {
                parseBatteryLevel(value)
            }
            gattQueue.completePending(success)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Required for API < 33; new overload is preferred.")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val success = status == BluetoothGatt.GATT_SUCCESS
                if (success && characteristic.uuid == batteryLevel) {
                    characteristic.value?.let { parseBatteryLevel(it) }
                }
                gattQueue.completePending(success)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            gattQueue.completePending(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            if (!success) {
                _state.update { it.copy(status = BpStatus.NotifyError(status)) }
            }
            gattQueue.completePending(success)
        }
    }

    private fun dispatchCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        when (characteristic.uuid) {
            measurement -> parseMeasurement(value)
            batteryLevel -> parseBatteryLevel(value)
        }
    }

    private suspend fun setupBpCharacteristics(gatt: BluetoothGatt, service: BluetoothGattService) {
        measurementCharacteristic = service.getCharacteristic(measurement)
        controlCharacteristic = service.getCharacteristic(control)

        measurementCharacteristic?.let { queueEnableNotifications(it) }

        val ready = measurementCharacteristic != null && controlCharacteristic != null
        _state.update {
            it.copy(
                canMeasure = ready,
                status = if (ready) BpStatus.Ready else BpStatus.Discovering
            )
        }
    }

    private suspend fun setupBatteryCharacteristic(gatt: BluetoothGatt, service: BluetoothGattService) {
        val char = service.getCharacteristic(batteryLevel) ?: return
        batteryCharacteristic = char
        queueReadCharacteristic(char)
        if ((char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            queueEnableNotifications(char)
        }
    }

    private fun parseBatteryLevel(data: ByteArray) {
        if (data.isEmpty()) return
        val level = data[0].toInt() and 0xFF
        if (level in 0..100) {
            updateBatteryStatus(level)
        }
    }

    private fun parseMeasurement(data: ByteArray) {
        val reading = BpReadingParser.parse(data) ?: return
        _state.update { it.copy(lastReading = reading) }
        scheduleFinalize()
    }

    private fun scheduleFinalize() {
        finalizeJob?.cancel()
        finalizeJob = scope.launch {
            delay((completionDebounceSeconds * 1000).toLong())
            finalizeIfNeeded()
        }
    }

    private fun finalizeIfNeeded() {
        val reading = _state.value.lastReading ?: return
        if (!sessionActive || hasFiredFinal || reading.dia <= 0) return

        if (!BpValidation.isValid(reading)) {
            failSession(BpStatus.MeasurementInvalid)
            return
        }

        if (_state.value.measurementMode == MeasurementMode.AVERAGE3) {
            accumulatedReadings.add(reading)

            if (remainingRuns > 1) {
                remainingRuns -= 1
                launchCountdownAndNextRun()
                return
            }

            if (accumulatedReadings.size < 3) {
                failSession(BpStatus.AverageSessionInvalid)
                return
            }

            val avg = average(accumulatedReadings)
            if (!BpValidation.isValid(avg)) {
                failSession(BpStatus.AverageReadingInvalid)
                return
            }

            completeSession(avg)
            return
        }

        completeSession(reading)
    }

    private fun failSession(status: BpStatus) {
        sessionActive = false
        hasFiredFinal = true
        remainingRuns = 0
        accumulatedReadings.clear()
        _state.update {
            it.copy(lastReading = null, isMeasuring = false, status = status)
        }
        scope.launch { readBatteryLevelQueued() }
    }

    private fun completeSession(reading: BpReading) {
        sessionActive = false
        hasFiredFinal = true
        remainingRuns = 0
        accumulatedReadings.clear()
        _state.update {
            it.copy(lastReading = reading, status = BpStatus.Ready, isMeasuring = false)
        }
        onFinalReading?.invoke(reading)
        scope.launch { readBatteryLevelQueued() }
    }

    private fun launchCountdownAndNextRun() {
        countdownJob?.cancel()
        var countdown = _state.value.delayBetweenRunsSeconds
        val completedRun = 3 - remainingRuns
        val nextRun = completedRun + 1

        fun postCountdown() {
            _state.update {
                it.copy(
                    status = BpStatus.Countdown(
                        secondsRemaining = countdown,
                        justCompletedRun = completedRun,
                        total = 3
                    ),
                    isMeasuring = true
                )
            }
        }

        postCountdown()
        countdownJob = scope.launch {
            while (countdown > 0) {
                delay(1000)
                countdown -= 1
                postCountdown()
            }
            _state.update {
                it.copy(status = BpStatus.MeasuringRun(current = nextRun, total = 3), isMeasuring = true)
            }
            performSingleRunStart()
        }
    }

    private fun average(readings: List<BpReading>): BpReading {
        val valid = readings.filter { BpValidation.isValid(it) }
        if (valid.isEmpty()) return BpReading(0.0, 0.0, null, null)

        val n = valid.size.toDouble()
        val sysAvg = valid.sumOf { it.sys } / n
        val diaAvg = valid.sumOf { it.dia } / n

        val mapVals = valid.mapNotNull { it.map }.filter { it.isFinite() }
        val mapAvg = mapVals.takeIf { it.isNotEmpty() }?.average()

        val hrVals = valid.mapNotNull { it.hr }.filter { it.isFinite() && it in BpValidation.HR_RANGE }
        val hrAvg = hrVals.takeIf { it.isNotEmpty() }?.average()

        return BpReading(sys = sysAvg, dia = diaAvg, map = mapAvg, hr = hrAvg)
    }

    private enum class BatteryStage { UNKNOWN, NORMAL, LOW, CRITICAL }

    companion object {
        private const val CLIENT_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val LOW_BATTERY = 20
        private const val CRITICAL_BATTERY = 10
    }
}
