package com.sirvivar.blifi

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb") // Example UUID; replace with your custom one
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "MainActivity"
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
            Manifest.permission.BLUETOOTH_ADVERTISE
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
                    startBackgroundServices()
                } else {
                    Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_SHORT).show()
                }
            }
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            startBackgroundServices()
        }
    }

    private fun startBackgroundServices() {
        val startTime = System.currentTimeMillis()
        val chatIntent = Intent(this, BluetoothChatService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(chatIntent)
            } else {
                startService(chatIntent)
            }
            Log.d(TAG, "BluetoothChatService started successfully at ${System.currentTimeMillis() - startTime}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service: ${e.message}")
            Toast.makeText(this, "BLE service failed to start.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Note: Service continues running in background
    }
}