package com.droiddeck.launcher.agent

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.droiddeck.launcher.input.PenRouter
import com.droiddeck.launcher.session.SessionState
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

object AgentPen {
    interface Sink {
        fun area(): RectF?
        fun hover(event: MotionEvent)
        fun touch(event: MotionEvent)
        fun announceTilt()
        fun takeRoute(): PenRouter.Route?
        fun tablet(): Boolean
    }

    const val PEN_DEVICE = -1001
    const val FINGER_DEVICE = -1002
    const val MAX_SAMPLES = 4096
    const val MAX_MS = 30_000L
    const val MAX_BATCH = 8
    private const val MAX_FINGER_ID = 9
    private const val DELIVERY_MS = 10_000L
    private const val PEN_SOURCE = InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS
    private val BUTTONS = mapOf("primary" to MotionEvent.BUTTON_STYLUS_PRIMARY, "secondary" to MotionEvent.BUTTON_STYLUS_SECONDARY)
    private val BUTTON_BITS = BUTTONS.values.toIntArray()
    private val PHASES = setOf("hover", "down", "move", "up", "out", "cancel")

    internal data class Sample(val t: Long, val x: Float, val y: Float, val pressure: Float, val distance: Float, val tiltX: Float, val tiltY: Float)

    internal interface Step { val t: Long }

    internal class PenStep(val action: Int, val downT: Long, val buttons: Int, first: Sample, val flags: Int = 0) : Step {
        val samples = arrayListOf(first)
        override val t: Long get() = samples.last().t
        val generic: Boolean get() = action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
    }

    internal class FingerStep(override val t: Long, val action: Int, val downT: Long, val ids: IntArray, val xs: FloatArray, val ys: FloatArray,
        val flags: Int = 0) : Step

    internal class Plan(val samples: Int, val tool: Int, val tilt: Boolean, val steps: List<Step>)

    fun handle(request: JSONObject): JSONObject {
        val plan = plan(request, SessionState.outputSize)
        val sink = SessionState.penSink ?: throw AgentException("NO_PEN_SINK", "No session screen is open to take pen input")
        val main = Handler(Looper.getMainLooper())
        val started = System.nanoTime()
        val delivery = Delivery(plan, sink, SystemClock.uptimeMillis())
        main.post(delivery::begin)
        for (step in plan.steps) {
            if (delivery.failure != null) break
            val wait = step.t - (System.nanoTime() - started) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
            main.post { delivery.deliver(step) }
        }
        val done = CountDownLatch(1)
        main.post {
            delivery.finish()
            done.countDown()
        }
        if (!done.await(DELIVERY_MS, TimeUnit.MILLISECONDS)) {
            throw AgentException("TIMEOUT", "The session screen did not take the pen events within ${DELIVERY_MS / 1000} s")
        }
        delivery.failure?.let { throw it }
        return JSONObject().put("via", "pipeline").put("samples", plan.samples).put("events", delivery.events)
            .put("ms", (System.nanoTime() - started) / 1_000_000).put("route", delivery.route)
    }

    internal class Delivery(private val plan: Plan, private val sink: Sink, private val base: Long) {
        var events = 0
            private set
        var route = "ui"
            private set
        @Volatile var failure: AgentException? = null
            private set
        private var area: RectF? = null
        private var landed = false
        private var downRoute: PenRouter.Route? = null
        private var lastRoute: PenRouter.Route? = null

        fun begin() {
            area = sink.area()
            if (area == null) {
                failure = AgentException("SESSION_NOT_READY", "The session screen shows no picture yet")
                return
            }
            sink.takeRoute()
            if (plan.tilt) sink.announceTilt()
        }

        fun deliver(step: Step) {
            if (failure != null) return
            if (SessionState.penSink !== sink) {
                failure = AgentException("NO_PEN_SINK", "The session screen closed during the pen gesture")
                return
            }
            val rect = sink.area()?.also { area = it } ?: area ?: return
            when (step) {
                is PenStep -> {
                    val event = penEvent(step, base, rect, plan.tool)
                    try { if (step.generic) sink.hover(event) else sink.touch(event) } finally { event.recycle() }
                    val taken = sink.takeRoute()
                    if (taken != null) lastRoute = taken
                    if (step.action == MotionEvent.ACTION_DOWN) {
                        landed = true
                        downRoute = taken
                    }
                }
                is FingerStep -> {
                    val event = fingerEvent(step, base, rect)
                    try { sink.touch(event) } finally { event.recycle() }
                }
            }
            events++
        }

        fun finish() {
            if (failure != null) return
            route = when (if (landed) downRoute else lastRoute) {
                null -> "ui"
                PenRouter.Route.PEN -> if (sink.tablet()) "tablet" else "pointer"
                PenRouter.Route.RELATIVE -> "relative"
                PenRouter.Route.TOUCH -> "touch"
                PenRouter.Route.BLOCKED -> "blocked"
            }
        }
    }

    internal fun plan(request: JSONObject, output: Pair<Int, Int>): Plan {
        val via = request.optString("via", "pipeline")
        if (via != "pipeline") invalid("via '$via' is not pipeline; droiddeckctl drives uinput itself")
        val space = request.optString("space", "output")
        val extent = when (space) {
            "fraction" -> 1.0 to 1.0
            "output" -> (output.first - 1).coerceAtLeast(1).toDouble() to (output.second - 1).coerceAtLeast(1).toDouble()
            else -> invalid("space '$space' is not output or fraction")
        }
        val tool = when (val name = request.optString("tool", "pen")) {
            "pen" -> MotionEvent.TOOL_TYPE_STYLUS
            "eraser" -> MotionEvent.TOOL_TYPE_ERASER
            else -> invalid("tool '$name' is not pen or eraser")
        }
        val batch = integer(request, "batch", "request", 1, MAX_BATCH) ?: 1
        val raw = list(request, "samples", "request") ?: invalid("samples is required")
        if (raw.length() == 0) invalid("samples is empty")
        val pen = penSteps(raw, extent, batch)
        val fingers = list(request, "fingers", "request")?.let { fingerSteps(it, extent) }.orEmpty()
        return Plan(raw.length(), tool, pen.tilt, merge(pen.steps, fingers))
    }

    private class Parsed(val phase: String, val sample: Sample, val buttons: Int)

    private fun penSteps(raw: JSONArray, extent: Pair<Double, Double>, batch: Int): PenSteps {
        if (raw.length() > MAX_SAMPLES) invalid("samples holds ${raw.length()} entries, more than $MAX_SAMPLES")
        val parsed = ArrayList<Parsed>(raw.length())
        var t = 0L
        var x = Float.NaN
        var y = Float.NaN
        var pressure = 0.5f
        var distance = 0f
        var tiltX = 0f
        var tiltY = 0f
        var buttons = 0
        for (i in 0 until raw.length()) {
            val where = "sample $i"
            val o = raw.optJSONObject(i) ?: invalid("$where is not an object")
            t = time(o, where, t)
            val phase = o.optString("phase")
            if (phase !in PHASES) invalid("$where: phase '$phase' is not one of ${PHASES.joinToString("|")}")
            x = coordinate(o, "x", extent.first, where) ?: x
            y = coordinate(o, "y", extent.second, where) ?: y
            if (x.isNaN() || y.isNaN()) invalid("$where: x and y are required on the first sample")
            pressure = bounded(o, "pressure", where, 0.0, 1.0, "0..1")?.toFloat() ?: pressure
            distance = bounded(o, "distance", where, 0.0, Double.MAX_VALUE, "0 or more")?.toFloat() ?: distance
            tiltX = bounded(o, "tiltX", where, -90.0, 90.0, "-90..90")?.toFloat() ?: tiltX
            tiltY = bounded(o, "tiltY", where, -90.0, 90.0, "-90..90")?.toFloat() ?: tiltY
            buttons = buttons(o, where) ?: buttons
            parsed += Parsed(phase, Sample(t, x, y, pressure, distance, tiltX, tiltY), buttons)
        }
        val steps = PenSteps(batch)
        for ((i, p) in parsed.withIndex()) {
            val next = parsed.getOrNull(i + 1)
            steps.add(p.phase, p.sample, p.buttons, next?.phase == "out" && next.sample.t == p.sample.t, "sample $i")
        }
        if (steps.touching) invalid("the samples end with the pen down; lift it with up or cancel")
        return steps
    }

    // Event order within one sample follows AOSP's TouchInputMapper::sync: button releases, hover exit, contact,
    // hover enter and move, button presses.
    private class PenSteps(private val batch: Int) {
        val steps = ArrayList<Step>()
        var tilt = false
        var touching = false
        private var near = false
        private var hovering = false
        private var downT = 0L
        private var sent = 0
        private var last: Sample? = null
        private var open: PenStep? = null

        fun add(phase: String, s: Sample, buttons: Int, liftsAway: Boolean, where: String) {
            if (s.tiltX != 0f || s.tiltY != 0f) tilt = true
            when (phase) {
                "hover" -> {
                    if (touching) invalid("$where: hover while the pen is down; lift it with up first")
                    release(s.t, buttons)
                    if (!hovering) {
                        emit(MotionEvent.ACTION_HOVER_ENTER, s, buttons)
                        hovering = true
                    }
                    batch(MotionEvent.ACTION_HOVER_MOVE, s, buttons)
                    press(s, buttons)
                    near = true
                }
                "down" -> {
                    if (touching) invalid("$where: down while the pen is already down")
                    release(s.t, buttons)
                    if (hovering) emit(MotionEvent.ACTION_HOVER_EXIT, last!!.copy(t = s.t), sent)
                    hovering = false
                    touching = true
                    near = true
                    downT = s.t
                    sent = buttons
                    emit(MotionEvent.ACTION_DOWN, s, buttons)
                }
                "move" -> {
                    if (!touching) invalid("$where: move needs a down first")
                    sent = buttons
                    batch(MotionEvent.ACTION_MOVE, s, buttons)
                }
                "up" -> {
                    if (!touching) invalid("$where: up needs a down first")
                    sent = buttons
                    touching = false
                    // Android's UP repeats the last contact, so the pen first moves to where it lifts
                    val l = last!!
                    if (s.x != l.x || s.y != l.y || s.pressure != l.pressure || s.tiltX != l.tiltX || s.tiltY != l.tiltY) {
                        batch(MotionEvent.ACTION_MOVE, s, buttons)
                    }
                    emit(MotionEvent.ACTION_UP, s, buttons)
                    if (!liftsAway) {
                        emit(MotionEvent.ACTION_HOVER_ENTER, s, buttons)
                        hovering = true
                        batch(MotionEvent.ACTION_HOVER_MOVE, s, buttons)
                    }
                }
                "out" -> {
                    if (touching) invalid("$where: out while the pen is down; lift it with up first")
                    if (!near) invalid("$where: out needs a hover first")
                    release(s.t, buttons)
                    if (hovering) emit(MotionEvent.ACTION_HOVER_EXIT, s, sent)
                    hovering = false
                    near = false
                    sent = buttons
                }
                "cancel" -> {
                    if (!touching) invalid("$where: cancel needs a down first")
                    sent = buttons
                    emit(MotionEvent.ACTION_CANCEL, s, buttons, MotionEvent.FLAG_CANCELED)
                    touching = false
                    near = false
                }
            }
            last = s
        }

        private fun release(t: Long, buttons: Int) {
            for (bit in BUTTON_BITS) {
                if (sent and bit == 0 || buttons and bit != 0) continue
                sent = sent and bit.inv()
                if (hovering) emit(MotionEvent.ACTION_BUTTON_RELEASE, last!!.copy(t = t), sent)
            }
        }

        private fun press(s: Sample, buttons: Int) {
            for (bit in BUTTON_BITS) {
                if (sent and bit != 0 || buttons and bit == 0) continue
                sent = sent or bit
                emit(MotionEvent.ACTION_BUTTON_PRESS, s, sent)
            }
        }

        private fun emit(action: Int, s: Sample, buttons: Int, flags: Int = 0) {
            open = null
            steps += PenStep(action, downT, buttons, s, flags)
        }

        private fun batch(action: Int, s: Sample, buttons: Int) {
            val step = open
            if (step != null && step.action == action && step.buttons == buttons && step.samples.size < batch) {
                step.samples += s
                return
            }
            open = PenStep(action, downT, buttons, s).also { steps += it }
        }
    }

    private fun fingerSteps(raw: JSONArray, extent: Pair<Double, Double>): List<Step> {
        if (raw.length() > MAX_SAMPLES) invalid("fingers holds ${raw.length()} entries, more than $MAX_SAMPLES")
        val down = TreeMap<Int, FloatArray>()
        val steps = ArrayList<Step>(raw.length())
        var t = 0L
        var downT = 0L
        for (i in 0 until raw.length()) {
            val where = "finger $i"
            val o = raw.optJSONObject(i) ?: invalid("$where is not an object")
            t = time(o, where, t)
            val id = integer(o, "id", where, 0, MAX_FINGER_ID) ?: invalid("$where: id is required")
            val x = coordinate(o, "x", extent.first, where)
            val y = coordinate(o, "y", extent.second, where)
            val phase = o.optString("phase")
            val point = when (phase) {
                "down" -> {
                    if (id in down) invalid("$where: finger $id is already down")
                    if (x == null || y == null) invalid("$where: x and y are required for down")
                    floatArrayOf(x, y).also { down[id] = it }
                }
                "move", "up" -> down[id] ?: invalid("$where: finger $id is not down")
                else -> invalid("$where: phase '$phase' is not down, move or up")
            }
            x?.let { point[0] = it }
            y?.let { point[1] = it }
            val index = down.headMap(id).size
            val action = when {
                phase == "move" -> MotionEvent.ACTION_MOVE
                down.size == 1 && phase == "down" -> MotionEvent.ACTION_DOWN.also { downT = t }
                down.size == 1 -> MotionEvent.ACTION_UP
                phase == "down" -> MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                else -> MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            val ids = down.keys.toIntArray()
            steps += FingerStep(t, action, downT, ids, FloatArray(ids.size) { down.getValue(ids[it])[0] }, FloatArray(ids.size) { down.getValue(ids[it])[1] })
            if (phase == "up") down.remove(id)
        }
        if (down.isNotEmpty()) invalid("fingers ${down.keys.joinToString()} are still down at the end; lift them with up")
        return steps
    }

    // Pen and fingers are separate devices, so Android's stylus-over-touch rule applies: a finger gesture that is down
    // when the pen comes near or lands is canceled and sends nothing more, and one that starts while the pen touches
    // is dropped. One that starts while the pen hovers is delivered.
    private fun merge(pen: List<Step>, fingers: List<Step>): List<Step> {
        if (fingers.isEmpty()) return pen
        val out = ArrayList<Step>(pen.size + fingers.size)
        var touching = false
        var held: FingerStep? = null
        var dropping = false
        var f = 0
        fun finger(step: FingerStep) {
            val action = step.action and MotionEvent.ACTION_MASK
            if (action == MotionEvent.ACTION_DOWN && touching) dropping = true
            if (!dropping) {
                out += step
                held = step.takeIf { action != MotionEvent.ACTION_UP }
            }
            if (action == MotionEvent.ACTION_UP) dropping = false
        }
        for (step in pen) {
            while (f < fingers.size && fingers[f].t < step.t) finger(fingers[f++] as FingerStep)
            val action = (step as PenStep).action
            val down = held
            if (down != null && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_HOVER_ENTER)) {
                out += canceled(down, step.t)
                held = null
                dropping = true
            }
            when (action) {
                MotionEvent.ACTION_DOWN -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
            }
            out += step
        }
        while (f < fingers.size) finger(fingers[f++] as FingerStep)
        return out
    }

    private fun canceled(last: FingerStep, t: Long): FingerStep {
        val lifted = if (last.action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_POINTER_UP) {
            (last.action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
        } else -1
        val kept = last.ids.indices.filter { it != lifted }
        return FingerStep(t, MotionEvent.ACTION_CANCEL, last.downT, IntArray(kept.size) { last.ids[kept[it]] },
            FloatArray(kept.size) { last.xs[kept[it]] }, FloatArray(kept.size) { last.ys[kept[it]] }, MotionEvent.FLAG_CANCELED)
    }

    internal fun penEvent(step: PenStep, base: Long, area: RectF, tool: Int): MotionEvent {
        val contact = !step.generic
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val first = step.samples[0]
        val event = MotionEvent.obtain(base + step.downT, base + first.t, step.action, 1, properties, arrayOf(coords(first, area, contact)),
            0, step.buttons, 1f, 1f, PEN_DEVICE, 0, PEN_SOURCE, step.flags)
        for (i in 1 until step.samples.size) event.addBatch(base + step.samples[i].t, arrayOf(coords(step.samples[i], area, contact)), 0)
        return event
    }

    internal fun fingerEvent(step: FingerStep, base: Long, area: RectF): MotionEvent {
        val properties = Array(step.ids.size) { i -> MotionEvent.PointerProperties().apply { id = step.ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(step.ids.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = area.left + step.xs[i] * area.width()
                y = area.top + step.ys[i] * area.height()
                pressure = 1f
            }
        }
        return MotionEvent.obtain(base + step.downT, base + step.t, step.action, step.ids.size, properties, coords,
            0, 0, 1f, 1f, FINGER_DEVICE, 0, InputDevice.SOURCE_TOUCHSCREEN, step.flags)
    }

    // AOSP's TouchInputMapper cooks evdev tilt into AXIS_TILT and AXIS_ORIENTATION this way; PenAxes.tilt inverts it.
    private fun coords(s: Sample, area: RectF, contact: Boolean) = MotionEvent.PointerCoords().apply {
        x = area.left + s.x * area.width()
        y = area.top + s.y * area.height()
        pressure = if (contact) s.pressure else 0f
        setAxisValue(MotionEvent.AXIS_DISTANCE, if (contact) 0f else s.distance)
        if (s.tiltX != 0f || s.tiltY != 0f) {
            val tx = Math.toRadians(s.tiltX.toDouble())
            val ty = Math.toRadians(s.tiltY.toDouble())
            orientation = atan2(-sin(tx), sin(ty)).toFloat()
            setAxisValue(MotionEvent.AXIS_TILT, acos(cos(tx) * cos(ty)).toFloat())
        }
    }

    private fun invalid(message: String): Nothing = throw AgentException("INVALID_INPUT", message)

    private fun time(o: JSONObject, where: String, previous: Long): Long {
        val t = bounded(o, "t", where, 0.0, MAX_MS.toDouble(), "0..$MAX_MS ms")?.toLong() ?: invalid("$where: t is required")
        if (t < previous) invalid("$where: t $t is before the previous $previous")
        return t
    }

    private fun coordinate(o: JSONObject, key: String, extent: Double, where: String): Float? =
        bounded(o, key, where, 0.0, extent, if (extent == 1.0) "0..1" else "0..${extent.toLong()}")?.let { (it / extent).toFloat() }

    private fun bounded(o: JSONObject, key: String, where: String, min: Double, max: Double, rule: String): Double? {
        if (!o.has(key)) return null
        val value = (o.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: invalid("$where: $key is not a number")
        if (value < min || value > max) invalid("$where: $key ${shown(value)} must be $rule")
        return value
    }

    private fun shown(value: Double): String = if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private fun integer(o: JSONObject, key: String, where: String, min: Int, max: Int): Int? =
        bounded(o, key, where, min.toDouble(), max.toDouble(), "$min..$max")?.let { value ->
            value.toInt().takeIf { it.toDouble() == value } ?: invalid("$where: $key ${shown(value)} is not a whole number")
        }

    private fun list(o: JSONObject, key: String, where: String): JSONArray? {
        if (!o.has(key)) return null
        return o.opt(key) as? JSONArray ?: invalid("$where: $key is not a list")
    }

    private fun buttons(o: JSONObject, where: String): Int? {
        val names = list(o, "buttons", where) ?: return null
        var state = 0
        for (i in 0 until names.length()) {
            val name = names.opt(i) as? String
            state = state or (BUTTONS[name] ?: invalid("$where: button '${names.opt(i)}' is not primary or secondary"))
        }
        return state
    }
}
