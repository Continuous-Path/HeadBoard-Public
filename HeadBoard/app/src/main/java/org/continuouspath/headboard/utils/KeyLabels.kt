package org.continuouspath.headboard.utils

import android.view.KeyEvent

/**
 * Human-readable labels for captured keycodes. Cascade (modeled on Switch Access / JustType):
 * friendly names for common switch hardware -> printable key label -> humanized
 * KeyEvent.keyCodeToString fallback (never raw "KEYCODE_BUTTON_L1").
 */
object KeyLabels {

    private val FRIENDLY = mapOf(
        KeyEvent.KEYCODE_1 to "Switch 1",
        KeyEvent.KEYCODE_2 to "Switch 2",
        KeyEvent.KEYCODE_3 to "Switch 3",
        KeyEvent.KEYCODE_NUMPAD_1 to "Numpad 1",
        KeyEvent.KEYCODE_NUMPAD_2 to "Numpad 2",
        KeyEvent.KEYCODE_NUMPAD_3 to "Numpad 3",
        KeyEvent.KEYCODE_SPACE to "Space",
        KeyEvent.KEYCODE_ENTER to "Enter",
        KeyEvent.KEYCODE_NUMPAD_ENTER to "Numpad enter",
        KeyEvent.KEYCODE_TAB to "Tab",
        KeyEvent.KEYCODE_DEL to "Backspace",
        KeyEvent.KEYCODE_BACK to "Back",
        KeyEvent.KEYCODE_VOLUME_UP to "Volume up",
        KeyEvent.KEYCODE_VOLUME_DOWN to "Volume down",
        KeyEvent.KEYCODE_DPAD_CENTER to "D-pad center",
        KeyEvent.KEYCODE_DPAD_UP to "D-pad up",
        KeyEvent.KEYCODE_DPAD_DOWN to "D-pad down",
        KeyEvent.KEYCODE_DPAD_LEFT to "D-pad left",
        KeyEvent.KEYCODE_DPAD_RIGHT to "D-pad right",
        KeyEvent.KEYCODE_HEADSETHOOK to "Headset button",
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Play/pause",
        KeyEvent.KEYCODE_BUTTON_A to "Button A",
        KeyEvent.KEYCODE_BUTTON_B to "Button B",
        KeyEvent.KEYCODE_BUTTON_C to "Button C",
        KeyEvent.KEYCODE_BUTTON_X to "Button X",
        KeyEvent.KEYCODE_BUTTON_Y to "Button Y",
        KeyEvent.KEYCODE_BUTTON_Z to "Button Z",
        KeyEvent.KEYCODE_BUTTON_L1 to "Button L1",
        KeyEvent.KEYCODE_BUTTON_R1 to "Button R1",
        KeyEvent.KEYCODE_BUTTON_L2 to "Button L2",
        KeyEvent.KEYCODE_BUTTON_R2 to "Button R2",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "Left stick button",
        KeyEvent.KEYCODE_BUTTON_THUMBR to "Right stick button",
        KeyEvent.KEYCODE_BUTTON_START to "Start button",
        KeyEvent.KEYCODE_BUTTON_SELECT to "Select button",
        KeyEvent.KEYCODE_BUTTON_MODE to "Mode button",
    )

    @JvmStatic
    fun labelFor(keyCode: Int): String {
        FRIENDLY[keyCode]?.let { return it }

        val display = KeyEvent(KeyEvent.ACTION_DOWN, keyCode).displayLabel
        if (display.code != 0 && !display.isWhitespace()) return display.toString()

        return KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    }
}
