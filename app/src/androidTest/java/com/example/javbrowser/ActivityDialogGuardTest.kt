package com.example.javbrowser

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Detached checks do not launch screens or access websites. */
@RunWith(AndroidJUnit4::class)
class ActivityDialogGuardTest {
    @Test fun lateCallbackRechecksHostAndTokenRaceDoesNotEscape() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var active = true
            var shown = 0
            val callback = { ActivityDialogGuard.showWindow({ active }) { shown++ } }
            assertTrue(callback())
            assertEquals(1, shown)
            active = false
            assertFalse(callback())
            assertEquals(1, shown)
            assertFalse(ActivityDialogGuard.showWindow({ true }) {
                throw WindowManager.BadTokenException("Synthetic token race")
            })
            assertFalse(ActivityDialogGuard.showWindow({ true }) {
                throw WindowManager.InvalidDisplayException("Synthetic removed display")
            })
        }
    }
    @Test fun rejectsOwnerBeforeActivityCreationAndOffMainThread() {
        lateinit var unattached: androidx.activity.ComponentActivity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            unattached = androidx.activity.ComponentActivity()
            assertFalse(ActivityDialogGuard.canShow(unattached))
        }
        assertFalse(ActivityDialogGuard.canShow(unattached))
    }
}
