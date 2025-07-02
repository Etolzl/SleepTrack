package com.example.sleeptrack

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import org.json.JSONException
import org.json.JSONObject
import java.util.*
import android.Manifest

class BleGattServer(
    private val context: Context,
    private val firebaseManager: FirebaseManager,
    private val onDataReceived: (String, Map<String, Any>) -> Unit
) {
    private val serviceUUID = UUID.fromString("0000181A-0000-1000-8000-00805f9b34fb")
    private val characteristicUUID = UUID.fromString("00002A6E-0000-1000-8000-00805f9b34fb")

    private var bluetoothGattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedDevice: BluetoothDevice? = null
    private val receivedData = StringBuilder()
    private val gson = Gson()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d("BLE", "Publicidad BLE iniciada correctamente")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BLE", "Fallo al iniciar publicidad BLE: $errorCode")
        }
    }

    fun startServer() {
        if (!checkBluetoothPermissions()) {
            Log.e("BLE", "Permisos Bluetooth no concedidos")
            return
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: run {
            Log.e("BLE", "Bluetooth no disponible")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e("BLE", "Bluetooth no activado")
            return
        }

        try {
            bluetoothGattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            if (bluetoothGattServer == null) {
                Log.e("BLE", "Error al crear servidor GATT")
                return
            }
        } catch (e: SecurityException) {
            Log.e("BLE", "Error de seguridad al abrir servidor GATT: ${e.message}")
            return
        }

        val service = BluetoothGattService(serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            characteristicUUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(characteristic)

        try {
            bluetoothGattServer?.addService(service)
            Log.d("BLE", "Servicio añadido")
        } catch (e: SecurityException) {
            Log.e("BLE", "Error de seguridad al añadir servicio: ${e.message}")
            return
        }

        try {
            advertiser = bluetoothAdapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e("BLE", "Error al obtener anunciante")
                return
            }
        } catch (e: SecurityException) {
            Log.e("BLE", "Error de seguridad al obtener anunciante: ${e.message}")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(serviceUUID))
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.d("BLE", "Iniciando publicidad BLE...")
        } catch (e: SecurityException) {
            Log.e("BLE", "Error de seguridad al iniciar publicidad: ${e.message}")
        }
    }

    fun stopServer() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            bluetoothGattServer?.close()
            bluetoothGattServer = null
            connectedDevice = null
            receivedData.clear()
            Log.d("BLE", "Servidor BLE detenido")
        } catch (e: SecurityException) {
            Log.e("BLE", "Error de seguridad al detener servidor: ${e.message}")
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED))
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            Log.d("GATT", "Estado conexión: $newState, estado: $status, dispositivo: ${device?.address}")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    Log.d("GATT", "Dispositivo conectado: ${getDeviceName(device)}")
                    receivedData.clear()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    Log.d("GATT", "Dispositivo desconectado")
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            value?.let {
                val chunk = String(it, Charsets.UTF_8)
                receivedData.append(chunk)

                if (isValidJson(receivedData.toString())) {
                    try {
                        val completeMessage = receivedData.toString()
                        Log.d("GATT", "Mensaje completo recibido: $completeMessage")

                        val results = gson.fromJson(completeMessage, Map::class.java)
                        val summary = results["summary"] as Map<*, *>
                        val fullData = results["fullData"] as Map<*, *>

                        val processedSummary = summary.mapValues { (_, value) ->
                            when (value) {
                                is Number -> value.toDouble()
                                else -> value
                            }
                        } as Map<String, Any>

                        val sessionId = UUID.randomUUID().toString()
                        val firebaseData = mutableMapOf<String, Any>(
                            "summary" to processedSummary,
                            "heartRateData" to (fullData["heartRateData"] as? List<*> ?: emptyList<Any>()),
                            "movementData" to (fullData["movementData"] as? List<*> ?: emptyList<Any>()),
                            "timestamp" to System.currentTimeMillis(),
                            "deviceAddress" to (device?.address ?: "unknown"),
                            "deviceName" to (getDeviceName(device) ?: "unknown")
                        )

                        firebaseManager.uploadSleepData(null, sessionId, firebaseData)
                        onDataReceived(sessionId, processedSummary)
                        receivedData.clear()
                    } catch (e: Exception) {
                        Log.e("GATT", "Error procesando JSON: ${e.message}")
                    }
                }
            }

            if (responseNeeded && hasBluetoothConnectPermission() && device != null) {
                try {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                } catch (e: SecurityException) {
                    Log.e("GATT", "Error de seguridad al enviar respuesta: ${e.message}")
                }
            }
        }

        private fun isValidJson(json: String): Boolean {
            return try {
                JSONObject(json)
                true
            } catch (e: JSONException) {
                false
            }
        }

        private fun getDeviceName(device: BluetoothDevice?): String {
            return if (device != null && hasBluetoothConnectPermission()) {
                try {
                    device.name ?: "desconocido"
                } catch (e: SecurityException) {
                    "desconocido"
                }
            } else {
                device?.address ?: "desconocido"
            }
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}