package org.continuouspath.headboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.KeyEvent;

import org.continuouspath.headboard.BlendshapeEventTriggerConfig.EventType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time per-profile migration from the fixed "Switch 1/2/3 on KEYCODE_1/2/3" scheme to
 * captured-key bindings (index KEY_INDEX_IN_UI + a per-action keycode pref).
 *
 * The legacy scheme stored a reverse pref per switch ("SWITCH_ONE_event" = EventType name) that
 * the service dispatched from, plus a forward positional index per action. Rebinding never
 * cleared the old side, so either direction can hold stale entries; the reverse pref is what
 * actually fired at runtime, so it is the authority here. All "<BLENDSHAPE>_event" prefs are
 * deleted at the end — the new scheme derives the reverse map from the forward prefs.
 */
public final class KeyBindingMigration {
    private static final String TAG = "KeyBindingMigration";

    static final String MIGRATION_MARKER = "key_capture_migrated_v1";

    // Legacy enum constant names as stored in prefs (the enum constants have been renamed).
    private static final String[] LEGACY_SWITCH_NAMES = {"SWITCH_ONE", "SWITCH_TWO", "SWITCH_THREE"};
    // Positions the legacy switches occupied in BLENDSHAPE_FROM_ORDER_IN_UI.
    private static final int[] LEGACY_SWITCH_INDICES = {8, 9, 10};
    // The keycodes the legacy switches were hardwired to. KEYCODE_1/2/3 == 8/9/10 — numerically
    // identical to the UI indices above by coincidence; don't conflate the two.
    private static final int[] LEGACY_SWITCH_KEYCODES =
        {KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3};

    private static final int SWIPE_KBD_INDEX = 11;

    // Every name that may appear in a legacy "<BLENDSHAPE>_event" pref.
    private static final String[] ALL_LEGACY_EVENT_PREF_NAMES = {
        "SWITCH_ONE", "SWITCH_TWO", "SWITCH_THREE", "SWIPE_FROM_RIGHT_KBD", "NONE",
        "OPEN_MOUTH", "MOUTH_LEFT", "MOUTH_RIGHT", "ROLL_LOWER_MOUTH",
        "RAISE_LEFT_EYEBROW", "LOWER_LEFT_EYEBROW", "RAISE_RIGHT_EYEBROW", "LOWER_RIGHT_EYEBROW",
    };

    private KeyBindingMigration() {}

    public static void migrateAllProfiles(Context context) {
        for (String profileName : ProfileManager.getProfiles(context)) {
            SharedPreferences prefs =
                context.getSharedPreferences(profileName, Context.MODE_PRIVATE);
            if (migrateProfile(prefs)) {
                Log.i(TAG, "Migrated key bindings for profile: " + profileName);
            }
        }
    }

    /** Returns true if a migration was performed (false if this profile was already migrated). */
    static boolean migrateProfile(SharedPreferences prefs) {
        if (prefs.getBoolean(MIGRATION_MARKER, false)) {
            return false;
        }
        SharedPreferences.Editor editor = prefs.edit();

        // Resolve which action each legacy switch actually fired, and hand it that keycode.
        Map<Integer, EventType> ownerByLegacyIndex = new HashMap<>();
        for (int i = 0; i < LEGACY_SWITCH_NAMES.length; i++) {
            EventType owner =
                resolveOwner(prefs, LEGACY_SWITCH_NAMES[i] + "_event", LEGACY_SWITCH_INDICES[i]);
            if (owner == null) {
                continue;
            }
            ownerByLegacyIndex.put(LEGACY_SWITCH_INDICES[i], owner);
            editor.putInt(owner.toString(), BlendshapeEventTriggerConfig.KEY_INDEX_IN_UI);
            editor.putInt(owner.toString() + "_size", 0);
            editor.putInt(owner.toString() + BlendshapeEventTriggerConfig.KEYCODE_SUFFIX,
                LEGACY_SWITCH_KEYCODES[i]);
        }
        EventType swipeOwner = resolveOwner(prefs, "SWIPE_FROM_RIGHT_KBD_event", SWIPE_KBD_INDEX);

        // Stale forward entries (the rebind bug never cleared them) point extra actions at the
        // legacy switch positions; only resolved owners keep their bindings. Owners are skipped
        // outright — an owner's own stored index may be stale (pointing at a different switch
        // position than the one that resolved to it) and its rewrite above must not be clobbered.
        for (EventType eventType : EventType.values()) {
            if (ownerByLegacyIndex.containsValue(eventType) || eventType == swipeOwner) {
                continue;
            }
            int storedIndex = prefs.getInt(eventType.toString(), -1);
            boolean staleSwitch = isLegacySwitchIndex(storedIndex);
            boolean staleSwipe = storedIndex == SWIPE_KBD_INDEX && swipeOwner != null;
            if (staleSwitch || staleSwipe) {
                editor.putInt(eventType.toString(), BlendshapeEventTriggerConfig.NONE_INDEX_IN_UI);
                editor.remove(eventType.toString() + "_size");
                editor.remove(eventType.toString() + BlendshapeEventTriggerConfig.KEYCODE_SUFFIX);
            }
        }

        for (String name : ALL_LEGACY_EVENT_PREF_NAMES) {
            editor.remove(name + "_event");
        }

        editor.putBoolean(MIGRATION_MARKER, true);
        editor.apply();
        return true;
    }

    /**
     * The reverse pref is authoritative (it is what the service dispatched); if it is missing or
     * unparseable but exactly one action points at the position, keep that action.
     */
    private static EventType resolveOwner(
        SharedPreferences prefs, String eventPrefKey, int legacyIndex) {
        String eventName = prefs.getString(eventPrefKey, null);
        if (eventName != null) {
            try {
                EventType owner = EventType.valueOf(eventName);
                return owner == EventType.NONE ? null : owner;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Unparseable legacy binding: " + eventPrefKey + " = " + eventName);
            }
        }
        List<EventType> candidates = new ArrayList<>();
        for (EventType eventType : EventType.values()) {
            if (prefs.getInt(eventType.toString(), -1) == legacyIndex) {
                candidates.add(eventType);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static boolean isLegacySwitchIndex(int index) {
        for (int legacyIndex : LEGACY_SWITCH_INDICES) {
            if (index == legacyIndex) {
                return true;
            }
        }
        return false;
    }
}
