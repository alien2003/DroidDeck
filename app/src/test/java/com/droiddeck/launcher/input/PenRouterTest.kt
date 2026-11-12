package com.droiddeck.launcher.input

import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import org.junit.Assert.*
import org.junit.Test

class PenRouterTest {
    private val modes = listOf(SessionPrefs.PEN_AUTO, SessionPrefs.PEN_MOUSE, SessionPrefs.PEN_TOUCH)
    private val sessions = listOf(SessionService.MODE_DESKTOP, SessionService.MODE_STEAM, SessionService.MODE_RUN)
    private val flags = listOf(false, true)

    private fun every(check: (PenRouter.Snapshot) -> Unit) {
        for (mode in modes) for (session in sessions) for (steamDesktopUi in flags) for (glasses in flags) for (lock in flags)
            check(PenRouter.Snapshot(false, mode, session, steamDesktopUi, glasses, lock))
    }

    @Test fun appUiBlocksThePenInEveryModeSessionAndScreen() = every { s ->
        val blocked = s.copy(uiBlocking = true)
        assertEquals("$s", PenRouter.Route.BLOCKED, PenRouter.decide(blocked))
        assertFalse("$s", PenRouter.wantsTablet(blocked))
    }

    @Test fun touchModeKeepsThePenAFingerEverywhere() = every { s ->
        if (s.mode != SessionPrefs.PEN_TOUCH) return@every
        assertEquals("$s", PenRouter.Route.TOUCH, PenRouter.decide(s))
        assertFalse("$s", PenRouter.wantsTablet(s))
    }

    @Test fun glassesAndProgramLocksMakeMouseAndAutoRelative() = every { s ->
        if (s.mode == SessionPrefs.PEN_TOUCH || !(s.glasses || s.programLock)) return@every
        assertEquals("$s", PenRouter.Route.RELATIVE, PenRouter.decide(s))
        assertFalse("$s", PenRouter.wantsTablet(s))
    }

    @Test fun mouseAndAutoDriveThePenRouteOnThePhone() = every { s ->
        if (s.mode == SessionPrefs.PEN_TOUCH || s.glasses || s.programLock) return@every
        assertEquals("$s", PenRouter.Route.PEN, PenRouter.decide(s))
    }

    @Test fun onlyAutoInTheDesktopAsksForTheTablet() = every { s ->
        val expected = s.mode == SessionPrefs.PEN_AUTO && s.session == SessionService.MODE_DESKTOP && !s.glasses && !s.programLock
        assertEquals("$s", expected, PenRouter.wantsTablet(s))
    }

    @Test fun autoInSteamIsAMouseEvenInItsDesktopUi() {
        for (desktopUi in flags) {
            val s = PenRouter.Snapshot(false, SessionPrefs.PEN_AUTO, SessionService.MODE_STEAM, desktopUi, false, false)
            assertEquals(PenRouter.Route.PEN, PenRouter.decide(s))
            assertFalse(PenRouter.wantsTablet(s))
        }
    }

    @Test fun theFieldOverloadsAgreeWithTheSnapshot() = every { open ->
        for (s in listOf(open, open.copy(uiBlocking = true))) {
            val route = PenRouter.decide(s.uiBlocking, s.mode, s.glasses, s.programLock)
            assertEquals("$s", PenRouter.decide(s), route)
            assertEquals("$s", PenRouter.wantsTablet(s), PenRouter.wantsTablet(route, s.mode, s.session))
        }
    }

    @Test fun anUnknownModeIsAMouse() {
        val s = PenRouter.Snapshot(false, "tablet", SessionService.MODE_DESKTOP, false, false, false)
        assertEquals(PenRouter.Route.PEN, PenRouter.decide(s))
        assertFalse(PenRouter.wantsTablet(s))
    }
}
