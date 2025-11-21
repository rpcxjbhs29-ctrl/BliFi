package com.sirvivar.blifi.ui.chat

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
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
        val messageBubble: CardView = itemView.findViewById(R.id.message_bubble)
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

        val params = holder.messageBubble.layoutParams as ConstraintLayout.LayoutParams

        if (message.isSentByUser) {
            // Sent message (Right side, Blue bubble)
            holder.messageBubble.setCardBackgroundColor(
                ContextCompat.getColor(context, R.color.sent_bubble)
            )
            // Set text colors based on theme
            val textColor = ContextCompat.getColor(context, R.color.text_primary)
            holder.messageText.setTextColor(textColor)
            holder.messageTimestamp.setTextColor(textColor)
            
            // Set bubble background using theme colors (already set above)

            
            // Position on right
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.UNSET
            params.horizontalBias = 1.0f
        } else {
            // Received message (Left side, Gray bubble)
            holder.messageBubble.setCardBackgroundColor(
                ContextCompat.getColor(context, R.color.received_bubble)
            )
            holder.messageText.setTextColor(Color.BLACK)
            holder.messageTimestamp.setTextColor(Color.parseColor("#80000000")) // Semi-transparent black
            
            // Position on left
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.UNSET
            params.horizontalBias = 0.0f
        }
        
        holder.messageBubble.layoutParams = params
    }

    override fun getItemCount(): Int = messages.size
}