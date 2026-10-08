package com.musicplus.app

/** How long ago [thenMs] was, as short as a status line wants it: "just now", "5m ago", "2h ago", "3d ago". */
fun agoText(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val minutes = ((nowMs - thenMs).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}
