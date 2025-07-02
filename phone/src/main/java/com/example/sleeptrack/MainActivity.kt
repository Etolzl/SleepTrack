package com.example.sleeptrack

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.example.sleeptrack.view.SleepResultsCard

class MainActivity : AppCompatActivity() {

    // Componentes UI
    private lateinit var resultsRecycler: RecyclerView
    private lateinit var tabLayout: TabLayout
    private lateinit var fabSync: FloatingActionButton

    // Adaptador y datos
    private val resultsAdapter = ResultsAdapter()
    private val recentSessions = mutableListOf<Pair<String, Map<String, Any>>>()
    private val historicalSessions = mutableListOf<Pair<String, Map<String, Any>>>()

    // Servicios
    private lateinit var bleGattServer: BleGattServer
    private val firebaseManager = FirebaseManager()

    // Estado
    private var currentTabPosition = 0
    private var isLoadingHistoricalData = false
    private var isRefreshing = false

    // Permisos
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startBleServer()
            safelyMakeDiscoverable()
        } else {
            showSnackbar("Se requieren permisos de Bluetooth para funcionar", true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupRecyclerView()
        setupTabLayout()
        setupBleServer()
        setupFabSync()
        checkPermissionsAndStart()
    }

    private fun initializeViews() {
        resultsRecycler = findViewById(R.id.results_recycler)
        tabLayout = findViewById(R.id.tab_layout)
        fabSync = findViewById(R.id.fab_sync)
    }

    private fun setupRecyclerView() {
        resultsRecycler.layoutManager = LinearLayoutManager(this)
        resultsRecycler.adapter = resultsAdapter
    }

    private fun setupTabLayout() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTabPosition = tab.position
                handleTabChange()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupBleServer() {
        bleGattServer = BleGattServer(this, firebaseManager) { sessionId, data ->
            runOnUiThread {
                addNewSession(sessionId, data)
            }
        }
    }

    private fun setupFabSync() {
        fabSync.setOnClickListener {
            refreshHistoricalData()
        }
    }

    private fun refreshHistoricalData() {
        if (isRefreshing) return

        if (currentTabPosition == 1) {
            isRefreshing = true
            // Animación de rotación
            fabSync.animate().rotationBy(360f).setDuration(1000).start()

            historicalSessions.clear()
            resultsAdapter.notifyDataSetChanged()
            loadHistoricalData()
            showSnackbar("Actualizando datos históricos...", false)

            // Detener la animación cuando termine la carga
            fabSync.animate().rotation(0f).setDuration(500).withEndAction {
                isRefreshing = false
            }.start()
        } else {
            showSnackbar("Cambia a la pestaña de Historial para actualizar", false)
        }
    }

    private fun handleTabChange() {
        when (currentTabPosition) {
            0 -> showRecentSessions()
            1 -> loadHistoricalDataIfNeeded()
        }
    }

    private fun showRecentSessions() {
        resultsAdapter.notifyDataSetChanged()
        if (recentSessions.isEmpty()) {
            showSnackbar("No hay datos recientes", false)
        }
    }

    private fun loadHistoricalDataIfNeeded() {
        if (historicalSessions.isEmpty() && !isLoadingHistoricalData) {
            loadHistoricalData()
        } else {
            resultsAdapter.notifyDataSetChanged()
        }
    }

    private fun loadHistoricalData() {
        isLoadingHistoricalData = true
        showSnackbar("Cargando datos históricos...", false)

        firebaseManager.getSleepData(
            onDataLoaded = { sessionId, data ->
                runOnUiThread {
                    if (!historicalSessions.any { it.first == sessionId }) {
                        historicalSessions.add(sessionId to data)
                        if (currentTabPosition == 1) {
                            resultsAdapter.notifyItemInserted(historicalSessions.size - 1)
                        }
                    }
                }
            },
            onComplete = {
                runOnUiThread {
                    isLoadingHistoricalData = false
                    if (currentTabPosition == 1) {
                        if (historicalSessions.isEmpty()) {
                            showSnackbar("No hay datos históricos disponibles", false)
                        }
                        resultsAdapter.notifyDataSetChanged()
                    }
                }
            }
        )
    }

    private fun addNewSession(sessionId: String, data: Map<String, Any>) {
        recentSessions.add(0, sessionId to data)
        if (currentTabPosition == 0) {
            resultsAdapter.notifyItemInserted(0)
            resultsRecycler.scrollToPosition(0)
            showSnackbar("Nuevos datos de sueño recibidos", false)
        }
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {
        private fun getCurrentItems() = when (currentTabPosition) {
            0 -> recentSessions
            1 -> historicalSessions
            else -> emptyList()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(SleepResultsCard.create(parent))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val items = getCurrentItems()
            if (position < items.size) {
                val (sessionId, data) = items[position]
                holder.card.bind(data, sessionId)
                holder.itemView.visibility = View.VISIBLE
            } else {
                holder.itemView.visibility = View.GONE
            }
        }

        override fun getItemCount() = getCurrentItems().size

        inner class ViewHolder(val card: SleepResultsCard) : RecyclerView.ViewHolder(card.view)
    }

    private fun showSnackbar(message: String, showAction: Boolean) {
        val snackbar = Snackbar.make(resultsRecycler, message,
            if (showAction) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_SHORT)
        if (showAction) {
            snackbar.setAction("Reintentar") { checkPermissionsAndStart() }
        }
        snackbar.show()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ))
        }

        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startBleServer()
            safelyMakeDiscoverable()
        } else {
            requestPermissions.launch(permissions.toTypedArray())
        }
    }

    private fun startBleServer() {
        bleGattServer.startServer()
        showSnackbar("Servidor BLE iniciado", false)
    }

    private fun safelyMakeDiscoverable() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null) {
            showSnackbar("Bluetooth no disponible", false)
            return
        }

        if (!hasBluetoothConnectPermission()) {
            showSnackbar("Se requieren permisos de Bluetooth", false)
            return
        }

        try {
            if (bluetoothAdapter.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                }
                startActivity(discoverableIntent)
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Error making device discoverable", e)
            showSnackbar("Error al hacer dispositivo descubrible", false)
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleGattServer.stopServer()
    }
}