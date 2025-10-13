package com.sirvivar.blifi.ui.discovery

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.sirvivar.blifi.MainActivity
import com.sirvivar.blifi.R
import com.sirvivar.blifi.ui.chat.ChatActivity
import com.sirvivar.blifi.ui.chats.ChatFragment
import java.util.HashSet

class DiscoveryFragment : Fragment() {

    private var recyclerView: RecyclerView? = null
    private var discoveryAdapter: DiscoveryAdapter? = null
    private val discoveredDevices = mutableListOf<ScanResult>()
    private val uniqueAddresses = HashSet<String>() // For deduplication
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var isScanning = false
    private var swipeRefreshLayout: SwipeRefreshLayout? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device ->
                val address = device.address
                if (!uniqueAddresses.contains(address)) {
                    uniqueAddresses.add(address)
                    discoveredDevices.add(result)
                    discoveryAdapter?.notifyItemInserted(discoveredDevices.size - 1)
                    // Update ChatFragment with unique devices
                    val chatFragment = parentFragmentManager.fragments
                        .filterIsInstance<ChatFragment>()
                        .firstOrNull()
                    chatFragment?.updateDevices(discoveredDevices.map { it.device })
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Toast.makeText(context, "Scan failed with error code: $errorCode", Toast.LENGTH_SHORT).show()
            isScanning = false
            swipeRefreshLayout?.isRefreshing = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_discovery, container, false)
        swipeRefreshLayout = root.findViewById(R.id.swipe_refresh_discovery)
        recyclerView = root.findViewById(R.id.recycler_discovery)
        recyclerView?.layoutManager = LinearLayoutManager(context)

        discoveryAdapter = DiscoveryAdapter(discoveredDevices) { scanResult ->
            val intent = Intent(context, ChatActivity::class.java).apply {
                putExtra("DEVICE_ADDRESS", scanResult.device.address)
                putExtra("DEVICE_NAME", scanResult.device.name ?: "Unknown Device")
            }
            startActivity(intent)
        }
        recyclerView?.adapter = discoveryAdapter

        swipeRefreshLayout?.setOnRefreshListener {
            startBleScan()
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startBleScan()
    }

    private fun startBleScan() {
        if (bluetoothAdapter == null) {
            Toast.makeText(context, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            swipeRefreshLayout?.isRefreshing = false
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Bluetooth is not enabled", Toast.LENGTH_SHORT).show()
            swipeRefreshLayout?.isRefreshing = false
            return
        }

        val bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            Toast.makeText(context, "BLE not supported", Toast.LENGTH_SHORT).show()
            swipeRefreshLayout?.isRefreshing = false
            return
        }

        // Check required permissions
        val requiredPermissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Toast.makeText(
                context,
                "Missing permissions: ${missingPermissions.joinToString()}. Please grant in settings.",
                Toast.LENGTH_LONG
            ).show()
            swipeRefreshLayout?.isRefreshing = false
            return
        }

        // Permissions granted, start scanning
        try {
            // Clear existing data for fresh scan
            val oldSize = discoveredDevices.size
            discoveredDevices.clear()
            uniqueAddresses.clear()
            discoveryAdapter?.notifyItemRangeRemoved(0, oldSize)

            if (isScanning) {
                bleScanner.stopScan(scanCallback)
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Faster scans, fewer duplicates
                .build()

            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(MainActivity.SERVICE_UUID))
                .build()

            bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true
            Toast.makeText(context, "Starting BLE scan...", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(context, "Permission error during scan", Toast.LENGTH_SHORT).show()
            isScanning = false
        } finally {
            swipeRefreshLayout?.isRefreshing = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isScanning) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: SecurityException) {
                // Handle case where permission was revoked during scan
            }
            isScanning = false
        }
        recyclerView = null
        discoveryAdapter = null
        swipeRefreshLayout = null
    }
}