package com.example.safe_kids.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.safe_kids.auth.AuthManager
import com.example.safe_kids.auth.User
import com.example.safe_kids.auth.isParentLike
import com.example.safe_kids.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class FamilyViewModel(
    private val authManager: AuthManager,
    private val repository: FamilyRepository
) : ViewModel() {

    private val familyId: StateFlow<String?> = authManager.currentUser.map { user ->
        user?.parentIds?.firstOrNull() ?: user?.id
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentUser: StateFlow<User?> = authManager.currentUser

    // Compute a chatId that matches the pairwise chat used by ChatActivity when possible.
    private val chatId: StateFlow<String?> = combine(currentUser, familyId) { user, fid ->
        if (user == null) return@combine null
        // Determine the other participant: for child -> parent, for parent -> first child (fallback to fid)
        val otherId = when {
            user.role == com.example.safe_kids.auth.UserRole.CHILD -> user.parentIds.firstOrNull() ?: fid ?: user.id
            user.role.isParentLike -> user.childIds.firstOrNull() ?: fid ?: user.id
            else -> fid ?: user.id
        }
        if (otherId.isBlank()) return@combine null
        val a = user.id
        val b = otherId
        if (a < b) "${a}_$b" else "${b}_$a"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages: StateFlow<List<ChatMessage>> = chatId.flatMapLatest { id ->
        if (id != null) repository.getMessagesByChatId(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val requests: StateFlow<List<FamilyRequest>> = familyId.flatMapLatest { id ->
        if (id != null) repository.getRequests(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun sendMessage(text: String) {
        val user = currentUser.value ?: return
        val id = chatId.value ?: return
        viewModelScope.launch {
            val message = ChatMessage(
                senderId = user.id,
                receiverId = id,
                message = text,
                timestamp = System.currentTimeMillis(),
                isRead = false,
                isDelivered = false
            )
            repository.sendMessageToChat(id, message)
        }
    }

    fun sendRequest(type: RequestType, content: String) {
        val user = currentUser.value ?: return
        val id = familyId.value ?: return
        viewModelScope.launch {
            val request = FamilyRequest(
                senderId = user.id,
                senderName = user.displayName ?: "User",
                type = type,
                content = content
            )
            repository.sendRequest(id, request)
        }
    }

    fun updateRequestStatus(requestId: String, status: RequestStatus) {
        val id = familyId.value ?: return
        viewModelScope.launch {
            repository.updateRequestStatus(id, requestId, status)
        }
    }
}
