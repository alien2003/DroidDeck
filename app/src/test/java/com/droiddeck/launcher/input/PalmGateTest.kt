package com.droiddeck.launcher.input

import com.droiddeck.launcher.input.PalmGate.Pen.CONTACT
import com.droiddeck.launcher.input.PalmGate.Pen.HOVER
import org.junit.Assert.*
import org.junit.Test

class PalmGateTest {
    private val logs = mutableListOf<String>()
    private val engages = mutableListOf<Long>()
    private var fingers = 0
    private val gate = PalmGate { logs += it }.apply { register { time -> engages += time; fingers } }

    @Test fun aFingerIsAcceptedWhenNoPenWasSeen() {
        assertFalse(gate.armed(1_000))
        assertTrue(engages.isEmpty())
    }

    @Test fun aFingerIsRejectedWhileThePenHovers() {
        gate.pen(1_000, HOVER)
        assertTrue(gate.armed(1_100))
        gate.pen(2_500, HOVER)
        assertTrue(gate.armed(4_000))
        assertEquals(listOf(1_000L), engages)
    }

    @Test fun aFingerIsRejectedWithinTheGraceAfterHoverExitAndAcceptedAfterIt() {
        gate.pen(1_000, HOVER)
        gate.leave(1_100)
        assertTrue(gate.armed(1_100 + PalmGate.GRACE_MS - 1))
        assertFalse(gate.armed(1_100 + PalmGate.GRACE_MS))
    }

    @Test fun aContactOnlyPenKeepsTheGateArmedBetweenStrokes() {
        gate.pen(1_000, CONTACT)
        gate.leave(1_100)
        assertTrue(gate.armed(1_400))
        gate.pen(1_400, CONTACT)
        gate.leave(1_500)
        assertTrue(gate.armed(1_800))
        assertFalse(gate.armed(1_950))
        assertEquals(listOf(1_000L), engages)
    }

    @Test fun hoverExitThenDownDoesNotEngageAgain() {
        gate.pen(1_000, HOVER)
        gate.leave(1_050)
        gate.pen(1_050, CONTACT)
        gate.pen(1_060, CONTACT)
        assertEquals(listOf(1_000L), engages)
    }

    @Test fun penArrivalCancelsOnlyOnTheFirstEngageAndLogsWhatItCancelled() {
        fingers = 2
        gate.pen(1_000, HOVER)
        gate.pen(1_010, HOVER)
        gate.button(1_020)
        assertEquals(listOf(1_000L), engages)
        assertEquals(listOf("palm: pen near, cancelled 2 finger(s)"), logs)
        fingers = 0
        gate.leave(1_100)
        gate.pen(2_000, HOVER)
        assertEquals(listOf(1_000L, 2_000L), engages)
        assertEquals(1, logs.size)
    }

    @Test fun aSilentHoverExpiresAfterTheWatchdog() {
        gate.pen(1_000, HOVER)
        assertTrue(gate.armed(1_000 + PalmGate.HOVER_WATCHDOG_MS - 1))
        assertFalse(gate.armed(1_000 + PalmGate.HOVER_WATCHDOG_MS))
        assertEquals(listOf("palm: hover watchdog"), logs)
        assertEquals(PenStream.HOVER_TIMEOUT_MS, PalmGate.HOVER_WATCHDOG_MS)
    }

    @Test fun anExplicitHoverExitAfterASilentHoverStillStartsTheGrace() {
        gate.pen(1_000, HOVER)
        assertFalse(gate.armed(3_200))
        gate.leave(3_500)
        assertTrue(gate.exitedAt(3_500))
        assertTrue(gate.armed(3_500))
        assertFalse(gate.armed(3_900))
    }

    @Test fun aForcedOutStaleHoverIsBackdatedAndAFreshOneGetsTheGrace() {
        gate.pen(1_000, HOVER)
        gate.forceOut(3_500)
        assertFalse(gate.armed(3_500))
        gate.pen(5_000, HOVER)
        gate.forceOut(5_100)
        assertTrue(gate.armed(5_499))
        assertFalse(gate.armed(5_500))
    }

    @Test fun aHeldContactNeverExpiresAndAButtonKeepsIt() {
        gate.pen(1_000, CONTACT)
        gate.button(1_100)
        assertTrue(gate.armed(60_000))
    }

    @Test fun aStaleHoverThatMovesAgainCancelsFingersAcceptedMeanwhile() {
        gate.pen(1_000, HOVER)
        assertFalse(gate.armed(3_500))
        gate.pen(3_600, HOVER)
        assertEquals(listOf(1_000L, 3_600L), engages)
    }

    @Test fun removingThePenDeviceForcesItOut() {
        gate.pen(1_000, HOVER, deviceId = 5)
        gate.deviceRemoved(6, 1_100)
        assertTrue(gate.armed(2_000))
        gate.deviceRemoved(5, 2_100)
        assertTrue(gate.armed(2_499))
        assertFalse(gate.armed(2_500))
    }

    @Test fun disabledGateAcceptsEverythingButKeepsTrackOfThePen() {
        gate.enabled = false
        gate.pen(1_000, HOVER)
        assertFalse(gate.armed(1_100))
        assertTrue(engages.isEmpty())
        gate.enabled = true
        assertTrue(gate.armed(1_200))
    }
}
