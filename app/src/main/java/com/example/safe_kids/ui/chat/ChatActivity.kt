package com.example.safe_kids.ui.chat

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.safe_kids.R
import com.example.safe_kids.data.ChatMessage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var userNameText: TextView
    private lateinit var userAvatar: ImageView
    private lateinit var onlineStatus: View

    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var messageListener: ListenerRegistration? = null
    private var presenceListener: com.google.firebase.database.ValueEventListener? = null

    private var currentUserId: String = ""
    private var otherUserId: String = ""
    private var otherUserName: String = ""
    private var isParent: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // Get intent data
        otherUserId = intent.getStringExtra("OTHER_USER_ID") ?: ""
        otherUserName = intent.getStringExtra("OTHER_USER_NAME") ?: "Unknown"
        isParent = intent.getBooleanExtra("IS_PARENT", false)

        currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

        // Initialize views
        recyclerView = findViewById(R.id.recyclerView)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        backButton = findViewById(R.id.backButton)
        userNameText = findViewById(R.id.userNameText)
        userAvatar = findViewById(R.id.userAvatar)
        onlineStatus = findViewById(R.id.onlineStatus)

        // Setup UI
        userNameText.text = otherUserName
        backButton.setOnClickListener { finish() }
        sendButton.setOnClickListener { sendMessage() }

        // Setup RecyclerView
        chatAdapter = ChatAdapter(messages, currentUserId, isParent)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = chatAdapter

        // Load messages
        loadMessages()
        
        // Start presence tracking for the other user
        startPresenceTracking()
    }

    private fun loadMessages() {
        val db = FirebaseFirestore.getInstance()
        
        // Create chat document ID (sorted combination of user IDs)
        val chatId = if (currentUserId < otherUserId) {
            "${currentUserId}_$otherUserId"
        } else {
            "${otherUserId}_$currentUserId"
        }

        messageListener = db.collection("chats")
            .document(chatId)
            .collection("messages")
            .orderBy("timestamp")
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener

                messages.clear()
                snapshot?.documents?.forEach { doc ->
                    val message = doc.toObject(ChatMessage::class.java)
                    message?.let { messages.add(it) }
                }

                chatAdapter.notifyDataSetChanged()
                recyclerView.scrollToPosition(messages.size - 1)

                // Mark unread messages as read
                markMessagesAsRead(chatId)
            }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return

        val db = FirebaseFirestore.getInstance()
        
        // Create chat document ID (sorted combination of user IDs)
        val chatId = if (currentUserId < otherUserId) {
            "${currentUserId}_$otherUserId"
        } else {
            "${otherUserId}_$currentUserId"
        }

        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = currentUserId,
            receiverId = otherUserId,
            message = text,
            timestamp = System.currentTimeMillis(),
            isRead = false,
            isDelivered = false
        )

        db.collection("chats")
            .document(chatId)
            .collection("messages")
            .add(message)
            .addOnSuccessListener {
                messageInput.text.clear()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show()
            }
    }

    private fun markMessagesAsRead(chatId: String) {
        val db = FirebaseFirestore.getInstance()
        
        db.collection("chats")
            .document(chatId)
            .collection("messages")
            .whereEqualTo("receiverId", currentUserId)
            .whereEqualTo("isRead", false)
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.documents.forEach { doc ->
                    doc.reference.update("isRead", true)
                }
            }
    }
    
    private fun startPresenceTracking() {
        val database = com.google.firebase.database.FirebaseDatabase.getInstance()
        val presenceRef = database.getReference("child_presence/$otherUserId")
        
        presenceListener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val isOnline = snapshot.child("isOnline").getValue(Boolean::class.java) ?: false
                val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0
                
                updateOnlineStatus(isOnline, lastSeen)
            }
            
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                // Handle error
            }
        }
        
        presenceRef.addValueEventListener(presenceListener!!)
    }
    
    private fun updateOnlineStatus(isOnline: Boolean, lastSeen: Long) {
        val presenceStatus = when {
            isOnline && (System.currentTimeMillis() - lastSeen) < 3 * 60 * 1000L -> com.example.safe_kids.utils.PresenceStatus.ONLINE
            (System.currentTimeMillis() - lastSeen) < 10 * 60 * 1000L -> com.example.safe_kids.utils.PresenceStatus.IDLE
            else -> com.example.safe_kids.utils.PresenceStatus.OFFLINE
        }
        
        val statusColor = when (presenceStatus) {
            com.example.safe_kids.utils.PresenceStatus.ONLINE -> android.graphics.Color.parseColor("#4CAF50") // Green
            com.example.safe_kids.utils.PresenceStatus.IDLE -> android.graphics.Color.parseColor("#FFA000") // Yellow
            com.example.safe_kids.utils.PresenceStatus.OFFLINE -> android.graphics.Color.parseColor("#9E9E9E") // Gray
        }
        
        onlineStatus.setBackgroundColor(statusColor)
    }

    override fun onDestroy() {
        super.onDestroy()
        messageListener?.remove()
        presenceListener?.let {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("child_presence/$otherUserId")
                .removeEventListener(it)
        }

        if (::recyclerView.isInitialized) {
            try { recyclerView.adapter = null } catch (e: Exception) { }
        }
        if (::sendButton.isInitialized) {
            try { sendButton.setOnClickListener(null) } catch (e: Exception) { }
        }
        if (::backButton.isInitialized) {
            try { backButton.setOnClickListener(null) } catch (e: Exception) { }
        }
        if (::messageInput.isInitialized) {
            try { messageInput.text.clear() } catch (e: Exception) { }
        }
    }
}
