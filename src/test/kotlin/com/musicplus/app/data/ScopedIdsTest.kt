package com.musicplus.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScopedIdsTest {
    private val ids = ScopedIds("srv-1", "Test")

    @Test
    fun aServersOwnIdIsScopedToIt() {
        assertEquals("srv-1:abc", ids.scope("abc"))
    }

    @Test
    fun aScopedIdOfThisServerGoesBackToTheServersOwn() {
        assertEquals("abc", ids.native("srv-1:abc"))
        assertEquals("a:b:c", ids.native("srv-1:a:b:c"), "only the first separator ends the server id")
    }

    @Test
    fun anIdOfAnotherServerIsAnErrorRatherThanAQuestionAboutSomeoneElsesItem() {
        assertFailsWith<IllegalArgumentException> { ids.native("srv-2:abc") }
    }

    @Test
    fun anIdThatWasNeverScopedIsPassedThroughAsItIs() {
        assertEquals("abc", ids.native("abc"))
    }

    @Test
    fun scopingAndUnscopingRoundTrip() {
        assertEquals("xyz", ids.native(ids.scope("xyz")))
    }
}
