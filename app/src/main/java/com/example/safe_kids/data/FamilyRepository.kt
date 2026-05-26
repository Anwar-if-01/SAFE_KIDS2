package com.example.safe_kids.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FamilyRepository {
    private val firestore = FirebaseFirestore.getInstance()

    fun getMessages(familyId: String): Flow<List<ChatMessage>> = callbackFlow {
        val listener = firestore.collection("chats")
            .document(familyId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val messages = snapshot?.toObjects(ChatMessage::class.java) ?: emptyList()
                trySend(messages)
            }
        awaitClose { listener.remove() }
    }

    fun getMessagesByChatId(chatId: String): Flow<List<ChatMessage>> = callbackFlow {
        val listener = firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val messages = snapshot?.toObjects(ChatMessage::class.java) ?: emptyList()
                trySend(messages)
            }
        awaitClose { listener.remove() }
    }

    suspend fun sendMessageToChat(chatId: String, message: ChatMessage) {
        firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .add(message)
            .await()
    }

    suspend fun sendMessage(familyId: String, message: ChatMessage) {
        firestore.collection("chats")
            .document(familyId)
            .collection("messages")
            .add(message)
            .await()
    }

    fun getRequests(familyId: String): Flow<List<FamilyRequest>> = callbackFlow {
        val listener = firestore.collection("chats")
            .document(familyId)
            .collection("requests")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val requests = snapshot?.toObjects(FamilyRequest::class.java) ?: emptyList()
                trySend(requests)
            }
        awaitClose { listener.remove() }
    }

    suspend fun sendRequest(familyId: String, request: FamilyRequest) {
        val docRef = firestore.collection("chats")
            .document(familyId)
            .collection("requests")
            .document()
        
        docRef.set(request.copy(id = docRef.id)).await()
    }

    suspend fun updateRequestStatus(familyId: String, requestId: String, status: RequestStatus) {
        firestore.collection("chats")
            .document(familyId)
            .collection("requests")
            .document(requestId)
            .update("status", status)
            .await()
    }
}
