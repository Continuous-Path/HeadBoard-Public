package org.continuouspath.headboard.utils

object Config {
    const val STATS_DIR: String = "stats/"
    const val LOGS_DIR: String = "logs/"
    const val ARCHIVED_DIR: String = ".archived/"

    const val LOG_FILE: String = "headboard.log"
    const val ERR_LOG_FILE: String = "headboard-err.log"
    const val STATS_FILE: String = "stats.json"

    const val DEBUG: Boolean = true
    const val TIME_BETWEEN_WORDS: Long = 5000

    const val STATS_VERSION: Int = 1

    const val OPENBOARD_KDB_VIEW_ID = "org.dslul.openboard.inputmethod.latin:id/keyboard_view"

    const val DEFAULT_ANIMATION_DURATION = 1000

    /* Default HeadBoard Settings */
    const val DEFAULT_HEAD_COORD_SCALE_FACTOR_X = 3f
    const val DEFAULT_HEAD_COORD_SCALE_FACTOR_Y = 3f
    const val DEFAULT_EDGE_HOLD_DURATION = 1000
    const val DEFAULT_DRAG_TOGGLE_DURATION = 300
    const val DEFAULT_PITCH_YAW = true
    const val DEFAULT_NOSE_TIP = true
    const val DEFAULT_REALTIME_SWIPE = true
    const val DEFAULT_DEBUG_SWIPE = false
    const val DEFAULT_DURATION_POP_OUT = true
    const val DEFAULT_DIRECT_MAPPING = true

    /* Cursor Smoothing */
    const val DEFAULT_RAW_SMOOTHING = 14
    const val DEFAULT_EXPONENTIAL_SMOOTHING = false
    const val MIN_SMOOTHING_FACTOR = 0.01f // 0 freezes the cursor
    const val MAX_SMOOTHING_FACTOR = 0.4f // 0.25 is a reasonable upper limit for responsiveness

    /* Cursor Settings */
    const val DEFAULT_UI_FEEDBACK_DELAY = 3 // (D1A)
    const val DEFAULT_ACTION_STATE_CHANGE_DELAY = 2000 // (ms) UI action state change
    const val DEFAULT_LONG_TAP_THRESHOLD = 2500 // (ms) /* deprecated */
    const val QUICK_TAP_DURATION = 250 // (ms) duration for taps dispatched via gesture descriptions
    const val DEFAULT_PATH_CURSOR: Int = 4
    const val DEFAULT_PATH_CURSOR_MIN: Int = 5
    const val HOVER_ZONE_RADIUS: Int = 150 // pixels
    const val D1A_DURATION: Int = 500 // (ms) rolling avg window
    const val SHOW_KEY_POPUP: Boolean = true
    const val HIGHLIGHT_KEY_ON_TOUCH: Boolean = true
    const val DEFAULT_ENABLE_PATH_CURSOR: Boolean = true

    /* No Face Detection / Power Saving */
    const val DEFAULT_NO_FACE_PAUSE_TIMEOUT: Int = 3000 // (ms) pause cursor after no face detected for this duration
    const val DEFAULT_NO_FACE_PAUSE_ENABLED: Boolean = true // enable no-face pause feature by default
    const val NO_FACE_REDUCED_PROCESS_INTERVAL: Int = 200 // (ms) reduced camera polling interval when no face detected (battery saver)

    /* Inactivity sleep tier timeouts (seconds; user-tunable in HeadBoard Settings) */
    const val DEFAULT_SLEEP_TIER1_TIMEOUT_S: Int = 30 // no face this long -> 5fps
    const val DEFAULT_SLEEP_TIER2_TIMEOUT_S: Int = 180 // no face this long -> 1fps
    const val DEFAULT_SLEEP_TIER3_TIMEOUT_S: Int = 900 // no face this long -> deep sleep (camera off)
}
