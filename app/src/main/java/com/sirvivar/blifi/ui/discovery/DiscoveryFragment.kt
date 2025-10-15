package com.sirvivar.blifi.ui.discovery

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sirvivar.blifi.MainActivity
import com.sirvivar.blifi.R
import com.sirvivar.blifi.ui.SharedViewModel
import java.util.HashSet

class DiscoveryFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var discoveryAdapter: DiscoveryAdapter
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private val discoveredDevices = mutableListOf<ScanResult>()
    private val uniqueAddresses = HashSet<String>()
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var isScanning = false

    private val sharedViewModel: SharedViewModel by activityViewModels()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.address?.let { address ->
                if (uniqueAddresses.add(address)) {
                    discoveredDevices.add(result)
                    discoveryAdapter.notifyItemInserted(discoveredDevices.size - 1)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Toast.makeText(context, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
            stopBleScan()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_discovery, container, false)
        swipeRefreshLayout = root.findViewById(R.id.swipe_refresh_discovery)
        recyclerView = root.findViewById(R.id.recycler_discovery)

        setupRecyclerView()

        swipeRefreshLayout.setOnRefreshListener {
            startBleScan()
        }

        return root
    }

    private fun setupRecyclerView() {
        discoveryAdapter = DiscoveryAdapter(discoveredDevices) { scanResult ->
            stopBleScan()
            sharedViewModel.selectDevice(scanResult)
            activity?.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId = R.id.navigation_chats
        }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = discoveryAdapter
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startBleScan()
    }

    private fun startBleScan() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Bluetooth Scan permission not granted", Toast.LENGTH_SHORT).show()
            return
        }

        if (isScanning) {
            stopBleScan()
        }

        discoveredDevices.clear()
        uniqueAddresses.clear()
        discoveryAdapter.notifyDataSetChanged()

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MainActivity.SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // ⭐️ FIX: Catch potential SecurityException from the OS
        try {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true
            swipeRefreshLayout.isRefreshing = true
        } catch (e: SecurityException) {
            Log.e("DiscoveryFragment", "Failed to start scan due to SecurityException", e)
            Toast.makeText(context, "Scan failed: Lacking privileged permissions.", Toast.LENGTH_LONG).show()
            swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun stopBleScan() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: SecurityException) {
                Log.e("DiscoveryFragment", "Failed to stop scan due to SecurityException", e)
            }
        }
        isScanning = false
        swipeRefreshLayout.isRefreshing = false
    }

    override fun onStop() {
        super.onStop()
        if (isScanning) {
            stopBleScan()
        }
    }
}