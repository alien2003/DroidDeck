package com.droiddeck.launcher.input

import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.droiddeck.launcher.session.SessionPrefs
import java.util.Locale

class PenStream(private val sink: Sink) {
    interface Sink {
        fun sample(flags: Int, x: Int, y: Int, pressure: Int, distance: Int, tiltX: Int, tiltY: Int, timeMs: Int)
        fun capabilities(capabilities: Int)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val exitLater = Runnable { if (exitPending) leave() }
    private val hoverWatchdog = Runnable { checkHover() }
    private var watching = false
    private val tilt = IntArray(2)
    private var proximity = false
    private var contact = false
    private var eraser = false
    private var toolEraser = false
    private var tablet = false
    private var barrel = 0
    private var keyDown = false
    private var keyDevice = Int.MIN_VALUE
    private var keyCode = 0
    private var exitPending = false
    private var exitTime = 0L
    private var deviceId = Int.MIN_VALUE
    private var sentCapabilities = 0
    private var distanceMin = 0f
    private var distanceMax = 0f
    private var x = 0
    private var y = 0
    private var pressure = 0
    private var distance = 0
    private var tilted = false
    private var time = 0L

    var sideButton = SessionPrefs.PEN_BUTTON_RIGHT

    val inProximity: Boolean get() = proximity

    fun onMotion(event: MotionEvent, pointer: Int, area: RectF, width: Int, height: Int, wantsTablet: Boolean): Boolean {
        val action = event.actionMasked
        val time = event.eventTime
        val own = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP ||
            ((action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) && event.actionIndex == pointer)
        expire(time)
        when (action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                continueAt(time, switchesTool(event, pointer))
                contact = false
                enter(event, pointer, wantsTablet)
                samples(event, pointer, area, width, height)
            }
            MotionEvent.ACTION_HOVER_EXIT -> if (proximity && !exitPending) {
                if (contact) return true
                place(event, pointer, -1, area, width, height)
                pendExit(time)
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                continueAt(time)
                if (!proximity) return true
                tablet = wantsTablet
                barrel = event.buttonState
                present()
                samples(event, pointer, area, width, height)
            }
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (own) {
                continueAt(time, switchesTool(event, pointer))
                enter(event, pointer, wantsTablet)
                contact = true
                samples(event, pointer, area, width, height)
            } else if (contact) samples(event, pointer, area, width, height)
            MotionEvent.ACTION_MOVE -> if (contact) {
                barrel = event.buttonState
                samples(event, pointer, area, width, height)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (!own) {
                if (contact) samples(event, pointer, area, width, height)
            } else if (contact) {
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) cancel()
                else {
                    barrel = event.buttonState
                    samples(event, pointer, area, width, height, lift = true)
                    pendExit(time)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
            else -> return false
        }
        return true
    }

    fun onKey(event: KeyEvent): Boolean {
        expire(event.eventTime)
        val down = event.action == KeyEvent.ACTION_DOWN
        val repeat = event.repeatCount > 0
        if (exitPending && !repeat) leave()
        // A key held while the pen is away comes back only as repeats, so its state outlives proximity.
        if (!proximity || exitPending) {
            if (!repeat && event.deviceId == keyDevice && event.keyCode == keyCode) keyDown = down
            return true
        }
        if (down == keyDown) return true
        val before = state()
        keyDown = down
        if (down) {
            keyDevice = event.deviceId
            keyCode = event.keyCode
        }
        present()
        if (state() == before) return true
        time = event.eventTime
        emit(state() or tiltFlag())
        return true
    }

    fun cancel(releaseKey: Boolean = false) {
        if (releaseKey) keyDown = false
        if (!proximity) return
        emit(PEN_CANCEL or (if (eraser) PEN_ERASER else 0) or (if (tablet) PEN_TABLET else 0))
        reset()
    }

    fun holdsKey(event: KeyEvent): Boolean = keyDown && event.deviceId == keyDevice && event.keyCode == keyCode

    fun announce(device: InputDevice) {
        if (device.supportsSource(InputDevice.SOURCE_STYLUS) && device.motionRanges.isNotEmpty())
            send(capabilities(device, InputDevice.SOURCE_STYLUS))
    }

    fun deviceRemoved(deviceId: Int) {
        if (deviceId == this.deviceId) cancel()
        if (!keyDown || deviceId != keyDevice) return
        val before = state()
        keyDown = false
        if (!proximity) return
        present()
        if (state() != before) emit(state() or tiltFlag())
    }

    private fun enter(event: MotionEvent, pointer: Int, wantsTablet: Boolean) {
        toolEraser = event.getToolType(pointer) == MotionEvent.TOOL_TYPE_ERASER
        tablet = wantsTablet
        barrel = event.buttonState
        if (!proximity) {
            proximity = true
            deviceId = event.deviceId
            readDevice(event)
        }
        present()
    }

    // Windows Pen States: an eraser switch during contact waits until the tip lifts.
    private fun present() {
        if (!contact) eraser = toolEraser || (sideButton == SessionPrefs.PEN_BUTTON_ERASER && primaryHeld())
    }

    private fun primaryHeld(): Boolean = keyDown || barrel and MotionEvent.BUTTON_STYLUS_PRIMARY != 0

    private fun switchesTool(event: MotionEvent, pointer: Int): Boolean =
        exitPending && (event.getToolType(pointer) == MotionEvent.TOOL_TYPE_ERASER) != toolEraser

    private fun continueAt(time: Long, switchingTool: Boolean = false) {
        if (!exitPending) return
        if (time != exitTime && !(switchingTool && time - exitTime <= EXIT_GRACE_MS)) { leave(); return }
        exitPending = false
        handler.removeCallbacks(exitLater)
    }

    private fun pendExit(time: Long) {
        exitPending = true
        exitTime = time
        handler.removeCallbacks(exitLater)
        handler.postDelayed(exitLater, EXIT_GRACE_MS)
    }

    private fun expire(time: Long) {
        if (proximity && !contact && time - this.time >= HOVER_TIMEOUT_MS) leave()
    }

    private fun watchHover() {
        if (watching || !proximity || contact) return
        watching = true
        handler.postDelayed(hoverWatchdog, HOVER_TIMEOUT_MS)
    }

    private fun checkHover() {
        watching = false
        if (!proximity || contact || exitPending) return
        val silent = SystemClock.uptimeMillis() - time
        if (silent >= HOVER_TIMEOUT_MS) {
            leave()
            return
        }
        watching = true
        handler.postDelayed(hoverWatchdog, HOVER_TIMEOUT_MS - silent)
    }

    private fun leave() {
        if (!proximity) return
        if (exitPending) time = exitTime
        emit((if (eraser) PEN_ERASER else 0) or (if (tablet) PEN_TABLET else 0))
        reset()
    }

    private fun reset() {
        handler.removeCallbacks(exitLater)
        handler.removeCallbacks(hoverWatchdog)
        watching = false
        proximity = false
        contact = false
        exitPending = false
        eraser = false
        toolEraser = false
        barrel = 0
        tilted = false
    }

    private fun samples(event: MotionEvent, pointer: Int, area: RectF, width: Int, height: Int, lift: Boolean = false) {
        for (h in 0 until event.historySize) sample(event, pointer, h, area, width, height)
        if (lift) contact = false
        sample(event, pointer, -1, area, width, height)
    }

    private fun sample(event: MotionEvent, pointer: Int, h: Int, area: RectF, width: Int, height: Int) {
        place(event, pointer, h, area, width, height)
        pressure = PenAxes.pressure(event.axis(MotionEvent.AXIS_PRESSURE, pointer, h))
        distance = PenAxes.distance(event.axis(MotionEvent.AXIS_DISTANCE, pointer, h), distanceMin, distanceMax)
        tilted = PenAxes.tilt(event.axis(MotionEvent.AXIS_TILT, pointer, h), event.axis(MotionEvent.AXIS_ORIENTATION, pointer, h), tilt)
        time = if (h < 0) event.eventTime else event.getHistoricalEventTime(h)
        emit(state() or tiltFlag())
        watchHover()
    }

    private fun place(event: MotionEvent, pointer: Int, h: Int, area: RectF, width: Int, height: Int) {
        x = InputSpace.fixedX(event.axis(MotionEvent.AXIS_X, pointer, h).coerceIn(area.left, area.right), width)
        y = InputSpace.fixedY(event.axis(MotionEvent.AXIS_Y, pointer, h).coerceIn(area.top, area.bottom), height)
    }

    private fun emit(flags: Int) {
        val near = flags and PEN_IN != 0
        val tilts = flags and PEN_TILT != 0
        sink.sample(flags, x, y, if (near) pressure else 0, if (near) distance else 0,
            if (tilts) tilt[0] else 0, if (tilts) tilt[1] else 0, time.toInt())
    }

    private fun state(): Int =
        PEN_IN or (if (contact) PEN_TIP else 0) or (if (eraser) PEN_ERASER else 0) or
            buttonFlags(primaryHeld(), barrel and MotionEvent.BUTTON_STYLUS_SECONDARY != 0, sideButton) or
            (if (tablet) PEN_TABLET else 0)

    private fun tiltFlag(): Int = if (tilted) PEN_TILT else 0

    private fun readDevice(event: MotionEvent) {
        val device = event.device
        val distance = device?.range(MotionEvent.AXIS_DISTANCE, event.source)
        distanceMin = distance?.min ?: 0f
        distanceMax = distance?.max ?: 0f
        send(device?.let { capabilities(it, event.source) } ?: CAP_PRESSURE)
    }

    private fun capabilities(device: InputDevice, source: Int): Int =
        capabilities(device.range(MotionEvent.AXIS_TILT, source) != null,
            device.range(MotionEvent.AXIS_DISTANCE, source)?.let { it.max - it.min })

    private fun send(capabilities: Int) {
        if (capabilities or sentCapabilities == sentCapabilities) return
        sentCapabilities = sentCapabilities or capabilities
        sink.capabilities(sentCapabilities)
    }

    private fun InputDevice.range(axis: Int, source: Int): InputDevice.MotionRange? =
        getMotionRange(axis, source) ?: getMotionRange(axis)

    private fun MotionEvent.axis(axis: Int, pointer: Int, h: Int): Float =
        if (h < 0) getAxisValue(axis, pointer) else getHistoricalAxisValue(axis, pointer, h)

    companion object {
        const val PEN_IN = 1 shl 0
        const val PEN_TIP = 1 shl 1
        const val PEN_ERASER = 1 shl 2
        const val PEN_RIGHT = 1 shl 3
        const val PEN_MIDDLE = 1 shl 4
        const val PEN_TABLET = 1 shl 5
        const val PEN_CANCEL = 1 shl 6
        const val PEN_TILT = 1 shl 7
        const val CAP_PRESSURE = 1 shl 0
        const val CAP_TILT = 1 shl 1
        const val CAP_DISTANCE = 1 shl 2
        const val HOVER_TIMEOUT_MS = 2_000L
        const val EXIT_GRACE_MS = 30L

        fun capabilities(tilt: Boolean, distanceSpan: Float?): Int =
            CAP_PRESSURE or (if (tilt) CAP_TILT else 0) or (if (distanceSpan != null && distanceSpan > 1f) CAP_DISTANCE else 0)

        fun isPen(event: MotionEvent, index: Int): Boolean = when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
            MotionEvent.TOOL_TYPE_UNKNOWN -> event.isFromSource(InputDevice.SOURCE_STYLUS)
            else -> false
        }

        fun stylusConnected(): Boolean =
            InputDevice.getDeviceIds().any { id -> InputDevice.getDevice(id)?.let { isPenDevice(it.sources) } == true }

        // Before Android 14 a touchscreen that declares ABS_MT_TOOL_TYPE reports SOURCE_STYLUS without any pen.
        fun isPenDevice(sources: Int, sdk: Int = Build.VERSION.SDK_INT): Boolean =
            sources and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS &&
                (sources and InputDevice.SOURCE_TOUCHSCREEN != InputDevice.SOURCE_TOUCHSCREEN || sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

        fun penIndex(event: MotionEvent): Int {
            for (index in 0 until event.pointerCount) if (isPen(event, index)) return index
            return -1
        }

        fun isPenKey(event: KeyEvent): Boolean {
            val device = event.device ?: return false
            return isPenKeyboard(device.name, device.sources, Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || device.isExternal,
                device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        }

        fun isPenKeyboard(name: String?, sources: Int, external: Boolean, alphabetic: Boolean): Boolean {
            if (!external || alphabetic || sources != InputDevice.SOURCE_KEYBOARD) return false
            val lower = name?.lowercase(Locale.ROOT) ?: return false
            return "pen" in lower || "stylus" in lower
        }

        fun buttonFlags(primary: Boolean, secondary: Boolean, sideButton: String): Int {
            val first = when (sideButton) {
                SessionPrefs.PEN_BUTTON_RIGHT -> PEN_RIGHT
                SessionPrefs.PEN_BUTTON_MIDDLE -> PEN_MIDDLE
                else -> 0
            }
            val second = if (sideButton == SessionPrefs.PEN_BUTTON_RIGHT) PEN_MIDDLE else PEN_RIGHT
            return (if (primary) first else 0) or (if (secondary) second else 0)
        }
    }
}
