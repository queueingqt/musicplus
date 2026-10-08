package com.musicplus.app.data

import android.os.Build
import com.musicplus.app.BuildConfig
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Auto-reports uncaught exceptions to GitHub Issues on this repo, gated by
 * [AppSettingsRepository.debugLoggingEnabled] — the same toggle already
 * driving [AppLogger] (issue #41: "can Debug Logging automatically report
 * crashes"). Installs its own uncaught-exception handler chained after
 * [AppLogger]'s (see [init]'s call site in AppGraph.build), so a crash both
 * gets written to the local log AND, if enabled, reported here.
 *
 * The reporting call is deliberately synchronous, blocking, plain
 * [HttpURLConnection] — not Ktor/coroutines. This runs from inside an
 * uncaught-exception handler, where the process may be torn down at any
 * moment; a suspend call launched into a coroutine scope has no guarantee of
 * completing before that happens. Short timeouts below so a slow/dead network
 * doesn't hang app termination.
 *
 * Uses a fine-grained GitHub PAT scoped to Issues:write on this one repo only
 * (see [BuildConfig.GITHUB_CRASH_TOKEN]'s doc in build.gradle.kts for why
 * that's an accepted tradeoff rather than a server-side relay) — no-ops
 * entirely if that token is blank, so a build without it configured is silent
 * rather than broken.
 *
 * Never touches [ServerConfigRepository] — the payload is exception
 * type/message/stack trace, app version, OS version, device model, and (since
 * this toggle also gates [AppLogger]) a tail of the local debug log for
 * context. A failed report is logged via [AppLogger] itself rather than
 * swallowed silently — this toggle being on means logging is on, so a report
 * failure is exactly the kind of thing that should show up there.
 */
object CrashReporter {
    private const val REPO_OWNER = "queueingqt"
    private const val REPO_NAME = "musicplus"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 4000
    private const val MAX_REPORTED_SIGNATURES = 200
    private const val MAX_LOG_LINES_ATTACHED = 150

    private val lock = ReentrantLock()

    @Volatile private var enabled = false
    @Volatile private var installed = false
    @Volatile private var reportedSignaturesFile: File? = null
    @Volatile private var localLogFile: File? = null

    fun init(filesDir: File) {
        if (installed) return
        installed = true

        reportedSignaturesFile = File(filesDir, "logs/reported_crash_signatures.txt")
        // Same path AppLogger.kt itself writes to — deliberately not read
        // through AppLogger (which exposes no read API), just the same
        // filesDir/logs/app.log convention both objects already share.
        localLogFile = File(filesDir, "logs/app.log")

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { reportIfNeeded(throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    private fun reportIfNeeded(throwable: Throwable) {
        if (!enabled) return
        if (BuildConfig.GITHUB_CRASH_TOKEN.isBlank()) return

        val signature = signatureFor(throwable)
        if (alreadyReported(signature)) return

        // Log our own failures via AppLogger rather than swallowing them —
        // debug logging is on whenever this is (same toggle), so a failed
        // report should be visible the same way any other error is.
        runCatching { createIssue(throwable, signature) }
            .onSuccess { markReported(signature) }
            .onFailure { e -> AppLogger.e("CrashReporter", "Failed to report crash $signature", e) }
    }

    /** Exception class + top app-frame (class, method, line) — stable across repeat launches of the same bug, distinct across different bugs. */
    private fun signatureFor(throwable: Throwable): String {
        val topFrame = throwable.stackTrace.firstOrNull { it.className.startsWith("com.musicplus.app") }
            ?: throwable.stackTrace.firstOrNull()
        val raw = "${exceptionTypeName(throwable)}@${topFrame?.className}.${topFrame?.methodName}:${topFrame?.lineNumber}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    // The SDK build plugin blocks anything touching java.lang.Class (flagged as
    // "reflection"), so `.javaClass`/`::class` are both off the table here —
    // Throwable's own default toString() is "<fully-qualified type>: <message>",
    // which gives the same info without calling a Class API directly.
    private fun exceptionTypeName(throwable: Throwable): String =
        throwable.toString().substringBefore(":").substringAfterLast(".")

    private fun alreadyReported(signature: String): Boolean {
        val file = reportedSignaturesFile ?: return false
        return lock.withLock {
            runCatching { file.readLines() }.getOrDefault(emptyList()).contains(signature)
        }
    }

    private fun markReported(signature: String) {
        val file = reportedSignaturesFile ?: return
        lock.withLock {
            runCatching {
                val existing = runCatching { file.readLines() }.getOrDefault(emptyList())
                val updated = (existing + signature).takeLast(MAX_REPORTED_SIGNATURES)
                file.parentFile?.mkdirs()
                file.writeText(updated.joinToString("\n"))
            }
        }
    }

    // Recent debug-log context, not just the stack trace — the toggle that
    // gates crash reporting also gates AppLogger, so if we got this far
    // there's real log content to attach. Best-effort: a missing/unreadable
    // log file just means this section is omitted, never a failed report.
    private fun recentLogTail(): String? {
        val file = localLogFile ?: return null
        return runCatching { file.readLines().takeLast(MAX_LOG_LINES_ATTACHED) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
    }

    private fun createIssue(throwable: Throwable, signature: String) {
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val title = "Auto-reported crash: ${exceptionTypeName(throwable)} ($signature)"
        val logTail = recentLogTail()
        val body = buildString {
            appendLine("Automatically reported by CrashReporter.kt — a real user hit this.")
            appendLine()
            appendLine("**App version:** ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("**Android version:** ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("**Device:** ${Build.MODEL}")
            appendLine()
            appendLine("```")
            append(stackTrace)
            appendLine("```")
            if (logTail != null) {
                appendLine()
                appendLine("<details><summary>Recent log (last $MAX_LOG_LINES_ATTACHED lines)</summary>")
                appendLine()
                appendLine("```")
                appendLine(logTail)
                appendLine("```")
                appendLine("</details>")
            }
        }

        val payload = Json.encodeToString(GithubIssuePayload(title = title, body = body, labels = listOf("auto-crash-report")))

        val url = URL("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/issues")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${BuildConfig.GITHUB_CRASH_TOKEN}")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                error("GitHub API returned $responseCode: $errorBody")
            }
        } finally {
            connection.disconnect()
        }
    }
}

@Serializable
private data class GithubIssuePayload(val title: String, val body: String, val labels: List<String>)
