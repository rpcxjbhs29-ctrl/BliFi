package com.sirvivar.blifi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sirvivar.blifi.service.BluetoothChatService
import com.sirvivar.blifi.utils.Constants.SERVICE_UUID

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navView: BottomNavigationView = findViewById(R.id.nav_view)
        val navController = findNavController(R.id.nav_host_fragment_activity_main)

        supportActionBar?.hide()
        navView.setupWithNavController(navController)
        requestBluetoothPermissions()
        requestBatteryOptimizationExemption()
        
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("EXTRA_DEVICE_ADDRESS")?.let { address ->
            // We need to navigate to ChatFragment and open the chat
            // Since we are using SharedViewModel, we can set the selected device there.
            // However, we need the full device object or at least name.
            // For now, let's just set the address and let ChatFragment handle it if possible,
            // or we can try to find the device in the repository (but that's async).
            
            // A simpler way might be to just navigate to the chat list, 
            // and then have the chat list (DiscoveryFragment) or ChatFragment pick it up.
            
            // Let's try to find the device from the BluetoothAdapter since we have the address
            val device = bluetoothAdapter?.getRemoteDevice(address)
            if (device != null) {
                 // We need to access SharedViewModel. 
                 // Since we are in Activity, we can't easily get the ViewModel scoped to Activity 
                 // without using ViewModelProvider.
                 val sharedViewModel = androidx.lifecycle.ViewModelProvider(this)[com.sirvivar.blifi.ui.SharedViewModel::class.java]
                 
                 // We need a ScanResult-like object or just the device.
                 // SharedViewModel expects a ScanResult. Let's create a dummy one or update SharedViewModel.
                 // Actually, SharedViewModel.selectDevice takes a ScanResult.
                 // Let's update SharedViewModel to allow selecting by device/address or 
                 // create a dummy ScanResult.
                 
                 // Creating a dummy ScanResult is a bit hacky but works for now.
                 // android.bluetooth.le.ScanResult constructor is public.
                 // Use the simpler constructor available since API 21
                 val scanResult = android.bluetooth.le.ScanResult(
                     device,
                     null, // scanRecord
                     0, // rssi
                     System.nanoTime() // timestampNanos
                 )
                 sharedViewModel.selectDevice(scanResult)
                 
                 // Navigate to the chats fragment
                 val navController = findNavController(R.id.nav_host_fragment_activity_main)
                 if (navController.currentDestination?.id != R.id.navigation_chats) {
                     navController.navigate(R.id.navigation_chats)
                 }
            }
        }
    }

    private fun requestBluetoothPermissions() {
        val permissionsToRequest = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSION_REQUEST_CODE)
        } else {
            checkBluetoothEnabled()
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.toTypedArray()
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
                    "Permissions denied. App features may not work.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkBluetoothEnabled() {
        // ⭐️ FIX: Read the delegated property into a local variable.
        val btAdapter = bluetoothAdapter
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            return
        }

        // Now, smart casting works correctly on 'btAdapter'.
        if (!btAdapter.isEnabled) {
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
        val chatIntent = Intent(this, BluetoothChatService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(chatIntent)
            } else {
                startService(chatIntent)
            }
            Log.d(TAG, "BluetoothChatService started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service: ${e.message}")
            Toast.makeText(this, "BLE service failed to start.", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
            val packageName = packageName
            
            if (powerManager?.isIgnoringBatteryOptimizations(packageName) == false) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Toast.makeText(
                        this,
                        "Please disable battery optimization for continuous BLE advertising",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request battery optimization exemption", e)
                }
            }
        }
    }
}