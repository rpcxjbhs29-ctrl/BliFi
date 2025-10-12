package com.sirvivar.blifi.ui.discovery

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sirvivar.blifi.R

class DiscoveryFragment : Fragment() {

    private var recyclerView: RecyclerView? = null
    private var discoveryAdapter: DiscoveryAdapter? = null
    private val discoveredDevices = mutableListOf<ScanResult>()
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device ->
                if (!discoveredDevices.any { it.device.address == device.address }) {
                    discoveredDevices.add(result)
                    discoveryAdapter?.notifyDataSetChanged()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Toast.makeText(context, "Scan failed with error code: $errorCode", Toast.LENGTH_SHORT).show()
            isScanning = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_discovery, container, false)
        recyclerView = root.findViewById(R.id.recycler_discovery)
        recyclerView?.layoutManager = LinearLayoutManager(context)

        discoveryAdapter = DiscoveryAdapter(discoveredDevices)
        recyclerView?.adapter = discoveryAdapter

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startBleScan()
    }

    private fun startBleScan() {
        if (bluetoothAdapter == null) {
            Toast.makeText(context, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Bluetooth is not enabled", Toast.LENGTH_SHORT).show()
            return
        }

        val bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            Toast.makeText(context, "BLE not supported", Toast.LENGTH_SHORT).show()
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
            return
        }

        // Permissions granted, start scanning
        try {
            discoveredDevices.clear()
            discoveryAdapter?.notifyDataSetChanged()

            if (isScanning) {
                bleScanner.stopScan(scanCallback)
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER) // Low power mode for BLE
                .build()

            bleScanner.startScan(null, scanSettings, scanCallback)
            isScanning = true
            Toast.makeText(context, "Starting BLE scan...", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(context, "Permission error during scan", Toast.LENGTH_SHORT).show()
            isScanning = false
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
    }
}