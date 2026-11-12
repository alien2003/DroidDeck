package com.droiddeck.launcher.input

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object PenAxes {
    const val PRESSURE_MAX = 65535
    const val DISTANCE_MAX = 65535
    const val TILT_MAX = 9000

    fun interface PressureCurve {
        fun map(pressure: Float): Float
    }

    val LINEAR = PressureCurve { it }

    fun pressure(value: Float, curve: PressureCurve = LINEAR): Int = scale(curve.map(value.coerceIn(0f, 1f)), PRESSURE_MAX)

    fun distance(value: Float, min: Float, max: Float): Int =
        if (max <= min) 0 else scale((value - min) / (max - min), DISTANCE_MAX)

    /**
     * Recovers evdev's tilt from Android's, which cooked it as orientation = atan2(-sin tx, sin ty) and
     * tilt = acos(cos tx * cos ty). Writes tilt x and y in 1/100 degree to [out]; false when the sample has
     * none: some pens report tilt 0 with orientation 0 at the edges of hover range.
     */
    fun tilt(tilt: Float, orientation: Float, out: IntArray): Boolean {
        if (tilt == 0f && orientation == 0f) return false
        val s = sin(orientation.toDouble())
        val c = cos(orientation.toDouble())
        val k = s * s * c * c
        val t = sin(tilt.toDouble()).let { it * it }
        val r = sqrt(2 * t / (1 + sqrt((1 - 4 * k * t).coerceAtLeast(0.0))))
        out[0] = centiDegrees(asin((-r * s).coerceIn(-1.0, 1.0)))
        out[1] = centiDegrees(asin((r * c).coerceIn(-1.0, 1.0)))
        return true
    }

    fun centiDegrees(radians: Double): Int = Math.round(Math.toDegrees(radians).toFloat() * 100).coerceIn(-TILT_MAX, TILT_MAX)

    private fun scale(fraction: Float, max: Int): Int = (fraction.coerceIn(0f, 1f) * max + 0.5f).toInt()
}
