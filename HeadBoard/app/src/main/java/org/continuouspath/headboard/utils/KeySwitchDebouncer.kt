package org.continuouspath.headboard.utils

/**
 * Debounce for external switch key events. Pure logic, unit-tested.
 *
 * Rules:
 *  - OS auto-repeats (repeatCount > 0) of a held switch are never dispatched.
 *  - A DOWN arriving within [debounceMs] of the last accepted UP of the same keycode is a
 *    bounce: it and its matching UP are both suppressed. Suppressing the UP too keeps
 *    DOWN/UP strictly paired downstream — CONTINUOUS_TOUCH holds depend on isStartingEvent
 *    edges never desyncing.
 */
class KeySwitchDebouncer(private val debounceMs: Long = DEFAULT_DEBOUNCE_MS) {

    private val lastAcceptedUpMs = HashMap<Int, Long>()
    private val suppressedPresses = HashSet<Int>()

    /** Should this edge be dispatched? Call for every DOWN and UP of a bound keycode. */
    fun shouldDispatch(keyCode: Int, isDown: Boolean, repeatCount: Int, eventTimeMs: Long): Boolean {
        if (repeatCount > 0) return false

        if (isDown) {
            val lastUp = lastAcceptedUpMs[keyCode]
            if (lastUp != null && eventTimeMs - lastUp < debounceMs) {
                suppressedPresses.add(keyCode)
                return false
            }
            suppressedPresses.remove(keyCode)
            return true
        }

        if (suppressedPresses.remove(keyCode)) return false
        lastAcceptedUpMs[keyCode] = eventTimeMs
        return true
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 120L
    }
}
