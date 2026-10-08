package com.musicplus.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LazyCollapsibleSectionTest {
    // Unconfined runs the fetch inline, so each toggle has finished by the time it returns.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun aFetchThatThrowsStopsLoadingAndLetsTheNextExpandTryAgain() {
        var calls = 0
        val section = LazyCollapsibleSection(scope) {
            calls++
            if (calls == 1) error("server hiccup") else listOf("a", "b")
        }
        section.toggle() // expand: fails
        assertFalse(section.loading.value, "not stuck on Loading")
        assertEquals(emptyList(), section.items.value)

        section.toggle() // collapse
        section.toggle() // expand again
        assertEquals(listOf("a", "b"), section.items.value)
        assertEquals(2, calls)
    }

    @Test
    fun aSuccessfulFetchIsNotRepeatedOnLaterExpands() {
        var calls = 0
        val section = LazyCollapsibleSection(scope) { calls++; listOf("x") }
        section.toggle(); section.toggle(); section.toggle()
        assertEquals(1, calls)
    }
}
