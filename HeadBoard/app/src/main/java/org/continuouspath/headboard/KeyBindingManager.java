package org.continuouspath.headboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

import org.continuouspath.headboard.BlendshapeEventTriggerConfig.EventType;
import org.continuouspath.headboard.utils.KeySwitchDebouncer;

import java.util.Collections;
import java.util.Map;

/**
 * In-memory keycode -> action map for the accessibility service's key handling. Refreshed via
 * the existing LOAD_SHARED_CONFIG_GESTURE / PROFILE_CHANGED receivers instead of re-reading
 * SharedPreferences on every keypress.
 */
public class KeyBindingManager {
    private static final String TAG = "KeyBindingManager";

    private volatile Map<Integer, EventType> keycodeToAction = Collections.emptyMap();
    @Nullable private volatile EventType swipeFromRightKbdAction = null;
    private final KeySwitchDebouncer debouncer = new KeySwitchDebouncer();

    public void reload(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
            ProfileManager.getCurrentProfile(context), Context.MODE_PRIVATE);
        keycodeToAction = BlendshapeEventTriggerConfig.readKeyBindings(prefs);
        swipeFromRightKbdAction = readSwipeFromRightKbdAction(prefs);
        Log.i(TAG, "reload: " + keycodeToAction.size() + " key bindings, swipeFromRightKbd -> "
            + swipeFromRightKbdAction);
    }

    @Nullable
    public EventType actionForKeyCode(int keyCode) {
        return keycodeToAction.get(keyCode);
    }

    /** The action bound to the "swipe from right side of keyboard" trigger, if any. */
    @Nullable
    public EventType swipeFromRightKbdAction() {
        return swipeFromRightKbdAction;
    }

    public KeySwitchDebouncer debouncer() {
        return debouncer;
    }

    @Nullable
    private static EventType readSwipeFromRightKbdAction(SharedPreferences prefs) {
        int swipeIndex = BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI
            .indexOf(BlendshapeEventTriggerConfig.Blendshape.SWIPE_FROM_RIGHT_KBD);
        for (EventType eventType : EventType.values()) {
            if (prefs.getInt(eventType.toString(), -1) == swipeIndex) {
                return eventType;
            }
        }
        return null;
    }
}
