package org.continuouspath.headboard.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class KeyLabelsTest {

  @Test
  public void friendlyNames_forCommonSwitchHardware() {
    assertEquals("Switch 1", KeyLabels.labelFor(KeyEvent.KEYCODE_1));
    assertEquals("Switch 2", KeyLabels.labelFor(KeyEvent.KEYCODE_2));
    assertEquals("Switch 3", KeyLabels.labelFor(KeyEvent.KEYCODE_3));
    assertEquals("Enter", KeyLabels.labelFor(KeyEvent.KEYCODE_ENTER));
    assertEquals("Space", KeyLabels.labelFor(KeyEvent.KEYCODE_SPACE));
    assertEquals("Volume up", KeyLabels.labelFor(KeyEvent.KEYCODE_VOLUME_UP));
    assertEquals("D-pad center", KeyLabels.labelFor(KeyEvent.KEYCODE_DPAD_CENTER));
  }

  @Test
  public void gamepadButtons_haveFriendlyNames() {
    assertEquals("Button A", KeyLabels.labelFor(KeyEvent.KEYCODE_BUTTON_A));
    assertEquals("Button L1", KeyLabels.labelFor(KeyEvent.KEYCODE_BUTTON_L1));
  }

  @Test
  public void printableKeys_useTheirCharacter() {
    assertEquals("A", KeyLabels.labelFor(KeyEvent.KEYCODE_A));
  }

  @Test
  public void neverLeaksRawPrefix() {
    for (int keyCode = 1; keyCode <= KeyEvent.getMaxKeyCode(); keyCode++) {
      assertFalse("keycode " + keyCode,
          KeyLabels.labelFor(keyCode).startsWith("KEYCODE_"));
    }
  }
}
