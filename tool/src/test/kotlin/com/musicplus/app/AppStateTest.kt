package com.musicplus.app

import com.musicplus.app.data.AppServerPrefs
import com.musicplus.app.data.AppState
import com.musicplus.app.data.AvailabilityState
import com.musicplus.app.data.LibraryState
import com.musicplus.app.data.ServerState
import com.musicplus.app.data.Settings
import com.musicplus.app.data.SettingsState
import com.musicplus.app.data.WarmedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What a screen reads is what it is handed: a test gives it a state of its own and nothing global is involved. */
class AppStateTest {
    @Test
    fun theAppsDefaultStateIsTheSharedOneTheDataLayerReadsByName() {
        val state = AppState()
        assertSame(AppServerPrefs, state.servers, "the same instances: screens and the data layer see one set of warm values")
        assertSame(Settings.showAlbumArtwork.warm, state.settings.showAlbumArtwork)
    }

    @Test
    fun aStateBuiltForATestIsIndependentOfTheAppsOwn() {
        val before = AppServerPrefs.isConfigured.value.value
        val mine = AppState(servers = ServerState(), library = LibraryState(), availability = AvailabilityState(), settings = SettingsState(showAlbumArtwork = WarmedFlow(false)))
        mine.servers.isConfigured.set(true)
        mine.settings.showAlbumArtwork.set(true)
        assertTrue(mine.servers.isConfigured.value.value)
        assertEquals(before, AppServerPrefs.isConfigured.value.value, "the app's own flag is untouched")
        assertEquals(true, Settings.showAlbumArtwork.warm.value.value, "and so is the real default")
    }

    @Test
    fun theHomeScreenReadsItsConfiguredFlagFromTheStateItIsGiven() {
        val servers = ServerState()
        val viewModel = HomeScreenViewModel(AppState(servers = servers))
        assertFalse(viewModel.isConfigured.value, "seeded from the given state's default")
        servers.isConfigured.set(true)
        assertTrue(viewModel.isConfigured.value, "and following it, not the app's own")
    }
}
