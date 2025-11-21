package com.sirvivar.blifi.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sirvivar.blifi.R
import com.sirvivar.blifi.data.model.Conversation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationAdapter(
    private val onItemClick: (Conversation) -> Unit
) : ListAdapter<Conversation, ConversationAdapter.ConversationViewHolder>(ConversationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ConversationViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ConversationViewHolder(
        itemView: View,
        private val onItemClick: (Conversation) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val profileCircle: TextView = itemView.findViewById(R.id.profile_circle)
        private val deviceName: TextView = itemView.findViewById(R.id.device_name)
        private val lastMessage: TextView = itemView.findViewById(R.id.last_message)
        private val timestamp: TextView = itemView.findViewById(R.id.timestamp)
        private val onlineIndicator: View = itemView.findViewById(R.id.online_indicator)

        fun bind(conversation: Conversation) {
            val name = conversation.deviceName
            deviceName.text = name
            lastMessage.text = conversation.lastMessage ?: "No messages"
            timestamp.text = formatTimestamp(conversation.lastMessageTime)
            
            // Display emoji if set, otherwise show first letter of device name
            profileCircle.text = conversation.profileEmoji ?: name.firstOrNull()?.toString()?.uppercase() ?: "?"
            
            // Set online status
            onlineIndicator.visibility = if (conversation.isOnline) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                onItemClick(conversation)
            }
        }

        private fun formatTimestamp(timestamp: Long): String {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }

    class ConversationDiffCallback : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return oldItem.deviceId == newItem.deviceId  // Compare by deviceId instead of address
        }

        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return oldItem == newItem
        }
    }
}
