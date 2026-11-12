package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.input.PenStream.Companion.PEN_CANCEL
import com.droiddeck.launcher.input.PenStream.Companion.PEN_IN
import com.droiddeck.launcher.input.PenStream.Companion.PEN_TABLET
import com.droiddeck.launcher.input.PenStream.Companion.PEN_TIP
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [SessionPenRoutingTest.CompositorShadow::class])
class SessionPenRoutingTest {
    private lateinit var activity: SessionActivity
    private val previousSize = SessionState.outputSize
    private val previousMode = SessionState.mode
    private val previousSteamUi = SessionState.steamUi
    private val pen = MotionEvent.TOOL_TYPE_STYLUS
    private val finger = MotionEvent.TOOL_TYPE_FINGER
    private val stylusScreen = InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS

    @Before fun setUp() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        activity.window.decorView
        activity.getSharedPreferences("session", 0).edit().clear().commit()
        field("surfaceView", SurfaceView(activity).apply { layout(0, 0, 1000, 600) })
        SessionState.outputSize = 1000 to 600
        SessionState.mode = SessionService.MODE_DESKTOP
        SessionState.steamUi = null
        CompositorShadow.clear()
    }

    @After fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        SessionState.outputSize = previousSize
        SessionState.mode = previousMode
        SessionState.steamUi = previousSteamUi
    }

    @Test fun penHoverMovesThePointerInAutoAndMouse() {
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertEquals(listOf(PenStream.CAP_PRESSURE), CompositorShadow.capabilities)
        assertEquals(listOf(Pen(PEN_IN or PEN_TABLET, InputSpace.fixedX(500f, 1000), InputSpace.fixedY(300f, 600), 10)), CompositorShadow.pens)
        assertTrue(state<Boolean>("cursorVisible").value)
        assertTrue(CompositorShadow.pointers.isEmpty())

        SessionPrefs.setPenMode(activity, SessionPrefs.PEN_MOUSE)
        invoke("readPrefs")
        assertEquals(PEN_CANCEL or PEN_TABLET, CompositorShadow.pens.last().flags)
        CompositorShadow.pens.clear()
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 600f)))
        assertEquals(listOf(Pen(PEN_IN, InputSpace.fixedX(600f, 1000), InputSpace.fixedY(300f, 600), 20)), CompositorShadow.pens)
        assertEquals(listOf(PenStream.CAP_PRESSURE), CompositorShadow.capabilities)
    }

    @Test fun aTabletPenHidesTheOverlayWithoutMovingItAndRestoresItWhereThePenLeft() {
        CompositorShadow.route = PenCursorPolicy.ROUTE_TABLET
        showCursor(100f, 200f, fromMouse = true)
        assertTrue(state<Boolean>("cursorVisible").value)
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_ENTER, 10, pen to 500f)))
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 520f)))
        assertFalse(state<Boolean>("cursorVisible").value)
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "mouseOwnsCursor"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_EXIT, 30, pen to 520f)))
        assertFalse(state<Boolean>("cursorVisible").value)
        assertEquals(Offset(100f, 200f), state<Offset>("cursorPos").value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PenStream.EXIT_GRACE_MS))
        assertEquals(listOf(PEN_IN or PEN_TABLET, PEN_IN or PEN_TABLET, PEN_TABLET), CompositorShadow.pens.map { it.flags })
        assertTrue(state<Boolean>("cursorVisible").value)
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "mouseOwnsCursor"))
        assertEquals(Offset(520f, 300f), state<Offset>("cursorPos").value)
    }

    @Test fun aTabletPenLeavesAHiddenOverlayHiddenAndACancelRestoresItToo() {
        CompositorShadow.route = PenCursorPolicy.ROUTE_TABLET
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f))
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_EXIT, 20, pen to 500f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PenStream.EXIT_GRACE_MS))
        assertFalse(state<Boolean>("cursorVisible").value)
        overlay(Offset(100f, 200f), visible = true, mouseOwns = false)
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 30, pen to 500f))
        assertFalse(state<Boolean>("cursorVisible").value)
        invoke("cancelPen")
        assertEquals(PEN_CANCEL or PEN_TABLET, CompositorShadow.pens.last().flags)
        assertTrue(state<Boolean>("cursorVisible").value)
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "mouseOwnsCursor"))
    }

    @Test fun aMouseRemovedOrMovedWhileATabletPenHoversDecidesWhetherTheOverlayShowsWhereThePenLeft() {
        CompositorShadow.route = PenCursorPolicy.ROUTE_TABLET
        fun proximity(time: Long, meanwhile: () -> Unit = {}) {
            activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, time, pen to 500f))
            assertFalse(state<Boolean>("cursorVisible").value)
            meanwhile()
            activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_EXIT, time + 10, pen to 500f))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PenStream.EXIT_GRACE_MS))
        }
        showCursor(100f, 200f, fromMouse = true)
        proximity(10) { invoke("dropMouseCursor") }
        assertFalse(state<Boolean>("cursorVisible").value)
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "mouseOwnsCursor"))
        proximity(10_000)
        assertFalse(state<Boolean>("cursorVisible").value)
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "mouseOwnsCursor"))
        proximity(20_000) { showCursor(300f, 200f, fromMouse = true) }
        assertTrue(state<Boolean>("cursorVisible").value)
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "mouseOwnsCursor"))
        assertEquals(Offset(500f, 300f), state<Offset>("cursorPos").value)
    }

    @Test fun aPenOnThePointerRouteMovesTheOverlayAndLeavesItWhereThePenWas() {
        for ((step, case) in listOf(SessionPrefs.PEN_MOUSE to PenCursorPolicy.ROUTE_TABLET,
            SessionPrefs.PEN_AUTO to PenCursorPolicy.ROUTE_POINTER).withIndex()) {
            SessionPrefs.setPenMode(activity, case.first)
            invoke("readPrefs")
            CompositorShadow.route = case.second
            overlay(Offset(100f, 200f), visible = false, mouseOwns = false)
            val time = 10_000L * (step + 1)
            activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, time, pen to 520f))
            assertTrue("$case", state<Boolean>("cursorVisible").value)
            assertTrue("$case", ReflectionHelpers.getField<Boolean>(activity, "mouseOwnsCursor"))
            assertEquals("$case", Offset(520f, 300f), state<Offset>("cursorPos").value)
            activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_EXIT, time + 10, pen to 530f))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PenStream.EXIT_GRACE_MS))
            assertEquals("$case", 0, CompositorShadow.pens.last().flags and PEN_IN)
            assertTrue("$case", state<Boolean>("cursorVisible").value)
            assertEquals("$case", Offset(530f, 300f), state<Offset>("cursorPos").value)
        }
    }

    @Test fun aPenThatCrossesFromTheTabletToThePointerRouteKeepsThePointerCursor() {
        CompositorShadow.route = PenCursorPolicy.ROUTE_TABLET
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f))
        assertFalse(state<Boolean>("cursorVisible").value)
        CompositorShadow.route = PenCursorPolicy.ROUTE_POINTER
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 520f))
        assertTrue(state<Boolean>("cursorVisible").value)
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_EXIT, 30, pen to 520f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PenStream.EXIT_GRACE_MS))
        assertTrue(state<Boolean>("cursorVisible").value)
        assertEquals(Offset(520f, 300f), state<Offset>("cursorPos").value)
    }

    @Test fun penHoverInSteamIsAMouseWithoutTheTabletWish() {
        steamDirect(SessionPrefs.PEN_AUTO)
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertEquals(listOf(PEN_IN), CompositorShadow.pens.map { it.flags })
    }

    @Test fun penContactInMouseModeHoldsTheTipUntilItLifts() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, pen to 500f)))
        assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_MOVE, 20, pen to 520f)))
        assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_UP, 30, pen to 520f)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PenStream.EXIT_GRACE_MS))
        assertEquals(listOf(PEN_IN or PEN_TIP, PEN_IN or PEN_TIP, PEN_IN, 0), CompositorShadow.pens.map { it.flags })
        assertTrue(CompositorShadow.touches.isEmpty())
    }

    @Test fun anExternalTabletTipReachesThePenInsteadOfTheMouse() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        val source = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS
        assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, pen to 500f, source = source)))
        assertEquals(listOf(PEN_IN or PEN_TIP), CompositorShadow.pens.map { it.flags })
        assertTrue(CompositorShadow.pointers.isEmpty())
    }

    @Test fun penContactInTouchModeTakesTheFingerRouteAsBefore() {
        steamDirect(SessionPrefs.PEN_TOUCH)
        assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, pen to 500f)))
        assertEquals(listOf(Touch(0, 0, InputSpace.x(500f, 1000), InputSpace.y(300f, 600))), CompositorShadow.touches)
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 600f))
        assertTrue(CompositorShadow.pens.isEmpty())
        assertTrue(CompositorShadow.pointers.isEmpty())
    }

    @Test fun fingersKeepTheirRouteWhatEverThePenMode() {
        for (mode in listOf(SessionPrefs.PEN_AUTO, SessionPrefs.PEN_MOUSE, SessionPrefs.PEN_TOUCH)) {
            steamDirect(mode)
            CompositorShadow.clear()
            assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, finger to 500f, source = InputDevice.SOURCE_TOUCHSCREEN)))
            assertEquals(listOf(Touch(0, 0, InputSpace.x(500f, 1000), InputSpace.y(300f, 600))), CompositorShadow.touches)
            assertTrue(CompositorShadow.pens.isEmpty())
            activity.onTouchEvent(event(MotionEvent.ACTION_CANCEL, 20, finger to 500f, source = InputDevice.SOURCE_TOUCHSCREEN))
        }
    }

    @Test fun aFingerAndAPenInOneEventAreSplitByToolType() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, finger to 100f))
        activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20, finger to 110f, pen to 500f))
        assertEquals(listOf(PEN_IN or PEN_TIP), CompositorShadow.pens.map { it.flags })
        assertEquals(InputSpace.fixedX(500f, 1000), CompositorShadow.pens.last().x)
        assertTrue(CompositorShadow.touches.isNotEmpty())
        assertTrue(CompositorShadow.touches.all { it.id == 0 && it.x < InputSpace.x(200f, 1000) })
    }

    @Test fun theLowerScreenGameTakesPenHoverAndTabletContact() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        fun hover(event: MotionEvent) = ReflectionHelpers.callInstanceMethod<Boolean>(activity, "onBottomGameHover",
            ReflectionHelpers.ClassParameter.from(MotionEvent::class.java, event))
        fun touch(event: MotionEvent) = ReflectionHelpers.callInstanceMethod<Boolean>(activity, "onBottomGameTouch",
            ReflectionHelpers.ClassParameter.from(MotionEvent::class.java, event))
        assertTrue(hover(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertTrue(touch(event(MotionEvent.ACTION_DOWN, 20, pen to 500f, source = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS)))
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_TIP), CompositorShadow.pens.map { it.flags })
        SessionPrefs.setPenMode(activity, SessionPrefs.PEN_TOUCH)
        invoke("readPrefs")
        assertFalse(hover(event(MotionEvent.ACTION_HOVER_MOVE, 30, pen to 500f)))
        assertFalse(hover(event(MotionEvent.ACTION_HOVER_MOVE, 40, finger to 500f, source = InputDevice.SOURCE_MOUSE)))
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_TIP, PEN_CANCEL), CompositorShadow.pens.map { it.flags })
    }

    @Test fun glassesAndAppUiDropPenHover() {
        state<Boolean>("glassesOutputActive").value = true
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f))
        state<Boolean>("glassesOutputActive").value = false
        state<Boolean>("achievementsOpen").value = true
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 500f))
        assertTrue(CompositorShadow.pens.isEmpty())
        assertTrue(CompositorShadow.pointers.isEmpty())
    }

    @Test fun openingAppUiEndsAHoveringPen() {
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f))
        state<Boolean>("achievementsOpen").value = true
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 510f))
        assertEquals(listOf(PEN_IN or PEN_TABLET, PEN_CANCEL or PEN_TABLET), CompositorShadow.pens.map { it.flags })
    }

    @Test fun losingFocusEndsThePen() {
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f))
        invoke("releaseHeldGuestInput")
        invoke("cancelTouchGestures")
        assertEquals(listOf(PEN_IN or PEN_TABLET, PEN_CANCEL or PEN_TABLET), CompositorShadow.pens.map { it.flags })
    }

    @Test fun aHoveringPenLeavesGamescopeRelativeMouseOffForDirectTouch() {
        steamDirect(SessionPrefs.PEN_AUTO)
        fun mode() = ReflectionHelpers.callInstanceMethod<Int>(activity, "gamescopeRelativeMouseMode",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, TouchGestureConfig.RELATIVE_MOUSE_AUTO))
        assertEquals(TouchGestureConfig.RELATIVE_MOUSE_OFF, mode())
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertEquals(listOf(PEN_IN), CompositorShadow.pens.map { it.flags })
        assertEquals(TouchGestureConfig.RELATIVE_MOUSE_OFF, mode())
    }

    @Test fun aFingerAndARelativePenOnTheTouchpadStayOneTwoPointerStream() {
        state<Boolean>("glassesOutputActive").value = true
        field("padMouseCheckedAt", android.os.SystemClock.uptimeMillis())
        for (penFirst in listOf(false, true)) {
            val pad = touchpad()
            val first = if (penFirst) pen to 500f else finger to 100f
            val second = if (penFirst) finger to 100f else pen to 500f
            fun both(step: Int) = arrayOf(first.first to first.second + 10f * step, second.first to second.second + 10f * step)
            activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10_000, first))
            activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10_010, *both(0)))
            for (step in 1..4) activity.onTouchEvent(event(MotionEvent.ACTION_MOVE, 10_010L + 10 * step, *both(step)))
            activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10_060, *both(4)))
            activity.onTouchEvent(event(MotionEvent.ACTION_UP, 10_070, first.first to first.second + 40f))
            assertTrue("penFirst=$penFirst", pad.scrolls.isNotEmpty())
            assertTrue("penFirst=$penFirst ${pad.moves}", pad.moves.all { it in 450f..550f })
            assertTrue(CompositorShadow.pens.isEmpty())
        }
    }

    @Test fun aRelativePenStrokeThatAndroidCanceledDoesNotClick() {
        steamDirect(SessionPrefs.PEN_AUTO)
        state<Boolean>("glassesOutputActive").value = true
        val pad = touchpad()
        activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10_000, finger to 100f))
        activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10_010, finger to 100f, pen to 500f))
        activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10_050, finger to 100f, pen to 500f,
            flags = MotionEvent.FLAG_CANCELED))
        activity.onTouchEvent(event(MotionEvent.ACTION_UP, 10_060, finger to 100f))
        activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 20_000, pen to 500f))
        activity.onTouchEvent(event(MotionEvent.ACTION_UP, 20_050, pen to 500f, flags = MotionEvent.FLAG_CANCELED))
        assertTrue(pad.presses.isEmpty())
        assertTrue(CompositorShadow.touches.isNotEmpty())
        activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 30_000, pen to 500f))
        activity.onTouchEvent(event(MotionEvent.ACTION_UP, 30_050, pen to 500f))
        assertEquals(listOf(PointerGestures.BTN_LEFT), pad.presses)
        assertTrue(CompositorShadow.pens.isEmpty())
    }

    @Test fun theFirstPenEventRemembersThatThePhoneHasAPen() {
        invoke("readPrefs")
        assertFalse(state<Boolean>("penSeen").value)
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertTrue(state<Boolean>("penSeen").value)
        assertTrue(SessionPrefs.penSeen(activity))
    }

    @Config(sdk = [34])
    @Test fun aConnectedStylusDeviceCountsAsSeenBeforeAnyPenEvent() {
        invoke("readPrefs")
        assertFalse(state<Boolean>("penSeen").value)
        shadowOf(activity.getSystemService(InputManager::class.java)).addInputDevice(
            InputDeviceBuilder.newBuilder().setId(42).setName("digitizer").setSources(stylusScreen).build())
        invoke("readPrefs")
        assertTrue(state<Boolean>("penSeen").value)
        assertTrue(SessionPrefs.penSeen(activity))
    }

    @Config(sdk = [33])
    @Test fun beforeAndroid14ATouchscreenWithTheStylusBitWaitsForAPenEvent() {
        shadowOf(activity.getSystemService(InputManager::class.java)).addInputDevice(
            ShadowInputDevice.makeInputDeviceNamed("touchscreen").also {
                ReflectionHelpers.setField(it, "mId", 42)
                ReflectionHelpers.setField(it, "mSources", stylusScreen)
            })
        assertEquals(listOf(42), InputDevice.getDeviceIds().toList())
        invoke("readPrefs")
        assertFalse(state<Boolean>("penSeen").value)
        assertFalse(SessionPrefs.penSeen(activity))
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertTrue(state<Boolean>("penSeen").value)
    }

    @Test fun changingAnyPenSettingEndsThePenAndRereadingTheSameSettingsDoesNot() {
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        invoke("readPrefs")
        assertEquals(PEN_IN or PEN_TABLET, CompositorShadow.pens.last().flags)
        var time = 20L
        for ((key, value) in listOf("penPalm" to SessionPrefs.PEN_PALM_OFF, "penButton" to SessionPrefs.PEN_BUTTON_MIDDLE,
                "penPressure" to SessionPrefs.PEN_PRESSURE_FIRM)) {
            assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, time, pen to 500f)))
            time += 10
            SessionPrefs.setPenSetting(activity, key, value)
            invoke("readPrefs")
            assertEquals(key, PEN_CANCEL or PEN_TABLET, CompositorShadow.pens.last().flags)
        }
        assertEquals(SessionPrefs.Pen(palm = SessionPrefs.PEN_PALM_OFF, button = SessionPrefs.PEN_BUTTON_MIDDLE,
            pressure = SessionPrefs.PEN_PRESSURE_FIRM), state<SessionPrefs.Pen>("penSettings").value)
    }

    @Test fun aStylusDeviceIsNotAHardwareMouse() {
        fun device(sources: Int) = ShadowInputDevice.makeInputDeviceNamed("device").also {
            ReflectionHelpers.setField(it, "mId", 7)
            ReflectionHelpers.setField(it, "mSources", sources)
        }
        fun mouse(sources: Int) = ReflectionHelpers.callInstanceMethod<Boolean>(activity, "isHardwareMouse",
            ReflectionHelpers.ClassParameter.from(InputDevice::class.java, device(sources)))
        assertTrue(mouse(InputDevice.SOURCE_MOUSE))
        assertFalse(mouse(InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS))
        assertFalse(mouse(InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_KEYBOARD))
        assertFalse(mouse(stylusScreen))
    }

    private fun steamDirect(mode: String) {
        SessionPrefs.setPenMode(activity, mode)
        invoke("readPrefs")
        SessionState.mode = SessionService.MODE_STEAM
        field("routedTouchMode", SessionPrefs.TOUCH_DIRECT)
        field("padMouseCheckedAt", android.os.SystemClock.uptimeMillis())
        field("threeFingerTap", ThreeFingerTap(8f))
        field("directTouchHold", DirectTouchHold(8f, object : PointerGestures.Listener {
            override fun onMove(x: Float, y: Float) {}
            override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {}
            override fun onWheel(steps: Int) {}
            override fun onLongPress() {}
        }, ReflectionHelpers.getField(activity, "directTouchSink")).also {
            it.configure(TouchGestureConfig(directHoldBehavior = TouchGestureConfig.MOUSE_BEHAVIOR_NONE))
        })
    }

    private fun event(action: Int, time: Long, vararg pointers: Pair<Int, Float>, source: Int = stylusScreen, flags: Int = 0): MotionEvent {
        val properties = Array(pointers.size) { MotionEvent.PointerProperties().apply { id = it; toolType = pointers[it].first } }
        val coords = Array(pointers.size) { MotionEvent.PointerCoords().apply { x = pointers[it].second; y = 300f; pressure = 0.5f } }
        return MotionEvent.obtain(0, time, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 6, 0, source, flags)
    }

    private class Pad : PointerGestures.Listener {
        val moves = mutableListOf<Float>()
        val scrolls = mutableListOf<Float>()
        val presses = mutableListOf<Int>()
        override fun onMove(x: Float, y: Float) { moves += x }
        override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) { if (pressed) presses += button }
        override fun onWheel(steps: Int) {}
        override fun onLongPress() {}
        override fun onScroll(dx: Float, dy: Float) { scrolls += dx }
    }

    private fun touchpad() = Pad().also { field("touchpad", TouchpadGestures(8f, it)) }

    private fun showCursor(x: Float, y: Float, fromMouse: Boolean) = ReflectionHelpers.callInstanceMethod<Any?>(activity, "showCursor",
        ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, x),
        ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, y),
        ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, fromMouse))

    private fun overlay(position: Offset, visible: Boolean, mouseOwns: Boolean) {
        state<Offset>("cursorPos").value = position
        state<Boolean>("cursorVisible").value = visible
        field("mouseOwnsCursor", mouseOwns)
    }

    private fun field(name: String, value: Any) = ReflectionHelpers.setField(activity, name, value)
    private fun invoke(name: String) = ReflectionHelpers.callInstanceMethod<Any?>(activity, name)
    private fun <T> state(name: String): MutableState<T> = ReflectionHelpers.getField(activity, "$name\$delegate")

    data class Pen(val flags: Int, val x: Int, val y: Int, val time: Int)
    data class Touch(val action: Int, val id: Int, val x: Int, val y: Int)

    @Implements(value = WaylandCompositor::class, isInAndroidSdk = false)
    class CompositorShadow {
        companion object {
            val pens = mutableListOf<Pen>()
            val capabilities = mutableListOf<Int>()
            val touches = mutableListOf<Touch>()
            val pointers = mutableListOf<Triple<Int, Int, Int>>()
            var route = PenCursorPolicy.ROUTE_POINTER
            fun clear() { pens.clear(); capabilities.clear(); touches.clear(); pointers.clear(); route = PenCursorPolicy.ROUTE_POINTER }
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun nativeSendPen(flags: Int, x: Int, y: Int, pressure: Int, distance: Int, tiltX: Int, tiltY: Int, timeMs: Int) {
                pens += Pen(flags, x, y, timeMs)
            }
            @JvmStatic @Implementation fun nativeSendPenCapabilities(capabilities: Int) { this.capabilities += capabilities }
            @JvmStatic @Implementation fun nativeSendTouch(action: Int, id: Int, x: Int, y: Int) { touches += Touch(action, id, x, y) }
            @JvmStatic @Implementation fun nativeSendPointer(action: Int, x: Int, y: Int) { pointers += Triple(action, x, y) }
            @JvmStatic @Implementation fun nativeSendSceneInput(type: Int, code: Int, value: Int) {}
            @JvmStatic @Implementation fun nativeSendKey(code: Int, value: Int) {}
            @JvmStatic @Implementation fun nativeFocusTakesTouch(): Boolean = false
            @JvmStatic @Implementation fun nativePenRoute(): Int = route
        }
    }
}
