package com.sirvivar.blifi.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sirvivar.blifi.R
import com.sirvivar.blifi.data.model.ChatMessage

class ChatAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.message_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]

        // Use the isSentByUser property from the ChatMessage object to format the text
        holder.messageText.text = if (message.isSentByUser) {
            "You: ${message.text}"
        } else {
            "Them: ${message.text}"
        }
    }

    override fun getItemCount(): Int = messages.size
}