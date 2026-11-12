package com.droiddeck.launcher.input

import com.droiddeck.launcher.input.PenCursorPolicy.Overlay
import org.junit.Assert.*
import org.junit.Test

class PenCursorPolicyTest {
    private val shownByMouse = Overlay(visible = true, mouseOwns = true)
    private val shownByTouchpad = Overlay(visible = true, mouseOwns = false)
    private val hidden = Overlay(visible = false, mouseOwns = false)

    @Test fun theTabletRouteNeedsTheTabletWishAndACompositorFocusThatTakesTheTablet() {
        assertTrue(PenCursorPolicy.tablet(true, PenCursorPolicy.ROUTE_TABLET))
        assertFalse(PenCursorPolicy.tablet(false, PenCursorPolicy.ROUTE_TABLET))
        assertFalse(PenCursorPolicy.tablet(true, PenCursorPolicy.ROUTE_POINTER))
        assertFalse(PenCursorPolicy.tablet(true, 0))
    }

    @Test fun thePointerRouteFollowsThePenAndRestoresNothing() {
        for (before in listOf(shownByMouse, shownByTouchpad, hidden)) {
            val policy = PenCursorPolicy()
            assertTrue(policy.followsPen(false, before))
            assertTrue(policy.followsPen(false, shownByMouse))
            assertNull(policy.restoreOnLeave())
        }
    }

    @Test fun theTabletRouteHidesWhileNearAndRestoresTheStateFromBeforeTheProximity() {
        for (before in listOf(shownByMouse, shownByTouchpad, hidden)) {
            val policy = PenCursorPolicy()
            assertFalse(policy.followsPen(true, before))
            assertFalse(policy.followsPen(true, hidden))
            assertEquals(before, policy.restoreOnLeave())
        }
    }

    @Test fun aPenThatEndsOnThePointerRouteLeavesTheCursorWhereThePenPutIt() {
        val policy = PenCursorPolicy()
        assertFalse(policy.followsPen(true, shownByMouse))
        assertTrue(policy.followsPen(false, hidden))
        assertNull(policy.restoreOnLeave())
    }

    @Test fun aPenThatEndsOnTheTabletRouteRestoresTheStateFromBeforeItCameNear() {
        val policy = PenCursorPolicy()
        assertTrue(policy.followsPen(false, shownByTouchpad))
        assertFalse(policy.followsPen(true, shownByMouse))
        assertEquals(shownByTouchpad, policy.restoreOnLeave())
    }

    @Test fun eachProximityRemembersItsOwnStateAndALeaveWithoutSamplesRestoresNothing() {
        val policy = PenCursorPolicy()
        assertNull(policy.restoreOnLeave())
        policy.followsPen(true, shownByMouse)
        assertEquals(shownByMouse, policy.restoreOnLeave())
        assertNull(policy.restoreOnLeave())
        policy.followsPen(true, hidden)
        assertEquals(hidden, policy.restoreOnLeave())
    }

    @Test fun otherInputWhileATabletPenIsNearReplacesTheStateToRestore() {
        val policy = PenCursorPolicy()
        policy.changed(shownByMouse)
        policy.mouseRemoved()
        assertNull(policy.restoreOnLeave())
        policy.followsPen(true, shownByMouse)
        policy.mouseRemoved()
        assertEquals(hidden, policy.restoreOnLeave())
        policy.followsPen(true, shownByTouchpad)
        policy.mouseRemoved()
        assertEquals(shownByTouchpad, policy.restoreOnLeave())
        policy.followsPen(true, hidden)
        policy.changed(shownByMouse)
        policy.followsPen(true, hidden)
        assertEquals(shownByMouse, policy.restoreOnLeave())
        policy.followsPen(false, hidden)
        policy.changed(shownByMouse)
        assertNull(policy.restoreOnLeave())
    }
}
