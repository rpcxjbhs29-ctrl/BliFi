package com.sirvivar.blifi.ui.settings

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import com.sirvivar.blifi.R
import com.sirvivar.blifi.service.BluetoothChatService
import com.sirvivar.blifi.utils.Constants.DEFAULT_DEVICE_NAME
import com.sirvivar.blifi.utils.Constants.PREF_DEVICE_NAME
import com.sirvivar.blifi.utils.Constants.PREFS_NAME

class SettingsFragment : Fragment() {

    private var deviceNameInput: TextInputEditText? = null
    private var saveButton: Button? = null
    private var deviceAddressText: TextView? = null
    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)
        
        deviceNameInput = root.findViewById(R.id.device_name_input)
        saveButton = root.findViewById(R.id.save_button)
        deviceAddressText = root.findViewById(R.id.device_address)
        
        loadCurrentDeviceName()
        loadDeviceAddress()
        
        saveButton?.setOnClickListener {
            saveDeviceName()
        }
        
        return root
    }
    
    private fun loadCurrentDeviceName() {
        val sharedPref = activity?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedName = sharedPref?.getString(PREF_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        deviceNameInput?.setText(savedName)
    }
    
    private fun loadDeviceAddress() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val address = bluetoothAdapter?.address ?: "Unknown"
            deviceAddressText?.text = address
        } else {
            deviceAddressText?.text = "Permission required"
        }
    }
    
    private fun saveDeviceName() {
        val newName = deviceNameInput?.text?.toString()?.trim()
        
        if (newName.isNullOrEmpty()) {
            Toast.makeText(context, "Device name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Save to SharedPreferences
        val sharedPref = activity?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPref?.edit()?.apply {
            putString(PREF_DEVICE_NAME, newName)
            apply()
        }
        
        // Restart the service to apply the new name
        val serviceIntent = Intent(activity, BluetoothChatService::class.java)
        activity?.stopService(serviceIntent)
        activity?.startService(serviceIntent)
        
        Toast.makeText(context, "Device name saved: $newName", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        deviceNameInput = null
        saveButton = null
        deviceAddressText = null
    }
}