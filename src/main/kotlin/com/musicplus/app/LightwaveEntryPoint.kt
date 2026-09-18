package com.musicplus.app

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * An entry point is optional (per the SDK reference notes) — only needed for work
 * outside any one screen. Lightwave doesn't need push notifications or recents
 * screenshots, so this is a placeholder that exists mainly so
 * `sdk/client/.../LightSdkApplication` has something to call and so there's an
 * obvious home for that work later (e.g. registering server-side push credentials
 * for "download finished" notifications).
 */
@EntryPoint
object LightwaveEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {
        // No-op for now.
    }
}
