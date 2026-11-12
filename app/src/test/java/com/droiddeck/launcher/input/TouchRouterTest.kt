package com.droiddeck.launcher.input

import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchRouterTest {
    @Test fun gesturesAndOffAreNotTouchpadRoutes() {
        for (mode in listOf(SessionPrefs.TOUCH_GESTURES, SessionPrefs.TOUCH_OFF)) {
            assertEquals(TouchRouter.Route.GESTURES.takeIf { mode == SessionPrefs.TOUCH_GESTURES } ?: TouchRouter.Route.OFF,
                TouchRouter.decide(TouchRouter.Snapshot(false, mode, false, false, false)))
            assertEquals(TouchRouter.Route.GESTURES.takeIf { mode == SessionPrefs.TOUCH_GESTURES } ?: TouchRouter.Route.OFF,
                TouchRouter.decide(TouchRouter.Snapshot(false, mode, false, true, true)))
        }
    }

    @Test fun autoUsesTouchpadOnlyWhenRouterSelectsTouchpad() {
        assertEquals(TouchRouter.Route.TOUCHPAD,
            TouchRouter.decide(TouchRouter.Snapshot(false, SessionPrefs.TOUCH_AUTO, false, false, false)))
        assertEquals(TouchRouter.Route.DIRECT,
            TouchRouter.decide(TouchRouter.Snapshot(false, SessionPrefs.TOUCH_AUTO, false, true, false)))
    }

    @Test fun fingersOnlyScrollOverridesEveryModeButOff() {
        for (mode in listOf(SessionPrefs.TOUCH_AUTO, SessionPrefs.TOUCH_PAD, SessionPrefs.TOUCH_DIRECT, SessionPrefs.TOUCH_GESTURES)) {
            assertEquals(mode, TouchRouter.Route.SCROLL,
                TouchRouter.decide(TouchRouter.Snapshot(false, mode, false, true, false, fingersScroll = true)))
        }
        assertEquals(TouchRouter.Route.OFF,
            TouchRouter.decide(TouchRouter.Snapshot(false, SessionPrefs.TOUCH_OFF, false, false, false, fingersScroll = true)))
        assertEquals(TouchRouter.Route.BLOCKED,
            TouchRouter.decide(TouchRouter.Snapshot(true, SessionPrefs.TOUCH_AUTO, false, false, false, fingersScroll = true)))
    }
}
