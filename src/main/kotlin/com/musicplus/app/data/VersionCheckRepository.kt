package com.musicplus.app.data

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** The two fields of GitHub's `/releases/latest` response this actually uses. */
@Serializable
data class GithubRelease(
    @Suppress("PropertyName") val tag_name: String,
    @Suppress("PropertyName") val html_url: String,
)

data class NewerVersion(val versionName: String, val releaseUrl: String)

/**
 * Checks GitHub Releases for a version newer than what's installed — a public,
 * unauthenticated API call (this repo is public; no token needed, unlike
 * CrashReporter's write-scoped one), once per process start.
 *
 * LightOS tools can't open a browser or trigger a package install from code —
 * the SDK build plugin (LightSdkPlugin.BLOCKED_CODE_PATTERNS) rejects any
 * source touching `LocalContext` or `startActivity(` at compile time, and
 * `REQUEST_INSTALL_PACKAGES` isn't in LightToolPolicy.ALLOWED_PERMISSIONS —
 * confirmed by reading both directly, not assumed. So this only surfaces the
 * version number and release URL as plain (selectable, but not tappable) text
 * via VersionAvailableScreen, rather than a link or an in-app updater.
 */
object VersionCheckRepository {
    private const val REPO_OWNER = "queueingqt"
    private const val REPO_NAME = "musicplus"

    private val _newerVersion = MutableStateFlow<NewerVersion?>(null)
    val newerVersion: StateFlow<NewerVersion?> = _newerVersion.asStateFlow()

    suspend fun checkForUpdate(currentVersionName: String) {
        val release = runCatching {
            newJsonHttpClient().use { client ->
                client.get("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest") {
                    header("Accept", "application/vnd.github+json")
                }.body<GithubRelease>()
            }
        }.getOrNull() ?: return

        val latestVersionName = release.tag_name.removePrefix("v")
        if (isNewer(latestVersionName, currentVersionName)) {
            _newerVersion.value = NewerVersion(versionName = latestVersionName, releaseUrl = release.html_url)
        }
    }

    /** Strict major.minor.patch comparison, matching lighttool.toml's versionName format — not a generic semver parser. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val c = candidate.split(".").mapNotNull { it.toIntOrNull() }
        val cur = current.split(".").mapNotNull { it.toIntOrNull() }
        if (c.size != 3 || cur.size != 3) return false
        for (i in 0..2) {
            if (c[i] != cur[i]) return c[i] > cur[i]
        }
        return false
    }
}
