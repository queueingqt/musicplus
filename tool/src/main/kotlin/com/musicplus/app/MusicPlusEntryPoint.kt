package com.musicplus.app

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * An entry point is optional (per the SDK reference notes) — only needed for work
 * outside any one screen. Music + doesn't need push notifications, so this mostly
 * exists so `sdk/client/.../LightSdkApplication` has something to call and so
 * there's an obvious home for that work later (e.g. registering server-side push
 * credentials for "download finished" notifications). It does turn on the app
 * switcher preview, below.
 */
@EntryPoint
object MusicPlusEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {
        // No-op for now.
    }

    // Shows a preview of the app in the app switcher; the SDK leaves this off, which
    // gives a blank card (issue #52). The SDK's own comment says to leave it off
    // unless building for other devices, and that LightOS may briefly show a stale
    // screenshot when it brings the app back to the front.
    // BEFORE SUBMITTING TO LIGHT'S STORE (issue #53): check whether Light accepts this,
    // and if it is a blocker, delete this override.
    override val enableRecentsScreenshots: Boolean
        get() = true
}
