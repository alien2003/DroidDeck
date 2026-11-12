package com.droiddeck.launcher.input

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent

internal class PalmFilter(private val gate: PalmGate, private val deliver: (MotionEvent) -> Boolean) : PalmGate.Canceler {
    private var passed = 0
    private var rejected = 0
    private var pens = 0
    private var deviceId = 0
    private var source = 0
    private var downTime = 0L
    private var logged = false
    private val xs = FloatArray(MAX_IDS)
    private val ys = FloatArray(MAX_IDS)
    private val tools = IntArray(MAX_IDS)

    init { gate.register(this) }

    fun dispatch(e: MotionEvent): Boolean {
        var all = 0
        var penBits = 0
        var palmPens = 0
        for (i in 0 until e.pointerCount) {
            val bit = 1 shl e.getPointerId(i)
            all = all or bit
            if (PenStream.isPen(e, i)) {
                penBits = penBits or bit
                if (armsPalm(e, i)) palmPens = palmPens or bit
            }
        }
        if (penBits == 0 && !e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return deliver(e)
        val action = e.actionMasked
        val t = e.eventTime
        val bit = 1 shl e.getPointerId(e.actionIndex)
        val down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
        if (action == MotionEvent.ACTION_DOWN) {
            passed = 0
            rejected = 0
            pens = 0
            logged = false
        }
        if (penBits == 0 && pens == 0 && rejected == 0 && !(down && gate.armed(t))) {
            if (down) passed = passed or bit
            return output(e, action, bit, all, all)
        }
        if (palmPens != 0) {
            val lifting = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
                (action == MotionEvent.ACTION_POINTER_UP && palmPens == bit)
            if (lifting) gate.leave(t) else gate.pen(t, PalmGate.Pen.CONTACT, e.deviceId)
        }
        if (down) when {
            bit and penBits != 0 -> {
                pens = pens or bit
                passed = passed or bit
            }
            rejected == 0 && !gate.armed(t) -> passed = passed or bit
            else -> reject(bit, t)
        }
        return output(e, action, bit, passed and all, all)
    }

    fun reset() {
        passed = 0
        rejected = 0
        pens = 0
    }

    override fun cancelFingers(time: Long): Int {
        if (passed == 0 || pens != 0) return 0
        val count = Integer.bitCount(passed)
        val properties = Array(count) { MotionEvent.PointerProperties() }
        val coords = Array(count) { MotionEvent.PointerCoords() }
        var n = 0
        for (id in 0 until MAX_IDS) {
            if (passed and (1 shl id) == 0) continue
            properties[n].id = id
            properties[n].toolType = tools[id]
            coords[n].x = xs[id]
            coords[n].y = ys[id]
            n++
        }
        val cancel = MotionEvent.obtain(downTime, time, MotionEvent.ACTION_CANCEL, count, properties, coords,
            0, 0, 1f, 1f, deviceId, 0, source, MotionEvent.FLAG_CANCELED)
        rejected = rejected or passed
        passed = 0
        try { deliver(cancel) } finally { cancel.recycle() }
        return count
    }

    private fun reject(bit: Int, t: Long) {
        rejected = rejected or bit
        if (logged) return
        logged = true
        gate.log(when {
            gate.exitedAt(t) -> "palm: same-time hover exit before finger"
            gate.armed(t) -> "palm: finger ignored while the pen is near"
            else -> "palm: finger ignored, a palm is still down"
        })
    }

    private fun output(e: MotionEvent, action: Int, bit: Int, out: Int, all: Int): Boolean {
        when (action) {
            MotionEvent.ACTION_POINTER_UP -> {
                passed = passed and bit.inv()
                rejected = rejected and bit.inv()
                pens = pens and bit.inv()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        if (out == 0) return true
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            xs[id] = e.getX(i)
            ys[id] = e.getY(i)
            tools[id] = e.getToolType(i)
        }
        val starts = (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && out == bit
        if (starts) {
            downTime = if (action == MotionEvent.ACTION_DOWN) e.downTime else e.eventTime
            deviceId = e.deviceId
            source = e.source
        }
        if (out == all && e.downTime == downTime) return deliver(e)
        val split = e.pointerSubset(out, downTime) ?: return true
        try { return deliver(split) } finally { split.recycle() }
    }

    companion object {
        private const val MAX_IDS = 32

        fun armsPalm(e: MotionEvent, index: Int): Boolean {
            if (!PenStream.isPen(e, index)) return false
            if (e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) || !e.isFromSource(InputDevice.SOURCE_MOUSE)) return true
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && InputDevice.getDevice(e.deviceId)?.isExternal == false
        }
    }
}
