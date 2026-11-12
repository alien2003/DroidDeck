package com.droiddeck.launcher.input

// A pen delivered as a tablet leaves the host pointer where it is and KWin draws the tool cursor in its own frame,
// so the overlay hides while the pen is near and, when the pen leaves, returns to its earlier state or to what other
// input made of it meanwhile.
class PenCursorPolicy {
    data class Overlay(val visible: Boolean, val mouseOwns: Boolean)

    private var before: Overlay? = null
    private var hidden = false

    fun followsPen(tablet: Boolean, overlay: Overlay): Boolean {
        if (before == null) before = overlay
        hidden = tablet
        return !tablet
    }

    fun changed(overlay: Overlay) {
        if (hidden) before = overlay
    }

    fun mouseRemoved() {
        if (before?.mouseOwns == true) changed(Overlay(visible = false, mouseOwns = false))
    }

    fun restoreOnLeave(): Overlay? {
        val restore = before.takeIf { hidden }
        before = null
        hidden = false
        return restore
    }

    companion object {
        const val ROUTE_POINTER = 1
        const val ROUTE_TABLET = 2

        fun tablet(wantsTablet: Boolean, compositorRoute: Int): Boolean = wantsTablet && compositorRoute == ROUTE_TABLET
    }
}
