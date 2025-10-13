package com.sirvivar.blifi.ui.chats

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sirvivar.blifi.BluetoothChatService
import com.sirvivar.blifi.R
import com.sirvivar.blifi.ui.chat.ChatActivity
import com.sirvivar.blifi.ui.chat.ChatAdapter

class ChatFragment : Fragment() {

    private var recyclerView: RecyclerView? = null
    private var chatAdapter: ChatsAdapter? = null
    private val devices = mutableListOf<BluetoothDevice>()
    private var bluetoothChatService: BluetoothChatService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            bluetoothChatService = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_chats, container, false)
        recyclerView = root.findViewById(R.id.recycler_chats)
        recyclerView?.layoutManager = LinearLayoutManager(context)

        chatAdapter = ChatsAdapter(devices) { device ->
            val intent = Intent(context, ChatActivity::class.java).apply {
                putExtra("DEVICE_ADDRESS", device.address)
                putExtra("DEVICE_NAME", device.name ?: "Unknown Device")
            }
            startActivity(intent)
        }
        recyclerView?.adapter = chatAdapter

        return root
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(context, BluetoothChatService::class.java)
        context?.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            context?.unbindService(connection)
            isBound = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
        chatAdapter = null
    }

    fun updateDevices(newDevices: List<BluetoothDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        chatAdapter?.notifyDataSetChanged()
    }
}