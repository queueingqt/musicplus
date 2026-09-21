package com.musicplus.app

import java.util.Locale

/** A size for a status line: "312 KB", "210 MB", "1.2 GB". */
fun sizeText(bytes: Long): String = when {
    bytes < 1_000_000L -> "${(bytes / 1_000L).coerceAtLeast(0)} KB"
    bytes < 1_000_000_000L -> "${bytes / 1_000_000L} MB"
    else -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
}

/** "1 song", "12 songs". */
fun songsText(count: Int): String = if (count == 1) "1 song" else "$count songs"
