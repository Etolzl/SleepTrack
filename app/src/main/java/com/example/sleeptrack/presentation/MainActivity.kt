package com.example.sleeptrack.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.sleeptrack.R
import com.google.gson.Gson

class MainActivity : ComponentActivity() {
    private lateinit var btnConnect: Button
    private lateinit var btnToggleMonitor: Button
    private lateinit var tvBleStatus: TextView
    private lateinit var tvMonitorStatus: TextView
    private lateinit var tvResults: TextView
    private lateinit var tvHeartRate: TextView
    private lateinit var tvMovement: TextView
    private lateinit var tvSleepQuality: TextView

    private var isBleConnected = false
    private var isMonitoring = false
    private var lastSessionData: String? = null

    private val permissions = mutableListOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            updateUI()
        } else {
            Toast.makeText(this, "All permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SensorService.ACTION_BLE_CONNECTION_STATUS -> {
                    isBleConnected = intent.getBooleanExtra(SensorService.EXTRA_BLE_STATUS, false)
                    updateUI()
                }
                SensorService.ACTION_SESSION_DATA -> {
                    lastSessionData = intent.getStringExtra(SensorService.EXTRA_SESSION_DATA)
                    showResults(lastSessionData)
                    isMonitoring = false
                    updateUI()
                }
                SensorService.ACTION_HEART_RATE_UPDATE -> {
                    val heartRate = intent.getIntExtra(SensorService.EXTRA_HEART_RATE, 0)
                    runOnUiThread {
                        tvHeartRate.text = "❤ Ritmo: $heartRate bpm"
                    }
                }
                SensorService.ACTION_MONITOR_STATUS -> {
                    isMonitoring = intent.getBooleanExtra(SensorService.EXTRA_MONITOR_STATUS, false)
                    updateUI()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        checkPermissions()
        registerReceiver()

        btnConnect.setOnClickListener {
            if (checkPermissions()) {
                if (isBleConnected) {
                    SensorService.startService(this, SensorService.ACTION_DISCONNECT_BLE)
                } else {
                    SensorService.startService(this, SensorService.ACTION_START_BLE_SCAN)
                }
            }
        }

        btnToggleMonitor.setOnClickListener {
            if (isMonitoring) {
                SensorService.startService(this, SensorService.ACTION_STOP_MONITORING)
            } else {
                if (isBleConnected) {
                    SensorService.startService(this, SensorService.ACTION_START_MONITORING)
                } else {
                    Toast.makeText(this, "Connect BLE device first", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initializeViews() {
        btnConnect = findViewById(R.id.btn_connect)
        btnToggleMonitor = findViewById(R.id.btn_toggle_monitor)
        tvBleStatus = findViewById(R.id.tv_ble_status)
        tvMonitorStatus = findViewById(R.id.tv_monitor_status)
        tvResults = findViewById(R.id.tv_results)
        tvHeartRate = findViewById(R.id.tv_heart_rate)
        tvMovement = findViewById(R.id.tv_movement)
        tvSleepQuality = findViewById(R.id.tv_sleep_quality)
    }

    private fun checkPermissions(): Boolean {
        return if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            true
        } else {
            requestPermissionsLauncher.launch(permissions.toTypedArray())
            false
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(SensorService.ACTION_BLE_CONNECTION_STATUS)
            addAction(SensorService.ACTION_SESSION_DATA)
            addAction(SensorService.ACTION_HEART_RATE_UPDATE)
            addAction(SensorService.ACTION_MONITOR_STATUS)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
    }

    private fun updateUI() {
        runOnUiThread {
            tvBleStatus.text = "BLE: ${if (isBleConnected) "Connected" else "Disconnected"}"
            btnConnect.text = if (isBleConnected) "Disconnect BLE" else "Connect BLE"

            btnToggleMonitor.isEnabled = isBleConnected
            btnToggleMonitor.text = if (isMonitoring) "Stop Monitoring" else "Start Monitoring"
            tvMonitorStatus.text = "Monitoring: ${if (isMonitoring) "Active" else "Inactive"}"
        }
    }

    private fun showResults(jsonData: String?) {
        jsonData?.let {
            try {
                val results = Gson().fromJson(it, Map::class.java)
                val summary = results["summary"] as Map<*, *>

                runOnUiThread {
                    tvResults.visibility = View.VISIBLE
                    tvResults.text = "Results:\n" +
                            "Duration: ${summary["duration"]}s\n" +
                            "Heartbeats: ${summary["heartRateDataPoints"]}\n" +
                            "Movements: ${summary["movementDataPoints"]}"

                    tvHeartRate.text = "Heart rate: ${summary["averageHeartRate"]} bpm"
                    tvMovement.text = "Movement: ${"%.2f".format(summary["movementScore"])}"
                    tvSleepQuality.text = "Sleep quality: ${summary["sleepQuality"]}"
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error showing results: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver: ${e.message}")
        }
    }
}