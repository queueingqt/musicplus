package com.musicplus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings declared once, against a real DataStore in a temp file: the default, a stored value, "original", and the warm copy. */
class SettingsTest {
    private val root: File = Files.createTempDirectory("settings-test").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { File(root, "prefs.preferences_pb") }

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun anUnsetSettingIsItsDefaultDeclaredOnce() = runBlocking {
        assertEquals(true, Settings.showAlbumArtwork.observe(store).first())
        assertEquals(false, Settings.downloadedOnly.observe(store).first())
        assertEquals(320, Settings.streamQualityWifi.observe(store).first())
        assertEquals(192, Settings.streamQualityCellular.observe(store).first())
        assertNull(Settings.downloadQuality.observe(store).first())
        // The warm copy starts at the same default: there is no second place to keep in step.
        assertEquals(Settings.streamQualityWifi.default, Settings.streamQualityWifi.warm.value.value)
        assertEquals(Settings.showAlbumArtwork.default, Settings.showAlbumArtwork.warm.value.value)
    }

    @Test
    fun aStoredValueReplacesTheDefault() = runBlocking {
        Settings.showAlbumArtwork.set(store, false)
        Settings.streamQualityWifi.set(store, 128)
        assertFalse(Settings.showAlbumArtwork.observe(store).first())
        assertEquals(128, Settings.streamQualityWifi.observe(store).first())
    }

    @Test
    fun originalQualityIsNullAndSurvivesBeingStoredEvenWhereTheDefaultIsACap() = runBlocking {
        Settings.streamQualityWifi.set(store, null)
        assertNull(Settings.streamQualityWifi.observe(store).first(), "stored as 'original', not mistaken for 'unset' and given the 320 default")
        Settings.streamQualityWifi.set(store, 96)
        assertEquals(96, Settings.streamQualityWifi.observe(store).first())
    }

    @Test
    fun theWarmCopyFollowsWhatIsStored() = runBlocking {
        // A setting of the test's own, so nothing global is changed for the tests that run after this one.
        val mine = Setting.flag("test_flag", default = false)
        mine.mirror(store, scope)
        mine.set(store, true)
        withTimeout(15_000) { while (!mine.warm.value.value) delay(5) }
        assertTrue(mine.warm.loaded.value, "marked loaded once the real value arrived")
    }

    @Test
    fun everySettingIsInTheListThatIsMirrored() {
        assertEquals(6, Settings.all.size)
    }
}
