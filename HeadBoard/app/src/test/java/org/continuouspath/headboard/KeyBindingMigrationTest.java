package org.continuouspath.headboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.continuouspath.headboard.BlendshapeEventTriggerConfig.EventType;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class KeyBindingMigrationTest {

  // Legacy positional indices of the fixed switches in BLENDSHAPE_FROM_ORDER_IN_UI.
  private static final int LEGACY_SWITCH_ONE_INDEX = 8;
  private static final int LEGACY_SWITCH_TWO_INDEX = 9;
  private static final int LEGACY_SWITCH_THREE_INDEX = 10;
  private static final int GESTURE_OPEN_MOUTH_INDEX = 0;

  private SharedPreferences prefs;

  @Before
  public void setUp() {
    prefs = ApplicationProvider.getApplicationContext()
        .getSharedPreferences("migration-test", Context.MODE_PRIVATE);
    prefs.edit().clear().commit();
  }

  private void putLegacyBinding(EventType action, int index, String switchName) {
    prefs.edit()
        .putInt(action.toString(), index)
        .putInt(action.toString() + "_size", 0)
        .putString(switchName + "_event", action.toString())
        .commit();
  }

  @Test
  public void happyPath_legacyDefaultsBecomeKeycodeBindings() {
    putLegacyBinding(EventType.CONTINUOUS_TOUCH, LEGACY_SWITCH_ONE_INDEX, "SWITCH_ONE");
    putLegacyBinding(EventType.CURSOR_TAP, LEGACY_SWITCH_TWO_INDEX, "SWITCH_TWO");
    putLegacyBinding(EventType.TOGGLE_TOUCH, LEGACY_SWITCH_THREE_INDEX, "SWITCH_THREE");

    assertTrue(KeyBindingMigration.migrateProfile(prefs));

    Map<Integer, EventType> bindings = BlendshapeEventTriggerConfig.readKeyBindings(prefs);
    assertEquals(EventType.CONTINUOUS_TOUCH, bindings.get(KeyEvent.KEYCODE_1));
    assertEquals(EventType.CURSOR_TAP, bindings.get(KeyEvent.KEYCODE_2));
    assertEquals(EventType.TOGGLE_TOUCH, bindings.get(KeyEvent.KEYCODE_3));
    assertEquals(3, bindings.size());

    assertFalse(prefs.contains("SWITCH_ONE_event"));
    assertFalse(prefs.contains("SWITCH_TWO_event"));
    assertFalse(prefs.contains("SWITCH_THREE_event"));
    assertTrue(prefs.getBoolean(KeyBindingMigration.MIGRATION_MARKER, false));
  }

  @Test
  public void staleForwardEntry_isClearedNotMigrated() {
    // The rebind bug: CURSOR_TAP still points at switch two's position, but the switch was
    // last bound to TOGGLE_TOUCH (the reverse pref is what actually fired).
    prefs.edit().putInt(EventType.CURSOR_TAP.toString(), LEGACY_SWITCH_TWO_INDEX).commit();
    putLegacyBinding(EventType.TOGGLE_TOUCH, LEGACY_SWITCH_TWO_INDEX, "SWITCH_TWO");

    KeyBindingMigration.migrateProfile(prefs);

    Map<Integer, EventType> bindings = BlendshapeEventTriggerConfig.readKeyBindings(prefs);
    assertEquals(EventType.TOGGLE_TOUCH, bindings.get(KeyEvent.KEYCODE_2));
    assertEquals(1, bindings.size());
    assertEquals(BlendshapeEventTriggerConfig.NONE_INDEX_IN_UI,
        prefs.getInt(EventType.CURSOR_TAP.toString(), -1));
  }

  @Test
  public void ownerWithStaleIndex_keepsItsResolvedKeycode() {
    // CURSOR_TAP's own forward entry stale-points at switch ONE's position, but switch TWO's
    // reverse pref owns it. It must end up on switch two's keycode, not cleared.
    prefs.edit()
        .putInt(EventType.CURSOR_TAP.toString(), LEGACY_SWITCH_ONE_INDEX)
        .putString("SWITCH_TWO_event", EventType.CURSOR_TAP.toString())
        .commit();

    KeyBindingMigration.migrateProfile(prefs);

    Map<Integer, EventType> bindings = BlendshapeEventTriggerConfig.readKeyBindings(prefs);
    assertEquals(EventType.CURSOR_TAP, bindings.get(KeyEvent.KEYCODE_2));
    assertEquals(1, bindings.size());
  }

  @Test
  public void missingReversePref_singleCandidateStillMigrates() {
    prefs.edit()
        .putInt(EventType.CURSOR_TAP.toString(), LEGACY_SWITCH_ONE_INDEX)
        .putInt(EventType.CURSOR_TAP.toString() + "_size", 0)
        .commit();

    KeyBindingMigration.migrateProfile(prefs);

    Map<Integer, EventType> bindings = BlendshapeEventTriggerConfig.readKeyBindings(prefs);
    assertEquals(EventType.CURSOR_TAP, bindings.get(KeyEvent.KEYCODE_1));
  }

  @Test
  public void gestureBindings_areUntouched() {
    prefs.edit()
        .putInt(EventType.CURSOR_PAUSE.toString(), GESTURE_OPEN_MOUTH_INDEX)
        .putInt(EventType.CURSOR_PAUSE.toString() + "_size", 40)
        .putString("OPEN_MOUTH_event", EventType.CURSOR_PAUSE.toString())
        .commit();

    KeyBindingMigration.migrateProfile(prefs);

    assertEquals(GESTURE_OPEN_MOUTH_INDEX, prefs.getInt(EventType.CURSOR_PAUSE.toString(), -1));
    assertEquals(40, prefs.getInt(EventType.CURSOR_PAUSE.toString() + "_size", -1));
    // The reverse pref family is removed for gestures too — nothing reads it anymore.
    assertFalse(prefs.contains("OPEN_MOUTH_event"));
  }

  @Test
  public void migration_runsOnlyOnce() {
    putLegacyBinding(EventType.CURSOR_TAP, LEGACY_SWITCH_ONE_INDEX, "SWITCH_ONE");
    assertTrue(KeyBindingMigration.migrateProfile(prefs));

    // Simulate a post-migration user change, then a second migration attempt.
    prefs.edit()
        .putInt(EventType.CURSOR_TAP.toString() + BlendshapeEventTriggerConfig.KEYCODE_SUFFIX,
            KeyEvent.KEYCODE_SPACE)
        .commit();
    assertFalse(KeyBindingMigration.migrateProfile(prefs));

    Map<Integer, EventType> bindings = BlendshapeEventTriggerConfig.readKeyBindings(prefs);
    assertEquals(EventType.CURSOR_TAP, bindings.get(KeyEvent.KEYCODE_SPACE));
  }

  @Test
  public void readKeyBindings_roundTripsWrittenBindings() {
    prefs.edit()
        .putInt(EventType.HOME.toString(), BlendshapeEventTriggerConfig.KEY_INDEX_IN_UI)
        .putInt(EventType.HOME.toString() + BlendshapeEventTriggerConfig.KEYCODE_SUFFIX,
            KeyEvent.KEYCODE_BUTTON_A)
        .commit();

    Map<Integer, EventType> bindings = BlendshapeEventTriggerConfig.readKeyBindings(prefs);
    assertEquals(EventType.HOME, bindings.get(KeyEvent.KEYCODE_BUTTON_A));
    assertEquals(KeyEvent.KEYCODE_BUTTON_A,
        BlendshapeEventTriggerConfig.readBoundKeyCode(prefs, EventType.HOME));
    assertEquals(-1, BlendshapeEventTriggerConfig.readBoundKeyCode(prefs, EventType.BACK));
  }
}
