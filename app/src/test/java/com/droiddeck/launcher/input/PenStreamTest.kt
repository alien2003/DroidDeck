package com.droiddeck.launcher.input

import android.graphics.RectF
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.droiddeck.launcher.input.PenStream.Companion.PEN_CANCEL
import com.droiddeck.launcher.input.PenStream.Companion.PEN_ERASER
import com.droiddeck.launcher.input.PenStream.Companion.PEN_IN
import com.droiddeck.launcher.input.PenStream.Companion.PEN_MIDDLE
import com.droiddeck.launcher.input.PenStream.Companion.PEN_RIGHT
import com.droiddeck.launcher.input.PenStream.Companion.PEN_TABLET
import com.droiddeck.launcher.input.PenStream.Companion.PEN_TILT
import com.droiddeck.launcher.input.PenStream.Companion.PEN_TIP
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.InputDeviceBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PenStreamTest {
    data class Record(val flags: Int, val x: Int, val y: Int, val pressure: Int, val distance: Int, val tiltX: Int, val tiltY: Int, val time: Int)
    data class Sample(val time: Long, val x: Float, val y: Float = 300f, val pressure: Float = 0f,
        val tilt: Float = 0f, val orientation: Float = 0f)

    private val records = mutableListOf<Record>()
    private val capabilities = mutableListOf<Int>()
    private val stream = PenStream(object : PenStream.Sink {
        override fun sample(flags: Int, x: Int, y: Int, pressure: Int, distance: Int, tiltX: Int, tiltY: Int, timeMs: Int) {
            records += Record(flags, x, y, pressure, distance, tiltX, tiltY, timeMs)
        }
        override fun capabilities(capabilities: Int) { this@PenStreamTest.capabilities += capabilities }
    })
    private val area = RectF(0f, 0f, 1000f, 600f)

    @Test fun hoverEntersMovesAndLeavesProximity() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(20, 200f))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(30, 250f))
        assertEquals(listOf(PEN_IN to 10, PEN_IN to 20), states())
        idle()
        assertEquals(listOf(PEN_IN to 10, PEN_IN to 20), states())
        grace()
        assertEquals(listOf(PEN_IN to 10, PEN_IN to 20, 0 to 30), states())
        assertEquals(x(250f), records.last().x)
        assertEquals(listOf(PenStream.CAP_PRESSURE), capabilities)
        assertFalse(stream.inProximity)
    }

    @Test fun hoverExitThenDownWithTheSameTimeStaysInProximity() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(20, 100f))
        send(MotionEvent.ACTION_DOWN, Sample(20, 100f, pressure = 0.5f))
        idle()
        assertEquals(listOf(PEN_IN to 10, (PEN_IN or PEN_TIP) to 20), states())
        assertEquals(32768, records.last().pressure)
    }

    @Test fun hoverExitThenALaterDownLeavesProximityFirst() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(20, 100f))
        send(MotionEvent.ACTION_DOWN, Sample(25, 100f, pressure = 0.5f))
        assertEquals(listOf(PEN_IN to 10, 0 to 20, (PEN_IN or PEN_TIP) to 25), states())
    }

    @Test fun liftingIntoHoverKeepsProximityAndLiftingAwayLeavesIt() {
        send(MotionEvent.ACTION_DOWN, Sample(10, 100f, pressure = 0.3f))
        send(MotionEvent.ACTION_UP, Sample(20, 110f, pressure = 0.3f))
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(20, 110f))
        idle()
        assertEquals(listOf((PEN_IN or PEN_TIP) to 10, PEN_IN to 20, PEN_IN to 20), states())
        records.clear()
        send(MotionEvent.ACTION_DOWN, Sample(30, 100f, pressure = 0.3f))
        send(MotionEvent.ACTION_UP, Sample(40, 100f))
        grace()
        assertEquals(listOf((PEN_IN or PEN_TIP) to 30, PEN_IN to 40, 0 to 40), states())
    }

    @Test fun aHoverExitReadAloneStillWaitsForTheDownWithTheSameTime() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        stream.onKey(key(KeyEvent.ACTION_DOWN, 15))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(20, 100f))
        idle()
        send(MotionEvent.ACTION_DOWN, Sample(20, 100f, pressure = 0.5f))
        grace()
        assertEquals(listOf(PEN_IN to 10, (PEN_IN or PEN_RIGHT) to 15, (PEN_IN or PEN_TIP or PEN_RIGHT) to 20), states())
    }

    @Test fun anUpReadAloneStillWaitsForTheHoverWithTheSameTime() {
        send(MotionEvent.ACTION_DOWN, Sample(10, 100f, pressure = 0.3f))
        send(MotionEvent.ACTION_UP, Sample(20, 110f))
        idle()
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(20, 110f))
        grace()
        assertEquals(listOf((PEN_IN or PEN_TIP) to 10, PEN_IN to 20, PEN_IN to 20), states())
    }

    @Test fun aPenKeyAfterTheLiftEndsProximityFirst() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(20, 100f))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 25)))
        grace()
        assertEquals(listOf(PEN_IN to 10, 0 to 20), states())
    }

    @Test fun historicalSamplesGoOutOldestFirstWithTheirOwnTimes() {
        send(MotionEvent.ACTION_DOWN, Sample(100, 10f, pressure = 0.1f))
        send(MotionEvent.ACTION_MOVE, Sample(103, 20f, pressure = 0.2f), Sample(106, 30f, pressure = 0.4f), Sample(109, 40f, pressure = 1f))
        assertEquals(listOf(100, 103, 106, 109), records.map { it.time })
        assertEquals(listOf(10f, 20f, 30f, 40f).map(::x), records.map { it.x })
        assertEquals(listOf(6554, 13107, 26214, 65535), records.map { it.pressure })
        assertTrue(records.all { it.flags == PEN_IN or PEN_TIP })
        assertEquals(y(300f), records.last().y)
    }

    @Test fun cancelEndsTheStrokeWithoutAClickAndIgnoresTheRest() {
        send(MotionEvent.ACTION_DOWN, Sample(10, 100f, pressure = 0.5f))
        send(MotionEvent.ACTION_MOVE, Sample(20, 150f, pressure = 0.5f))
        send(MotionEvent.ACTION_CANCEL, Sample(30, 160f))
        send(MotionEvent.ACTION_MOVE, Sample(40, 170f, pressure = 0.5f))
        send(MotionEvent.ACTION_UP, Sample(50, 170f))
        idle()
        assertEquals(listOf((PEN_IN or PEN_TIP) to 10, (PEN_IN or PEN_TIP) to 20, PEN_CANCEL to 20), states())
        assertEquals(x(150f), records.last().x)
        assertFalse(stream.inProximity)
    }

    @Test fun anUpThatAndroidCanceledIsACancelNotARelease() {
        send(MotionEvent.ACTION_DOWN, Sample(10, 100f, pressure = 0.5f))
        send(MotionEvent.ACTION_UP, Sample(20, 100f), flags = MotionEvent.FLAG_CANCELED)
        idle()
        assertEquals(listOf((PEN_IN or PEN_TIP) to 10, PEN_CANCEL to 10), states())
    }

    @Test fun switchingToTheEraserLeavesWithThePenAndEntersWithTheEraser() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(20, 120f), tool = MotionEvent.TOOL_TYPE_ERASER)
        send(MotionEvent.ACTION_DOWN, Sample(30, 120f, pressure = 0.5f), tool = MotionEvent.TOOL_TYPE_ERASER)
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(40, 120f))
        assertEquals(listOf(PEN_IN to 10, 0 to 10, (PEN_IN or PEN_ERASER) to 20, (PEN_IN or PEN_ERASER or PEN_TIP) to 30,
            PEN_ERASER to 30, PEN_IN to 40), states())
    }

    @Test fun barrelButtonsFollowTheButtonState() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(20, 100f), buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        send(MotionEvent.ACTION_BUTTON_PRESS, Sample(21, 100f), buttons = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)
        send(MotionEvent.ACTION_DOWN, Sample(30, 100f, pressure = 0.5f), buttons = MotionEvent.BUTTON_STYLUS_SECONDARY)
        send(MotionEvent.ACTION_MOVE, Sample(40, 100f, pressure = 0.5f))
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_RIGHT, PEN_IN or PEN_RIGHT or PEN_MIDDLE, PEN_IN or PEN_TIP or PEN_MIDDLE,
            PEN_IN or PEN_TIP), records.map { it.flags })
    }

    @Test fun aBluetoothPenKeyHoldsRightWhileThePenIsNear() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 15)))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 16, repeat = 1)))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(20, 130f))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_UP, 25)))
        assertEquals(listOf(PEN_IN to 10, (PEN_IN or PEN_RIGHT) to 15, (PEN_IN or PEN_RIGHT) to 20, PEN_IN to 25), states())
        assertEquals(x(130f), records.last().x)
    }

    @Test fun aBluetoothPenKeyIsConsumedWhenThePenIsAway() {
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 10)))
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(20, 100f))
        assertTrue(stream.onKey(key(KeyEvent.ACTION_UP, 30)))
        assertEquals(listOf(PEN_IN to 20), states())
    }

    @Test fun leavingProximityReleasesTheKeyHold() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        stream.onKey(key(KeyEvent.ACTION_DOWN, 15))
        send(MotionEvent.ACTION_HOVER_EXIT, Sample(20, 100f))
        grace()
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(30, 100f))
        assertEquals(listOf(PEN_IN to 10, (PEN_IN or PEN_RIGHT) to 15, 0 to 20, PEN_IN to 30), states())
    }

    @Test fun aHoverThatStopsReportingCountsAsGone() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(1_000, 100f))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(2_000, 100f))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(4_500, 110f))
        assertEquals(listOf(PEN_IN to 1_000, PEN_IN to 2_000, 0 to 2_000, PEN_IN to 4_500), states())
        records.clear()
        assertTrue(stream.onKey(key(KeyEvent.ACTION_DOWN, 7_000)))
        assertEquals(listOf(0 to 4_500), states())
        assertEquals(listOf(PenStream.CAP_PRESSURE), capabilities)
    }

    @Test fun aStrokeIsNeverTimedOut() {
        send(MotionEvent.ACTION_DOWN, Sample(1_000, 100f, pressure = 0.5f))
        send(MotionEvent.ACTION_MOVE, Sample(9_000, 110f, pressure = 0.5f))
        assertEquals(listOf((PEN_IN or PEN_TIP) to 1_000, (PEN_IN or PEN_TIP) to 9_000), states())
    }

    @Test fun tiltIsSentOnlyWithSamplesThatCarryIt() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(20, 100f, tilt = (Math.PI / 6).toFloat(), orientation = (-Math.PI / 2).toFloat()))
        assertEquals(PEN_IN, records[0].flags)
        assertEquals(PEN_IN or PEN_TILT, records[1].flags)
        assertEquals(3000 to 0, records[1].tiltX to records[1].tiltY)
    }

    @Test fun lifecycleCancelEndsEverythingOnceAndRecordsTheTabletWish() {
        send(MotionEvent.ACTION_DOWN, Sample(10, 100f, pressure = 0.5f), tablet = true)
        stream.cancel()
        stream.cancel()
        assertEquals(listOf((PEN_IN or PEN_TIP or PEN_TABLET) to 10, (PEN_CANCEL or PEN_TABLET) to 10), states())
        records.clear()
        stream.cancel()
        assertTrue(records.isEmpty())
    }

    @Test fun removingThePenDeviceEndsItsStream() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f), device = 6)
        stream.deviceRemoved(8)
        assertTrue(stream.inProximity)
        stream.deviceRemoved(6)
        assertEquals(listOf(PEN_IN to 10, PEN_CANCEL to 10), states())
    }

    @Test fun removingTheBluetoothPenWhileItsButtonIsHeldReleasesRight() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f), device = 6)
        val down = key(KeyEvent.ACTION_DOWN, 15)
        stream.onKey(down)
        assertTrue(stream.holdsKey(down))
        stream.deviceRemoved(10)
        assertFalse(stream.holdsKey(key(KeyEvent.ACTION_UP, 18)))
        send(MotionEvent.ACTION_HOVER_MOVE, Sample(20, 110f), device = 6)
        stream.deviceRemoved(10)
        assertEquals(listOf(PEN_IN to 10, (PEN_IN or PEN_RIGHT) to 15, PEN_IN to 15, PEN_IN to 20), states())
        assertTrue(stream.inProximity)
    }

    @Test fun aPenNextToAFingerKeepsItsOwnStroke() {
        val finger = MotionEvent.TOOL_TYPE_FINGER
        val stylus = MotionEvent.TOOL_TYPE_STYLUS
        mixed(MotionEvent.ACTION_DOWN, 10, finger to 50f)
        assertTrue(records.isEmpty())
        mixed(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20, finger to 50f, stylus to 400f)
        mixed(MotionEvent.ACTION_MOVE, 30, finger to 60f, stylus to 410f)
        mixed(MotionEvent.ACTION_POINTER_UP, 40, finger to 60f, stylus to 420f)
        mixed(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50, finger to 60f, stylus to 430f)
        assertEquals(listOf((PEN_IN or PEN_TIP) to 20, (PEN_IN or PEN_TIP) to 30, (PEN_IN or PEN_TIP) to 40, PEN_IN to 50), states())
        assertEquals(listOf(400f, 410f, 420f, 430f).map(::x), records.map { it.x })
    }

    @Test fun positionsAreClampedToThePicture() {
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, -40f, y = 900f))
        assertEquals(0, records.last().x)
        assertEquals(InputSpace.HEIGHT * InputSpace.FIXED_ONE - 1, records.last().y)
    }

    @Test fun pensAreFoundByToolTypeFirstAndSourceSecond() {
        assertEquals(0, PenStream.penIndex(event(MotionEvent.ACTION_DOWN, listOf(Sample(0, 1f)), MotionEvent.TOOL_TYPE_STYLUS, source = InputDevice.SOURCE_MOUSE)))
        assertEquals(0, PenStream.penIndex(event(MotionEvent.ACTION_DOWN, listOf(Sample(0, 1f)), MotionEvent.TOOL_TYPE_ERASER, source = InputDevice.SOURCE_TOUCHSCREEN)))
        assertEquals(0, PenStream.penIndex(event(MotionEvent.ACTION_DOWN, listOf(Sample(0, 1f)), MotionEvent.TOOL_TYPE_UNKNOWN, source = InputDevice.SOURCE_STYLUS)))
        assertEquals(-1, PenStream.penIndex(event(MotionEvent.ACTION_DOWN, listOf(Sample(0, 1f)), MotionEvent.TOOL_TYPE_FINGER, source = InputDevice.SOURCE_STYLUS)))
        assertEquals(-1, PenStream.penIndex(event(MotionEvent.ACTION_DOWN, listOf(Sample(0, 1f)), MotionEvent.TOOL_TYPE_MOUSE, source = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS)))
        assertEquals(-1, PenStream.penIndex(event(MotionEvent.ACTION_DOWN, listOf(Sample(0, 1f)), MotionEvent.TOOL_TYPE_UNKNOWN, source = InputDevice.SOURCE_TOUCHSCREEN)))
    }

    @Test fun penButtonKeyboardsAreRecognisedByNameAndShape() {
        val keyboard = InputDevice.SOURCE_KEYBOARD
        assertTrue(PenStream.isPenKeyboard("OPPO AI Pen", keyboard, external = true, alphabetic = false))
        assertTrue(PenStream.isPenKeyboard("Xiaomi Smart STYLUS", keyboard, external = true, alphabetic = false))
        assertFalse(PenStream.isPenKeyboard("OPPO AI Pen", keyboard, external = false, alphabetic = false))
        assertFalse(PenStream.isPenKeyboard("OPPO AI Pen", keyboard or InputDevice.SOURCE_MOUSE, external = true, alphabetic = false))
        assertFalse(PenStream.isPenKeyboard("Pen Keyboard K380", keyboard, external = true, alphabetic = true))
        assertFalse(PenStream.isPenKeyboard("8BitDo Remote", keyboard, external = true, alphabetic = false))
        assertFalse(PenStream.isPenKeyboard(null, keyboard, external = true, alphabetic = false))
    }

    @Config(sdk = [34])
    @Test fun sessionStartAnnouncesAStylusDeviceFromItsMotionRanges() {
        val pen = InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS
        stream.announce(device(InputDevice.SOURCE_KEYBOARD, tilt = true))
        stream.announce(InputDeviceBuilder.newBuilder().setId(7).setSources(pen).build())
        assertTrue(capabilities.isEmpty())
        stream.announce(device(pen, tilt = true, distance = 1f))
        stream.announce(device(pen, tilt = true))
        assertEquals(listOf(PenStream.CAP_PRESSURE or PenStream.CAP_TILT), capabilities)
        stream.announce(device(pen, distance = 255f))
        send(MotionEvent.ACTION_HOVER_ENTER, Sample(10, 100f))
        assertEquals(listOf(PenStream.CAP_PRESSURE or PenStream.CAP_TILT,
            PenStream.CAP_PRESSURE or PenStream.CAP_TILT or PenStream.CAP_DISTANCE), capabilities)
    }

    @Test fun distanceCountsOnlyWhenItsRangeHasMoreThanHoverAndTouch() {
        assertEquals(PenStream.CAP_PRESSURE, PenStream.capabilities(tilt = false, distanceSpan = null))
        assertEquals(PenStream.CAP_PRESSURE or PenStream.CAP_TILT, PenStream.capabilities(tilt = true, distanceSpan = 1f))
        assertEquals(PenStream.CAP_PRESSURE or PenStream.CAP_DISTANCE, PenStream.capabilities(tilt = false, distanceSpan = 1.5f))
    }

    private fun device(sources: Int, tilt: Boolean = false, distance: Float? = null): InputDevice {
        val builder = InputDeviceBuilder.newBuilder().setId(7).setName("pen").setSources(sources)
            .addMotionRange(MotionEvent.AXIS_X, sources, 0f, 1000f, 0f, 0f, 0f)
            .addMotionRange(MotionEvent.AXIS_PRESSURE, sources, 0f, 1f, 0f, 0f, 0f)
        if (tilt) builder.addMotionRange(MotionEvent.AXIS_TILT, sources, 0f, 1.5f, 0f, 0f, 0f)
        if (distance != null) builder.addMotionRange(MotionEvent.AXIS_DISTANCE, sources, 0f, distance, 0f, 0f, 0f)
        return builder.build()
    }

    private fun states() = records.map { it.flags to it.time }
    private fun x(position: Float) = InputSpace.fixedX(position, 1000)
    private fun y(position: Float) = InputSpace.fixedY(position, 600)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun grace() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PenStream.EXIT_GRACE_MS))

    private fun key(action: Int, time: Long, repeat: Int = 0) =
        KeyEvent(time, time, action, KeyEvent.KEYCODE_UNKNOWN, repeat, 0, 10, 191, 0, InputDevice.SOURCE_KEYBOARD)

    private fun send(action: Int, vararg samples: Sample, tool: Int = MotionEvent.TOOL_TYPE_STYLUS, buttons: Int = 0,
        flags: Int = 0, tablet: Boolean = false, device: Int = 6) {
        val event = event(action, samples.toList(), tool, buttons, flags, device = device)
        try { stream.onMotion(event, 0, area, 1000, 600, tablet) } finally { event.recycle() }
    }

    private fun mixed(action: Int, time: Long, vararg pointers: Pair<Int, Float>) {
        val properties = Array(pointers.size) { MotionEvent.PointerProperties().apply { id = it; toolType = pointers[it].first } }
        val coords = Array(pointers.size) { MotionEvent.PointerCoords().apply { x = pointers[it].second; y = 300f; pressure = 0.5f } }
        val event = MotionEvent.obtain(0, time, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 6, 0,
            InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS, 0)
        try {
            val pen = PenStream.penIndex(event)
            if (pen >= 0) stream.onMotion(event, pen, area, 1000, 600, false)
        } finally { event.recycle() }
    }

    private fun event(action: Int, samples: List<Sample>, tool: Int, buttons: Int = 0, flags: Int = 0,
        source: Int = InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS, device: Int = 6): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        fun coords(sample: Sample) = arrayOf(MotionEvent.PointerCoords().apply {
            x = sample.x
            y = sample.y
            pressure = sample.pressure
            setAxisValue(MotionEvent.AXIS_TILT, sample.tilt)
            setAxisValue(MotionEvent.AXIS_ORIENTATION, sample.orientation)
        })
        val first = samples.first()
        val event = MotionEvent.obtain(0, first.time, action, 1, properties, coords(first), 0, buttons, 1f, 1f, device, 0, source, flags)
        for (sample in samples.drop(1)) event.addBatch(sample.time, coords(sample), 0)
        return event
    }
}
