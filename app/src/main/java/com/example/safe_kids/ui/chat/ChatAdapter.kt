package com.example.safe_kids.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.safe_kids.R
import com.example.safe_kids.data.ChatMessage
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val currentUserId: String,
    private val isParent: Boolean
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message, currentUserId, isParent)
    }

    override fun getItemCount(): Int = messages.size

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        private val readReceipt: ImageView = itemView.findViewById(R.id.readReceipt)
        private val messageContainer: View = itemView.findViewById(R.id.messageContainer)
        private val dateFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

        fun bind(message: ChatMessage, currentUserId: String, isParent: Boolean) {
            messageText.text = message.message
            timestampText.text = dateFormat.format(Date(message.timestamp))

            // Determine if message is from current user
            val isFromCurrentUser = message.senderId == currentUserId

            // Set message bubble color
            if (isFromCurrentUser) {
                // Current user's message - purple for parent, blue for child
                messageContainer.setBackgroundResource(
                    if (isParent) R.drawable.message_bubble_purple else R.drawable.message_bubble_blue
                )
            } else {
                // Other user's message - gray
                messageContainer.setBackgroundResource(R.drawable.message_bubble_gray)
            }

            // Set read receipt
            when {
                message.isRead -> {
                    readReceipt.setImageResource(R.drawable.ic_double_tick_blue)
                    readReceipt.visibility = View.VISIBLE
                }
                message.isDelivered -> {
                    readReceipt.setImageResource(R.drawable.ic_double_tick_gray)
                    readReceipt.visibility = View.VISIBLE
                }
                else -> {
                    readReceipt.setImageResource(R.drawable.ic_single_tick)
                    readReceipt.visibility = View.VISIBLE
                }
            }
        }
    }
}
