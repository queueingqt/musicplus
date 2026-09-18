package com.musicplus.app.data

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * File-based crash/error log, independent of Logcat — once a device is out in
 * the field there's no adb session to read Logcat from, only whatever this app
 * wrote to its own storage, pullable later via `adb run-as` (same mechanism used
 * for the package-rename data migration). Gated by
 * AppSettingsRepository.debugLoggingEnabled for routine [e]/[d] calls, but a
 * crash is always captured regardless of that toggle — a disabled toggle means
 * "don't clutter the log with routine errors," not "don't capture the one thing
 * this whole feature exists for."
 *
 * [init] must run once, as early as possible in the process — see
 * AppGraph.build(), the first thing any screen touches.
 */
object AppLogger {
    private const val MAX_LOG_BYTES = 1_000_000L
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = ReentrantLock()

    @Volatile private var enabled = true
    @Volatile private var logFile: File? = null
    @Volatile private var installed = false

    fun init(filesDir: File) {
        if (installed) return
        installed = true

        val dir = File(filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "app.log")

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeSync("FATAL", "Uncaught exception on ${thread.name}", throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }
        writeSync("INFO", "AppLogger installed", null)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun d(tag: String, message: String) {
        if (!enabled) return
        writeSync("DEBUG", "$tag: $message", null)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        writeSync("ERROR", "$tag: $message", throwable)
    }

    private fun writeSync(level: String, message: String, throwable: Throwable?) {
        val file = logFile ?: return
        lock.withLock {
            runCatching {
                rotateIfNeeded(file)
                file.appendText("${timestampFormat.format(Date())} $level $message\n")
                if (throwable != null) {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    file.appendText("$sw\n")
                }
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            val previous = File(file.parentFile, "app.log.1")
            previous.delete()
            file.renameTo(previous)
        }
    }
}
