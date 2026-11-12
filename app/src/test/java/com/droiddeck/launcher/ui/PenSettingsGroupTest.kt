package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w800dp-h600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PenSettingsGroupTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val rows = listOf("setting-pen", "setting-penPalm", "setting-penButton", "setting-penPressure")

    @Test fun theDrawerHasNoPenGroupUntilAPenIsKnown() {
        compose.setContent { DroidDeckTheme { SessionDrawer(true, SessionDrawerPage.CONTROLLER, false, {}, actions(pen = null)) } }
        compose.onNodeWithTag("setting-touch").assertExists()
        rows.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test fun theDesktopDrawerShowsEveryPenRowWithAutoResolvedAndSavesPicks() {
        val picks = mutableListOf<Pair<String, String>>()
        compose.setContent {
            DroidDeckTheme {
                SessionDrawer(true, SessionDrawerPage.CONTROLLER, false, {},
                    actions(pen = SessionPrefs.Pen(), penAuto = context.getString(R.string.session_pen_auto_tablet)) { k, v -> picks += k to v })
            }
        }
        rows.forEach { compose.onNodeWithTag(it).assertExists() }
        compose.onNodeWithText(context.getString(R.string.drawer_touch_auto, context.getString(R.string.session_pen_auto_tablet)))
            .assertExists()
        compose.onNodeWithTag("setting-penPalm").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.mode_pen_palm_scroll)).performScrollTo().performClick()
        compose.onNodeWithTag("setting-penButton").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.mode_pen_button_eraser)).performScrollTo().performClick()
        compose.onNodeWithTag("setting-penPressure").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.mode_pen_pressure_firm)).performScrollTo().performClick()
        compose.onNodeWithTag("setting-pen").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.mode_pen_mouse)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("penPalm" to SessionPrefs.PEN_PALM_SCROLL, "penButton" to SessionPrefs.PEN_BUTTON_ERASER,
                "penPressure" to SessionPrefs.PEN_PRESSURE_FIRM, "pen" to SessionPrefs.PEN_MOUSE), picks)
        }
    }

    @Test fun rowsThatCannotApplyAreHidden() {
        val pen = mutableStateOf(SessionPrefs.Pen())
        val desktop = mutableStateOf(false)
        compose.setContent {
            DroidDeckTheme { Column { PenSettingsGroup(rememberMenuHost(), pen.value, "Auto", desktop.value) { _, _ -> } } }
        }
        compose.onNodeWithTag("setting-penButton").assertExists()
        compose.onNodeWithTag("setting-penPressure").assertDoesNotExist()
        compose.runOnIdle { desktop.value = true; pen.value = SessionPrefs.Pen(mode = SessionPrefs.PEN_MOUSE) }
        compose.onNodeWithTag("setting-penButton").assertExists()
        compose.onNodeWithTag("setting-penPressure").assertDoesNotExist()
        compose.runOnIdle { pen.value = SessionPrefs.Pen(mode = SessionPrefs.PEN_TOUCH) }
        compose.onNodeWithTag("setting-penPalm").assertExists()
        compose.onNodeWithTag("setting-penButton").assertDoesNotExist()
        compose.onNodeWithTag("setting-penPressure").assertDoesNotExist()
    }

    @Test fun theControlsTabShowsThePenGroupAfterTheTouchRowsOnlyOnceAPenIsKnown() {
        val pen = mutableStateOf<SessionPrefs.Pen?>(null)
        val picks = mutableListOf<Pair<String, String>>()
        compose.setContent {
            DroidDeckTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModeSheetRows(ModeSettings(mode = SessionService.MODE_DESKTOP, resolution = "auto", hdr = false, hdrReason = null,
                        touchMode = "auto", pen = pen.value, suspendPolicy = "auto", oscMode = "auto", directAudio = null, mic = null),
                        ModeSettingsActions(onResolution = {}, onHdr = {}, onTouch = {}, onPen = { k, v -> picks += k to v },
                            onSuspendPolicy = {}, onOsc = {}, onInputSettings = {}, onDirectAudio = {}, onMic = {}, onDismiss = {}),
                        SheetTab.CONTROLS)
                }
            }
        }
        compose.onNodeWithTag("setting-touch").assertExists()
        rows.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.runOnIdle { pen.value = SessionPrefs.Pen() }
        rows.forEach { compose.onNodeWithTag(it).assertExists() }
        assertTrue(compose.onNodeWithTag("setting-touch").fetchSemanticsNode().positionInRoot.y <
            compose.onNodeWithTag("setting-pen").fetchSemanticsNode().positionInRoot.y)
        compose.onNodeWithTag("setting-penPalm").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.mode_pen_palm_scroll)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("penPalm" to SessionPrefs.PEN_PALM_SCROLL), picks) }
    }

    private fun actions(pen: SessionPrefs.Pen?, penAuto: String = "", onPen: (String, String) -> Unit = { _, _ -> }) =
        DrawerActions(steam = false, desktop = true, isHomeApp = false, androidApps = emptyList(), hudOn = false,
            frameGen = FrameGen.Mode.OFF, lossless = Lossless.State.NONE, oscMode = "never",
            suspendPolicy = "auto", backActionsInverted = false,
            touchMode = "auto", touchAuto = "touchpad", pen = pen, penAuto = penAuto, onPen = onPen, fexPreset = "default",
            secondScreenMode = SecondScreenMode.NONE, secondScreenDisplays = emptyList(), selectedSecondScreenDisplay = 1,
            onHud = {}, onFrameGenPick = {}, onImportLossless = {}, onKeyboard = {}, onHardwareKeyboard = {},
            onSteamMenu = null, onQam = null, onOsc = {}, onSuspendPolicy = {}, onBackActionsInverted = {},
            onTouch = {}, onFexPreset = {}, onSecondScreenMode = {}, onSecondScreenDisplay = {},
            onLaunchAndroidApp = { _, _ -> }, onBackground = {}, onShareLogs = {}, onStop = {}, onClose = {})
}
