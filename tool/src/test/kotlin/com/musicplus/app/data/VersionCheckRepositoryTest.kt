package com.musicplus.app.data

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionCheckRepositoryTest {
    @Test
    fun aCheckThatCouldNotAskGitHubSaysSoAndFindsNothing() = runBlocking {
        val asked = VersionCheckRepository.checkForUpdate("0.7.2", fetchLatest = { throw IOException("offline") })
        assertFalse(asked, "so the caller does not hold the next check off for a day")
        assertNull(VersionCheckRepository.newerVersion.value)
    }

    @Test
    fun aCheckThatAnsweredSaysSoAndReportsANewerVersion() = runBlocking {
        val asked = VersionCheckRepository.checkForUpdate("0.7.2", fetchLatest = { GithubRelease("v0.8.0", "https://example/r/0.8.0") })
        assertTrue(asked)
        assertEquals("0.8.0", VersionCheckRepository.newerVersion.value?.versionName)
    }
}
