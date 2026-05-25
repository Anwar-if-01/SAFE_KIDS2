package com.example.safe_kids.data

enum class RequestType { PERMISSION, SCREEN_TIME, APP_UNLOCK, OTHER }

data class FamilyRequest(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val type: RequestType = RequestType.OTHER,
    val content: String = "",
    val status: RequestStatus = RequestStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis()
)
