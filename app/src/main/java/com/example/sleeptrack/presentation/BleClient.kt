package com.example.sleeptrack.presentation

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.*

class BleClient(
    private val context: Context,
    private val sensorService: SensorService
) {
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter.bluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null

    private val serviceUUID = UUID.fromString("0000181A-0000-1000-8000-00805f9b34fb")
    private val characteristicUUID = UUID.fromString("00002A6E-0000-1000-8000-00805f9b34fb")
    private val SCAN_TIMEOUT = 15000L // 15 segundos

    fun startScan(onConnected: () -> Unit, onFailure: () -> Unit) {
        if (!checkBluetoothPermissions()) {
            Log.e("BLE", "❌ Permisos de Bluetooth no concedidos")
            onFailure()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e("BLE", "❌ Bluetooth no está activado")
            onFailure()
            return
        }

        safeStopScan()

        handler.postDelayed({
            safeStopScan()
            Log.w("BLE", "⏰ Timeout de escaneo alcanzado")
            onFailure()
        }, SCAN_TIMEOUT)

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = createScanCallback(onConnected, onFailure)

        try {
            if (checkScanPermission()) {
                Log.d("BLE", "🔍 Iniciando escaneo BLE")
                bleScanner?.startScan(listOf(filter), settings, scanCallback)
            } else {
                Log.e("BLE", "⚠️ No se tienen permisos para escanear")
                onFailure()
            }
        } catch (e: SecurityException) {
            Log.e("BLE", "⚠️ Error de seguridad al escanear: ${e.message}")
            onFailure()
        }
    }

    private fun safeStopScan() {
        try {
            if (checkScanPermission() && scanCallback != null) {
                bleScanner?.stopScan(scanCallback)
            }
        } catch (e: SecurityException) {
            Log.e("BLE", "⚠️ Error de seguridad al detener escaneo: ${e.message}")
        }
    }

    private fun checkScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun createScanCallback(
        onConnected: () -> Unit,
        onFailure: () -> Unit
    ): ScanCallback {
        return object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    val deviceName = if (checkConnectPermission()) {
                        device.name ?: "Dispositivo BLE"
                    } else {
                        "Dispositivo BLE"
                    }

                    Log.d("BLE", "📡 Dispositivo encontrado: $deviceName (${device.address})")

                    try {
                        safeStopScan()
                        if (checkConnectPermission()) {
                            bluetoothGatt = device.connectGatt(
                                context,
                                false,
                                createGattCallback(onConnected, onFailure),
                                BluetoothDevice.TRANSPORT_LE
                            )
                            Log.d("BLE", "🔗 Conectando a GATT del servidor...")
                        } else {
                            Log.e("BLE", "⚠️ No se tienen permisos para conectar")
                            onFailure()
                        }
                    } catch (e: SecurityException) {
                        Log.e("BLE", "⚠️ Error de seguridad al conectar: ${e.message}")
                        onFailure()
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BLE", "❌ Fallo escaneo BLE: $errorCode")
                onFailure()
            }
        }
    }

    private fun checkConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun createGattCallback(
        onConnected: () -> Unit,
        onFailure: () -> Unit
    ): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d("BLE", "✅ Conectado al servidor BLE")
                        try {
                            handler.removeCallbacksAndMessages(null)
                            if (checkConnectPermission()) {
                                gatt.discoverServices()
                            } else {
                                Log.e("BLE", "⚠️ No se tienen permisos para descubrir servicios")
                                gatt.disconnect()
                                onFailure()
                            }
                        } catch (e: SecurityException) {
                            Log.e("BLE", "⚠️ Error de seguridad: ${e.message}")
                            gatt.disconnect()
                            onFailure()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d("BLE", "🔌 Desconectado del servidor BLE")
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.e("BLE", "❌ Error de conexión: $status")
                            onFailure()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d("BLE", "🔎 Servicios descubiertos (status=$status)")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e("BLE", "❌ Error al descubrir servicios: status=$status")
                    onFailure()
                    return
                }

                try {
                    if (!checkConnectPermission()) {
                        Log.e("BLE", "⚠️ No se tienen permisos para acceder a servicios")
                        onFailure()
                        return
                    }

                    val service = gatt.getService(serviceUUID)
                    if (service == null) {
                        Log.e("BLE", "❌ Servicio GATT no encontrado en el servidor")
                        onFailure()
                        return
                    }
                    Log.d("BLE", "✅ Servicio encontrado")

                    val characteristic = service.getCharacteristic(characteristicUUID)
                    if (characteristic == null) {
                        Log.e("BLE", "❌ Característica no encontrada en el servicio")
                        onFailure()
                        return
                    }
                    Log.d("BLE", "✅ Característica encontrada")

                    sensorService.setBluetoothGatt(gatt)
                    onConnected()
                } catch (e: SecurityException) {
                    Log.e("BLE", "⚠️ Error de seguridad al acceder a servicios: ${e.message}")
                    onFailure()
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                Log.d("BLE", "📨 onCharacteristicWrite status=$status")
                sensorService.onCharacteristicWrite(status)
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    sensorService.onMtuChanged(mtu)
                } else {
                    Log.e("BLE", "❌ Error al cambiar MTU: $status")
                }
            }
        }
    }

    fun disconnect() {
        try {
            safeStopScan()
            if (checkConnectPermission()) {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            }
            bluetoothGatt = null
            handler.removeCallbacksAndMessages(null)
            Log.d("BLE", "🔌 Cliente BLE desconectado")
        } catch (e: SecurityException) {
            Log.e("BLE", "⚠️ Error al desconectar BLE: ${e.message}")
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        val hasBasicPermissions = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) == PackageManager.PERMISSION_GRANTED

        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasAndroid12Permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return hasBasicPermissions && hasLocationPermission && hasAndroid12Permissions
    }
}