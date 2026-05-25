package com.example.safe_kids.ui.parent

fun formatTimeAmPm(hour: Int, minute: Int): String {
    val amPm = if (hour < 12) "AM" else "PM"
    val h = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return String.format("%d:%02d %s", h, minute, amPm)
}

fun formatTimeRangeAmPm(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): String {
    return "${formatTimeAmPm(startHour, startMinute)} - ${formatTimeAmPm(endHour, endMinute)}"
}
