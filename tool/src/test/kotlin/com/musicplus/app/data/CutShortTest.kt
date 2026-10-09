package com.musicplus.app.data

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CutShortTest {
    @Test
    fun aFileThatPlaysAFractionOfTheSongIsCutShort() {
        assertTrue(CutShort.isCutShort(actualMs = 30_000, expectedSec = 240))
    }

    @Test
    fun aWholeFileOrOneWithinTheEstimateSlackIsNot() {
        assertFalse(CutShort.isCutShort(240_000, 240))
        assertFalse(CutShort.isCutShort(225_000, 240), "an mp3 with no length header is estimated; 94 % is fine")
        assertFalse(CutShort.isCutShort(3_000, 6), "a few seconds short on a very short song is not worth throwing away")
    }

    @Test
    fun anUnreadableOrUnknownLengthIsNeverCalledCutShort() {
        assertFalse(CutShort.isCutShort(null, 240))
        assertFalse(CutShort.isCutShort(1_000, 0))
        assertFalse(CutShort.isCutShort(1_000, -1))
    }

    @Test
    fun checkThrowsOnlyForAClearlyShortFile() {
        val file = File("x")
        CutShort.check(file, null) { error("not asked when the song's length is unknown") }
        CutShort.check(file, 240) { 240_000 }
        val e = assertFailsWith<IOException> { CutShort.check(file, 240) { 10_000 } }
        assertTrue(e.message!!.startsWith("cut short"))
        assertEquals(Unit, CutShort.check(file, 240) { null })
    }
}
