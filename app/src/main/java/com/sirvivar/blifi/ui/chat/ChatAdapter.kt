package com.sirvivar.blifi.ui.chat

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
        val messageBubble: ConstraintLayout = itemView.findViewById(R.id.message_bubble)
        val messageText: TextView = itemView.findViewById(R.id.message_text)
        val messageTimestamp: TextView = itemView.findViewById(R.id.message_timestamp)
        val readReceiptIndicator: ImageView = itemView.findViewById(R.id.read_receipt_indicator)
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
            // Sent message (Right side, Glass blue bubble)
            holder.messageBubble.setBackgroundResource(R.drawable.bg_glass_bubble_sent)
            holder.messageTimestamp.visibility = View.VISIBLE

            // Show read receipt indicator for sent messages
            holder.readReceiptIndicator.visibility = View.VISIBLE
            if (message.isRead) {
                // Both checks are blue (read)
                holder.readReceiptIndicator.setImageResource(R.drawable.ic_check_double_read)
            } else {
                // Single check (sent but not delivered/read yet)
                holder.readReceiptIndicator.setImageResource(R.drawable.ic_check_single)
            }

            // Position on right
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.UNSET
            params.horizontalBias = 1.0f
        } else {
            // Received message (Left side, Glass gray bubble)
            holder.messageBubble.setBackgroundResource(R.drawable.bg_glass_bubble_received)
            holder.messageTimestamp.visibility = View.VISIBLE

            // Hide read receipt indicator for received messages
            holder.readReceiptIndicator.visibility = View.GONE

            // Position on left
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.UNSET
            params.horizontalBias = 0.0f
        }

        holder.messageBubble.layoutParams = params
    }

    override fun getItemCount(): Int = messages.size
}