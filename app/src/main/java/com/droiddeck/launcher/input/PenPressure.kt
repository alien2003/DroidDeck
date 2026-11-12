package com.droiddeck.launcher.input

import com.droiddeck.launcher.session.SessionPrefs
import java.util.Arrays

/**
 * KWin's pressure curve: a cubic Bézier from (0,0) through "x1,y1;x2,y2;" to (1,1), read as y of x. The table
 * samples the curve at even steps of its parameter, not of x, because Soft and Firm are as steep as a square root
 * at one end.
 */
class PenPressure private constructor(x1: Float, y1: Float, x2: Float, y2: Float) : PenAxes.PressureCurve {
    private val xs = FloatArray(STEPS + 1)
    private val ys = FloatArray(STEPS + 1)

    init {
        for (i in 1 until STEPS) {
            val t = i.toDouble() / STEPS
            xs[i] = bezier(t, x1, x2)
            ys[i] = bezier(t, y1, y2)
        }
        xs[STEPS] = 1f
        ys[STEPS] = 1f
    }

    override fun map(pressure: Float): Float {
        if (!(pressure > 0f)) return 0f
        if (pressure >= 1f) return 1f
        val found = Arrays.binarySearch(xs, pressure)
        if (found >= 0) return ys[found]
        val i = -found - 1
        return ys[i - 1] + (ys[i] - ys[i - 1]) * (pressure - xs[i - 1]) / (xs[i] - xs[i - 1])
    }

    companion object {
        const val STEPS = 1024

        val PRESETS = mapOf(
            SessionPrefs.PEN_PRESSURE_SOFT to "0,0.25;0.75,1;",
            SessionPrefs.PEN_PRESSURE_FIRM to "0.25,0;1,0.75;",
        )

        fun forPreset(preset: String): PenAxes.PressureCurve = PRESETS[preset]?.let(::parse) ?: PenAxes.LINEAR

        fun parse(curve: String): PenPressure? {
            val points = curve.removeSuffix(";").split(';')
            if (points.size != 2) return null
            val values = points.flatMap { it.split(',') }.map { value ->
                value.toFloatOrNull()?.takeIf { it in 0f..1f } ?: return null
            }
            if (values.size != 4) return null
            return PenPressure(values[0], values[1], values[2], values[3])
        }

        private fun bezier(t: Double, p1: Float, p2: Float): Float {
            val u = 1 - t
            return (3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t).toFloat()
        }
    }
}
