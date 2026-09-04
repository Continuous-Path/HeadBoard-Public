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

import static android.accessibilityservice.GestureDescription.getMaxGestureDuration;
import static android.accessibilityservice.GestureDescription.getMaxStrokeCount;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 200;
    private static final int BLUETOOTH_PERMISSION_CODE = 201;
    private static final int BLUETOOTH_ADMIN_PERMISSION_CODE = 202;
    private static final int BLUETOOTH_CONNECT_PERMISSION_CODE = 203;
    private static final int POST_NOTIFICATIONS_PERMISSION_CODE = 204;
    private static final int MEDIA_PROJECTION_PERMISSION_CODE = 333;
    private static final String KEY_FIRST_RUN = "HeadBoardFirstRun";

    private final String TAG = "MainActivity";

    private Intent cursorServiceIntent;

    private SharedPreferences preferences;
    private boolean isServiceBound = false;
    
    // Track permission dialogs to dismiss them when returning from settings
    private AlertDialog cameraPermissionDialog;
    private AlertDialog accessibilityPermissionDialog;
    private boolean keep = true;
    private static final String PROFILE_PREFS = "SelectedProfilePrefs";
    private static final String SELECTED_PROFILE_KEY = "selectedProfile";
    private static final String FIRST_LAUNCH_PREFS = "FirstLaunchPrefs";
    
    // Handler for retry logic when checking service state
    private android.os.Handler serviceStateRetryHandler;
    private int serviceStateRetryCount = 0;
    private static final int MAX_SERVICE_STATE_RETRIES = 3;
    private static final int SERVICE_STATE_RETRY_DELAY_MS = 400;

    // Register the result launcher to handle the MediaProjection request
//    private ActivityResultLauncher<Intent> startMediaProjection = registerForActivityResult(
//            new ActivityResultContracts.StartActivityForResult(),
//            result -> {
//                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
//                    Intent intent = new Intent("SCREEN_CAPTURE_PERMISSION_RESULT");
//                    intent.putExtra("resultCode", result.getResultCode());
//                    intent.putExtra("data", result.getData());
//                    sendBroadcast(intent);
//                }
//            }
//    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.w("DEBUG", "MAX STROKE COUNT: " + getMaxStrokeCount());
        Log.w("DEBUG", "MAX GESTURE DURATION: " + getMaxGestureDuration());
        // Handle the splash screen transition.
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        // Runs once per profile; must happen before any binding prefs are read or written.
        KeyBindingMigration.migrateAllProfiles(this);

        setContentView(R.layout.activity_main);
        // FLAG_KEEP_SCREEN_ON is managed by the toggle listener: pinned only while the
        // service is ON (head-tracking users can't tap to keep the screen awake). With the
        // service off, leaving this screen open must not hold the display on forever.

        // Spinner setup
        Spinner profileSpinner = findViewById(R.id.profileSpinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ProfileManager.getProfiles(this));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);

        // Restore the selected profile from SharedPreferences
        SharedPreferences profilePrefs = getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE);
        String selectedProfile = profilePrefs.getString(SELECTED_PROFILE_KEY, ProfileManager.DEFAULT_PROFILE);
        int selectedIndex = adapter.getPosition(selectedProfile);
        if (selectedIndex != -1) {
            profileSpinner.setSelection(selectedIndex);
        }

        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                String selectedProfile = (String) parentView.getItemAtPosition(position);
                ProfileManager.setCurrentProfile(MainActivity.this, selectedProfile);

                // Save selected profile to SharedPreferences
                SharedPreferences profilePrefs = getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = profilePrefs.edit();
                editor.putString(SELECTED_PROFILE_KEY, selectedProfile);
                editor.apply();

                // Broadcast to update all settings
                Intent intent = new Intent("PROFILE_CHANGED");
                sendBroadcast(intent);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });


        try {
            TextView versionNumber = findViewById(R.id.versionNumber);
            String versionName = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0 ).versionName;
            versionNumber.setText(versionName);
        } catch (NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }


        findViewById(R.id.speedRow).setOnClickListener(v -> {
            Intent intent = new Intent(this, CursorSpeed.class);
            startActivity(intent);
        });

        findViewById(R.id.bindingRow).setOnClickListener(v -> {
            Intent intent = new Intent(this, CursorBinding.class);
            startActivity(intent);
        });

        findViewById(R.id.faceSwypeRow).setOnClickListener(v -> {
            Intent intent = new Intent(this, HeadBoardSettings.class);
            startActivity(intent);
        });


        findViewById(R.id.helpButton).setOnClickListener(v -> {
            Intent intent = new Intent(this, TutorialActivity.class);
            startActivity(intent);
        });


        Switch headBoardToggleSwitch = findViewById(R.id.headBoardToggleSwitch);


        //Check if service is enabled.
        checkIfServiceEnabled();

        requestNotificationPermission();

        // Receive service state message and force toggle the switch accordingly
        BroadcastReceiver toggleStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "toggleStateReceiver onReceive");
                
                // Cancel any pending retries since we got a response
                if (serviceStateRetryHandler != null) {
                    serviceStateRetryHandler.removeCallbacksAndMessages(null);
                }
                
                if (intent.getAction().equals("SERVICE_STATE")) {
                    int stateIndex = intent.getIntExtra("state", CursorAccessibilityService.ServiceState.DISABLE.ordinal());
                    switch (CursorAccessibilityService.ServiceState.values()[stateIndex]) {
                        case ENABLE:
                            headBoardToggleSwitch.setChecked(true);
                        case PAUSE:
                            headBoardToggleSwitch.setChecked(true);
                        case GLOBAL_STICK:
                            headBoardToggleSwitch.setChecked(true);
                            break;
                        case DISABLE:
                            headBoardToggleSwitch.setChecked(false);
                            break;
                    }

                }
            }

        };
        registerReceiver(toggleStateReceiver, new IntentFilter("SERVICE_STATE"), RECEIVER_EXPORTED);


        // Toggle switch interaction.
        headBoardToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if(!checkAccessibilityPermission()){
                headBoardToggleSwitch.setChecked(false);
                CameraDialog();
            } else if(isChecked){
                getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);
                wakeUpService();
            } else {
                getWindow().clearFlags(LayoutParams.FLAG_KEEP_SCREEN_ON);
                sleepCursorService();
            }

        });


        if (isFirstLaunch()) {
            // Assign some default binding so user can navigate around.
            // Keycodes 1/2/3 match what common USB/BT switch interfaces emit out of the box.
            Log.i(TAG, "First launch, assign default binding");
            BlendshapeEventTriggerConfig.writeKeyBindingConfig(this,
                    BlendshapeEventTriggerConfig.EventType.CONTINUOUS_TOUCH, KeyEvent.KEYCODE_1);
            BlendshapeEventTriggerConfig.writeKeyBindingConfig(this,
                    BlendshapeEventTriggerConfig.EventType.CURSOR_TAP, KeyEvent.KEYCODE_2);
            BlendshapeEventTriggerConfig.writeKeyBindingConfig(this,
                    BlendshapeEventTriggerConfig.EventType.TOGGLE_TOUCH, KeyEvent.KEYCODE_3);
            String profileName = ProfileManager.getCurrentProfile(this);
            preferences = getSharedPreferences(profileName, Context.MODE_PRIVATE);
            preferences.edit().putBoolean(KEY_FIRST_RUN, false).apply();

            // Goto tutorial page.
            Intent intent = new Intent(this, TutorialActivity.class);
            startActivity(intent);

            // Set the first launch flag to false
            SharedPreferences firstLaunchPrefs = getSharedPreferences(FIRST_LAUNCH_PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = firstLaunchPrefs.edit();
            editor.putBoolean(KEY_FIRST_RUN, false);
            editor.apply();
        }


        findViewById(R.id.addProfileButton).setOnClickListener(v -> {
            LayoutInflater inflater = LayoutInflater.from(this);
            View dialogView = inflater.inflate(R.layout.dialog_add_profile, null);
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setView(dialogView);
            AlertDialog dialog = builder.create();
            dialog.show();

            EditText profileNameEditText = dialogView.findViewById(R.id.profileNameEditText);
            Button addButton = dialogView.findViewById(R.id.buttonAdd);
            Button cancelButton = dialogView.findViewById(R.id.buttonCancel);

            addButton.setOnClickListener(view -> {
                String newProfileName = profileNameEditText.getText().toString().trim();
                if (!newProfileName.isEmpty()) {
                    ProfileManager.addProfile(this, newProfileName);
                    adapter.add(newProfileName);
                    adapter.notifyDataSetChanged();
                    profileSpinner.setSelection(adapter.getPosition(newProfileName));
                    dialog.dismiss();
                } else {
                    Toast.makeText(this, "Profile name cannot be empty", Toast.LENGTH_SHORT).show();
                }
            });

            cancelButton.setOnClickListener(view -> dialog.dismiss());
        });

        // Adding the Remove Profile Dialog
        findViewById(R.id.removeProfileButton).setOnClickListener(v -> {
            String currentProfile = (String) profileSpinner.getSelectedItem();
            if (!currentProfile.equals(ProfileManager.DEFAULT_PROFILE)) {
                LayoutInflater inflater = LayoutInflater.from(this);
                View dialogView = inflater.inflate(R.layout.dialog_remove_profile, null);
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setView(dialogView);
                AlertDialog dialog = builder.create();
                dialog.show();

                TextView removeProfileText = dialogView.findViewById(R.id.removeProfileText);
                removeProfileText.setText(Html.fromHtml("Are you sure you want to delete <b>" + currentProfile + "</b>?"));

                Button yesButton = dialogView.findViewById(R.id.buttonYes);
                Button noButton = dialogView.findViewById(R.id.buttonNo);

                yesButton.setOnClickListener(view -> {
                    ProfileManager.removeProfile(this, currentProfile);
                    adapter.remove(currentProfile);
                    adapter.notifyDataSetChanged();
                    dialog.dismiss();
                });

                noButton.setOnClickListener(view -> dialog.dismiss());
            }
        });

    }

    /**Send broadcast to service request service enable state
     * Service should send back its state via SERVICE_STATE message*/
    public void checkIfServiceEnabled() {
        // send broadcast to service to check its state.
        Intent intent = new Intent("REQUEST_SERVICE_STATE");
        intent.putExtra("state", "main");
        sendBroadcast(intent);
    }

    /**
     * Check if service is enabled with retry logic.
     * This helps when returning from accessibility settings where the service may still be starting.
     */
    public void checkIfServiceEnabledWithRetry() {
        // Cancel any pending retries
        if (serviceStateRetryHandler != null) {
            serviceStateRetryHandler.removeCallbacksAndMessages(null);
        } else {
            serviceStateRetryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        
        // Reset retry count
        serviceStateRetryCount = 0;
        
        // Send initial request
        checkIfServiceEnabled();
        
        // Schedule retries to handle case where service is still starting
        scheduleServiceStateRetry();
    }

    /**
     * Schedule a retry for checking service state.
     */
    private void scheduleServiceStateRetry() {
        if (serviceStateRetryCount >= MAX_SERVICE_STATE_RETRIES) {
            return;
        }
        
        // Increase delay for each retry: 400ms, 800ms, 1200ms
        int delay = SERVICE_STATE_RETRY_DELAY_MS * (serviceStateRetryCount + 1);
        
        serviceStateRetryHandler.postDelayed(() -> {
            serviceStateRetryCount++;
            Log.i(TAG, "Retrying REQUEST_SERVICE_STATE, attempt " + serviceStateRetryCount);
            checkIfServiceEnabled();
            
            // Schedule next retry if not at max
            scheduleServiceStateRetry();
        }, delay);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(!isFirstLaunch()){
            CameraDialog();
        }

        // Use retry logic when returning from settings
        checkIfServiceEnabledWithRetry();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up dialogs to prevent memory leaks
        dismissPermissionDialogs();
        cameraPermissionDialog = null;
        accessibilityPermissionDialog = null;
        
        // Clean up service state retry handler
        if (serviceStateRetryHandler != null) {
            serviceStateRetryHandler.removeCallbacksAndMessages(null);
            serviceStateRetryHandler = null;
        }
    }

    private void CameraDialog() {
        // Dismiss any existing permission dialogs first (user may have returned from settings)
        dismissPermissionDialogs();
        
        // Check Camera Permission
        if(!checkCameraPermission()){
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            String alertMsg = "Allow HeadBoard to access \nthe camera?";
            builder.setTitle("Access Camera");
            builder.setMessage(alertMsg);
            builder.setPositiveButton("Allow", (dialog, which) -> {
                RequestCameraPermission();
                dialog.dismiss();
            });
            builder.setNegativeButton("Deny", (dialog, which) -> {
                dialog.cancel();
                Intent intent = new Intent(getBaseContext(), GrantPermissionActivity.class);
                intent.putExtra("permission", "grantCamera");
                startActivity(intent);
            });
            cameraPermissionDialog = builder.create();
            cameraPermissionDialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = cameraPermissionDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setTextColor(getResources().getColor(R.color.blue));
                Button negativeButton = cameraPermissionDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(getResources().getColor(R.color.blue));
            });
            cameraPermissionDialog.setCanceledOnTouchOutside(false);
            cameraPermissionDialog.show();
            Button positiveButton = cameraPermissionDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            positiveButton.setTransformationMethod(null);
            Button negativeButton = cameraPermissionDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            negativeButton.setTransformationMethod(null);
        } else {
            AccessibilityDialog();
        }
    }
    
    /**
     * Dismiss any permission dialogs that may be showing.
     * Called when returning from settings to ensure stale dialogs are removed.
     */
    private void dismissPermissionDialogs() {
        if (cameraPermissionDialog != null && cameraPermissionDialog.isShowing()) {
            cameraPermissionDialog.dismiss();
        }
        if (accessibilityPermissionDialog != null && accessibilityPermissionDialog.isShowing()) {
            accessibilityPermissionDialog.dismiss();
        }
    }

    public void AccessibilityDialog(){
        // Check Accessibility Permission
        if(!checkAccessibilityPermission()){
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            String alertMsg = "Full control is appropriate for apps \nthat help you with accessibility \nneeds, but not for most apps.";
            builder.setTitle("Allow HeadBoard to have full control of your device?");
            builder.setMessage(alertMsg);
            builder.setPositiveButton("Allow", (dialog, which) -> {
                RequestAccessibilityPermission();
                dialog.dismiss();
            });
            builder.setNegativeButton("Deny", (dialog, which) -> {
                dialog.cancel();
                Intent intent = new Intent(getBaseContext(), GrantPermissionActivity.class);
                intent.putExtra("permission", "grantAccessibility");
                startActivity(intent);
            });
            accessibilityPermissionDialog = builder.create();
            accessibilityPermissionDialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = accessibilityPermissionDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setTextColor(getResources().getColor(R.color.blue));
                Button negativeButton = accessibilityPermissionDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(getResources().getColor(R.color.blue));
            });
            accessibilityPermissionDialog.setCanceledOnTouchOutside(false);
            accessibilityPermissionDialog.show();
            Button positiveButton = accessibilityPermissionDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            positiveButton.setTransformationMethod(null);
            Button negativeButton = accessibilityPermissionDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            negativeButton.setTransformationMethod(null);
        }
    }

    /**
     * Check the local preferences if this is the first time user launch the app.
     * @return boolean flag
     */
    private boolean isFirstLaunch() {
        SharedPreferences firstLaunchPrefs = getSharedPreferences(FIRST_LAUNCH_PREFS, Context.MODE_PRIVATE);
        return firstLaunchPrefs.getBoolean(KEY_FIRST_RUN, true);
    }

    public void wakeUpService(){
        Log.i(TAG, "MainActivity wakeUpService");
        findViewById(R.id.headBoardToggleSwitch).setEnabled(false);
        if (!checkAccessibilityPermission()){
            Log.i(TAG, "MainActivity RequestAccessibilityPermission");
            RequestAccessibilityPermission();
            return;
        }
        if (!checkCameraPermission()){
            Log.i(TAG, "MainActivity RequestCameraPermission");
            RequestCameraPermission();
            return;
        }

        // Run onStartCommand in service, currently doing nothing.
        cursorServiceIntent = new Intent(this, CursorAccessibilityService.class);
        startService(cursorServiceIntent);

        // Request MediaProjection permission
//        String currentKeyboardStr = Settings.Secure.getString(
//                getContentResolver(),
//                Settings.Secure.DEFAULT_INPUT_METHOD
//        );
//        if (currentKeyboardStr.contains("google")) {
//            MediaProjectionManager mediaProjectionManager = getSystemService(MediaProjectionManager.class);
//            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent());
//        }

        // Send broadcast to wake up service.
        Intent intent = new Intent("CHANGE_SERVICE_STATE");
        int stateOrdinal = CursorAccessibilityService.ServiceState.ENABLE.ordinal();
        intent.putExtra("state", stateOrdinal);
        Log.i(TAG, "Sending CHANGE_SERVICE_STATE broadcast with state: " + stateOrdinal);
        sendBroadcast(intent);
        Log.i(TAG, "CHANGE_SERVICE_STATE broadcast sent");

        Intent intentFlyOut = new Intent("FLY_OUT_FLOAT_WINDOW");
        sendBroadcast(intentFlyOut);
        findViewById(R.id.headBoardToggleSwitch).setEnabled(true);
    }
    public void sleepCursorService(){
        Log.i(TAG, "sleepCursorService");
        findViewById(R.id.headBoardToggleSwitch).setEnabled(false);
        // Send broadcast to stop service (sleep mode).
        Intent intent = new Intent("CHANGE_SERVICE_STATE");
        intent.putExtra("state", CursorAccessibilityService.ServiceState.DISABLE.ordinal());
        sendBroadcast(intent);
        if (isServiceBound) {
            isServiceBound = false;
        }
        cursorServiceIntent = null;
        findViewById(R.id.headBoardToggleSwitch).setEnabled(true);
    }

    public boolean checkAccessibilityPermission() {
        // Ask AccessibilityManager instead of string-matching the
        // ENABLED_ACCESSIBILITY_SERVICES setting: that setting stores component names in
        // either flattened form ("pkg/pkg.Cls" or "pkg/.Cls") depending on what wrote it,
        // and a form mismatch made this report "not granted" for a service that was
        // enabled until the user toggled it off/on in system settings.
        AccessibilityManager accessibilityManager =
            (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (accessibilityManager == null) {
            return false;
        }
        for (AccessibilityServiceInfo info : accessibilityManager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ServiceInfo serviceInfo = info.getResolveInfo().serviceInfo;
            if (getPackageName().equals(serviceInfo.packageName)
                && CursorAccessibilityService.class.getName().equals(serviceInfo.name)) {
                return true;
            }
        }
        return false;
    }

    // Request accessibility permission using intent
    public void RequestAccessibilityPermission()
    {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }


    public boolean checkCameraPermission()
    {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }


    // Request camera permission using basic requestPermissions method
    public void RequestCameraPermission()
    {
        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.CAMERA
        }, CAMERA_PERMISSION_CODE);
    }

    private void requestNotificationPermission() {
        // Only for Android 13 and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Check if the notification permission is granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                // Request the POST_NOTIFICATIONS permission
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        POST_NOTIFICATIONS_PERMISSION_CODE);
            }
        }
    }
}
