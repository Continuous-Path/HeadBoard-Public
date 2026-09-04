/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.continuouspath.headboard;

import android.util.Log;
import android.view.MenuItem;
import android.view.WindowManager.LayoutParams;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.text.HtmlCompat;

import android.app.Dialog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.continuouspath.headboard.utils.KeyLabels;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ChooseGestureActivity extends AppCompatActivity {

    private static final String TAG = "ChooseGestureActivity";
    private LinearLayout unFocus;

    private static final String CURRENT_TEXT = "\n(Current)";
    private static final String UNAVAILABLE_TEXT = "\n(Unavailable)";

    // View -> trigger it selects. BLENDSHAPE_FROM_ORDER_IN_UI stays purely a persistence
    // codec; the UI no longer mirrors its positions. Captured keys (Blendshape.KEY) are not
    // in this map — they live in the dynamic switch list above the gesture grid.
    private static final LinkedHashMap<Integer, BlendshapeEventTriggerConfig.Blendshape> VIEW_TO_BLENDSHAPE =
        new LinkedHashMap<Integer, BlendshapeEventTriggerConfig.Blendshape>() {{
            put(R.id.openMouth, BlendshapeEventTriggerConfig.Blendshape.OPEN_MOUTH);
            put(R.id.mouthLeft, BlendshapeEventTriggerConfig.Blendshape.MOUTH_LEFT);
            put(R.id.mouthRight, BlendshapeEventTriggerConfig.Blendshape.MOUTH_RIGHT);
            put(R.id.rollLowerMouth, BlendshapeEventTriggerConfig.Blendshape.ROLL_LOWER_MOUTH);
            put(R.id.raiseRightEyebrow, BlendshapeEventTriggerConfig.Blendshape.RAISE_RIGHT_EYEBROW);
            put(R.id.raiseLeftEyebrow, BlendshapeEventTriggerConfig.Blendshape.RAISE_LEFT_EYEBROW);
            put(R.id.lowerRightEyebrow, BlendshapeEventTriggerConfig.Blendshape.LOWER_RIGHT_EYEBROW);
            put(R.id.lowerLeftEyebrow, BlendshapeEventTriggerConfig.Blendshape.LOWER_LEFT_EYEBROW);
            put(R.id.swipeFromRightKbd, BlendshapeEventTriggerConfig.Blendshape.SWIPE_FROM_RIGHT_KBD);
            put(R.id.none, BlendshapeEventTriggerConfig.Blendshape.NONE);
        }};

    private KeyCaptureDialog keyCaptureDialog;

    // What is the target action for this page.
    BlendshapeEventTriggerConfig.EventType pageEventType;

    private BlendshapeEventTriggerConfig.Blendshape selectedBlendshape;

    /** Selected captured-key keycode; only meaningful while selectedBlendshape == KEY. */
    private int selectedKeyCode = -1;

    /** Which action currently owns each non-key trigger (rebuilt by setupUi). */
    private final Map<BlendshapeEventTriggerConfig.Blendshape, BlendshapeEventTriggerConfig.EventType>
        triggerOwners = new HashMap<>();

    /** Which action currently owns each captured keycode (rebuilt by setupUi). */
    private Map<Integer, BlendshapeEventTriggerConfig.EventType> keyOwners = new HashMap<>();

    /** The gesture label is always the text before the first newline; suffixes start with \n. */
    private static String baseLabel(String label) {
        int newline = label.indexOf('\n');
        return newline >= 0 ? label.substring(0, newline) : label;
    }

    private static TextView gestureText(View oneGestureBox) {
        if (!(oneGestureBox instanceof ViewGroup)) {
            return null;
        }
        return ((ViewGroup) oneGestureBox).findViewWithTag("text_view_gesture_name");
    }

    /**
     * Mark a trigger already used by another action: dimmed but still clickable (tapping it
     * offers to move it here), labeled with the owning action instead of a bare "in use".
     */
    private void changeButtonStyleToInUse(View oneGestureBox, String ownerActionName){
        TextView text = gestureText(oneGestureBox);
        oneGestureBox.setClickable(true);
        oneGestureBox.setAlpha(0.5f);
        if (text == null) {
            return;
        }
        text.setText(baseLabel((String) text.getText()) + "\n(" + ownerActionName + ")");
        text.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    }

    /** Grey out a trigger that cannot apply to this action at all. */
    private void changeButtonStyleToUnavailable(View oneGestureBox){
        TextView text = gestureText(oneGestureBox);
        oneGestureBox.setClickable(false);
        oneGestureBox.setAlpha(0.3f);
        if (text == null) {
            return;
        }
        text.setText(baseLabel((String) text.getText()) + UNAVAILABLE_TEXT);
        text.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    }

    /** Mark the trigger this action is currently bound to. */
    private void changeButtonStyleToCurrent(View oneGestureBox){
        TextView text = gestureText(oneGestureBox);
        oneGestureBox.setClickable(true);
        oneGestureBox.setAlpha(1.f);
        if (text == null) {
            return;
        }
        text.setText(baseLabel((String) text.getText()) + CURRENT_TEXT);
        text.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    }

    private void resetButtonStyle(View oneGestureBox){
        TextView text = gestureText(oneGestureBox);
        oneGestureBox.setClickable(true);
        oneGestureBox.setAlpha(1.f);
        if (text == null) {
            return;
        }
        text.setText(baseLabel((String) text.getText()));
        text.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
    }

    /**
     * Style every trigger button from the persisted bindings: the page action's own trigger
     * shows "(Current)", triggers owned by other actions show that action's name.
     */
    private void checkGestureButtonInUse(BlendshapeEventTriggerConfig.EventType pageEventType){
        for (Map.Entry<Integer, BlendshapeEventTriggerConfig.Blendshape> entry : VIEW_TO_BLENDSHAPE.entrySet()) {
            LinearLayout oneGestureBox = findViewById(entry.getKey());
            resetButtonStyle(oneGestureBox);

            BlendshapeEventTriggerConfig.EventType owner = triggerOwners.get(entry.getValue());
            if (owner == pageEventType) {
                changeButtonStyleToCurrent(oneGestureBox);
            } else if (owner != null && entry.getValue() != BlendshapeEventTriggerConfig.Blendshape.NONE) {
                changeButtonStyleToInUse(oneGestureBox,
                    BlendshapeEventTriggerConfig.BEATIFY_EVENT_TYPE_NAME.get(owner));
            }
        }

        // None button case need special handling: an unbound action is "currently" none.
        if (!triggerOwners.containsValue(pageEventType)
            && keyOwners.values().stream().noneMatch(owner -> owner == pageEventType)) {
            changeButtonStyleToCurrent(findViewById(R.id.none));
        }

        // Swiping gestures require special handling.
        ViewGroup swipeFromRightKbdBox = findViewById(R.id.swipeFromRightKbd);
        if (!Boolean.TRUE.equals(BlendshapeEventTriggerConfig.EVENT_TYPE_SHOULD_SHOW_SWIPING_INPUTS.get(pageEventType))) {
            changeButtonStyleToUnavailable(swipeFromRightKbdBox);
        }
    }

    /**
     * Make back button work as back action in device's navigation.
     * @param item The menu item that was selected.
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
      if (item.getItemId() == android.R.id.home) {
        finish();
        return true;
      }
      return super.onOptionsItemSelected(item);

    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_choose_input);
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);

        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);

        pageEventType = (BlendshapeEventTriggerConfig.EventType) getIntent().getSerializableExtra("eventType");

        if (pageEventType == null)
        {
            Log.e(TAG, "Start intent with invalid extra EventType.");
            finish();
            return;
        }
        Log.i(TAG, "onCreate: " + pageEventType);

        String pageDescription = BlendshapeEventTriggerConfig.getActionDescription(this, pageEventType);
        ((TextView)findViewById(R.id.actionDescriptionText)).setText(pageDescription);


        // Setting actionbar
        String actionBarText = BlendshapeEventTriggerConfig.BEATIFY_EVENT_TYPE_NAME.get(pageEventType);
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(actionBarText);

        // Seed the selection from the persisted binding.
        int savedIndex = preferences.getInt(pageEventType.toString(),
            BlendshapeEventTriggerConfig.NONE_INDEX_IN_UI);
        selectedBlendshape = BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI.get(savedIndex);
        if (selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.KEY) {
            selectedKeyCode = BlendshapeEventTriggerConfig.readBoundKeyCode(preferences, pageEventType);
        }

        findViewById(R.id.refreshBtn).setOnClickListener(v -> {
            Intent intentBack = new Intent(getBaseContext(), CursorBinding.class);
            startActivity(intentBack);
            finish();

        });

        findViewById(R.id.nextBtn).setOnClickListener(v -> {
            if (selectedBlendshape == null) {
                return;
            }
            if (selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.KEY) {
                if (selectedKeyCode <= 0) {
                    return;
                }
                BlendshapeEventTriggerConfig.writeKeyBindingConfig(
                    getBaseContext(), pageEventType, selectedKeyCode);
                showSettingCompleteAndLeave();
            } else if (selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.NONE ||
                selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.SWIPE_FROM_RIGHT_KBD
            ) {
                // Write config to sharedpref.
                BlendshapeEventTriggerConfig.writeBindingConfig(getBaseContext(),
                    selectedBlendshape,
                    pageEventType,
                    0);
                showSettingCompleteAndLeave();
            } else {
                Intent intentGoGestureSize = new Intent(getBaseContext(), GestureSizeActivity.class);
                intentGoGestureSize.putExtra("eventType", pageEventType);
                intentGoGestureSize.putExtra("selectedGesture", selectedBlendshape);
                intentGoGestureSize.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intentGoGestureSize);
            }
        });
        setupUi();
    }

    private void showSettingCompleteAndLeave() {
        try {
            Toast.makeText(getBaseContext(), "Setting Completed!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.i(TAG, e.toString());
        }
        Intent intentBack = new Intent(getBaseContext(), CursorBinding.class);
        intentBack.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intentBack);
        finish();
    }

    private void setupUi(){
        String profileName = ProfileManager.getCurrentProfile(this);
        SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);

        // Rebuild the trigger-ownership maps from the persisted bindings.
        keyOwners = BlendshapeEventTriggerConfig.readKeyBindings(preferences);
        triggerOwners.clear();
        for (BlendshapeEventTriggerConfig.EventType eventType : BlendshapeEventTriggerConfig.EventType.values()) {
            int index = preferences.getInt(eventType.toString(), -1);
            if (index < 0 || index >= BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI.size()) {
                continue;
            }
            BlendshapeEventTriggerConfig.Blendshape blendshape =
                BlendshapeEventTriggerConfig.BLENDSHAPE_FROM_ORDER_IN_UI.get(index);
            if (blendshape != BlendshapeEventTriggerConfig.Blendshape.NONE
                && blendshape != BlendshapeEventTriggerConfig.Blendshape.KEY) {
                triggerOwners.put(blendshape, eventType);
            }
        }

        for (Map.Entry<Integer, BlendshapeEventTriggerConfig.Blendshape> entry : VIEW_TO_BLENDSHAPE.entrySet()) {
            final BlendshapeEventTriggerConfig.Blendshape blendshape = entry.getValue();
            View childView = findViewById(entry.getKey());

            childView.setBackgroundResource(R.drawable.gesture_button);

            if (selectedBlendshape == blendshape) {
                childView.setBackgroundResource(R.drawable.gesture_button_selected);
                unFocus = (LinearLayout) childView;
            }

            childView.setOnClickListener(v -> onTriggerClicked(childView, blendshape));
        }
        buildSwitchList(preferences);
        checkGestureButtonInUse(pageEventType);
    }

    /** Trigger button tap: triggers owned by another action ask before being moved here. */
    private void onTriggerClicked(View childView, BlendshapeEventTriggerConfig.Blendshape blendshape) {
        BlendshapeEventTriggerConfig.EventType owner = triggerOwners.get(blendshape);
        if (owner != null && owner != pageEventType) {
            String triggerName = BlendshapeEventTriggerConfig.BEAUTIFY_BLENDSHAPE_NAME.get(blendshape);
            confirmReassign(triggerName, owner, () -> {
                BlendshapeEventTriggerConfig.clearBinding(this, owner);
                selectGesture(childView, blendshape);
            });
        } else {
            selectGesture(childView, blendshape);
        }
    }

    private void selectGesture(View childView, BlendshapeEventTriggerConfig.Blendshape blendshape) {
        selectedBlendshape = blendshape;
        selectedKeyCode = -1;
        childView.setBackgroundResource(R.drawable.gesture_button_selected);
        if (unFocus != null && unFocus != childView) {
            unFocus.setBackgroundResource(R.drawable.gesture_button);
        }
        unFocus = (LinearLayout) childView;
        updateNextButtonText();
        setupUi();
    }

    private void updateNextButtonText() {
        Button nextBtn = findViewById(R.id.nextBtn);
        boolean savesDirectly = selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.NONE
            || selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.KEY
            || selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.SWIPE_FROM_RIGHT_KBD;
        nextBtn.setText(savesDirectly ? "Done" : "Next");
    }

    /**
     * The captured-switch list: one selectable row per known switch, then a trailing
     * "Assign a switch or key…" row that captures a new one.
     */
    private void buildSwitchList(SharedPreferences preferences) {
        LinearLayout container = findViewById(R.id.switchListContainer);
        container.removeAllViews();

        List<Integer> knownKeys = BlendshapeEventTriggerConfig.readKnownSwitchKeys(preferences);
        for (int keyCode : knownKeys) {
            View row = getLayoutInflater().inflate(R.layout.item_switch_key_row, container, false);
            TextView label = row.findViewById(R.id.switchRowLabel);
            ImageButton removeBtn = row.findViewById(R.id.switchRowRemove);

            BlendshapeEventTriggerConfig.EventType owner = keyOwners.get(keyCode);
            String text = KeyLabels.labelFor(keyCode);
            if (owner == pageEventType) {
                text += CURRENT_TEXT;
            } else if (owner != null) {
                text += "\n(" + BlendshapeEventTriggerConfig.BEATIFY_EVENT_TYPE_NAME.get(owner) + ")";
                row.setAlpha(0.5f);
            }
            label.setText(text);

            boolean selected = selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.KEY
                && selectedKeyCode == keyCode;
            row.setBackgroundResource(selected ? R.drawable.gesture_button_selected : R.drawable.gesture_button);

            row.setOnClickListener(v -> onSwitchRowClicked(keyCode));
            removeBtn.setOnClickListener(v -> confirmRemoveSwitch(keyCode));
            container.addView(row);
        }

        View assignRow = getLayoutInflater().inflate(R.layout.item_switch_key_row, container, false);
        ((TextView) assignRow.findViewById(R.id.switchRowLabel)).setText(R.string.assign_key_prompt);
        assignRow.findViewById(R.id.switchRowRemove).setVisibility(View.GONE);
        assignRow.setOnClickListener(v -> showKeyCaptureDialog());
        container.addView(assignRow);
    }

    private void onSwitchRowClicked(int keyCode) {
        BlendshapeEventTriggerConfig.EventType owner = keyOwners.get(keyCode);
        if (owner != null && owner != pageEventType) {
            confirmReassign(KeyLabels.labelFor(keyCode), owner, () -> {
                BlendshapeEventTriggerConfig.clearBinding(this, owner);
                selectSwitchKey(keyCode);
            });
        } else {
            selectSwitchKey(keyCode);
        }
    }

    private void selectSwitchKey(int keyCode) {
        selectedBlendshape = BlendshapeEventTriggerConfig.Blendshape.KEY;
        selectedKeyCode = keyCode;
        if (unFocus != null) {
            unFocus.setBackgroundResource(R.drawable.gesture_button);
            unFocus = null;
        }
        updateNextButtonText();
        setupUi();
    }

    /** Styled two-button confirmation matching dialog_key_capture's look. */
    private void showConfirmDialog(CharSequence message, String positiveLabel, Runnable onConfirm) {
        Dialog dialog = new Dialog(this, R.style.HeadBoardDialog);
        dialog.setContentView(R.layout.dialog_confirm);
        ((TextView) dialog.findViewById(R.id.confirmMessage)).setText(message);
        Button positive = dialog.findViewById(R.id.confirmPositive);
        positive.setText(positiveLabel);
        positive.setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.run();
        });
        dialog.findViewById(R.id.confirmNegative).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /** "'Enter' is already assigned to Tap. Move it to Pause?" */
    private void confirmReassign(String triggerName, BlendshapeEventTriggerConfig.EventType owner,
            Runnable onConfirm) {
        showConfirmDialog(
            HtmlCompat.fromHtml(getString(R.string.reassign_message,
                triggerName,
                BlendshapeEventTriggerConfig.BEATIFY_EVENT_TYPE_NAME.get(owner),
                BlendshapeEventTriggerConfig.BEATIFY_EVENT_TYPE_NAME.get(pageEventType)),
                HtmlCompat.FROM_HTML_MODE_LEGACY),
            getString(R.string.move_it_here),
            onConfirm);
    }

    /** X button on a switch row: confirm before removing (and unbinding, if bound). */
    private void confirmRemoveSwitch(int keyCode) {
        BlendshapeEventTriggerConfig.EventType owner = keyOwners.get(keyCode);
        CharSequence message = owner != null
            ? HtmlCompat.fromHtml(getString(R.string.remove_switch_assigned_message,
                KeyLabels.labelFor(keyCode),
                BlendshapeEventTriggerConfig.BEATIFY_EVENT_TYPE_NAME.get(owner)),
                HtmlCompat.FROM_HTML_MODE_LEGACY)
            : getString(R.string.remove_switch_message, KeyLabels.labelFor(keyCode));
        showConfirmDialog(message, getString(R.string.remove), () -> {
                if (owner != null) {
                    BlendshapeEventTriggerConfig.clearBinding(this, owner);
                }
                BlendshapeEventTriggerConfig.removeKnownSwitchKey(this, keyCode);
                if (selectedBlendshape == BlendshapeEventTriggerConfig.Blendshape.KEY
                    && selectedKeyCode == keyCode) {
                    selectedBlendshape = BlendshapeEventTriggerConfig.Blendshape.NONE;
                    selectedKeyCode = -1;
                    updateNextButtonText();
                }
                setupUi();
        });
    }

    private void showKeyCaptureDialog() {
        if (keyCaptureDialog != null && keyCaptureDialog.isShowing()) {
            return;
        }
        keyCaptureDialog = new KeyCaptureDialog(this, pageEventType, keyCode -> {
            BlendshapeEventTriggerConfig.addKnownSwitchKey(this, keyCode);
            String profileName = ProfileManager.getCurrentProfile(this);
            SharedPreferences preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
            BlendshapeEventTriggerConfig.EventType owner =
                BlendshapeEventTriggerConfig.readKeyBindings(preferences).get(keyCode);
            if (owner != null && owner != pageEventType) {
                setupUi();
                confirmReassign(KeyLabels.labelFor(keyCode), owner, () -> {
                    BlendshapeEventTriggerConfig.clearBinding(this, owner);
                    selectSwitchKey(keyCode);
                });
            } else {
                selectSwitchKey(keyCode);
            }
        });
        keyCaptureDialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupUi();
        updateNextButtonText();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Never leave capture mode armed while this screen isn't in front.
        if (keyCaptureDialog != null && keyCaptureDialog.isShowing()) {
            keyCaptureDialog.dismiss();
        }
    }

}
