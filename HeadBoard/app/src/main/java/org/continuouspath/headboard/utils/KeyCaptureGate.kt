package org.continuouspath.headboard.utils

import android.os.SystemClock

/**
 * In-process gate that suppresses CursorAccessibilityService's key handling while the
 * key-capture dialog is open, so the key being assigned (or a key bound to another action,
 * needed for the conflict flow) passes through to the dialog window instead of firing an action.
 *
 * The service and all activities share one process, so static state is enough — no broadcasts.
 *
 * The expiry is a failsafe only: the dialog refreshes the gate on a heartbeat while showing,
 * so a healthy dialog never times out (the capture UI itself has no time limit — WCAG 2.2.1),
 * but a leaked gate self-clears within EXPIRY_MS.
 */
object KeyCaptureGate {
    const val EXPIRY_MS = 15_000L

    @Volatile
    private var activeAtMs = 0L // SystemClock.elapsedRealtime(), 0 = inactive

    @JvmStatic
    fun begin() {
        activeAtMs = SystemClock.elapsedRealtime()
    }

    @JvmStatic
    fun refresh() = begin()

    @JvmStatic
    fun end() {
        activeAtMs = 0L
    }

    @JvmStatic
    fun isActive(): Boolean {
        val at = activeAtMs
        return at != 0L && (SystemClock.elapsedRealtime() - at) in 0..EXPIRY_MS
    }
}
