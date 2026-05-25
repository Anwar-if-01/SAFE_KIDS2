package com.example.safe_kids.ui.components

import java.text.SimpleDateFormat
import java.util.*

fun toDisplayTime(storageTime: String): String {
    return try {
        val parser = SimpleDateFormat("HH:mm", Locale.getDefault())
        val date = parser.parse(storageTime)
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        if (date != null) fmt.format(date) else storageTime
    } catch (e: Exception) {
        storageTime
    }
}
