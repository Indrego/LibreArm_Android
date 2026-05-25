package com.ptylr.librearm.ble

import android.Manifest
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.ptylr.librearm.model.BpReading
import com.ptylr.librearm.model.BpState
import com.ptylr.librearm.model.MeasurementMode
import com.ptylr.librearm.notifications.BatteryNotifier
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.pow
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

    private var lastBatteryState: BatteryState = BatteryState.UNKNOWN

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
        if (!hasBlePermission()) {
            _state.update { it.copy(status = "Bluetooth permission required") }
            return
        }

        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _state.update { it.copy(status = "Bluetooth unavailable") }
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
        _state.update { it.copy(status = "Searching for device…") }

        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(TimeUnit.SECONDS.toMillis(timeoutSeconds))
            val current = _state.value
            if (!current.isConnected) {
                stopScan()
                _state.update { it.copy(status = "Not connected (timeout). Check power & Bluetooth.") }
            }
        }
    }

    fun startMeasurement() {
        if (!_state.value.canMeasure || _state.value.isMeasuring) return

        val battery = _state.value.batteryLevelPct
        if (battery != null && battery <= CRITICAL_BATTERY) {
            _state.update {
                it.copy(status = "Battery critical ($battery%). Replace batteries to measure.")
            }
            return
        }

        // NOTE: do not initiate a GATT read here. Android BLE only allows one
        // operation in flight at a time, and a read queued just before the
        // start-command write causes the write to be silently dropped on some
        // devices. Battery is re-read after each measurement completes.
        sessionActive = true
        hasFiredFinal = false
        accumulatedReadings.clear()
        finalizeJob?.cancel()
        countdownJob?.cancel()

        if (_state.value.measurementMode == MeasurementMode.AVERAGE3) {
            remainingRuns = 3
            _state.update { it.copy(status = "Measuring (run 1 of 3)…", isMeasuring = true) }
        } else {
            remainingRuns = 0
            _state.update { it.copy(status = "Measuring…", isMeasuring = true) }
        }

        performSingleRunStart()
    }

    fun cancelMeasurement() {
        writeControl(cancelCommand)
        remainingRuns = 0
        accumulatedReadings.clear()
        sessionActive = false
        hasFiredFinal = true
        finalizeJob?.cancel()
        countdownJob?.cancel()
        _state.update { it.copy(status = "Connected — ready", isMeasuring = false) }
    }

    @SuppressLint("MissingPermission")
    fun cleanup() {
        stopScan()
        finalizeJob?.cancel()
        countdownJob?.cancel()
        connectTimeoutJob?.cancel()
        gatt?.close()
        gatt = null
    }

    /**
     * Strict blood pressure validation matching the iOS v1.4.0 rules:
     * - Both values finite, diastolic > 0
     * - Systolic in 60..260, diastolic in 40..160
     * - Systolic strictly greater than diastolic
     * - Pulse pressure (sys − dia) ≤ 120
     */
    fun isValidReading(r: BpReading): Boolean {
        if (r.dia <= 0) return false
        if (!r.sys.isFinite() || !r.dia.isFinite()) return false
        if (r.sys !in 60.0..260.0) return false
        if (r.dia !in 40.0..160.0) return false
        if (r.sys <= r.dia) return false
        if ((r.sys - r.dia) > 120.0) return false
        return true
    }

    private fun hasBlePermission(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
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
    private fun performSingleRunStart() {
        writeControl(startCommand)
    }

    @SuppressLint("MissingPermission")
    private fun writeControl(command: ByteArray) {
        val char = controlCharacteristic ?: return
        gatt?.writeCharacteristic(
            char,
            command,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryLevel() {
        val char = batteryCharacteristic ?: return
        gatt?.readCharacteristic(char)
    }

    private fun updateBatteryStatus(level: Int?) {
        if (level == null) {
            _state.update {
                it.copy(batteryLevelPct = null, batteryStatusLine = "Battery: unavailable")
            }
            return
        }

        val statusLine = when {
            level <= CRITICAL_BATTERY -> "Battery: $level% (Critical)"
            level <= LOW_BATTERY -> "Battery: $level% (Low)"
            else -> "Battery: $level%"
        }
        _state.update { it.copy(batteryLevelPct = level, batteryStatusLine = statusLine) }

        val newState = when {
            level <= CRITICAL_BATTERY -> BatteryState.CRITICAL
            level <= LOW_BATTERY -> BatteryState.LOW
            else -> BatteryState.NORMAL
        }

        if (newState != lastBatteryState) {
            when (newState) {
                BatteryState.CRITICAL -> if (lastBatteryState != BatteryState.CRITICAL) {
                    batteryNotifier?.notifyBattery(level, isCritical = true)
                }
                BatteryState.LOW -> if (lastBatteryState == BatteryState.NORMAL || lastBatteryState == BatteryState.UNKNOWN) {
                    batteryNotifier?.notifyBattery(level, isCritical = false)
                }
                else -> Unit
            }
            lastBatteryState = newState
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            stopScan()
            connectTimeoutJob?.cancel()
            _state.update { it.copy(status = "Connecting…") }
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@BpClient.gatt = gatt
                _state.update { it.copy(isConnected = true, status = "Connected — discovering…") }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                measurementCharacteristic = null
                controlCharacteristic = null
                batteryCharacteristic = null
                lastBatteryState = BatteryState.UNKNOWN
                _state.update {
                    it.copy(
                        isConnected = false,
                        canMeasure = false,
                        isMeasuring = false,
                        status = "Disconnected",
                        batteryLevelPct = null,
                        batteryStatusLine = "Battery: unavailable"
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val bp = gatt.getService(bpsService)
            if (bp != null) {
                setupBpCharacteristics(gatt, bp)
            } else {
                _state.update { it.copy(status = "Blood Pressure service not found") }
            }

            val battery = gatt.getService(batteryService)
            if (battery != null) {
                setupBatteryCharacteristic(gatt, battery)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid) {
                measurement -> {
                    val data = characteristic.value ?: return
                    parseMeasurement(data)
                }
                batteryLevel -> {
                    val data = characteristic.value ?: return
                    parseBatteryLevel(data)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (characteristic.uuid == batteryLevel) {
                val data = characteristic.value ?: return
                parseBatteryLevel(data)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(status = "Notify error: $status") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupBpCharacteristics(gatt: BluetoothGatt, service: BluetoothGattService) {
        measurementCharacteristic = service.getCharacteristic(measurement)
        controlCharacteristic = service.getCharacteristic(control)

        val notifyChar = measurementCharacteristic
        if (notifyChar != null) {
            gatt.setCharacteristicNotification(notifyChar, true)
            val descriptor = notifyChar.getDescriptor(UUID.fromString(CLIENT_CONFIG_UUID))
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
        }

        val ready = measurementCharacteristic != null && controlCharacteristic != null
        _state.update { it.copy(canMeasure = ready, status = if (ready) "Connected — ready" else "Discovering…") }
    }

    @SuppressLint("MissingPermission")
    private fun setupBatteryCharacteristic(gatt: BluetoothGatt, service: BluetoothGattService) {
        val char = service.getCharacteristic(batteryLevel) ?: return
        batteryCharacteristic = char
        gatt.readCharacteristic(char)

        val supportsNotify = (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        if (supportsNotify) {
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(UUID.fromString(CLIENT_CONFIG_UUID))
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
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
        if (data.size < 7) return

        fun sfloat(lo: Byte, hi: Byte): Double {
            val raw = (hi.toInt() and 0xFF shl 8) or (lo.toInt() and 0xFF)
            val mantissa = raw and 0x0FFF
            val exponent = raw shr 12
            val m = if (mantissa >= 0x0800) mantissa - 0x1000 else mantissa
            return m * 10.0.pow(exponent.toDouble())
        }

        val flags = data[0].toInt()
        val sys = sfloat(data[1], data[2])
        val dia = sfloat(data[3], data[4])
        val map = sfloat(data[5], data[6])

        var idx = 7
        if (flags and 0x02 != 0) idx += 7 // timestamp present

        var hr: Double? = null
        if (flags and 0x04 != 0 && data.size >= idx + 2) {
            hr = sfloat(data[idx], data[idx + 1])
        }

        val reading = BpReading(sys = sys, dia = dia, map = map, hr = hr)
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

        // Strict validation: reject invalid readings outright.
        if (!isValidReading(reading)) {
            sessionActive = false
            hasFiredFinal = true
            remainingRuns = 0
            accumulatedReadings.clear()
            _state.update {
                it.copy(
                    lastReading = null,
                    isMeasuring = false,
                    status = "Measurement invalid or incomplete — please try again. Check cuff fit and battery."
                )
            }
            readBatteryLevel()
            return
        }

        if (_state.value.measurementMode == MeasurementMode.AVERAGE3) {
            accumulatedReadings.add(reading)

            if (remainingRuns > 1) {
                remainingRuns -= 1
                launchCountdownAndNextRun()
                return
            }

            // Last run: require all 3 valid readings (any invalid was already rejected above).
            if (accumulatedReadings.size < 3) {
                sessionActive = false
                hasFiredFinal = true
                remainingRuns = 0
                accumulatedReadings.clear()
                _state.update {
                    it.copy(
                        lastReading = null,
                        isMeasuring = false,
                        status = "Average session invalid — not all readings were valid. Please try again."
                    )
                }
                readBatteryLevel()
                return
            }

            val avg = average(accumulatedReadings)
            if (!isValidReading(avg)) {
                sessionActive = false
                hasFiredFinal = true
                remainingRuns = 0
                accumulatedReadings.clear()
                _state.update {
                    it.copy(
                        lastReading = null,
                        isMeasuring = false,
                        status = "Average reading invalid — please try again."
                    )
                }
                readBatteryLevel()
                return
            }

            sessionActive = false
            hasFiredFinal = true
            remainingRuns = 0
            accumulatedReadings.clear()
            _state.update {
                it.copy(lastReading = avg, status = "Connected — ready", isMeasuring = false)
            }
            onFinalReading?.invoke(avg)
            readBatteryLevel()
            return
        }

        sessionActive = false
        hasFiredFinal = true
        _state.update { it.copy(status = "Connected — ready", isMeasuring = false) }
        onFinalReading?.invoke(reading)
        readBatteryLevel()
    }

    private fun launchCountdownAndNextRun() {
        countdownJob?.cancel()
        val delaySeconds = _state.value.delayBetweenRunsSeconds
        var countdown = delaySeconds
        _state.update {
            it.copy(
                status = "Measured run ${3 - remainingRuns} of 3 — next in ${countdown}s…",
                isMeasuring = true
            )
        }

        countdownJob = scope.launch {
            while (countdown > 0) {
                delay(1000)
                countdown -= 1
                _state.update {
                    it.copy(
                        status = "Measured run ${3 - remainingRuns} of 3 — next in ${countdown}s…",
                        isMeasuring = true
                    )
                }
            }
            _state.update { it.copy(status = "Measuring (run ${4 - remainingRuns} of 3)…", isMeasuring = true) }
            performSingleRunStart()
        }
    }

    private fun average(readings: List<BpReading>): BpReading {
        val valid = readings.filter { isValidReading(it) }
        if (valid.isEmpty()) return BpReading(0.0, 0.0, null, null)

        val n = valid.size.toDouble()
        val sysAvg = valid.sumOf { it.sys } / n
        val diaAvg = valid.sumOf { it.dia } / n

        val mapVals = valid.mapNotNull { it.map }.filter { it.isFinite() }
        val mapAvg = mapVals.takeIf { it.isNotEmpty() }?.average()

        val hrVals = valid.mapNotNull { it.hr }.filter { it.isFinite() && it in 20.0..220.0 }
        val hrAvg = hrVals.takeIf { it.isNotEmpty() }?.average()

        return BpReading(sys = sysAvg, dia = diaAvg, map = mapAvg, hr = hrAvg)
    }

    private enum class BatteryState { UNKNOWN, NORMAL, LOW, CRITICAL }

    companion object {
        private const val CLIENT_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val LOW_BATTERY = 20
        private const val CRITICAL_BATTERY = 10
    }
}
