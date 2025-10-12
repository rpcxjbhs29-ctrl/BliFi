package com.sirvivar.blifi.ui.discovery

import android.bluetooth.le.ScanResult
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sirvivar.blifi.R

class DiscoveryAdapter(private val devices: List<ScanResult>) :
    RecyclerView.Adapter<DiscoveryAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val deviceName: TextView = itemView.findViewById(R.id.device_name)
        val deviceAddress: TextView = itemView.findViewById(R.id.device_address)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val scanResult = devices[position]
        val device = scanResult.device
        holder.deviceName.text = device.name ?: "Unknown Device"
        holder.deviceAddress.text = device.address
    }

    override fun getItemCount(): Int = devices.size
}