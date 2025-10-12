package com.sirvivar.blifi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false

    // Custom UUID for BliFi chat service (can be changed)
    private val CHAT_SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805F9B34FB")

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Toast.makeText(this@MainActivity, "BLE advertising started", Toast.LENGTH_SHORT).show()
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Toast.makeText(this@MainActivity, "Advertising failed: $errorCode", Toast.LENGTH_SHORT).show()
            isAdvertising = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navView: BottomNavigationView = findViewById(R.id.nav_view)
        val navController = findNavController(R.id.nav_host_fragment_activity_main)

        // Hide action bar
        supportActionBar?.hide()

        // Setup bottom navigation with NavController
        navView.setupWithNavController(navController)

        // Request Bluetooth and location permissions
        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        val permissionsToRequest = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSION_REQUEST_CODE)
        } else {
            // All permissions granted; check if Bluetooth is enabled
            checkBluetoothEnabled()
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE  // Added for advertising
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
                checkBluetoothEnabled()
            } else {
                Toast.makeText(
                    this,
                    "Permissions denied. BLE features may not work.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkBluetoothEnabled() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            // Prompt to enable Bluetooth
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            val enableBluetoothLauncher = registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
                    startBleAdvertising()  // Start advertising after enabling
                } else {
                    Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_SHORT).show()
                }
            }
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            startBleAdvertising()  // Start if already enabled
        }
    }

    private fun startBleAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Toast.makeText(this, "BLE advertising not supported", Toast.LENGTH_SHORT).show()
            return
        }

        // Check permission explicitly
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Advertising permission missing", Toast.LENGTH_SHORT).show()
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)  // Low power as requested
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(CHAT_SERVICE_UUID))  // Advertise chat service UUID
            .setIncludeDeviceName(true)
            .build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, advertiseCallback)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission error during advertising", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isAdvertising) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (e: SecurityException) {
                // Handle revocation
            }
            isAdvertising = false
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}