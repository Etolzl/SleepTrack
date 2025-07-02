package com.example.sleeptrack.presentation

import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import kotlin.math.pow
import kotlin.math.sqrt
import java.util.*
import android.Manifest

class SensorService : Service(), SensorEventListener {

    data class HeartRateData(val timestamp: Long, val heartRate: Int)
    data class MovementData(val timestamp: Long, val magnitude: Float)
    data class SleepSession(
        val startTime: Long = System.currentTimeMillis(),
        var endTime: Long = 0L,
        val heartRateData: MutableList<HeartRateData> = mutableListOf(),
        val movementData: MutableList<MovementData> = mutableListOf()
    ) {
        fun calculateAverageHeartRate(): Int {
            return if (heartRateData.isEmpty()) 0 else heartRateData.map { it.heartRate }.average().toInt()
        }

        fun calculateMovementScore(): Float {
            return if (movementData.isEmpty()) 0f else movementData.map { it.magnitude }.average().toFloat()
        }

        fun calculateSleepQuality(): String {
            val avgHeartRate = calculateAverageHeartRate()
            val movementScore = calculateMovementScore()

            return when {
                avgHeartRate < 60 && movementScore < 0.5 -> "Profundo"
                avgHeartRate < 70 && movementScore < 1.0 -> "Moderado"
                else -> "Ligero"
            }
        }

        fun getResultsSummary(): Map<String, Any> {
            return mapOf(
                "duration" to (endTime - startTime) / 1000,
                "averageHeartRate" to calculateAverageHeartRate(),
                "movementScore" to calculateMovementScore(),
                "sleepQuality" to calculateSleepQuality(),
                "heartRateDataPoints" to heartRateData.size,
                "movementDataPoints" to movementData.size,
                "startTime" to startTime,
                "endTime" to endTime
            )
        }
    }

    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var currentSession: SleepSession? = null
    private var isMonitoring = false
    private var currentBluetoothGatt: BluetoothGatt? = null
    private lateinit var bleClient: BleClient
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var bleWriteCallback: ((Int) -> Unit)? = null
    private var currentDataToSend: String? = null
    private var currentMtu = 20

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        bleClient = BleClient(this, this)
        initializeSensors()
        startForegroundService()
    }

    private fun initializeSensors() {
        try {
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing sensors", e)
        }
    }

    private fun startForegroundService() {
        val channelId = "sleep_track_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SleepTrack Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Monitoring sleep patterns" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        startForeground(1, NotificationCompat.Builder(this, channelId)
            .setContentTitle("SleepTrack Running")
            .setContentText("Monitoring your sleep...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            ACTION_START_BLE_SCAN -> startBleScan()
            ACTION_RETRY_BLE_CONNECTION -> retryBleConnection()
            ACTION_DISCONNECT_BLE -> disconnectBle()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (isMonitoring) return
        currentSession = SleepSession()
        isMonitoring = true
        sendMonitorStatusUpdate()

        heartRateSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        Log.d(TAG, "Monitoring started")
    }

    private fun stopMonitoring() {
        if (!isMonitoring) return

        currentSession?.endTime = System.currentTimeMillis()
        isMonitoring = false
        sendMonitorStatusUpdate()
        sensorManager.unregisterListener(this)

        currentSession?.let { session ->
            val resultsData = mapOf(
                "fullData" to session,
                "summary" to session.getResultsSummary()
            )
            val jsonResults = gson.toJson(resultsData)
            currentDataToSend = jsonResults

            val intent = Intent(ACTION_SESSION_DATA).apply {
                putExtra(EXTRA_SESSION_DATA, jsonResults)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

            Log.d(TAG, "Session data: $jsonResults")
            sendDataOverBluetooth(jsonResults)
        }
        Log.d(TAG, "Monitoring stopped")
    }

    private fun sendMonitorStatusUpdate() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_MONITOR_STATUS).apply {
                putExtra(EXTRA_MONITOR_STATUS, isMonitoring)
            }
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> handleHeartRate(event)
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
        }
    }

    private fun handleHeartRate(event: SensorEvent) {
        val heartRate = event.values[0].toInt()
        currentSession?.heartRateData?.add(HeartRateData(System.currentTimeMillis(), heartRate))
        Log.d(TAG, "Heart rate: $heartRate bpm")

        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_HEART_RATE_UPDATE).apply {
                putExtra(EXTRA_HEART_RATE, heartRate)
            }
        )
    }

    private fun handleAccelerometer(event: SensorEvent) {
        val (x, y, z) = event.values
        val magnitude = sqrt(x.pow(2) + y.pow(2) + z.pow(2)).toFloat()
        currentSession?.movementData?.add(MovementData(System.currentTimeMillis(), magnitude))
        Log.d(TAG, "Accelerometer - x:$x, y:$y, z:$z, magnitude:$magnitude")
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        Log.d(TAG, "Sensor ${sensor.name} accuracy changed to $accuracy")
    }

    private fun startBleScan() {
        bleClient.startScan(
            onConnected = {
                Log.d(TAG, "Connected to BLE server")
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                    Intent(ACTION_BLE_CONNECTION_STATUS).apply {
                        putExtra(EXTRA_BLE_STATUS, true)
                    }
                )
                negotiateMtu()
            },
            onFailure = {
                Log.e(TAG, "BLE connection failed")
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                    Intent(ACTION_BLE_CONNECTION_STATUS).apply {
                        putExtra(EXTRA_BLE_STATUS, false)
                    }
                )
                handler.postDelayed({
                    startService(Intent(this, SensorService::class.java).apply {
                        action = ACTION_RETRY_BLE_CONNECTION
                    })
                }, 5000)
            }
        )
    }

    private fun negotiateMtu() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                currentBluetoothGatt?.requestMtu(247)
                Log.d(TAG, "Requesting larger MTU")
            } catch (e: SecurityException) {
                Log.e(TAG, "Security error requesting MTU: ${e.message}")
            }
        }
    }

    private fun retryBleConnection() {
        Log.d(TAG, "Retrying BLE connection...")
        startBleScan()
    }

    private fun disconnectBle() {
        bleClient.disconnect()
        currentBluetoothGatt = null
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_BLE_CONNECTION_STATUS).apply {
                putExtra(EXTRA_BLE_STATUS, false)
            }
        )
    }

    private fun sendDataOverBluetooth(data: String) {
        val gatt = currentBluetoothGatt ?: run {
            Log.w(TAG, "BluetoothGatt not connected, starting scan...")
            startBleScan()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted")
            return
        }

        val service = gatt.getService(UUID.fromString("0000181A-0000-1000-8000-00805f9b34fb"))
        val characteristic = service?.getCharacteristic(
            UUID.fromString("00002A6E-0000-1000-8000-00805f9b34fb")
        ) ?: run {
            Log.e(TAG, "Service or characteristic not found")
            return
        }

        val dataBytes = data.toByteArray(Charsets.UTF_8)
        val chunkSize = currentMtu - 3
        var offset = 0

        fun writeNextChunk() {
            if (offset >= dataBytes.size) {
                Log.d(TAG, "All data sent via BLE")
                currentDataToSend = null
                return
            }

            val end = minOf(offset + chunkSize, dataBytes.size)
            val chunk = dataBytes.copyOfRange(offset, end)
            characteristic.value = chunk
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            try {
                if (gatt.writeCharacteristic(characteristic)) {
                    Log.d(TAG, "Sending chunk ${offset+1}-$end of ${dataBytes.size} bytes")
                    offset = end
                } else {
                    Log.e(TAG, "Error sending chunk, retrying...")
                    handler.postDelayed(::writeNextChunk, 100)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security error writing characteristic: ${e.message}")
            }
        }

        bleWriteCallback = { status ->
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Chunk confirmed")
                writeNextChunk()
            } else {
                Log.e(TAG, "Write error: $status, retrying...")
                handler.postDelayed(::writeNextChunk, 100)
            }
        }

        writeNextChunk()
    }

    fun setBluetoothGatt(gatt: BluetoothGatt) {
        currentBluetoothGatt = gatt
        Log.d(TAG, "BluetoothGatt assigned")
    }

    fun onCharacteristicWrite(status: Int) {
        bleWriteCallback?.invoke(status)
    }

    fun onMtuChanged(mtu: Int) {
        currentMtu = mtu
        Log.d(TAG, "MTU changed to $mtu")
    }

    override fun onDestroy() {
        super.onDestroy()
        bleClient.disconnect()
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Service destroyed")
    }

    companion object {
        const val TAG = "SensorService"
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_SESSION_DATA = "SESSION_DATA"
        const val ACTION_BLE_CONNECTION_STATUS = "BLE_CONNECTION_STATUS"
        const val ACTION_HEART_RATE_UPDATE = "HEART_RATE_UPDATE"
        const val ACTION_START_BLE_SCAN = "START_BLE_SCAN"
        const val ACTION_RETRY_BLE_CONNECTION = "RETRY_BLE_CONNECTION"
        const val ACTION_DISCONNECT_BLE = "DISCONNECT_BLE"
        const val ACTION_MONITOR_STATUS = "MONITOR_STATUS"
        const val EXTRA_SESSION_DATA = "EXTRA_SESSION_DATA"
        const val EXTRA_BLE_STATUS = "EXTRA_BLE_STATUS"
        const val EXTRA_HEART_RATE = "EXTRA_HEART_RATE"
        const val EXTRA_MONITOR_STATUS = "EXTRA_MONITOR_STATUS"

        fun startService(context: Context, action: String) {
            val intent = Intent(context, SensorService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}