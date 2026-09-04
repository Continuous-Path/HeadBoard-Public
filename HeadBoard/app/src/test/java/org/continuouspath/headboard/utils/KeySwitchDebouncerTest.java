package org.continuouspath.headboard.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class KeySwitchDebouncerTest {

  private static final int KEY = KeyEvent.KEYCODE_ENTER;
  private static final int OTHER_KEY = KeyEvent.KEYCODE_SPACE;

  @Test
  public void normalPress_bothEdgesDispatch() {
    KeySwitchDebouncer debouncer = new KeySwitchDebouncer();
    assertTrue(debouncer.shouldDispatch(KEY, true, 0, 1000));
    assertTrue(debouncer.shouldDispatch(KEY, false, 0, 1200));
  }

  @Test
  public void autoRepeat_neverDispatches() {
    KeySwitchDebouncer debouncer = new KeySwitchDebouncer();
    assertTrue(debouncer.shouldDispatch(KEY, true, 0, 1000));
    assertFalse(debouncer.shouldDispatch(KEY, true, 1, 1050));
    assertFalse(debouncer.shouldDispatch(KEY, true, 7, 1400));
    assertTrue(debouncer.shouldDispatch(KEY, false, 0, 1500));
  }

  @Test
  public void bouncedPress_downAndItsUpBothSuppressed() {
    KeySwitchDebouncer debouncer = new KeySwitchDebouncer();
    assertTrue(debouncer.shouldDispatch(KEY, true, 0, 1000));
    assertTrue(debouncer.shouldDispatch(KEY, false, 0, 1100));
    // Contact bounce 50ms after the accepted UP: the whole press is swallowed.
    assertFalse(debouncer.shouldDispatch(KEY, true, 0, 1150));
    assertFalse(debouncer.shouldDispatch(KEY, false, 0, 1160));
    // A later real press works again.
    assertTrue(debouncer.shouldDispatch(KEY, true, 0, 2000));
    assertTrue(debouncer.shouldDispatch(KEY, false, 0, 2100));
  }

  @Test
  public void suppressedUp_doesNotResetBounceWindow() {
    KeySwitchDebouncer debouncer = new KeySwitchDebouncer();
    assertTrue(debouncer.shouldDispatch(KEY, true, 0, 1000));
    assertTrue(debouncer.shouldDispatch(KEY, false, 0, 1100));
    assertFalse(debouncer.shouldDispatch(KEY, true, 0, 1150));
    assertFalse(debouncer.shouldDispatch(KEY, false, 0, 1160));
    // Window is measured from the last ACCEPTED up (1100), not the suppressed one.
    assertFalse(debouncer.shouldDispatch(KEY, true, 0, 1210));
  }

  @Test
  public void keycodes_areIndependent() {
    KeySwitchDebouncer debouncer = new KeySwitchDebouncer();
    assertTrue(debouncer.shouldDispatch(KEY, true, 0, 1000));
    assertTrue(debouncer.shouldDispatch(KEY, false, 0, 1100));
    // A different key right after is not a bounce of the first.
    assertTrue(debouncer.shouldDispatch(OTHER_KEY, true, 0, 1110));
    assertTrue(debouncer.shouldDispatch(OTHER_KEY, false, 0, 1150));
  }
}
