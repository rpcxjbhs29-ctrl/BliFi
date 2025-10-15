package com.sirvivar.blifi.ui

import android.bluetooth.le.ScanResult
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class SharedViewModel : ViewModel() {

    private val _selectedDevice = MutableLiveData<ScanResult?>()
    val selectedDevice: LiveData<ScanResult?> = _selectedDevice

    fun selectDevice(device: ScanResult) {
        _selectedDevice.value = device
    }

    fun clearSelectedDevice() {
        _selectedDevice.value = null
    }
}