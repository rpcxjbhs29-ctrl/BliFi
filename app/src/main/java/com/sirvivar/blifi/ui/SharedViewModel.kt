package com.sirvivar.blifi.ui

import android.bluetooth.le.ScanResult
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import android.util.Log

class SharedViewModel : ViewModel() {

    companion object {
        private const val TAG = "SharedViewModel"
    }

    private val _selectedDevice = MutableLiveData<ScanResult?>()
    val selectedDevice: LiveData<ScanResult?> = _selectedDevice

    fun selectDevice(device: ScanResult) {
        Log.d(TAG, "selectDevice() called with: ${device.device.address}")
        _selectedDevice.value = device
        Log.d(TAG, "selectedDevice value set, observers will be notified")
    }

    fun clearSelectedDevice() {
        Log.d(TAG, "clearSelectedDevice() called")
        _selectedDevice.value = null
    }
}