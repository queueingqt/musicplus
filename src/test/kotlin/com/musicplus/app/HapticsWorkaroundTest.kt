package com.musicplus.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * WORKAROUND(lightsdk-haptics) — delete with HapticsWorkaround.kt once the official
 * Light SDK support lands (issue #21).
 *
 * The property that matters: LightOS's answer wins whenever there is one, and "no answer"
 * never turns haptics off by itself.
 */
class HapticsWorkaroundTest {
    private val clock = TestTimeSource()

    /** A LightOS that answers from [answers], in order, and counts how often it was asked. */
    private class ScriptedLightOs(vararg answers: Boolean?) {
        private val queue = ArrayDeque(answers.toList())
        var asked = 0
            private set

        suspend fun answer(): Boolean? {
            asked++
            return queue.removeFirstOrNull()
        }
    }

    private fun haptics(os: ScriptedLightOs) =
        LightOsHaptics(fetch = { os.answer() }, timeSource = clock, retryAfterFailure = 30.seconds)

    @Test
    fun `is on before LightOS has ever answered`() {
        assertTrue(haptics(ScriptedLightOs()).enabled.value)
    }

    @Test
    fun `stays on when LightOS is unreachable`() = runBlocking {
        val h = haptics(ScriptedLightOs(null))
        h.refresh()
        assertTrue(h.enabled.value)
    }

    @Test
    fun `follows LightOS turning haptics off`() = runBlocking {
        val h = haptics(ScriptedLightOs(false))
        h.refresh()
        assertFalse(h.enabled.value)
    }

    @Test
    fun `follows LightOS turning haptics back on`() = runBlocking {
        val h = haptics(ScriptedLightOs(false, true))
        h.refresh()
        h.refresh()
        assertTrue(h.enabled.value)
    }

    @Test
    fun `an unreachable LightOS does not undo an earlier off`() = runBlocking {
        val h = haptics(ScriptedLightOs(false, null))
        h.refresh()
        h.refresh()
        assertFalse(h.enabled.value)
    }

    @Test
    fun `backs off after a failed lookup, then tries again`() = runBlocking {
        val os = ScriptedLightOs(null, null, true)
        val h = haptics(os)
        h.refresh()
        assertEquals(1, os.asked)

        clock += 29.seconds
        h.refresh()
        assertEquals(1, os.asked, "still inside the back-off, must not ask again")

        clock += 2.seconds
        h.refresh()
        assertEquals(2, os.asked, "back-off over, asks again (and fails again)")

        clock += 31.seconds
        h.refresh()
        assertEquals(3, os.asked)
        assertTrue(h.enabled.value)
    }

    @Test
    fun `a success is never held back, so a LightOS setting change is picked up`() = runBlocking {
        val os = ScriptedLightOs(true, false)
        val h = haptics(os)
        h.refresh()
        h.refresh()
        assertEquals(2, os.asked)
        assertFalse(h.enabled.value)
    }

    @Test
    fun `a success clears an earlier back-off`() = runBlocking {
        val os = ScriptedLightOs(null, true, false)
        val h = haptics(os)
        h.refresh()
        clock += 31.seconds
        h.refresh()
        h.refresh()
        assertEquals(3, os.asked, "no back-off left after the success")
        assertFalse(h.enabled.value)
    }

    @Test
    fun `never asks twice at once`() = runBlocking {
        var asked = 0
        val gate = CompletableDeferred<Boolean?>()
        val h = LightOsHaptics(fetch = { asked++; gate.await() }, timeSource = clock)

        // UNDISPATCHED: the first lookup must already be waiting on the gate before the second starts.
        val first = launch(start = CoroutineStart.UNDISPATCHED) { h.refresh() }
        h.refresh() // a second screen composing while the first lookup is still waiting
        assertEquals(1, asked)

        gate.complete(false)
        first.join()
        assertFalse(h.enabled.value)
    }
}
