package com.musicplus.app.data

import android.database.sqlite.SQLiteException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * Runs a Room query again when it fails the way a read can while a big write lands on the same table ("Couldn't read row
 * N, col 0 from CursorWindow"): the same query a moment later succeeds. Without this such a read takes the whole app down
 * (found on the phone: removing a server crashed the list that was reading 22,000 songs at the time). After a few tries
 * it gives up and lets the error through, so a real fault is still loud.
 */
internal fun <T> Flow<T>.retryOnTransientDbError(): Flow<T> = retryWhen { cause, attempt ->
    val transient = cause is SQLiteException || (cause is IllegalStateException && cause.message?.contains("CursorWindow") == true)
    if (transient && attempt < MAX_RETRIES) {
        AppLogger.e("Db", "a read failed while the table was being written, retrying (attempt ${attempt + 1})", cause)
        delay(RETRY_DELAY_MS * (attempt + 1))
        true
    } else {
        false
    }
}

private const val MAX_RETRIES = 5L
private const val RETRY_DELAY_MS = 300L
