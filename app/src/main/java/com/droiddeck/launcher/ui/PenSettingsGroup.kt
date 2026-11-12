package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs

@Composable
internal fun PenSettingsGroup(
    host: MenuHost, pen: SessionPrefs.Pen, auto: String, desktop: Boolean,
    track: (String) -> Modifier = { Modifier }, onPick: (key: String, value: String) -> Unit,
) {
    SettingsGroup(stringResource(R.string.mode_pen)) {
        ChoiceRow(host, "pen", stringResource(R.string.mode_pen), null,
            listOf(SessionPrefs.PEN_AUTO to auto,
                SessionPrefs.PEN_MOUSE to stringResource(R.string.mode_pen_mouse),
                SessionPrefs.PEN_TOUCH to stringResource(R.string.mode_pen_touch)),
            pen.mode, note = stringResource(R.string.mode_pen_note), chipModifier = track("pen")) { onPick("pen", it) }
        ChoiceRow(host, "penPalm", stringResource(R.string.mode_pen_palm), null,
            listOf(SessionPrefs.PEN_PALM_NEAR to stringResource(R.string.mode_pen_palm_near),
                SessionPrefs.PEN_PALM_SCROLL to stringResource(R.string.mode_pen_palm_scroll),
                SessionPrefs.PEN_PALM_OFF to stringResource(R.string.widgets_off)),
            pen.palm, note = stringResource(R.string.mode_pen_palm_note), chipModifier = track("penPalm")) { onPick("penPalm", it) }
        if (pen.mode != SessionPrefs.PEN_TOUCH) ChoiceRow(host, "penButton", stringResource(R.string.mode_pen_button), null,
            listOf(SessionPrefs.PEN_BUTTON_RIGHT to stringResource(R.string.gesture_right_click),
                SessionPrefs.PEN_BUTTON_MIDDLE to stringResource(R.string.gesture_middle_click),
                SessionPrefs.PEN_BUTTON_ERASER to stringResource(R.string.mode_pen_button_eraser),
                SessionPrefs.PEN_BUTTON_OFF to stringResource(R.string.widgets_off)),
            pen.button, chipModifier = track("penButton")) { onPick("penButton", it) }
        if (desktop && pen.mode == SessionPrefs.PEN_AUTO) ChoiceRow(host, "penPressure", stringResource(R.string.mode_pen_pressure), null,
            listOf(SessionPrefs.PEN_PRESSURE_SOFT to stringResource(R.string.mode_pen_pressure_soft),
                SessionPrefs.PEN_PRESSURE_NORMAL to stringResource(R.string.mode_pen_pressure_normal),
                SessionPrefs.PEN_PRESSURE_FIRM to stringResource(R.string.mode_pen_pressure_firm)),
            pen.pressure, note = stringResource(R.string.mode_pen_pressure_note), chipModifier = track("penPressure")) { onPick("penPressure", it) }
    }
}
