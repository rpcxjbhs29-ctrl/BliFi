package com.sirvivar.blifi.ui.chat

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sirvivar.blifi.R
import com.sirvivar.blifi.data.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageContainer: LinearLayout = itemView.findViewById(R.id.message_container)
        val messageText: TextView = itemView.findViewById(R.id.message_text)
        val messageTimestamp: TextView = itemView.findViewById(R.id.message_timestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val context = holder.itemView.context

        holder.messageText.text = message.text
        
        // Format timestamp
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        holder.messageTimestamp.text = sdf.format(Date(message.timestamp))

        val params = holder.messageContainer.layoutParams as ConstraintLayout.LayoutParams

        if (message.isSentByUser) {
            // Sent message (Right side, Blue bubble)
            holder.messageContainer.background = ContextCompat.getDrawable(context, R.drawable.bg_message_bubble_sent)
            holder.messageText.setTextColor(Color.WHITE)
            holder.messageTimestamp.setTextColor(Color.parseColor("#B3FFFFFF")) // Semi-transparent white
            
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.UNSET
        } else {
            // Received message (Left side, Gray bubble)
            holder.messageContainer.background = ContextCompat.getDrawable(context, R.drawable.bg_message_bubble_received)
            holder.messageText.setTextColor(Color.BLACK)
            holder.messageTimestamp.setTextColor(Color.parseColor("#80000000")) // Semi-transparent black
            
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.UNSET
        }
        
        holder.messageContainer.layoutParams = params
    }

    override fun getItemCount(): Int = messages.size
}