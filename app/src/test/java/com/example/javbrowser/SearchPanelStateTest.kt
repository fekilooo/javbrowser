package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class SearchPanelStateTest {
    @Test fun pendingEmptyAndFailedSearchesStayExpanded() {
        assertFalse(SearchPanelState().onResults(false).collapsed)
    }
    @Test fun firstResultsCollapseWithoutWaitingForEverySite() {
        assertEquals(SearchPanelState(true, true), SearchPanelState().onResults(true))
    }
    @Test fun manualExpansionSurvivesSubsequentResultsAndRetries() {
        val expanded = SearchPanelState().onResults(true).toggle()
        assertFalse(expanded.onResults(true).collapsed)
        assertFalse(expanded.onResults(false).onResults(true).collapsed)
    }
    @Test fun manualChoiceBeforeResultsIsAlsoRespected() {
        assertFalse(SearchPanelState().toggle().toggle().onResults(true).collapsed)
    }
    @Test fun restoredStateDoesNotCollapseAgain() {
        val saved = SearchPanelState().onResults(true).toggle()
        val restored = SearchPanelState(saved.collapsed, saved.automaticCollapseHandled)
        assertEquals(saved, restored.onResults(true))
    }
    @Test fun newQueryResetsAutomaticCollapse() {
        val previous = SearchPanelState().onResults(true).toggle()
        assertTrue(previous.automaticCollapseHandled)
        val next = SearchPanelState()
        assertFalse(next.collapsed)
        assertTrue(next.onResults(true).collapsed)
    }
}
