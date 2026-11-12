package com.droiddeck.launcher.input

internal class PalmGate(val log: (String) -> Unit = {}) {
    enum class Pen { OUT, HOVER, CONTACT }

    fun interface Canceler {
        fun cancelFingers(time: Long): Int
    }

    var enabled = true
    private var pen = Pen.OUT
    private var device = NO_DEVICE
    private var lastPenMs = NEVER
    private var outMs = NEVER
    private val cancelers = ArrayList<Canceler>(2)

    fun register(canceler: Canceler) { cancelers += canceler }

    fun armed(t: Long): Boolean {
        expire(t)
        return enabled && (pen != Pen.OUT || t - outMs < GRACE_MS)
    }

    fun exitedAt(t: Long): Boolean = pen == Pen.OUT && outMs == t

    fun pen(t: Long, next: Pen, deviceId: Int = NO_DEVICE) {
        val engage = enabled && !armed(t)
        pen = next
        device = deviceId
        lastPenMs = t
        if (!engage) return
        var cancelled = 0
        for (i in cancelers.indices) cancelled += cancelers[i].cancelFingers(t)
        if (cancelled > 0) log("palm: pen near, cancelled $cancelled finger(s)")
    }

    fun button(t: Long, deviceId: Int = NO_DEVICE) = pen(t, if (pen == Pen.CONTACT) Pen.CONTACT else Pen.HOVER, deviceId)

    fun leave(t: Long) {
        pen = Pen.OUT
        if (t > outMs) outMs = t
        lastPenMs = t
    }

    fun forceOut(now: Long) {
        if (pen == Pen.OUT) return
        outMs = if (pen == Pen.HOVER && now - lastPenMs >= HOVER_WATCHDOG_MS) lastPenMs else now
        pen = Pen.OUT
    }

    fun deviceRemoved(deviceId: Int, now: Long) {
        if (deviceId == device) forceOut(now)
    }

    private fun expire(t: Long) {
        if (pen != Pen.HOVER || t - lastPenMs < HOVER_WATCHDOG_MS) return
        pen = Pen.OUT
        outMs = lastPenMs
        log("palm: hover watchdog")
    }

    companion object {
        const val GRACE_MS = 400L
        const val HOVER_WATCHDOG_MS = PenStream.HOVER_TIMEOUT_MS
        const val NO_DEVICE = Int.MIN_VALUE
        private const val NEVER = Long.MIN_VALUE / 4
    }
}
