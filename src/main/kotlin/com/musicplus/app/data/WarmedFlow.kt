package com.musicplus.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One process-lifetime `StateFlow`, warmed at app start by mirroring a
 * DataStore-backed `Flow` into it (see `AppGraph.build()`'s `mirrorInto`).
 * Exists because a fresh ViewModel's `.stateIn(...)` has to seed from
 * *something* before the real DataStore `Flow`'s first emission arrives;
 * reading that raw `Flow` directly instead seeds every fresh screen open at
 * a hardcoded default, then visibly snaps to the real value a frame later —
 * reported live more than once this session (Settings "glitching" on open,
 * the "Server" row flashing "No servers yet", album art flashing before
 * disappearing). Replaces five near-identical hand-rolled
 * `MutableStateFlow` + `asStateFlow()` + setter objects (the `App*Prefs`
 * family) that each existed only to fix this same gap.
 */
class WarmedFlow<T>(initial: T) {
    private val _value = MutableStateFlow(initial)
    val value: StateFlow<T> = _value.asStateFlow()
    fun set(newValue: T) {
        _value.value = newValue
    }
}
