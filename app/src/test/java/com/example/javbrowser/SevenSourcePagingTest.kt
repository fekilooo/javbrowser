package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class SevenSourcePagingTest {
    @Test fun prefetchesNearBottomAndAllowsOnlyOneInFlightRequest() {
        val state = SevenSourcePaging()
        assertEquals(1, state.begin(true))
        assertNull(state.begin())
        state.complete(24, true)
        assertFalse(state.shouldLoad(8, 24))
        assertTrue(state.shouldLoad(17, 24))
        assertEquals(2, state.begin())
        assertFalse(state.shouldLoad(23, 24))
        assertNull(state.begin())
    }
    @Test fun failureWaitsForRetryAndRetriesTheSamePage() {
        val state = SevenSourcePaging()
        state.begin(true); state.complete(24, true)
        assertEquals(2, state.begin())
        state.fail()
        assertFalse(state.shouldLoad(23, 24))
        assertEquals(1, state.page)
        assertEquals(2, state.begin())
        state.complete(24, true)
        assertEquals(2, state.page)
    }
    @Test fun stopsOnEmptyOrRepeatedPageEvenWhenSourceClaimsMore() {
        val state = SevenSourcePaging()
        state.begin(true); state.complete(24, true)
        state.begin(); state.complete(0, true)
        assertFalse(state.hasMore)
        assertNull(state.begin())
        assertFalse(state.shouldLoad(23, 24))
    }
    @Test fun changingCategoryRestartsAtPageOneAndCancelKeepsLoadedPages() {
        val state = SevenSourcePaging()
        state.begin(true); state.complete(24, true)
        state.begin(); state.cancel()
        assertEquals(1, state.page)
        assertEquals(2, state.begin())
        assertEquals(1, state.begin(true))
        state.complete(5, false)
        assertEquals(1, state.page)
        assertFalse(state.hasMore)
    }
}
