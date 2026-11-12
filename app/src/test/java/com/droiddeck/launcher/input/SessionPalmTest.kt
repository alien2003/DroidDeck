package com.droiddeck.launcher.input

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import com.droiddeck.launcher.SessionActivity
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [SessionPalmTest.CompositorShadow::class])
class SessionPalmTest {
    private lateinit var activity: SessionActivity
    private val previousSize = SessionState.outputSize
    private val previousMode = SessionState.mode
    private val previousSteamUi = SessionState.steamUi
    private val finger = MotionEvent.TOOL_TYPE_FINGER
    private val pen = MotionEvent.TOOL_TYPE_STYLUS
    private val buttons = mutableListOf<Pair<Int, Boolean>>()
    private val scrolls = mutableListOf<Float>()
    private var t0 = 0L

    @Before fun setUp() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        activity.window.decorView
        activity.getSharedPreferences("session", 0).edit().clear().commit()
        field("surfaceView", SurfaceView(activity).apply { layout(0, 0, 1000, 600) })
        SessionState.outputSize = 1000 to 600
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.steamUi = null
        field("padMouseCheckedAt", SystemClock.uptimeMillis() + 60_000)
        field("threeFingerTap", ThreeFingerTap(8f))
        field("directTouchHold", DirectTouchHold(8f, pointer(), ReflectionHelpers.getField(activity, "directTouchSink")).also {
            it.configure(TouchGestureConfig(directHoldBehavior = TouchGestureConfig.MOUSE_BEHAVIOR_NONE))
        })
        field("touchpad", TouchpadGestures(8f, pointer()))
        field("scrollTouch", ScrollTouchGestures(8f, 50f, object : ScrollTouchGestures.Listener {
            override fun onMove(x: Float, y: Float) {}
            override fun onButton(button: Int, pressed: Boolean) { buttons += button to pressed }
            override fun onScroll(dx: Float, dy: Float) { scrolls += dy }
            override fun onLongPress() {}
        }))
        field("routedTouchMode", SessionPrefs.TOUCH_DIRECT)
        invoke("readPrefs")
        CompositorShadow.clear()
        t0 = SystemClock.uptimeMillis()
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<ScrollTouchGestures>(activity, "scrollTouch").cancel()
        shadowOf(Looper.getMainLooper()).idle()
        SessionState.outputSize = previousSize
        SessionState.mode = previousMode
        SessionState.steamUi = previousSteamUi
    }

    @Test fun fingersAreIgnoredWhileThePenHoversOnTheDirectAndTouchpadRoutes() {
        for ((step, mode) in listOf(SessionPrefs.TOUCH_DIRECT, SessionPrefs.TOUCH_PAD).withIndex()) {
            field("routedTouchMode", mode)
            val at = t0 + step * 10_000L
            hover(MotionEvent.ACTION_HOVER_MOVE, at, 500f)
            tap(at + 10, 100f)
            tap(at + 1_000, 100f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(mode, emptyList<String>(), CompositorShadow.log.filter { it.startsWith("touch") })
            assertTrue(mode, buttons.isEmpty())
            hover(MotionEvent.ACTION_HOVER_EXIT, at + 1_100, 500f)
        }
    }

    @Test fun aFingerAlreadyDownIsCancelledBeforeThePenArrives() {
        touch(MotionEvent.ACTION_DOWN, t0, finger to 100f)
        hover(MotionEvent.ACTION_HOVER_ENTER, t0 + 50, 500f)
        touch(MotionEvent.ACTION_MOVE, t0 + 60, finger to 150f)
        touch(MotionEvent.ACTION_UP, t0 + 70, finger to 150f)
        assertEquals(listOf("touch 0 0", "touch 3 -1", "pen ${PenStream.PEN_IN}"), CompositorShadow.log)
    }

    @Test fun aPenOnTheLowerScreenArmsTheGateToo() {
        val hovered = MotionEvent.obtain(0, t0, MotionEvent.ACTION_HOVER_MOVE, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = pen }),
            arrayOf(MotionEvent.PointerCoords().apply { x = 500f; y = 300f }), 0, 0, 1f, 1f, 6, 0, stylusScreen, 0)
        try {
            ReflectionHelpers.callInstanceMethod<Boolean>(activity, "onBottomGameHover",
                ReflectionHelpers.ClassParameter.from(MotionEvent::class.java, hovered))
        } finally { hovered.recycle() }
        tap(t0 + 10, 100f)
        assertTrue(CompositorShadow.log.none { it.startsWith("touch") })
    }

    @Test fun offNeverFiltersAndALifecycleCancelEndsTheGateWithItsGrace() {
        SessionPrefs.setPenSetting(activity, "penPalm", SessionPrefs.PEN_PALM_OFF)
        invoke("readPrefs")
        hover(MotionEvent.ACTION_HOVER_MOVE, t0, 500f)
        tap(t0 + 10, 100f)
        assertEquals(listOf("touch 0 0"), CompositorShadow.log.filter { it.startsWith("touch 0") })
        SessionPrefs.setPenSetting(activity, "penPalm", SessionPrefs.PEN_PALM_NEAR)
        invoke("readPrefs")
        CompositorShadow.clear()
        hover(MotionEvent.ACTION_HOVER_MOVE, t0 + 1_000, 500f)
        tap(t0 + 1_010, 100f)
        assertTrue(CompositorShadow.log.none { it.startsWith("touch") })
        invoke("cancelPen")
        val now = SystemClock.uptimeMillis()
        tap(now + 100, 100f)
        tap(now + PalmGate.GRACE_MS + 10, 100f)
        assertEquals(listOf("touch 0 0"), CompositorShadow.log.filter { it.startsWith("touch 0") })
    }

    @Test fun fingersOnlyScrollNeverClicksButScrollsUnlessThePenIsATouch() {
        SessionPrefs.setPenSetting(activity, "penPalm", SessionPrefs.PEN_PALM_SCROLL)
        invoke("readPrefs")
        tap(t0, 400f)
        touch(MotionEvent.ACTION_DOWN, t0 + 1_000, finger to 400f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TouchTiming.HOLD_MS + 100))
        touch(MotionEvent.ACTION_UP, t0 + 2_000, finger to 400f)
        touch(MotionEvent.ACTION_DOWN, t0 + 3_000, finger to 400f)
        touch(MotionEvent.ACTION_MOVE, t0 + 3_020, finger to 400f, y = 200f)
        touch(MotionEvent.ACTION_UP, t0 + 3_200, finger to 400f, y = 200f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertTrue("$buttons", buttons.isEmpty())
        assertTrue(CompositorShadow.log.none { it.startsWith("touch") })
        assertEquals(100f, scrolls.sum(), 0.01f)

        SessionPrefs.setPenMode(activity, SessionPrefs.PEN_TOUCH)
        invoke("readPrefs")
        tap(t0 + 5_000, 400f)
        assertEquals("touch 0 0", CompositorShadow.log.first())
    }

    private val stylusScreen = InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS

    private fun pointer() = object : PointerGestures.Listener {
        override fun onMove(x: Float, y: Float) {}
        override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) { buttons += button to pressed }
        override fun onWheel(steps: Int) {}
        override fun onLongPress() {}
    }

    private fun hover(action: Int, time: Long, x: Float) {
        val e = event(action, time, time, pen to x)
        try { activity.dispatchGenericMotionEvent(e) } finally { e.recycle() }
    }

    private var downTime = 0L

    private fun touch(action: Int, time: Long, vararg pointers: Pair<Int, Float>, y: Float = 300f) {
        if (action == MotionEvent.ACTION_DOWN) downTime = time
        val e = event(action, downTime, time, *pointers, y = y)
        try { activity.dispatchTouchEvent(e) } finally { e.recycle() }
    }

    private fun tap(time: Long, x: Float) {
        touch(MotionEvent.ACTION_DOWN, time, finger to x)
        touch(MotionEvent.ACTION_UP, time + 20, finger to x)
    }

    private fun event(action: Int, down: Long, time: Long, vararg pointers: Pair<Int, Float>, y: Float = 300f): MotionEvent {
        val properties = Array(pointers.size) { MotionEvent.PointerProperties().apply { id = it; toolType = pointers[it].first } }
        val coords = Array(pointers.size) { MotionEvent.PointerCoords().apply { x = pointers[it].second; this.y = y; pressure = 0.5f } }
        val source = if (pointers.any { it.first == pen }) stylusScreen else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, time, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 6, 0, source, 0)
    }

    private fun field(name: String, value: Any) = ReflectionHelpers.setField(activity, name, value)
    private fun invoke(name: String) = ReflectionHelpers.callInstanceMethod<Any?>(activity, name)

    @Implements(value = WaylandCompositor::class, isInAndroidSdk = false)
    class CompositorShadow {
        companion object {
            val log = mutableListOf<String>()
            fun clear() = log.clear()
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun nativeSendTouch(action: Int, id: Int, x: Int, y: Int) { log += "touch $action $id" }
            @JvmStatic @Implementation fun nativeSendPen(flags: Int, x: Int, y: Int, pressure: Int, distance: Int, tiltX: Int, tiltY: Int, timeMs: Int) {
                log += "pen $flags"
            }
            @JvmStatic @Implementation fun nativeSendPenCapabilities(capabilities: Int) {}
            @JvmStatic @Implementation fun nativeSendPointer(action: Int, x: Int, y: Int) {}
            @JvmStatic @Implementation fun nativeSendSceneInput(type: Int, code: Int, value: Int) {}
            @JvmStatic @Implementation fun nativeSendKey(code: Int, value: Int) {}
            @JvmStatic @Implementation fun nativeFocusTakesTouch(): Boolean = false
            @JvmStatic @Implementation fun nativePenRoute(): Int = PenCursorPolicy.ROUTE_POINTER
        }
    }
}
