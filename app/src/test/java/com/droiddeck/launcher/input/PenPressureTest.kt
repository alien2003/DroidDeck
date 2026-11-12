package com.droiddeck.launcher.input

import com.droiddeck.launcher.session.SessionPrefs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class PenPressureTest {
    private val soft = PenPressure.forPreset(SessionPrefs.PEN_PRESSURE_SOFT)
    private val firm = PenPressure.forPreset(SessionPrefs.PEN_PRESSURE_FIRM)
    private val softPoints = floatArrayOf(0f, 0.25f, 0.75f, 1f)
    private val firmPoints = floatArrayOf(0.25f, 0f, 1f, 0.75f)

    @Test fun normalIsTheIdentityAndBuildsNoTable() {
        assertSame(PenAxes.LINEAR, PenPressure.forPreset(SessionPrefs.PEN_PRESSURE_NORMAL))
        assertSame(PenAxes.LINEAR, PenPressure.forPreset("hard"))
        assertTrue(soft is PenPressure)
        assertTrue(firm is PenPressure)
    }

    @Test fun thePresetsAreKWinCurveStrings() {
        assertEquals("0,0.25;0.75,1;", PenPressure.PRESETS[SessionPrefs.PEN_PRESSURE_SOFT])
        assertEquals("0.25,0;1,0.75;", PenPressure.PRESETS[SessionPrefs.PEN_PRESSURE_FIRM])
        val kwinDefault = PenPressure.parse("0,0;1,1;")!!
        for (i in 0..1000) assertEquals(i / 1000f, kwinDefault.map(i / 1000f), 1e-6f)
    }

    @Test fun malformedCurveStringsAreRejected() {
        for (curve in listOf("", "0,0.25;", "0,0.25;0.75;", "0,0.25;0.75,1;0.5,0.5;", "a,0.25;0.75,1;", "0,1.5;0.75,1;",
                "-0.1,0;1,1;", "NaN,0;1,1;"))
            assertNull(curve, PenPressure.parse(curve))
    }

    @Test fun theEndsStayPutAndPressureOutsideTheRangeIsClampedFirst() {
        for (curve in listOf(soft, firm)) {
            assertEquals(0f, curve.map(0f), 0f)
            assertEquals(1f, curve.map(1f), 0f)
            assertEquals(0, PenAxes.pressure(0f, curve))
            assertEquals(65535, PenAxes.pressure(1f, curve))
            assertEquals(65535, PenAxes.pressure(1.3f, curve))
            assertEquals(0, PenAxes.pressure(-0.2f, curve))
            assertEquals(0, PenAxes.pressure(Float.NaN, curve))
        }
    }

    @Test fun morePressureNeverSendsLess() {
        for (curve in listOf(soft, firm)) {
            var last = 0
            for (k in 0..65535) {
                val value = PenAxes.pressure(k / 65535f, curve)
                assertTrue("$k", value >= last)
                last = value
            }
        }
    }

    @Test fun softRaisesAndFirmLowersMidStrokePressure() {
        val inputs = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
        assertArrayEquals(floatArrayOf(0.2309f, 0.4823f, 0.6838f, 0.8512f, 0.9740f), FloatArray(5) { soft.map(inputs[it]) }, 1e-4f)
        assertArrayEquals(floatArrayOf(0.0260f, 0.1488f, 0.3162f, 0.5177f, 0.7691f), FloatArray(5) { firm.map(inputs[it]) }, 1e-4f)
    }

    @Test fun firmIsSoftMirrored() {
        for (i in 0..100) assertEquals(i / 100f, firm.map(soft.map(i / 100f)), 1e-4f)
    }

    @Test fun theTableFollowsAnExactSolveIncludingTheSteepEnds() {
        for ((points, curve) in listOf(softPoints to soft, firmPoints to firm)) {
            for (k in 0..65535) {
                val x = k / 65535f
                assertEquals("$k", exact(x.toDouble(), points), curve.map(x).toDouble(), 4e-5)
            }
            for (k in 0..4095) {
                val x = k / 4095f
                val expected = (exact(x.toDouble(), points) * 65535).roundToInt()
                assertEquals("$k of 4095", expected.toDouble(), PenAxes.pressure(x, curve).toDouble(), 1.0)
            }
        }
    }

    private fun exact(x: Double, p: FloatArray): Double {
        fun bezier(t: Double, a: Float, b: Float) = 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * b + t * t * t
        var low = 0.0
        var high = 1.0
        repeat(60) {
            val mid = (low + high) / 2
            if (bezier(mid, p[0], p[2]) < x) low = mid else high = mid
        }
        return bezier((low + high) / 2, p[1], p[3])
    }
}
