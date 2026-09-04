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

import static java.lang.Math.max;
import static java.lang.Math.round;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import java.util.EnumSet;
import android.annotation.SuppressLint;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.util.SparseBooleanArray;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;
import org.continuouspath.headboard.utils.Config;
import org.continuouspath.headboard.utils.CursorUtils;
import org.continuouspath.headboard.utils.KeyCaptureGate;
import org.continuouspath.headboard.utils.DebuggingStats;
import org.continuouspath.headboard.utils.WriteToFile;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The cursor service of HeadBoard app.
 */
@SuppressLint("UnprotectedReceiver")
// All of the broadcasts can only be sent by system.
public class CursorAccessibilityService extends AccessibilityService implements LifecycleOwner,
        CursorController.JustTypeEngagementListener {
    private static final String TAG = "CursorAccessibilityService";

    /**
     * Limit UI update rate to 60 fps
     */
    public static final int UI_UPDATE = 16;

    /**
     * Limit the FaceLandmark detect rate (cursor control mode).
     */
    private static final int MIN_PROCESS = 30;

    /**
     * Limit the FaceLandmark detect rate when in JustType joystick mode.
     * Lower rate reduces CPU usage; JustType's state machine only needs ~20-25fps.
     * Tunable: try 30 (33fps), 40 (25fps), 45 (22fps), 50 (20fps).
     */
    private static final int MIN_PROCESS_JUSTTYPE = 45;

    /**
     * Limit the FaceLandmark detect rate while JustType Pause Mode is active (~1fps).
     * JustType only needs enough frames to notice the cursor entering the left Exit
     * Zone (the un-pause zone); it then asks for the normal rate back.
     */
    private static final int MIN_PROCESS_JUSTTYPE_PAUSED_DEFAULT = 1000;

    /**
     * Limit the FaceLandmark detect rate in the PAUSE service state (~5fps). PAUSE
     * previously ran the camera + inference at full rate and saved nothing. 5fps keeps
     * face-gesture un-pause responsive while cutting most of the inference cost.
     */
    private static final int MIN_PROCESS_PAUSED_STATE = 200;

    /**
     * Inactivity sleep tiers: with no face in frame for the tier-1/tier-2 timeouts
     * (user-tunable, SLEEP_TIER*_TIMEOUT_S configs), the analyzer throttles to 5fps /
     * 1fps. Any detected face snaps straight back to full rate, so the only wake path a
     * motor-impaired user needs is looking back at the camera (wake latency is one
     * analyzer interval + one tick, well under 2s even in tier 2). The camera stays bound
     * throughout — deeper (unbind/probe) sleep is a separate, opt-in feature.
     */
    private static final int SLEEP_TIER1_INTERVAL_MS = 200;
    private static final int SLEEP_TIER2_INTERVAL_MS = 1000;

    /**
     * Tier 3 "deep sleep" (opt-in via SLEEP_DEEP_ENABLED, default off): after no face for
     * T3 the camera is fully unbound (~zero draw) and rebound for a short probe window
     * every PROBE_PERIOD to check for a face. The landmarker stays warm so a probe costs
     * only the camera bind, not a model reload. Worst-case wake latency is one probe
     * period + window; a switch key or screen-on wakes instantly. If
     * DEEP_SLEEP_MAX_FAILED_PROBES consecutive probes produce no camera results at all
     * (bind failure, camera stolen by another app), deep sleep trips a backstop: the
     * pipeline resumes permanently so a motor-impaired user can never be locked out.
     */
    /** Dev-setting (SLEEP_TEST_TIMINGS) tier timeouts: 10s/20s/30s for quick testing. */
    private static final long SLEEP_TIER1_TIMEOUT_TEST_MS = 10_000;
    private static final long SLEEP_TIER2_TIMEOUT_TEST_MS = 20_000;
    private static final long SLEEP_TIER3_TIMEOUT_TEST_MS = 30_000;
    private static final long DEEP_SLEEP_PROBE_PERIOD_MS = 20_000;
    // Camera open + 3A convergence can eat ~1s of the window; 3s at the tier-1 analyzer
    // rate still yields several landmarker results to judge face presence from.
    private static final long DEEP_SLEEP_PROBE_WINDOW_MS = 3_000;
    private static final int DEEP_SLEEP_MAX_FAILED_PROBES = 3;

    private static final int IMAGE_ANALYZER_WIDTH = 300;
    private static final int IMAGE_ANALYZER_HEIGHT = 400;
    private ServiceUiManager serviceUiManager;
    public CursorController cursorController;
    private FaceLandmarkerHelper facelandmarkerHelper;
    public WindowManager windowManager;
    private Handler tickFunctionHandler;
    public Point screenSize;
    private KeyboardManager keyboardManager;
    private KeyBindingManager keyBindingManager;
    private ContinuousGestureController continuousGestureController;

    private ProcessCameraProvider cameraProvider;

    /**
     * Blocking ML operations are performed using this executor
     */
    private ExecutorService backgroundExecutor;

    private LifecycleRegistry lifecycleRegistry;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private long lastSendMessage = 0;
    private BroadcastReceiver changeServiceStateReceiver;
    private BroadcastReceiver requestServiceStateReceiver;
    private BroadcastReceiver loadSharedConfigBasicReceiver;
    private BroadcastReceiver loadSharedConfigGestureReceiver;
    private BroadcastReceiver enableScorePreviewReceiver;
    private BroadcastReceiver profileChangeReceiver;
    private BroadcastReceiver resetDebuggingStatsReceiver;
    private BroadcastReceiver justTypeHeadTrackingReceiver;
    private long startUptime;
    private long startTime;
    private long endUptime;
    private long endTime;
    private Instrumentation instrumentation;
    private HandlerThread handlerThread;
    private Handler handler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SparseBooleanArray keyStates = new SparseBooleanArray();
    private DebuggingStats gboardDebuggingStats = new DebuggingStats("GBoard");
    private DebuggingStats openboardDebuggingStats = new DebuggingStats("OpenBoard");
    private DebuggingStats debuggingStats = gboardDebuggingStats;
    private WriteToFile writeToFile;
    
    // Track current foreground activity for switch pass-through
    private String currentForegroundPackage = "";
    private String currentForegroundActivity = "";

    /**
     * This is state of cursor.
     */
    public enum ServiceState {
        ENABLE,
        DISABLE,
        /**
         * User cannot move cursor but can still perform event from face gesture.
         */
        PAUSE,
        /**
         * For user to see themself in config page. Remove buttons and make camera feed static.
         */
        GLOBAL_STICK
    }

    private ServiceState serviceState = ServiceState.DISABLE;

    /**
     * The setting app may request the float blendshape score.
     */
    private String requestedScoreBlendshapeName = "";

    /**
     * Should we send blendshape score to front-end or not.
     */
    private boolean shouldSendScore = false;
    private String[] debugText = {"", ""};

    private static final int HOVER_DETECTION_SAMPLE_RATE = 16; // ms between samples
    private static final int HOVER_DETECTION_WINDOW = 500; // ms to analyze movement
    private static final double HOVER_MOVEMENT_THRESHOLD = 5.0; // pixels
    private static final double SWIPE_VELOCITY_THRESHOLD = 0.5; // pixels per ms

    private class MovementSample {
        long timestamp;
        int[] position;
        double velocity;

        MovementSample(long timestamp, int[] position, double velocity) {
            this.timestamp = timestamp;
            this.position = position;
            this.velocity = velocity;
        }
    }

    private List<MovementSample> movementSamples = new ArrayList<>();
    private boolean isIntentionalMovement = false;
    private boolean canStartSwipe = false;

    // No-face detection power saving state
    /** Timestamp when face was last visible; drives the inactivity sleep tiers. */
    private long lastFaceVisibleTime = 0;

    /**
     * Current inactivity-sleep analyzer interval in ms; 0 = awake (no throttle).
     * Written on the main thread by {@link #updateSleepThrottle()}, read on the
     * analyzer thread in setImageAnalyzer → volatile.
     */
    private volatile int sleepThrottleIntervalMs = 0;

    // Tier 3 deep-sleep state (main thread only)
    /** True while the camera is unbound for inactivity and the probe loop is scheduled. */
    private boolean deepSleepActive = false;
    /** Consecutive probes that produced no landmarker results at all (camera trouble). */
    private int deepSleepFailedProbes = 0;
    /** Backstop tripped: deep sleep disabled until the next service enable. */
    private boolean deepSleepTripped = false;
    /** lastFrameTimestampMs before the current probe; unchanged after = no results. */
    private long probeBaselineResultTs = 0;
    /** Uptime of the next scheduled probe start; drives the overlay countdown. */
    private long nextProbeAtUptimeMs = 0;

    // Pipeline suspension state (screen off / inactivity deep sleep)
    /** Why the camera/ML pipeline is currently torn down. */
    private enum SuspendReason {
        /** Screen is off; also releases the landmarker + GPU delegate (long suspension). */
        SCREEN_OFF,
        /** Deep sleep after long inactivity; landmarker kept warm for cheap probes. */
        INACTIVITY,
    }

    /**
     * Reason-counted suspend (main thread only): the pipeline is torn down when the first
     * reason is added and rebuilt when the last is removed, so overlapping suspensions
     * (screen-off during inactivity deep sleep) can't double-unbind or resume early.
     */
    private final EnumSet<SuspendReason> suspendReasons = EnumSet.noneOf(SuspendReason.class);
    /** Receiver for screen on/off broadcasts. */
    private BroadcastReceiver screenStateReceiver;
    /** True when FaceLandmarkerHelper initialization is in progress. */
    private boolean isFaceLandmarkerInitializing = false;

    /**
     * Main-thread flag: an enableService() started async FaceLandmarkerHelper init and
     * intends to complete it. disableService() clears it, which makes the init completion
     * abort instead of enabling — otherwise a quick on→off toggle brings the camera up
     * AFTER the user turned the service off, draining battery with the toggle reading OFF.
     */
    private boolean enablePending = false;

    /**
     * Analyzes cursor movement to determine if it's intentional (swipe) or hovering (tap/long tap)
     *
     * @return true if movement appears intentional (swipe), false if hovering (tap/long tap)
     */
    private boolean analyzeMovement() {
        if (movementSamples.size() < 2) {
            return false;
        }

        // Calculate average velocity
        double totalVelocity = 0;
        int validSamples = 0;

        for (int i = 1; i < movementSamples.size(); i++) {
            MovementSample current = movementSamples.get(i);
            MovementSample previous = movementSamples.get(i - 1);

            float dpThreshold = 20f;
            float density = this.getResources().getDisplayMetrics().density;
            float pxThreshold = dpThreshold * density;

            double distance = Math.hypot(
                current.position[0] - previous.position[0],
                current.position[1] - previous.position[1]);

            if (distance > pxThreshold) {
                // distance is greater than 20dp
            }

            double timeDelta = current.timestamp - previous.timestamp;
            if (timeDelta > 0) {
                double velocity = distance / timeDelta;
                if (velocity > 0) {
                    totalVelocity += velocity;
                    validSamples++;
                }
            }
        }

        // Calculate movement consistency
        double avgVelocity = validSamples > 0 ? totalVelocity / validSamples : 0;
        boolean hasConsistentMovement = avgVelocity > SWIPE_VELOCITY_THRESHOLD;

        // Check for movement pattern
        boolean hasDirectionalMovement = false;
        if (movementSamples.size() >= 3) {
            int[] startPos = movementSamples.get(0).position;
            int[] endPos = movementSamples.get(movementSamples.size() - 1).position;
            double totalDistance = Math.sqrt(
                Math.pow(endPos[0] - startPos[0], 2) + Math.pow(endPos[1] - startPos[1], 2));
            hasDirectionalMovement = totalDistance > HOVER_MOVEMENT_THRESHOLD;
        }

        return hasConsistentMovement && hasDirectionalMovement;
    }

    /**
     * Starts monitoring cursor movement for hover detection
     */
    private void startMovementMonitoring() {
        movementSamples.clear();
        isIntentionalMovement = false;

        new Thread(() -> {
            long startTime = System.currentTimeMillis();

            while (swipeEventStarted && !swipeEventEnding) {
                int[] currentPosition = getPathCursorPosition();
                if (currentPosition == null) {
                    try {
                        Thread.sleep(HOVER_DETECTION_SAMPLE_RATE);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                long currentTime = System.currentTimeMillis();

                // Calculate velocity if we have previous samples
                double velocity = 0;
                if (!movementSamples.isEmpty()) {
                    MovementSample lastSample = movementSamples.get(movementSamples.size() - 1);
                    double distance = Math.sqrt(Math.pow(currentPosition[0] - lastSample.position[0], 2) +
                                                Math.pow(currentPosition[1] - lastSample.position[1], 2));
                    velocity = distance / (currentTime - lastSample.timestamp);
                }

                // Add new sample
                movementSamples.add(new MovementSample(currentTime, currentPosition, velocity));

                // Remove old samples outside our analysis window
                while (!movementSamples.isEmpty() && currentTime - movementSamples.get(0).timestamp > HOVER_DETECTION_WINDOW) {
                    movementSamples.remove(0);
                }

                // Analyze movement if we have enough samples
                if (currentTime - startTime >= HOVER_DETECTION_WINDOW) {
                    boolean isAttemptingSwipe = analyzeMovement();
                    isIntentionalMovement = isAttemptingSwipe;
                    if (isAttemptingSwipe && canStartSwipe && !cursorController.isSwiping && !swipeEventEnding) {
                        mainHandler.post(() -> {
                            startSwipe();
                        });
                        break; // Exit monitoring if swipe is detected
                    }
                }

                try {
                    Thread.sleep(HOVER_DETECTION_SAMPLE_RATE);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void defineAndRegisterBroadcastMessageReceivers() {
        loadSharedConfigBasicReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String configName = intent.getStringExtra("configName");
                if (CursorMovementConfig.isBooleanConfig(configName)) {
                    cursorController.cursorMovementConfig.updateOneBooleanConfigFromSharedPreference(
                        configName);
                } else {
                    cursorController.cursorMovementConfig.updateOneConfigFromSharedPreference(configName);
                    // Check if this is the LONG_TAP_THRESHOLD setting being updated
                    if (configName.equals("ACTION_STATE_CHANGE_DELAY")) {
                        sendLongPressDelayToIME(getActionStateChangeDelay());
                    }
                    if (configName.equals("FLOAT_CAM_OPACITY")) {
                        serviceUiManager.setCameraBoxOpacity(cursorController.cursorMovementConfig.get(
                            CursorMovementConfig.CursorMovementConfigType.FLOAT_CAM_OPACITY) / 100f);
                    }
                }
                refreshSleepConfigCache();
            }
        };

        loadSharedConfigGestureReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String configName = intent.getStringExtra("configName");
                cursorController.blendshapeEventTriggerConfig.updateOneConfigFromSharedPreference(
                    configName);
                keyBindingManager.reload(context);
            }
        };

        changeServiceStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int receivedEnumValue = intent.getIntExtra("state", -1);
                Log.i(TAG, "changeServiceStateReceiver: " + ServiceState.values()[receivedEnumValue]);

                // Target state to be changing.
                switch (ServiceState.values()[receivedEnumValue]) {
                    case ENABLE:
                        enableService();
                        break;
                    case DISABLE:
                        disableService();
                        break;
                    case PAUSE:
                        togglePause();
                        break;
                    case GLOBAL_STICK:
                        enterGlobalStickState();
                        break;
                }
            }
        };

        requestServiceStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String state = intent.getStringExtra("state");
                sendBroadcastServiceState(state);
            }
        };

        enableScorePreviewReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                shouldSendScore = intent.getBooleanExtra("enable", false);
                requestedScoreBlendshapeName = intent.getStringExtra("blendshapeName");
            }
        };

        profileChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Profile change detected. Reloading configuration.");
                cursorController.cursorMovementConfig.reloadSharedPreferences(context);
                cursorController.blendshapeEventTriggerConfig.updateAllConfigFromSharedPreference();
                keyBindingManager.reload(context);
                refreshSleepConfigCache();
                serviceUiManager.setCameraBoxOpacity(cursorController.cursorMovementConfig.get(
                    CursorMovementConfig.CursorMovementConfigType.FLOAT_CAM_OPACITY) / 100f);
            }
        };

        resetDebuggingStatsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                keyboardManager.checkForKeyboardType();
                gboardDebuggingStats.load(context);
                openboardDebuggingStats.load(context);
                debuggingStats.load(context);
            }
        };

        keyboardEventReceiver = new KeyboardEventReceiver();
        IntentFilter kbdFilter = new IntentFilter();
        kbdFilter.addAction(KeyboardEventReceiver.ACTION_SWIPE_START);
        kbdFilter.addAction(KeyboardEventReceiver.ACTION_LONGPRESS_ANIMATION);

        // JustType head tracking state receiver
        justTypeHeadTrackingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("org.continuouspath.justtype.ACTION_HEAD_TRACKING_ENABLED".equals(action)) {
                    handleJustTypeHeadTrackingEnabled();
                } else if ("org.continuouspath.justtype.ACTION_HEAD_TRACKING_DISABLED".equals(action)) {
                    handleJustTypeHeadTrackingDisabled();
                } else if ("org.continuouspath.justtype.ACTION_HEAD_TRACKING_POP_OUT".equals(action)) {
                    handleJustTypePopOut();
                } else if ("org.continuouspath.justtype.ACTION_NAV_HEAD_TRACKING_ENABLED".equals(action)) {
                    handleJustTypeNavHeadTrackingEnabled();
                } else if ("org.continuouspath.justtype.ACTION_NAV_HEAD_TRACKING_DISABLED".equals(action)) {
                    handleJustTypeNavHeadTrackingDisabled();
                } else if ("org.continuouspath.justtype.ACTION_NAV_HEAD_TRACKING_ARMED".equals(action)) {
                    handleJustTypeNavHeadTrackingArmed();
                } else if ("org.continuouspath.justtype.ACTION_HEAD_TRACKING_PAUSE".equals(action)) {
                    handleJustTypePauseMode(intent);
                } else if ("org.continuouspath.justtype.ACTION_HEAD_TRACKING_UNPAUSE".equals(action)) {
                    handleJustTypeUnpause();
                }
            }
        };
        IntentFilter justTypeFilter = new IntentFilter();
        justTypeFilter.addAction("org.continuouspath.justtype.ACTION_HEAD_TRACKING_ENABLED");
        justTypeFilter.addAction("org.continuouspath.justtype.ACTION_HEAD_TRACKING_DISABLED");
        justTypeFilter.addAction("org.continuouspath.justtype.ACTION_HEAD_TRACKING_POP_OUT");
        justTypeFilter.addAction("org.continuouspath.justtype.ACTION_NAV_HEAD_TRACKING_ENABLED");
        justTypeFilter.addAction("org.continuouspath.justtype.ACTION_NAV_HEAD_TRACKING_DISABLED");
        justTypeFilter.addAction("org.continuouspath.justtype.ACTION_NAV_HEAD_TRACKING_ARMED");
        justTypeFilter.addAction("org.continuouspath.justtype.ACTION_HEAD_TRACKING_PAUSE");
        justTypeFilter.addAction("org.continuouspath.justtype.ACTION_HEAD_TRACKING_UNPAUSE");

        ContextCompat.registerReceiver(this, changeServiceStateReceiver, new IntentFilter("CHANGE_SERVICE_STATE"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, requestServiceStateReceiver, new IntentFilter("REQUEST_SERVICE_STATE"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, loadSharedConfigBasicReceiver, new IntentFilter("LOAD_SHARED_CONFIG_BASIC"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, loadSharedConfigGestureReceiver, new IntentFilter("LOAD_SHARED_CONFIG_GESTURE"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, enableScorePreviewReceiver, new IntentFilter("ENABLE_SCORE_PREVIEW"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, serviceUiManager.flyInWindowReceiver, new IntentFilter("FLY_IN_FLOAT_WINDOW"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, serviceUiManager.flyOutWindowReceiver, new IntentFilter("FLY_OUT_FLOAT_WINDOW"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, profileChangeReceiver, new IntentFilter("PROFILE_CHANGED"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, resetDebuggingStatsReceiver, new IntentFilter("RESET_DEBUGGING_STATS"),
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, keyboardEventReceiver, kbdFilter,
            ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, justTypeHeadTrackingReceiver, justTypeFilter,
            ContextCompat.RECEIVER_EXPORTED);

        // Screen on/off receiver for battery optimization
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    handleScreenOff();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    handleScreenOn();
                }
            }
        };
        IntentFilter screenStateFilter = new IntentFilter();
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
        ContextCompat.registerReceiver(this, screenStateReceiver, screenStateFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /**
     * Handle screen turning off - suspend camera and ML processing to save battery.
     */
    private void handleScreenOff() {
        Log.i(TAG, "handleScreenOff: serviceState=" + serviceState);

        if (deepSleepActive) {
            // Screen went off during deep sleep: stop probing and hand the suspension to
            // SCREEN_OFF (which also releases the warm landmarker). Screen-on then does a
            // single full resume.
            cancelDeepSleepProbes();
            suspendReasons.remove(SuspendReason.INACTIVITY);
        }
        suspendPipeline(SuspendReason.SCREEN_OFF);
    }

    /**
     * Handle screen turning on - resume camera and ML processing.
     */
    private void handleScreenOn() {
        Log.i(TAG, "handleScreenOn: serviceState=" + serviceState + ", suspendReasons=" + suspendReasons);
        resumePipeline(SuspendReason.SCREEN_OFF);
    }

    /** True while any suspend reason holds the camera/ML pipeline torn down. */
    private boolean isPipelineSuspended() {
        return !suspendReasons.isEmpty();
    }

    /**
     * Tear down the camera/ML pipeline for {@code reason}. Only the first reason performs
     * the teardown; later reasons just join the set — except SCREEN_OFF, which always
     * releases the landmarker (INACTIVITY keeps it warm so deep-sleep probes skip the
     * model reload).
     */
    private void suspendPipeline(SuspendReason reason) {
        // Only suspend if service is actively running
        if (serviceState != ServiceState.ENABLE && serviceState != ServiceState.GLOBAL_STICK && serviceState != ServiceState.PAUSE) {
            return;
        }

        boolean wasSuspended = isPipelineSuspended();
        suspendReasons.add(reason);

        if (reason == SuspendReason.SCREEN_OFF && facelandmarkerHelper != null && facelandmarkerHelper.isRunning) {
            facelandmarkerHelper.pauseThread();
        }

        if (wasSuspended) {
            Log.i(TAG, "suspendPipeline(" + reason + "): already suspended, now " + suspendReasons);
            return;
        }
        Log.i(TAG, "suspendPipeline(" + reason + "): Suspending camera and ML processing");

        imageAnalyzer.clearAnalyzer();
        unbindCameraAsync();
    }

    /**
     * Drop {@code reason}; rebuilds the pipeline when the last reason is removed.
     */
    private void resumePipeline(SuspendReason reason) {
        if (!suspendReasons.remove(reason)) {
            return;
        }
        if (isPipelineSuspended()) {
            Log.i(TAG, "resumePipeline(" + reason + "): still suspended by " + suspendReasons);
            return;
        }

        // Restart the inactivity-sleep clock: it wasn't evaluated during suspend, and a
        // stale lastFaceVisibleTime would drop straight to a sleep tier before the camera
        // has produced its first post-resume frame.
        sleepThrottleIntervalMs = 0;
        lastFaceVisibleTime = 0;

        // The tick parks itself while suspended; restart it now that the last reason
        // cleared, and re-hold the display (a resume implies the user is back).
        ensureTickRunning();
        serviceUiManager.setKeepScreenOn(true);

        // Only resume if service is still in an active state
        if (serviceState != ServiceState.ENABLE && serviceState != ServiceState.GLOBAL_STICK && serviceState != ServiceState.PAUSE) {
            return;
        }

        Log.i(TAG, "resumePipeline(" + reason + "): Resuming camera and ML processing");

        if (facelandmarkerHelper == null) {
            Log.w(TAG, "resumePipeline: facelandmarkerHelper is null, cannot resume");
            return;
        }

        bindCameraAsync();

        // Recreate the landmarker only if a SCREEN_OFF suspension released it —
        // resumeThread() while running would leak the current FaceLandmarker.
        if (!facelandmarkerHelper.isRunning) {
            facelandmarkerHelper.resumeThread();
        }

        // Reattach image analyzer
        setImageAnalyzer();
    }

    /** Async unbindAll via the camera provider future (main-executor listener). */
    private void unbindCameraAsync() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(
            () -> {
                try {
                    cameraProvider = cameraProviderFuture.get();
                    cameraProvider.unbindAll();
                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "unbindCameraAsync: cameraProvider failed: " + e.getMessage());
                }
            }, ContextCompat.getMainExecutor(this));
    }

    /** Async preview+analysis bind via the camera provider future (main-executor listener). */
    private void bindCameraAsync() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(
            () -> {
                try {
                    cameraProvider = cameraProviderFuture.get();
                    CameraHelper.bindPreview(
                        cameraProvider,
                        serviceUiManager.innerCameraImageView,
                        imageAnalyzer,
                        this,
                        serviceUiManager.isCameraPreviewVisible());
                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "bindCameraAsync: cameraProvider failed: " + e.getMessage());
                }
            }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Sensor-rate control for throttled states. The analyzer's software frame-drop keeps
     * MediaPipe cheap, but the sensor/ISP still run at full rate unless the capture request
     * asks for less — so in sleep tiers, PAUSE, and JustType Pause Mode we rebind the
     * analysis use case with the lowest AE FPS range the camera supports. No-op (and no
     * rebinds) on devices with no sub-30fps range.
     */
    private android.util.Range<Integer> lowSensorFpsRange;
    private boolean analyzerLowFpsActive = false;

    @androidx.annotation.OptIn(markerClass = androidx.camera.camera2.interop.ExperimentalCamera2Interop.class)
    private ImageAnalysis buildImageAnalysis(boolean lowFps) {
        ImageAnalysis.Builder builder = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(
                new ResolutionSelector.Builder().setResolutionStrategy(
                    new ResolutionStrategy(new Size(IMAGE_ANALYZER_WIDTH, IMAGE_ANALYZER_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build());
        if (lowFps && lowSensorFpsRange != null) {
            new androidx.camera.camera2.interop.Camera2Interop.Extender<>(builder)
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    lowSensorFpsRange);
        }
        return builder.build();
    }

    /**
     * Rebind the camera at the sensor rate matching the current state. Cheap when nothing
     * changed; a state transition costs one camera rebind (~hundreds of ms), which is fine
     * for tier/pause transitions that happen at most every few seconds.
     */
    private void updateCameraFrameRate() {
        boolean wantLow = sleepThrottleIntervalMs > 0
            || serviceState == ServiceState.PAUSE
            || justTypePauseModeActive;
        if (wantLow == analyzerLowFpsActive || (wantLow && lowSensorFpsRange == null)) {
            return;
        }
        analyzerLowFpsActive = wantLow;
        Log.i(TAG, "updateCameraFrameRate: sensor rate " + (wantLow ? lowSensorFpsRange : "default"));
        imageAnalyzer.clearAnalyzer();
        imageAnalyzer = buildImageAnalysis(wantLow);
        setImageAnalyzer();
        if (!isPipelineSuspended()) {
            bindCameraAsync();
        }
    }

    /**
     * Get current service state.
     */
    public ServiceState getServiceState() {
        return serviceState;
    }

    /**
     * One-time service setup. This will run immediately after user toggle grant Accessibility permission.
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate() {
        super.onCreate();
//        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);

        instrumentation = new Instrumentation();
        handlerThread = new HandlerThread("MotionEventThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        windowManager = ContextCompat.getSystemService(this, WindowManager.class);

        screenSize = new Point();
        windowManager.getDefaultDisplay().getRealSize(screenSize);

        cursorController = new CursorController(this, screenSize.x, screenSize.y);
        serviceUiManager = new ServiceUiManager(this, windowManager, cursorController);
        cursorController.setServiceUiManager(serviceUiManager);
        cursorController.setJustTypeEngagementListener(this);
        // Engagement hides the cursor; that is only wanted for JustType's own keyboard.
        cursorController.setJustTypeImeActiveProvider(this::isJustTypeNativeAppIME);
        // Tapping the sleeping camera cover or expanding the minimized window counts as
        // deliberate user input, same as a switch key: wake and restart the sleep clock.
        serviceUiManager.setDeepSleepWakeRequestListener(this::noteUserActivityWake);
        serviceUiManager.setCameraBoxOpacity(cursorController.cursorMovementConfig.get(
            CursorMovementConfig.CursorMovementConfigType.FLOAT_CAM_OPACITY) / 100f);
        keyboardManager = new KeyboardManager(this, cursorController, serviceUiManager);
        cursorController.setKeyboardManager(keyboardManager);
        keyBindingManager = new KeyBindingManager();
        keyBindingManager.reload(this);
        continuousGestureController = new ContinuousGestureController(this, mainHandler);

        lifecycleRegistry = new LifecycleRegistry(this::getLifecycle);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

        defineAndRegisterBroadcastMessageReceivers();
        registerDefaultImeObserver();
        refreshSleepConfigCache();
        lowSensorFpsRange = CameraHelper.pickLowFpsRange(this);
        serviceUiManager.setPreviewVisibilityChangedListener(() -> {
            // Preview visibility changed (minimize/maximize, sleep cover): rebind with or
            // without the Preview use case. Skipped while suspended — the next resume or
            // probe binds with the then-current visibility.
            if (!isPipelineSuspended()
                && (serviceState == ServiceState.ENABLE || serviceState == ServiceState.PAUSE
                    || serviceState == ServiceState.GLOBAL_STICK)) {
                bindCameraAsync();
            }
        });

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor();

        // FaceLandmarkerHelper is now created lazily in enableService() to save battery
        // when accessibility is enabled but headboard service state is DISABLE.
        // setImageAnalyzer() is also called from enableService() after FaceLandmarkerHelper is ready.

        // Initialize the Handler
        tickFunctionHandler = new Handler();
        tickFunctionHandler.postDelayed(tick, 0);

//        if (isPlatformSignedAndCanInjectEvents()) {
//            Log.d(TAG, "Platform signed and can inject events!");
//        }
        writeToFile = new WriteToFile(this);

        gboardDebuggingStats.load(this);
        openboardDebuggingStats.load(this);
        keyboardManager.checkForKeyboardType();

        sendLongPressDelayToIME(getActionStateChangeDelay());
    }

    /**
     * Tick function of the service. This function runs every {@value UI_UPDATE}
     *
     * <p>Milliseconds. 1. Update cursor location on screen. 2. Dispatch event. 2. Change status icon.
     */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // When service is DISABLE, stop ticking entirely (no repost) — a permanent
            // 1 Hz wakeup while toggled off keeps the CPU from idling. Every path out
            // of DISABLE goes through ensureTickRunning() to restart the loop.
            if (serviceState == ServiceState.DISABLE) {
                return;
            }
            
            if (facelandmarkerHelper == null) {
                // Back-off until facelandmarkerHelper is initialized.
                tickFunctionHandler.postDelayed(this, CursorAccessibilityService.UI_UPDATE);
                return;
            }
            
            // While the pipeline is suspended (screen off / deep sleep) the tick has no
            // work at all — park it (no repost). resumePipeline() restarts the loop when
            // the last suspend reason clears; deep-sleep probes run on their own callbacks.
            if (isPipelineSuspended()) {
                return;
            }
            
            switch (serviceState) {
                case GLOBAL_STICK:
                    if (shouldSendScore) {
                        sendBroadcastScore();
                    }
                case ENABLE:
                    // Use for smoothing.
                    int gapFrames = round(max((facelandmarkerHelper.gapTimeMs / UI_UPDATE), 1.0f));

                    if (cursorController.isPathCursorEnabled()) {
                        if (cursorController.isEventActive()) {
                            if (!isPathCursorActive) {
                                // Display path cursor if it's still hidden.
                                Log.d(TAG, "Showing path cursor for active event.");
                                cursorController.resetPathCursorPosition();
                                serviceUiManager.showPathCursor();
                                isPathCursorActive = true;
                                cursorController.setIsPathCursorVisible(true);
                            }
                            serviceUiManager.updatePathCursorImagePositionOnScreen(
                                cursorController.getPathCursorPositionXY());
                        } else if (isPathCursorActive) {
                            // When the path cursor is still visible after an event has ended, hide it.
                            Log.d(TAG, "Hiding path cursor after event ended.");
                            serviceUiManager.hidePathCursor();
                            cursorController.setIsPathCursorVisible(false);
                            isPathCursorActive = false;
                        }
                    }

                    // Drag drag line if in drag mode.
                    if (cursorController.isDragging) {
                        serviceUiManager.updateDragLine(
                            cursorController.getPathCursorPositionXY());
                    }

                    if (checkKeyboardBoundsAgain && !cursorController.isEventActive()) {
                        Log.d(TAG, "Re-checking keyboard bounds after event ended.");
                        keyboardManager.checkForKeyboardBounds();
                        checkKeyboardBoundsAgain = false;
                    }

                    // Check if we should enter joystick mode for JustType native app IME
                    // Only enter joystick mode when JustType explicitly enables head tracking (broadcast-driven)
                    boolean imeJoystick = justTypeHeadTrackingActive &&
                        isJustTypeNativeAppIME() && keyboardManager.isKeyboardOpen() &&
                        cursorController.getActiveCursorRegionStr().equals("KBD");
                    // Nav overlay has no IME keyboard / cursor region — the overlay
                    // captures aim itself, so stream frames whenever nav HT is active.
                    boolean navJoystick = justTypeNavHeadTrackingActive;
                    boolean shouldEnterJoystickMode = imeJoystick || navJoystick;

                    if (shouldEnterJoystickMode) {
                        // Enter joystick mode
                        if (!isJustTypeJoystickMode) {
                            Log.d(TAG, "Entering JustType joystick mode - hiding cursor");
                            isJustTypeJoystickMode = true;
                            // A fresh joystick session always starts at normal rate. The RESUME
                            // below un-pauses JustType, so both sides drop Pause Mode together.
                            justTypePauseModeActive = false;
                            // Notify JustType that head-tracking frames are about to start
                            // flowing — authoritative trigger for its Restart Delay UI +
                            // activation lockout. JustType's 3 s frame-gap fallback covers
                            // the case where this broadcast is lost / from an older build.
                            keyboardManager.sendHeadTrackingResumeToJustType();
                            // Hide both regular and path cursors
                            serviceUiManager.hideCursor();
                            if (isPathCursorActive) {
                                serviceUiManager.hidePathCursor();
                                cursorController.setIsPathCursorVisible(false);
                            }
                        }

                        // Only broadcast when new MediaPipe data is available.
                        // Camera runs at ~30fps (MIN_PROCESS=30ms); tick runs at 60fps.
                        // Without this check, ~30 of 60 ticks per second send stale data.
                        // JustType's state machine processes frames in microseconds, so
                        // ~30fps is more than sufficient for responsive tracking.
                        if (facelandmarkerHelper.newDataAvailable) {
                            facelandmarkerHelper.newDataAvailable = false;
                            float[] pitchYaw = facelandmarkerHelper.getPitchYaw();
                            float[] normalizedValues = normalizePitchYaw(pitchYaw);
                            keyboardManager.sendJoystickInputToJustType(
                                normalizedValues, facelandmarkerHelper.lastFrameTimestampMs);
                        }

                        // Skip normal cursor update/display logic in joystick mode
                    } else {
                        // Exit joystick mode if we were in it
                        if (isJustTypeJoystickMode) {
                            Log.d(TAG, "Exiting JustType joystick mode - showing cursor");
                            isJustTypeJoystickMode = false;
                            // Show cursor again
                            serviceUiManager.showCursor();
                        }

                        // Normal cursor behavior (only when not in JustType joystick mode)
                        cursorController.updateInternalCursorPosition(
                            facelandmarkerHelper.getHeadCoordXY(),
                            facelandmarkerHelper.getNoseCoordXY(),
                            facelandmarkerHelper.getPitchYaw(),
                            new int[]{facelandmarkerHelper.mpInputWidth, facelandmarkerHelper.frameHeight},
                            new int[]{screenSize.x, screenSize.y});

                        dispatchEvent(null, null);

                        // Actually update the UI cursor image.
                        serviceUiManager.updateCursorImagePositionOnScreen(cursorController.getCursorPositionXY());

                        // While the JustType nav kbd is minimized + armed, watch for a
                        // sustained downward push to re-open it (mirrors the IME edge-hold).
                        if (justTypeNavHeadTrackingArmed) {
                            handleNavDownPushDetect(cursorController.getCursorPositionXY()[1]);
                        }

                        // Update continuous swipe gesture if it's active
                        if (continuousGestureController != null) {
                            ContinuousGestureController.GestureStatus status = continuousGestureController.getStatus();
                            if (status.isActive) {
                                updateGestureDescSwipe();
                            }
                        }
                    }

                    if (isPitchYawEnabled() && isNoseTipEnabled()) {
                        serviceUiManager.drawHeadCenter(
                            facelandmarkerHelper.getNoseCoordXY(),
                            facelandmarkerHelper.mpInputWidth,
                            facelandmarkerHelper.mpInputHeight);
                        serviceUiManager.drawSecondDot(
                            facelandmarkerHelper.getHeadCoordXY(),
                            facelandmarkerHelper.mpInputWidth,
                            facelandmarkerHelper.mpInputHeight);
                    } else if (isPitchYawEnabled()) {
                        serviceUiManager.drawHeadCenter(
                            facelandmarkerHelper.getHeadCoordXY(),
                            facelandmarkerHelper.mpInputWidth,
                            facelandmarkerHelper.mpInputHeight);
                    } else {
                        serviceUiManager.drawHeadCenter(
                            facelandmarkerHelper.getNoseCoordXY(),
                            facelandmarkerHelper.mpInputWidth,
                            facelandmarkerHelper.mpInputHeight);
                        
                    }


//                    if (isDebugSwipeEnabled()) {
//                        serviceUiManager.updateDebugTextOverlay(
//                                debugText[0],
//                                debugText[1],
//                                serviceState == ServiceState.PAUSE
//                        );
//                    } else {
//                        serviceUiManager.updateDebugTextOverlay(
//                                "pre: " + facelandmarkerHelper.preprocessTimeMs + "ms",
//                                "med: " + facelandmarkerHelper.mediapipeTimeMs + "ms",
//                                serviceState == ServiceState.PAUSE
//                        );
//                    }
//                    serviceUiManager.updateDebugTextOverlay(
//                        "x: " + pos[0],
//                        "y: " + pos[1],
//                        serviceState == ServiceState.PAUSE);
                    break;

                case PAUSE:
                    // Camera stays bound but the analyzer throttles to
                    // MIN_PROCESS_PAUSED_STATE (5fps) — enough for the face-gesture
                    // un-pause. In PAUSE state user cannot move cursor
                    // but still can perform some event from face gesture.
                    // dispatchEvent's PAUSE case only acts on CURSOR_PAUSE (un-pause),
                    // so evaluating gestures here is safe. Without this call — it was
                    // commented out at some point, though upstream has it — the un-pause
                    // face gesture never fires and PAUSE is only exitable via switch
                    // key or the app toggle.
                    dispatchEvent(null, null);
//
//                        if (isPitchYawEnabled() && isNoseTipEnabled()) {
//                            serviceUiManager.drawHeadCenter(
//                                    facelandmarkerHelper.getCombinedNoseAndHeadCoords(),
//                                    facelandmarkerHelper.mpInputWidth,
//                                    facelandmarkerHelper.mpInputHeight
//                            );
//                        } else if (isPitchYawEnabled()) {
//                            serviceUiManager.drawHeadCenter(
//                                    facelandmarkerHelper.getHeadCoordXY(false),
//                                    facelandmarkerHelper.mpInputWidth,
//                                    facelandmarkerHelper.mpInputHeight
//                            );
//                        } else {
//                            serviceUiManager.drawHeadCenter(
//                                    facelandmarkerHelper.getNoseCoordXY(false),
//                                    facelandmarkerHelper.mpInputWidth,
//                                    facelandmarkerHelper.mpInputHeight
//                            );
//                        }

                    serviceUiManager.updateDebugTextOverlay("", "", getServiceState() == ServiceState.PAUSE);
                    break;

                default:
                    break;
            }

            serviceUiManager.updateStatusIcon(serviceState == ServiceState.PAUSE, checkFaceVisibleInFrame());

            updateSleepThrottle();
            updateCameraFrameRate();

            // While sleeping nothing on screen moves, so the 60Hz tick is wasted wakeups.
            // Wake latency stays bounded by (analyzer interval + tick interval).
            int tickInterval = CursorAccessibilityService.UI_UPDATE;
            if (sleepThrottleIntervalMs >= SLEEP_TIER2_INTERVAL_MS) {
                tickInterval = 500;
            } else if (sleepThrottleIntervalMs > 0) {
                tickInterval = 200;
            } else if (serviceState == ServiceState.PAUSE) {
                // The analyzer feeds PAUSE at 5fps; faster ticks just re-evaluate the
                // same stale blendshapes. Un-pause latency is analyzer-bound either way.
                tickInterval = MIN_PROCESS_PAUSED_STATE;
            }
            tickFunctionHandler.postDelayed(this, tickInterval);
        }
    };

    /**
     * Escalate/clear the inactivity sleep throttle. Called every tick in active states.
     * Only ENABLE and PAUSE are eligible (GLOBAL_STICK is the calibration preview) and
     * anything interactive vetoes sleep: an in-flight cursor event, an active continuous
     * gesture, or any JustType head-tracking session. Gated by the NO_FACE_PAUSE_ENABLED
     * profile setting (default on).
     */
    private void updateSleepThrottle() {
        long now = SystemClock.uptimeMillis();
        if (lastFaceVisibleTime == 0) {
            // First evaluation after service start — begin the no-face clock now.
            lastFaceVisibleTime = now;
            return;
        }

        // A JustType head-tracking session vetoes sleep — unless it's paused: a paused
        // session with no face in frame is exactly the walked-away case the tiers exist
        // for. (JustType's gesture un-pause is analyzer-bound; tiers 1/2 keep feeding it,
        // and deep sleep only engages after the face has been gone for the full tier-3
        // timeout, at which point a face gesture can't fire anyway.)
        boolean justTypeSessionLive =
            (isJustTypeJoystickMode || justTypeHeadTrackingActive || justTypeNavHeadTrackingActive)
                && !justTypePauseModeActive;

        boolean eligible = (serviceState == ServiceState.ENABLE || serviceState == ServiceState.PAUSE)
            && !isPipelineSuspended()
            && cachedNoFacePauseEnabled
            && !cursorController.isEventActive()
            && !(continuousGestureController != null && continuousGestureController.getStatus().isActive)
            && !justTypeSessionLive;

        if (!eligible || checkFaceVisibleInFrame()) {
            lastFaceVisibleTime = now;
            if (sleepThrottleIntervalMs != 0) {
                sleepThrottleIntervalMs = 0;
                Log.i(TAG, "Sleep throttle: face detected / interaction - waking to full rate");
            }
            serviceUiManager.setKeepScreenOn(true);
            return;
        }

        long noFaceMs = now - lastFaceVisibleTime;

        // Tier 3: unbind the camera entirely and probe. Once entered, the tick's
        // suspended branch stops calling this method; wake decisions move to the probe
        // loop (or a switch key / screen event).
        if (noFaceMs >= cachedTier3TimeoutMs && !deepSleepActive && !deepSleepTripped
            && cachedDeepSleepEnabled) {
            enterDeepSleep(noFaceMs);
            return;
        }

        int target;
        if (noFaceMs >= cachedTier2TimeoutMs) {
            target = SLEEP_TIER2_INTERVAL_MS;
        } else if (noFaceMs >= cachedTier1TimeoutMs) {
            target = SLEEP_TIER1_INTERVAL_MS;
        } else {
            target = 0;
        }
        if (target != sleepThrottleIntervalMs) {
            sleepThrottleIntervalMs = target;
            Log.i(TAG, "Sleep throttle: no face for " + (noFaceMs / 1000) + "s - analyzer interval "
                + (target == 0 ? "full rate" : target + "ms"));
            // From tier 2 on the user has been gone for minutes — release the display.
            // Tier 1 keeps it held: a face briefly out of frame (reading with head down)
            // must not let the screen time out on a user who can't tap it back on.
            serviceUiManager.setKeepScreenOn(target < SLEEP_TIER2_INTERVAL_MS);
        }
    }

    /**
     * Sleep-tier config, cached out of the per-tick path (updateSleepThrottle runs up to
     * 60x/s and each config.get() walks a map + boxing). Refreshed from the existing
     * LOAD_SHARED_CONFIG_BASIC and PROFILE_CHANGED receivers.
     *
     * Later tiers are clamped to never fire before earlier ones, whatever the sliders
     * say. The dev fast-timers setting overrides all three with 10s/20s/30s.
     */
    private boolean cachedNoFacePauseEnabled = true;
    private boolean cachedDeepSleepEnabled = true;
    private long cachedTier1TimeoutMs = Config.DEFAULT_SLEEP_TIER1_TIMEOUT_S * 1000L;
    private long cachedTier2TimeoutMs = Config.DEFAULT_SLEEP_TIER2_TIMEOUT_S * 1000L;
    private long cachedTier3TimeoutMs = Config.DEFAULT_SLEEP_TIER3_TIMEOUT_S * 1000L;

    private void refreshSleepConfigCache() {
        CursorMovementConfig config = cursorController.cursorMovementConfig;
        cachedNoFacePauseEnabled = config.get(
            CursorMovementConfig.CursorMovementBooleanConfigType.NO_FACE_PAUSE_ENABLED);
        cachedDeepSleepEnabled = config.get(
            CursorMovementConfig.CursorMovementBooleanConfigType.SLEEP_DEEP_ENABLED);
        if (config.get(CursorMovementConfig.CursorMovementBooleanConfigType.SLEEP_TEST_TIMINGS)) {
            cachedTier1TimeoutMs = SLEEP_TIER1_TIMEOUT_TEST_MS;
            cachedTier2TimeoutMs = SLEEP_TIER2_TIMEOUT_TEST_MS;
            cachedTier3TimeoutMs = SLEEP_TIER3_TIMEOUT_TEST_MS;
        } else {
            cachedTier1TimeoutMs = (long) config.get(
                CursorMovementConfig.CursorMovementConfigType.SLEEP_TIER1_TIMEOUT_S) * 1000L;
            cachedTier2TimeoutMs = Math.max(cachedTier1TimeoutMs, (long) config.get(
                CursorMovementConfig.CursorMovementConfigType.SLEEP_TIER2_TIMEOUT_S) * 1000L);
            cachedTier3TimeoutMs = Math.max(cachedTier2TimeoutMs, (long) config.get(
                CursorMovementConfig.CursorMovementConfigType.SLEEP_TIER3_TIMEOUT_S) * 1000L);
        }
    }

    /** Enter tier 3: unbind the camera and start the probe loop. */
    private void enterDeepSleep(long noFaceMs) {
        Log.i(TAG, "Deep sleep: no face for " + (noFaceMs / 1000) + "s - unbinding camera, probing every "
            + (DEEP_SLEEP_PROBE_PERIOD_MS / 1000) + "s");
        deepSleepActive = true;
        deepSleepFailedProbes = 0;
        serviceUiManager.setDeepSleepUiActive(true);
        // Deep sleep exists to save power; holding the display awake would cost more
        // than everything the camera unbind saves. Wake paths re-hold it.
        serviceUiManager.setKeepScreenOn(false);
        suspendPipeline(SuspendReason.INACTIVITY);
        scheduleNextProbe();
    }

    /** Schedule the next probe start and (re)show the countdown overlay. */
    private void scheduleNextProbe() {
        serviceUiManager.setDeepSleepProbing(false);
        nextProbeAtUptimeMs = SystemClock.uptimeMillis() + DEEP_SLEEP_PROBE_PERIOD_MS;
        tickFunctionHandler.postDelayed(deepSleepProbeStart, DEEP_SLEEP_PROBE_PERIOD_MS);
        serviceUiManager.updateDeepSleepCountdown(DEEP_SLEEP_PROBE_PERIOD_MS / 1000);
        serviceUiManager.showDeepSleepOverlay();
        tickFunctionHandler.removeCallbacks(deepSleepCountdownTick);
        tickFunctionHandler.postDelayed(deepSleepCountdownTick, 1000);
    }

    /** Refreshes the overlay's "Resuming in Ns" line once a second while asleep. */
    private final Runnable deepSleepCountdownTick = new Runnable() {
        @Override
        public void run() {
            if (!deepSleepActive) {
                return;
            }
            long remainMs = nextProbeAtUptimeMs - SystemClock.uptimeMillis();
            serviceUiManager.updateDeepSleepCountdown((remainMs + 999) / 1000);
            // The label renders whole seconds; 1Hz is the honest update rate. (It also
            // skips entirely while the box is minimized — see updateDeepSleepCountdown.)
            tickFunctionHandler.postDelayed(this, 1000);
        }
    };

    /** Probe phase 1: rebind the camera for a short window. */
    private final Runnable deepSleepProbeStart = new Runnable() {
        @Override
        public void run() {
            if (!deepSleepActive) {
                return;
            }
            probeBaselineResultTs = facelandmarkerHelper != null
                ? facelandmarkerHelper.lastFrameTimestampMs : 0;
            // Probe at the tier-1 rate (5fps) so the short window yields several results.
            sleepThrottleIntervalMs = SLEEP_TIER1_INTERVAL_MS;
            // Reveal the live preview for the probe window — the visible "camera blip"
            // doubles as the user's cue that a wake attempt is happening right now.
            serviceUiManager.hideDeepSleepOverlay();
            serviceUiManager.setDeepSleepProbing(true);
            setImageAnalyzer();
            bindCameraAsync();
            tickFunctionHandler.postDelayed(deepSleepProbeEnd, DEEP_SLEEP_PROBE_WINDOW_MS);
        }
    };

    /** Probe phase 2: evaluate the window — wake, re-suspend, or trip the backstop. */
    private final Runnable deepSleepProbeEnd = new Runnable() {
        @Override
        public void run() {
            if (!deepSleepActive) {
                return;
            }
            boolean gotResults = facelandmarkerHelper != null
                && facelandmarkerHelper.lastFrameTimestampMs != probeBaselineResultTs;

            if (gotResults && checkFaceVisibleInFrame()) {
                wakeFromDeepSleep("face detected in probe");
                return;
            }
            if (!gotResults) {
                deepSleepFailedProbes++;
                Log.w(TAG, "Deep sleep: probe produced no camera results ("
                    + deepSleepFailedProbes + "/" + DEEP_SLEEP_MAX_FAILED_PROBES + ")");
                if (deepSleepFailedProbes >= DEEP_SLEEP_MAX_FAILED_PROBES) {
                    // Camera trouble — resume permanently rather than risk a lockout.
                    deepSleepTripped = true;
                    wakeFromDeepSleep("backstop: repeated probes with no camera results");
                    return;
                }
            } else {
                deepSleepFailedProbes = 0;
            }

            // No face: back to sleep until the next probe.
            imageAnalyzer.clearAnalyzer();
            unbindCameraAsync();
            scheduleNextProbe();
        }
    };

    /** Leave tier 3 and rebuild the pipeline (also resets the sleep clock). */
    private void wakeFromDeepSleep(String why) {
        if (!deepSleepActive) {
            return;
        }
        Log.i(TAG, "Deep sleep wake: " + why);
        cancelDeepSleepProbes();
        resumePipeline(SuspendReason.INACTIVITY);
    }

    /** Stop the probe loop and clear per-cycle probe state (does not touch the pipeline). */
    private void cancelDeepSleepProbes() {
        deepSleepActive = false;
        deepSleepFailedProbes = 0;
        tickFunctionHandler.removeCallbacks(deepSleepProbeStart);
        tickFunctionHandler.removeCallbacks(deepSleepProbeEnd);
        tickFunctionHandler.removeCallbacks(deepSleepCountdownTick);
        serviceUiManager.setDeepSleepUiActive(false);
        serviceUiManager.hideDeepSleepOverlay();
    }

    /**
     * Deliberate user input (switch key, pause toggle, calibration entry) proves the user
     * is present: wake from any sleep tier and restart the inactivity clock.
     */
    private void noteUserActivityWake() {
        if (deepSleepActive) {
            wakeFromDeepSleep("user input");
        }
        sleepThrottleIntervalMs = 0;
        lastFaceVisibleTime = 0;
    }

    /**
     * Assign function to image analyzer to send it to MediaPipe
     */
    private void setImageAnalyzer() {
        imageAnalyzer.setAnalyzer(
            backgroundExecutor, imageProxy -> {
                // Frame-rate precedence: inactivity sleep > PAUSE state > JustType
                // joystick (with its own Pause Mode) > normal cursor. Sleep only engages
                // when no face is in frame (updateSleepThrottle vetoes anything
                // interactive), so it can safely outrank the interactive modes.
                // serviceState/isJustTypeJoystickMode are main-thread fields read here on
                // the analyzer thread; a stale read costs at most a few frames at the
                // previous rate.
                int effectiveMinProcess;
                int sleepInterval = sleepThrottleIntervalMs;
                if (sleepInterval > 0) {
                    effectiveMinProcess = sleepInterval;
                } else if (serviceState == ServiceState.PAUSE) {
                    effectiveMinProcess = MIN_PROCESS_PAUSED_STATE;
                } else if (isJustTypeJoystickMode) {
                    // JustType Pause Mode only throttles while joystick mode is live: if
                    // the session ends with a stale flag, the cursor keeps full rate.
                    effectiveMinProcess = justTypePauseModeActive
                        ? justTypePauseFrameIntervalMs : MIN_PROCESS_JUSTTYPE;
                } else {
                    effectiveMinProcess = MIN_PROCESS;
                }
                if ((SystemClock.uptimeMillis() - lastSendMessage) > effectiveMinProcess) {

                    if ((facelandmarkerHelper != null) && (facelandmarkerHelper.getHandler() != null)) {
                        // Create a new message and attach image.
                        Message msg = Message.obtain();
                        msg.obj = imageProxy;
                        // Send message to the thread to process.
                        facelandmarkerHelper.getHandler().sendMessage(msg);
                        lastSendMessage = SystemClock.uptimeMillis();
                    } else {
                        // Handler not ready, close the imageProxy to prevent blocking frame queue
                        imageProxy.close();
                    }

                } else {
                    // Rate limiting - close the imageProxy
                    imageProxy.close();
                }
            });
    }

    /**
     * Send out blendshape score for visualize in setting page.
     */
    private void sendBroadcastScore() {
        if (!shouldSendScore) {
            return;
        }

        // Get float score of the requested blendshape.
        if (requestedScoreBlendshapeName != null) {
            try {
                BlendshapeEventTriggerConfig.Blendshape enumValue = BlendshapeEventTriggerConfig.Blendshape.valueOf(
                    requestedScoreBlendshapeName);

                float score = facelandmarkerHelper.getBlendshapes()[enumValue.value];
                Intent intent = new Intent(requestedScoreBlendshapeName);
                intent.putExtra("score", score);
                sendBroadcast(intent);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "No Blendshape named " + requestedScoreBlendshapeName);
            }
        } else {
            try {
                Intent intent = getPitchAndYawIntent();
                sendBroadcast(intent);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "err while retrieving pitch & yaw " + e);
            }
        }
    }

    private Intent getPitchAndYawIntent() {
        Intent intent = new Intent("PITCH_YAW");
        float[] pitchYaw = facelandmarkerHelper.getPitchYaw();
        float[] currHeadXY = facelandmarkerHelper.getNormalizedHeadCoordXY();
        float[] currNoseXY = facelandmarkerHelper.getNormalizedNoseCoordXY();
        intent.putExtra("PITCH", pitchYaw[0]);
        intent.putExtra("YAW", pitchYaw[1]);
        intent.putExtra("CLEARHEADED", currHeadXY);
        intent.putExtra("CONCURRENCY", currNoseXY);
        return intent;
    }

    /**
     * Send out service state to the front-end (MainActivity).
     */
    private void sendBroadcastServiceState(String state) {
        Intent intent;
        if (state.equals("main")) {
            intent = new Intent("SERVICE_STATE");
        } else {
            intent = new Intent("SERVICE_STATE_GESTURE");
        }
        intent.putExtra("state", serviceState.ordinal());
        sendBroadcast(intent);
    }

    /**
     * Called from startService in MainActivity. After user click the "Start" button.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        serviceUiManager.cameraBoxView.findViewById(R.id.popBtn).setBackground(null);

        return START_STICKY;
    }

    /**
     * The full AccessibilityServiceInfo from the XML config, captured on connect so
     * {@link #setAccessibilityServiceInfoActive} can restore it after stripping.
     */
    private AccessibilityServiceInfo fullAccessibilityServiceInfo;

    /**
     * Trim or restore the accessibility event stream. The XML config requests typeAllMask
     * + interactive-window retrieval at a 100ms timeout, which makes the system serialize
     * and IPC events to this (permanently resident) process for nearly every UI change on
     * the device — a steady battery cost even with the toggle OFF. While DISABLE we swap
     * in a near-empty info; every enable path restores the full one.
     */
    private void setAccessibilityServiceInfoActive(boolean active) {
        if (fullAccessibilityServiceInfo == null) {
            // Not connected yet; onServiceConnected captures the info and applies the
            // variant matching serviceState.
            return;
        }
        try {
            if (active) {
                setServiceInfo(fullAccessibilityServiceInfo);
            } else {
                AccessibilityServiceInfo idle = new AccessibilityServiceInfo();
                idle.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
                idle.feedbackType = fullAccessibilityServiceInfo.feedbackType;
                idle.notificationTimeout = 1000;
                idle.flags = AccessibilityServiceInfo.DEFAULT;
                setServiceInfo(idle);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "setAccessibilityServiceInfoActive(" + active + ") failed: " + e.getMessage());
        }
    }

    /**
     * (Re)start the tick loop. Idempotent (removeCallbacks first, so no double-posting) —
     * must be called on every transition out of DISABLE, because the tick self-terminates
     * (no repost) while DISABLE.
     */
    private void ensureTickRunning() {
        tickFunctionHandler.removeCallbacks(tick);
        tickFunctionHandler.postDelayed(tick, 0);
    }

    /**
     * Toggle between Pause <-> ENABLE.
     */
    public void togglePause() {
        // Reached via face gesture, switch key, or the app UI — all user activity.
        noteUserActivityWake();
        switch (serviceState) {
            case ENABLE:
                // Already enable, goto pause mode.
                serviceState = ServiceState.PAUSE;
                serviceUiManager.hideCursor();
                break;

            case PAUSE:
                // In pause mode, enable it.
                serviceState = ServiceState.ENABLE;
                serviceUiManager.showCursor();
                break;
            default:
        }
        serviceUiManager.setCameraBoxDraggable(true);
    }

    /**
     * Enter {@link ServiceState#GLOBAL_STICK} state.
     * For binding gesture size page.
     * Remove buttons and make camera feed static.
     */
    public void enterGlobalStickState() {
        Log.i(TAG, "enterGlobalStickState");
        // Calibration/gesture-size pages need a live camera — leave any sleep tier first.
        noteUserActivityWake();
        switch (serviceState) {
            case PAUSE:
                togglePause();
                break;
            case DISABLE:
                enableService();
                break;
            default:
                break;
        }
        serviceState = ServiceState.GLOBAL_STICK;
        serviceUiManager.setCameraBoxDraggable(false);
        ensureTickRunning();
        setAccessibilityServiceInfoActive(true);
    }

    /**
     * Enable HeadBoard service.
     */
    public void enableService() {
        Log.i(TAG, "enableService, current: " + serviceState);

        switch (serviceState) {
            case ENABLE:
                return;

            case DISABLE:
                // Check if FaceLandmarkerHelper needs to be initialized (deferred from onCreate)
                if (facelandmarkerHelper == null && !isFaceLandmarkerInitializing) {
                    Log.i(TAG, "enableService: Initializing FaceLandmarkerHelper lazily");
                    isFaceLandmarkerInitializing = true;
                    enablePending = true;

                    backgroundExecutor.execute(() -> {
                        FaceLandmarkerHelper helper = new FaceLandmarkerHelper();
                        helper.setFrontCameraOrientation(CameraHelper.checkFrontCameraOrientation(this));
                        helper.setRotation(windowManager.getDefaultDisplay().getRotation());
                        helper.start();
                        helper.init(this);

                        // Complete initialization on main thread
                        mainHandler.post(() -> {
                            isFaceLandmarkerInitializing = false;

                            // Disabled while initializing: abort. Completing anyway would
                            // bind the camera with the toggle reading OFF.
                            if (!enablePending) {
                                Log.i(TAG, "enableService: disabled during init, discarding helper");
                                helper.destroy();
                                return;
                            }
                            enablePending = false;
                            facelandmarkerHelper = helper;
                            completeEnableFromDisable();
                        });
                    });
                    return; // Will complete asynchronously
                } else if (isFaceLandmarkerInitializing) {
                    Log.i(TAG, "enableService: FaceLandmarkerHelper initialization already in progress");
                    enablePending = true; // re-enabled while init in flight — let it complete
                    return; // Already initializing
                }
                
                // FaceLandmarkerHelper is ready, complete enable synchronously
                completeEnableFromDisable();
                return;

            case PAUSE:
            case GLOBAL_STICK:
                // Transitioning from PAUSE or GLOBAL_STICK to ENABLE
                break;
            default:
        }

        serviceUiManager.showAllWindows();
        serviceUiManager.fitCameraBoxToScreen();
        serviceUiManager.setCameraBoxDraggable(true);

        serviceState = ServiceState.ENABLE;
        ensureTickRunning();
        setAccessibilityServiceInfoActive(true);
    }

    /**
     * Complete the enable process when transitioning from DISABLE state.
     * Called after FaceLandmarkerHelper is initialized.
     */
    private void completeEnableFromDisable() {
        Log.i(TAG, "completeEnableFromDisable");
        
        if (facelandmarkerHelper == null) {
            Log.e(TAG, "completeEnableFromDisable: facelandmarkerHelper is null, cannot enable");
            return;
        }

        // Start camera
        bindCameraAsync();

        facelandmarkerHelper.resumeThread();
        setImageAnalyzer();
        cursorController.resetRawCoordMinMax();
        facelandmarkerHelper.resetMinMaxValues();

        serviceUiManager.showAllWindows();
        serviceUiManager.fitCameraBoxToScreen();
        serviceUiManager.setCameraBoxDraggable(true);

        serviceState = ServiceState.ENABLE;
        ensureTickRunning();
        setAccessibilityServiceInfoActive(true);

        // Broadcast state so MainActivity can sync
        sendBroadcastServiceState("main");
    }

    /**
     * Re-apply the ENABLE state - rebind camera and show windows.
     * Used when accessibility service reconnects while already in ENABLE state.
     */
    private void reapplyEnableState() {
        Log.i(TAG, "reapplyEnableState");
        
        if (facelandmarkerHelper == null) {
            Log.w(TAG, "reapplyEnableState: facelandmarkerHelper is null, cannot reapply");
            return;
        }
        
        if (isPipelineSuspended()) {
            Log.i(TAG, "reapplyEnableState: Skipping reapply, pipeline suspended by " + suspendReasons);
            return;
        }

        // Rebind camera
        bindCameraAsync();

        facelandmarkerHelper.resumeThread();
        setImageAnalyzer();

        serviceUiManager.showAllWindows();
        serviceUiManager.fitCameraBoxToScreen();
        serviceUiManager.setCameraBoxDraggable(true);
        ensureTickRunning();
        setAccessibilityServiceInfoActive(true);

        // Broadcast state so MainActivity can sync
        sendBroadcastServiceState("main");
    }

    /**
     * Disable HeadBoard service.
     */
    public void disableService() {
        Log.i(TAG, "disableService");
        // Cancel any enable whose FaceLandmarkerHelper init is still in flight
        // (serviceState is still DISABLE then, so the switch below won't run).
        enablePending = false;
        switch (serviceState) {
            case ENABLE:
            case GLOBAL_STICK:
            case PAUSE:
                serviceUiManager.hideAllWindows();
                serviceUiManager.setCameraBoxDraggable(true);

                // stop the service functions.
                if (facelandmarkerHelper != null) {
                    facelandmarkerHelper.destroy();
                    facelandmarkerHelper = null;
                }
                imageAnalyzer.clearAnalyzer();

                // Stop camera.
                unbindCameraAsync();

                // Reset suspension + deep-sleep bookkeeping (probes cancelled; the
                // backstop re-arms for the next enable) and the inactivity-sleep clock
                // so the next enable starts awake.
                cancelDeepSleepProbes();
                deepSleepTripped = false;
                suspendReasons.clear();
                sleepThrottleIntervalMs = 0;
                lastFaceVisibleTime = 0;

                serviceState = ServiceState.DISABLE;
                setAccessibilityServiceInfoActive(false);
                break;
            default:
                break;
        }
    }

    /**
     * Destroy HeadBoard service and unregister broadcasts.
     */
    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        disableService();
        disableSelf();
        handlerThread.quitSafely();
        cursorController.cleanup();
        
        // Cleanup continuous swipe gesture controller
        if (continuousGestureController != null) {
            continuousGestureController.cleanup();
        }

        // Unregister when the service is destroyed
        try { unregisterReceiver(changeServiceStateReceiver); } catch (Exception e) {}
        try { unregisterReceiver(requestServiceStateReceiver); } catch (Exception e) {}
        try { unregisterReceiver(loadSharedConfigBasicReceiver); } catch (Exception e) {}
        try { unregisterReceiver(loadSharedConfigGestureReceiver); } catch (Exception e) {}
        try { unregisterReceiver(enableScorePreviewReceiver); } catch (Exception e) {}
        try { unregisterReceiver(serviceUiManager.flyInWindowReceiver); } catch (Exception e) {}
        try { unregisterReceiver(serviceUiManager.flyOutWindowReceiver); } catch (Exception e) {}
        try { unregisterReceiver(profileChangeReceiver); } catch (Exception e) {}
        try { unregisterReceiver(resetDebuggingStatsReceiver); } catch (Exception e) {}
        try { unregisterReceiver(keyboardEventReceiver); } catch (Exception e) {}
        try { unregisterReceiver(justTypeHeadTrackingReceiver); } catch (Exception e) {}
        try { unregisterReceiver(screenStateReceiver); } catch (Exception e) {}

        super.onDestroy();
    }

    /**
     * Function for perform {@link BlendshapeEventTriggerConfig.EventType} actions.
     */
    private void dispatchEvent(BlendshapeEventTriggerConfig.EventDetails inputEvent, KeyEvent keyEvent) {
        // Guard against null facelandmarkerHelper during lazy initialization
        if (facelandmarkerHelper == null) {
            return;
        }
        
        // Check what inputEvent to dispatch.
        if (inputEvent == null && keyEvent == null) {
            inputEvent = cursorController.createCursorEvent(facelandmarkerHelper.getBlendshapes());
        }

        switch (inputEvent.eventType) {
            case NONE:
                return;
            case DRAG_TOGGLE:
            case TOGGLE_TOUCH:
            case CONTINUOUS_TOUCH:
            case END_TOUCH:
            case BEGIN_TOUCH:
            case CURSOR_TAP:
                break;
            default:
                // Cancel drag if user perform any other inputEvent.
                cursorController.prepareDragEnd(0, 0);
                serviceUiManager.fullScreenCanvas.clearDragLine();
                break;
        }

        Log.d(TAG, "dispatchEvent: " + inputEvent);

        switch (serviceState) {
            case GLOBAL_STICK:
            case ENABLE:
                // Check inputEvent type and dispatch it.
                DispatchEventHelper.checkAndDispatchEvent(
                    CursorAccessibilityService.this,
                    cursorController,
                    serviceUiManager,
                    inputEvent);
                break;

            case PAUSE:
                // In PAUSE state user can only perform togglePause. Press edge only —
                // a switch bound to pause sends its release edge too, and an unguarded
                // toggle here would un-pause the moment the switch was let go.
                if (inputEvent.eventType == BlendshapeEventTriggerConfig.EventType.CURSOR_PAUSE
                    && inputEvent.isStartingEvent) {
                    togglePause();
                }
                if (cursorController.isDragging) {
                    serviceUiManager.fullScreenCanvas.clearDragLine();
                    cursorController.prepareDragEnd(0, 0);
                }
                break;
            default:
                break;
        }
    }

    /**
     * Check if face is visible in frame.
     */
    private Boolean checkFaceVisibleInFrame() {
        if (facelandmarkerHelper == null) return false;

        return facelandmarkerHelper.isFaceVisible;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.d(TAG, "onConfigurationChanged");
        super.onConfigurationChanged(newConfig);

        // Temporary hide UIs while screen is rotating.
        serviceUiManager.hideAllWindows();

        windowManager.getDefaultDisplay().getRealSize(screenSize);

        // Rotate mediapipe input.
        if (windowManager != null && facelandmarkerHelper != null) {
            int newRotation = windowManager.getDefaultDisplay().getRotation();
            facelandmarkerHelper.setRotation(newRotation);
        }

        // On-going drag event will be cancel when screen is rotate.
        cursorController.prepareDragEnd(0, 0);
        serviceUiManager.fullScreenCanvas.clearDragLine();

        switch (serviceState) {
            case ENABLE:
            case GLOBAL_STICK:
                serviceUiManager.showAllWindows();
            case PAUSE:
                serviceUiManager.showCameraBox();
                break;
            case DISABLE:
                break;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // In DISABLE, do no per-event work: the stripped service info (see
        // setAccessibilityServiceInfoActive) delivers almost nothing, and
        // disableService() already detached all overlay windows.
        if (serviceState == ServiceState.DISABLE) {
            return;
        }

        // Track foreground activity changes (for switch pass-through)
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence packageName = event.getPackageName();
            CharSequence className = event.getClassName();
            if (packageName != null) {
                currentForegroundPackage = packageName.toString();
            }
            if (className != null) {
                currentForegroundActivity = className.toString();
            }
            Log.d(TAG, "Foreground changed: " + currentForegroundPackage + "/" + currentForegroundActivity);
        }

        if (serviceState != ServiceState.ENABLE) {
            // In PAUSE/GLOBAL_STICK, ensure cursor is hidden if keyboard window changes occur
            // This prevents a frozen cursor from appearing when keyboard opens while inactive
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                serviceUiManager.hideCursor();
                serviceUiManager.hidePathCursor();
            }
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (cursorController.isEventActive()) {
                Log.d(TAG, String.valueOf(event.getWindowChanges()));
                checkKeyboardBoundsAgain = true;
                Log.d(TAG, "onAccessibilityEvent: Failed to get keyboard bounds because event actions is active. Will try again later.");
            } else {
                checkKeyboardBoundsAgain = false;
                keyboardManager.checkForKeyboardBounds();
            }
        } else if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            CharSequence newText = event.getText().toString();

            if (newText != null && newText.length() > 0) {
                processTypedText(newText);
            }
        }
    }
    private boolean checkKeyboardBoundsAgain = false;

    private StringBuilder typedText = new StringBuilder();
    private boolean checkForNewWord = false;
    private long checkForNewWordTimeStamp = 0;
    private String newWord;

    /**
     * Process the typed text and check for new words.
     * This method is called when an accessibility event occurs.
     *
     * @param newText The new text that was typed.
     */
    private void processTypedText(CharSequence newText) {
        typedText.append(newText);
        String[] words = typedText.toString().split("\\s+");
        Log.d(TAG, "processTypedText(): [words==" + words + "] [words.length==" + words.length + "]");
        if (words.length > 0) {
            Long now = System.currentTimeMillis();
            newWord = words[words.length - 1];
            Log.d(
                TAG,
                "processTypedText(): [newWord==" + newWord + "] [checkForNewWord==" + checkForNewWord +
                "] [(checkForNewWordTimeStamp + 1000 >= now)==" + (checkForNewWordTimeStamp + 1000 >= now) + "]");
            if (checkForNewWord && (checkForNewWordTimeStamp + 1000 >= now)) {
                if (newWord != null) {
                    keyboardManager.getCurrentDebuggingStats().addWordSwiped(newWord, startTime, endTime);
                    keyboardManager.getCurrentDebuggingStats().save(this);
                    checkForNewWord = false;
                }
            } else {
                Log.d(TAG, "processTypedText(): checkForNewWord is false, not adding word to stats.");
            }
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override @NonNull
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "onServiceConnected: serviceState=" + serviceState);

        // The service can come up after an app update before MainActivity ever opens, so the
        // legacy switch-binding migration must be safe to run from here too (idempotent).
        KeyBindingMigration.migrateAllProfiles(this);
        if (keyBindingManager != null) {
            keyBindingManager.reload(this);
        }

        // Capture the full XML-config service info once, then apply the variant matching
        // the current state (strips the typeAllMask event stream while DISABLE).
        fullAccessibilityServiceInfo = getServiceInfo();
        // The XML config requests typeAllMask, but onAccessibilityEvent only ever acts on
        // three types — narrow the active mask so the system stops serializing and IPC-ing
        // every other UI event on the device to this process.
        if (fullAccessibilityServiceInfo != null) {
            fullAccessibilityServiceInfo.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
        }
        setAccessibilityServiceInfoActive(serviceState != ServiceState.DISABLE);

        // If the service was already in an active state when accessibility was re-enabled,
        // we need to re-apply the state (rebind camera, show windows, etc.)
        if (serviceState == ServiceState.ENABLE || serviceState == ServiceState.GLOBAL_STICK || serviceState == ServiceState.PAUSE) {
            Log.i(TAG, "onServiceConnected: Re-applying active state after accessibility reconnect");
            if (facelandmarkerHelper != null) {
                reapplyEnableState();
            } else {
                Log.w(TAG, "onServiceConnected: facelandmarkerHelper is null, cannot reapply state");
            }
        }
        
        // Always send the current state so MainActivity can sync the toggle
        sendBroadcastServiceState("main");
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (serviceState != ServiceState.ENABLE && serviceState != ServiceState.PAUSE) {
            return false;
        }

        // A switch press proves the user is present — instant wake from any sleep tier.
        noteUserActivityWake();

        // Our own key-capture dialog is assigning a switch: every key belongs to it, including
        // keys currently bound to actions (it needs them for its conflict flow).
        if (KeyCaptureGate.isActive()) {
            return false;
        }

        // Pass through key events when JustType switch setup activities are in foreground —
        // its capture screens can bind any key, so all of them must reach it untouched.
        if (isJustTypeSwitchSetupActivity()) {
            Log.d(TAG, "onKeyEvent: JustType switch setup activity in foreground - allowing key event to pass through");
            return false;
        }

        // If JustType IME is open and cursor is inside keyboard region, let the key event pass through
        if (isJustTypeNativeAppIME() && keyboardManager.isKeyboardOpen()) {
            Rect keyboardBounds = keyboardManager.getKeyboardBounds();
            if (!keyboardBounds.isEmpty()) {
                int[] cursorPosition = getCursorPosition();
                if (cursorPosition != null && keyboardBounds.contains(cursorPosition[0], cursorPosition[1])) {
                    // JustType IME is open and cursor is inside keyboard region - let event pass through
                    Log.d(TAG, "onKeyEvent: JustType IME open, cursor in keyboard region - allowing key event to pass through");
                    return false;
                }
            }
        }

        return handleKeyEvent(event);
    }

    /**
     * Check if the current foreground activity is JustType's switch setup activity.
     * When these activities are in the foreground, switch key events should be passed through
     * so users can assign their switches.
     */
    private boolean isJustTypeSwitchSetupActivity() {
        if (currentForegroundPackage == null || currentForegroundActivity == null) {
            return false;
        }
        
        // Check if it's JustType package and a switch setup activity
        if (currentForegroundPackage.contains("justtype")) {
            return currentForegroundActivity.contains("SetupSingleSwitchActivity") ||
                   currentForegroundActivity.contains("SetupTwoSwitchActivity");
        }
        return false;
    }
    
    /**
     * Handles a key event by dispatching it's corresponding action if applicable.
     *
     * @param event The key event.
     * @return True if the key event is handled.
     */
    private boolean handleKeyEvent(KeyEvent event) {
        if (serviceState != ServiceState.ENABLE && serviceState != ServiceState.PAUSE) {
            return false;
        }
        BlendshapeEventTriggerConfig.EventType eventType =
            keyBindingManager.actionForKeyCode(event.getKeyCode());
        if (eventType == null) {
            // Unbound key: normal system handling.
            return false;
        }
        boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;
        if (!keyBindingManager.debouncer().shouldDispatch(
                event.getKeyCode(), isDown, event.getRepeatCount(), event.getEventTime())) {
            // Bounce or auto-repeat of a held switch: swallow it so it neither re-fires the
            // action nor leaks into the focused app.
            return true;
        }
        Log.d(TAG, "handleKeyEvent: " + KeyEvent.keyCodeToString(event.getKeyCode()) + " -> " + eventType);
        dispatchEvent(new BlendshapeEventTriggerConfig.EventDetails(
            eventType, BlendshapeEventTriggerConfig.Blendshape.KEY, isDown), event);
        return true;
    }

    /**
     * Dispatches a tap gesture at the specified cursor position.
     *
     * @param cursorPosition The cursor position in screen coordinates.
     * @param duration       The duration of the tap gesture in milliseconds.
     */
    public void dispatchTapGesture(int[] cursorPosition, Integer duration) {

        if (duration == null) {
            duration = 200;
        }
        dispatchGesture(
            CursorUtils.createClick(
                cursorPosition[0], cursorPosition[1],
                /* startTime= */ 0,
                /* duration= */ duration),
            /* callback= */ null,
            /* handler= */ null);

        serviceUiManager.drawTouchDot(cursorPosition);
    }

    public void beginDragOrHold(int[] cursorPosition) {
        if (cursorController.isDragging) {
            Log.d("dispatchDragOrHold", "cancel previous drag action");
            cancelDragOrHold();
        }
        if (!cursorController.isDragging) {
            Log.d("dispatchDragOrHold", "new drag action");
            if (cursorPosition == null || cursorPosition.length < 2) {
                Log.e("dispatchDragOrHold", "Invalid cursor position for beginDragOrHold");
                cursorPosition = cursorController.getPathCursorPositionXY();
            }

            cursorController.prepareDragStart(cursorPosition[0], cursorPosition[1]);
            serviceUiManager.setDragLineStart(cursorPosition[0], cursorPosition[1]);
            serviceUiManager.fullScreenCanvas.setHoldRadius(
                cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HOLD_RADIUS));
        }
    }

    public void endDragOrHold(int[] cursorPosition) {
        cursorController.prepareDragEnd(cursorPosition[0], cursorPosition[1]);
        serviceUiManager.fullScreenCanvas.clearDragLine();

        // Cursor path distance.
        float xOffset = cursorController.dragEndX - cursorController.dragStartX;
        float yOffset = cursorController.dragEndY - cursorController.dragStartY;

        // Is action finished inside defined circle or not.
        boolean isFinishedInside =
            (Math.abs(xOffset) < cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HOLD_RADIUS)) &&
                (Math.abs(yOffset) <  cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.HOLD_RADIUS));

        // If finished inside a circle, trigger HOLD action.
        if (isFinishedInside) {
            // Dispatch HOLD event.
            dispatchGesture(
                CursorUtils.createClick(
                    cursorController.dragStartX,
                    cursorController.dragStartY,
                    0,
                    (long)
                        cursorController.cursorMovementConfig.get(
                            CursorMovementConfig.CursorMovementConfigType.HOLD_TIME_MS)),
                /* callback= */ null,
                /* handler= */ null);
        }
        // Trigger normal DRAG action.
        else {
            dispatchGesture(
                CursorUtils.createSwipe(
                    cursorController.dragStartX,
                    cursorController.dragStartY,
                    xOffset,
                    yOffset,
                    /* duration= */
                    250
                ),
                /* callback= */ null,
                /* handler= */ null
            );
        }
    }

    private void cancelDragOrHold() {
        serviceUiManager.fullScreenCanvas.clearDragLine();
        cursorController.prepareDragEnd(0, 0);
    }

    /**
     * Dispatches a drag or hold action based on the current cursor position.
     */
    public void dispatchDragOrHold() {
        Log.d("dispatchDragOrHold", "dispatchDragOrHold");
        int[] cursorPosition = cursorController.getPathCursorPositionXY();

        // Register new drag action.
        if (!cursorController.isDragging) {
            beginDragOrHold(cursorPosition);
        }
        // Finish drag action.
        else {
            Log.d("dispatchDragOrHold", "end drag action");
            endDragOrHold(cursorPosition);
        }
    }

    /**
     * Handles the toggle touch action.
     *
     * @return True if the touch is toggled.
     */
    public void toggleTouch() {
        Log.d(TAG, "toggleTouch()");
        int[] cursorPosition = getPathCursorPosition();

        if (cursorController.isSwiping && cursorController.swipeToggleActive) {
            Log.d(TAG, "STOP SWIPE TOGGLE KeyEvent.ACTION_DOWN");
            cursorController.swipeToggleActive = false;
            handleSwipeEvent(false);
        } else if (!cursorController.isSwiping && !cursorController.swipeToggleActive) {
            Log.d(TAG, "START SWIPE TOGGLE KeyEvent.ACTION_DOWN");
            cursorController.swipeToggleActive = true;
            handleSwipeEvent(true);
        } else if (cursorController.isDragging) {
            Log.d(TAG, "DRAG TOGGLE KeyEvent.ACTION_DOWN");
            cancelDragOrHold();
        }
    }

    /**
     * Delete the last word in the focused EditText.
     */
    public void deleteLastWord() {
        Log.d(TAG, "deleteLastWord()");
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.d(TAG, "deleteLastWord(): rootNode is null");
            return;
        }

        AccessibilityNodeInfo focusedNode = findFocusedEditText(rootNode);
        if (focusedNode != null && focusedNode.getText() != null) {
            Log.d(TAG, "deleteLastWord(): focusedNode: " + focusedNode);
            String text = focusedNode.getText().toString();
            if (text.isEmpty()) {
                Log.d(TAG, "deleteLastWord(): text is empty");
                return;
            }
            int cursorPosition = focusedNode.getTextSelectionStart(); // get current cursor position
            if (cursorPosition <= 0) return;
            DeleteResult modifiedTextResult = removeLastWord(text, cursorPosition);

            Bundle setModifiedTextArgs = new Bundle();
            setModifiedTextArgs.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                modifiedTextResult.text);

            Bundle setCursorPositionArgs = new Bundle();
            setCursorPositionArgs.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                modifiedTextResult.newCursor);
            setCursorPositionArgs.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                modifiedTextResult.newCursor);

            focusedNode.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.getId(),
                setModifiedTextArgs);
            focusedNode.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION.getId(),
                setCursorPositionArgs);
        }
    }

    /**
     * Class to hold the result of the delete operation.
     */
    private static class DeleteResult {
        String text;
        int newCursor;
    }

    /**
     * Find the focused EditText node in the accessibility tree.
     *
     * @param rootNode The root node of the accessibility tree.
     * @return The focused EditText node, or null if not found.
     */
    private AccessibilityNodeInfo findFocusedEditText(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) {
            return null;
        }

        if (rootNode.isFocused() && rootNode.getClassName().equals("android.widget.EditText")) {
            return rootNode;
        }

        for (int i = 0; i < rootNode.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = rootNode.getChild(i);
            AccessibilityNodeInfo result = findFocusedEditText(childNode);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    /**
     * Remove the last word from the given text and return the modified text and new cursor position.
     *
     * @param text   The original text.
     * @param cursor The current cursor position.
     * @return A DeleteResult object containing the modified text and new cursor position.
     */
    private DeleteResult removeLastWord(String text, int cursor) {
        DeleteResult result = new DeleteResult();
        if (text == null || text.isEmpty()) {
            result.text = "";
            result.newCursor = 0;
            return result;
        }

        // First, find the start and end of the current word if cursor is inside a word
        int wordStart = cursor;
        int wordEnd = cursor;

        // Find start of current word
        while (wordStart > 0 && Character.isLetterOrDigit(text.charAt(wordStart - 1))) {
            wordStart--;
        }

        // Find end of current word
        while (wordEnd < text.length() && Character.isLetterOrDigit(text.charAt(wordEnd))) {
            wordEnd++;
        }

        // If cursor is inside a word, delete that word
        if (wordStart < cursor && wordEnd > cursor) {
            result.text = text.substring(0, wordStart) + text.substring(wordEnd);
            result.newCursor = wordStart;
            return result;
        }

        // Otherwise, proceed with original logic
        int start = cursor;
        char ch = text.charAt(cursor - 1);
        if (ch == '\n') {
            start = cursor - 1;
        } else if (Character.isWhitespace(ch)) {
            while (start > 0) {
                char c = text.charAt(start - 1);
                if (c == '\n' || !Character.isWhitespace(c)) break;
                start--;
            }
            if (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) {
                while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) start--;
            }
        } else if (Character.isLetterOrDigit(ch)) {
            while (start > 0) {
                char c = text.charAt(start - 1);
                if (c == '\n' || !Character.isLetterOrDigit(c)) break;
                start--;
            }
        } else {
            start = cursor - 1;
        }
        result.text = text.substring(0, start) + text.substring(cursor);
        result.newCursor = start;
        return result;
    }

    private int[] dragToggleStartPosition = new int[2];

    /**
     * Handle continuous touch action.
     */
    public void continuousTouch(boolean isStarting) {
        Log.d(TAG, "continuousTouch() SWIPE isStarting: " + isStarting);

        int[] cursorPosition;
        cursorPosition = getCursorPosition();

        if (isStarting && !cursorController.continuousTouchActive) {
            cursorController.continuousTouchActive = true;
            Log.d(TAG, "continuousTouch() SWIPE KeyEvent.ACTION_DOWN");

            if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                // Use ContinuousGestureController for keyboard
//                startGestureDescSwipe(cursorPosition);
                throw new UnsupportedOperationException("Continuous swipe gesture not implemented yet.");
            } else {
                // Use drag toggle for non-keyboard areas
                dragToggleStartTime = SystemClock.uptimeMillis();
                dragToggleCancelled = false;
                dragToggleStartPosition = cursorPosition;
                cursorController.isDragging = true;
                dragToggleHandler.postDelayed(dragToggleOrTapOnCancelRunnable, getActionStateChangeDelay());
            }
        } else if (cursorController.continuousTouchActive) {
            cursorController.continuousTouchActive = false;

            Log.d(TAG, "continuousTouch() CONTINUOUS GESTURE SWIPE KeyEvent.ACTION_UP");
            if (cursorController.isSwiping) {
                // End continuous swipe gesture if it's active
                endGestureDescSwipe();
            } else {
                // Handle drag toggle logic
                long elapsedTime = SystemClock.uptimeMillis() - dragToggleStartTime;
                dragToggleHandler.removeCallbacks(dragToggleOrTapOnCancelRunnable);
                if (elapsedTime < getActionStateChangeDelay()) {
                    dragToggleCancelled = true;
                    // Perform quick tap instead of enabling drag toggle
                    // Use shorter duration for non-keyboard areas (suggestion strip) 
                    // to avoid triggering long press handler
                    dispatchTapGesture(dragToggleStartPosition, Config.QUICK_TAP_DURATION);
                } else {
                    endDragOrHold(cursorPosition);
                }
            }
        }
    }

    /**
     * Handle start touch action.
     */
    public void startTouch() {
        int[] cursorPosition = new int[2];
        cursorPosition = getPathCursorPosition();
        if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
            Log.d(TAG, "START SWIPE");
            cursorController.swipeToggleActive = true;
            cancelDragOrHold();
            startRealtimeSwipe(cursorPosition);
        } else if (!cursorController.isDragging) {
            Log.d(TAG, "START DRAG");
            beginDragOrHold(cursorPosition);
        } else {
            cancelDragOrHold();
        }
    }

    /**
     * Handle stop touch action.
     */
    public void stopTouch() {
        int[] cursorPosition = new int[2];
        cursorPosition = getPathCursorPosition();
        if (cursorController.isSwiping) {
            Log.d(TAG, "STOP SWIPE");
            stopRealtimeSwipe();
        } else if (cursorController.isDragging) {
            Log.d(TAG, "STOP DRAG");
            endDragOrHold(cursorPosition);
        }
        else {
            cancelDragOrHold();
        }
    }

    // Fields for smart touch state
    private long smartTouchStartTime;
    private int[] smartTouchStartPosition;
    private boolean smartTouchCancelled = false;
    private final Handler smartTouchHandler = new Handler(Looper.getMainLooper());
    private int quickTapThreshold = CursorMovementConfig.InitialRawValue.ACTION_STATE_CHANGE_DELAY;
    private int longTapThreshold = CursorMovementConfig.InitialRawValue.LONG_TAP_THRESHOLD;

    /**
     * Runnable for quick touch action.
     * This will be executed after a delay to allow for quick touch to be cancelled.
     */
    private final Runnable quickTouchRunnable = new Runnable() {
        @Override
        public void run() {
            if (!smartTouchCancelled && cursorController.smartTouchActive) {
                // Start animating to red when we hit quick delay
                serviceUiManager.cursorAnimateToColor("RED", longTapThreshold - quickTapThreshold);
            }
        }
    };

    /**
     * Runnable for long touch action.
     * This will be executed after a delay to allow for long touch to be cancelled.
     */
    private final Runnable longTouchRunnable = new Runnable() {
        @Override
        public void run() {
            if (!smartTouchCancelled && cursorController.smartTouchActive) {
                // Execute long tap
                dispatchTapGesture(smartTouchStartPosition, 650);
                // Reset cursor to white
                serviceUiManager.cursorSetColor("WHITE");
                cursorController.smartTouchActive = false;
            }
        }
    };

    public boolean combinedTap(KeyEvent event) {
        Log.d(TAG, "smartTouch() KeyEvent: " + event);

        int eventAction = -1;
        if (event != null) {
            eventAction = event.getAction();
        }

        if (eventAction == KeyEvent.ACTION_DOWN && !cursorController.smartTouchActive) {

            quickTapThreshold = getActionStateChangeDelay();
            longTapThreshold = getActionStateChangeDelay();

            // Start the smart touch sequence
            cursorController.smartTouchActive = true;
            smartTouchStartTime = SystemClock.uptimeMillis();
            smartTouchCancelled = false;
            smartTouchStartPosition = getCursorPosition();

            // Start animating to green, duration matches quick delay
            serviceUiManager.cursorAnimateToColor("GREEN", quickTapThreshold);

            // Schedule both quick and long touch handlers
            smartTouchHandler.postDelayed(quickTouchRunnable, quickTapThreshold);
            smartTouchHandler.postDelayed(longTouchRunnable, longTapThreshold);

        } else if (eventAction == KeyEvent.ACTION_UP && cursorController.smartTouchActive) {

            // Calculate how long the touch has been active
            long elapsedTime = SystemClock.uptimeMillis() - smartTouchStartTime;

            // Remove pending handlers
            smartTouchHandler.removeCallbacks(quickTouchRunnable);
            smartTouchHandler.removeCallbacks(longTouchRunnable);

            // Mark as cancelled to prevent pending callbacks from executing
            smartTouchCancelled = true;
            cursorController.smartTouchActive = false;

            // Cancel any ongoing color animation
            serviceUiManager.cursorCancelAnimation();

            // Determine which action to take based on elapsed time
            if (elapsedTime >= quickTapThreshold) {
                // Long touch
                dispatchTapGesture(smartTouchStartPosition, getSystemLongpressDelay());
            } else if (elapsedTime <= quickTapThreshold) {
                // Quick touch
                dispatchTapGesture(smartTouchStartPosition, 250);
            }

            // Reset cursor to white after any action
            serviceUiManager.cursorSetColor("WHITE");
        }

        return true;
    }

    private final Handler dragToggleHandler = new Handler(Looper.getMainLooper());
    private boolean dragToggleCancelled = false;
    private long dragToggleStartTime;

    /**
     * Runnable to handle delayed drag toggle start.
     * This will be executed after a delay to allow for drag toggle to be cancelled.
     * If the drag toggle is cancelled, it will dispatch a CURSOR_TOUCH event instead.
     */
    private final Runnable dragToggleOrTapOnCancelRunnable = new Runnable() {
        @Override
        public void run() {
            if (!dragToggleCancelled) {
                dispatchDragOrHold();
            } else {
                Log.d(TAG, "Drag toggle cancelled");
                int[] cursorPosition = dragToggleStartPosition;
                // Use shorter duration for non-keyboard areas (suggestion strip)
                // to avoid triggering long press handler
                dispatchTapGesture(cursorPosition, Config.QUICK_TAP_DURATION);
            }
        }
    };

    int[] lastValidCoords = new int[2];

    /**
     * Start realtime swipe event.
     */
    private void startRealtimeSwipe(int[] startCoords) {
        cursorController.isSwiping = true;
        cursorController.isRealtimeSwipe = true;
        startUptime = SystemClock.uptimeMillis();
        startTime = System.currentTimeMillis();
        int[] initialPosition = getCursorPosition();
        if (startCoords != null && startCoords.length == 2) {
            initialPosition[0] = startCoords[0];
            initialPosition[1] = startCoords[1];
        }

        if (cursorController.checkForSwipingFromRightKbd) {
            lastValidCoords = initialPosition;
            cursorController.startedSwipeFromRightKbd = true;

            // Correct the cursor position to start swipe
//            initialPosition[0] = initialPosition[0] - 1;
            return;
        } else {
            cursorController.startedSwipeFromRightKbd = false;
        }

        new Thread(() -> {
            if (keyboardManager.canInjectEvent(initialPosition[0], initialPosition[1])) {
                lastValidCoords = initialPosition;
                MotionEvent event = MotionEvent.obtain(
                    startUptime,
                    startUptime,
                    MotionEvent.ACTION_DOWN,
                    initialPosition[0],
                    initialPosition[1],
                    0);
                injectMotionEvent(event);
                debugText[0] = "Swiping";
                debugText[1] = "X, Y: (" + initialPosition[0] + ", " + initialPosition[1] + ")";
            } else {
                Log.d(TAG, "Coords do not belong to either sender app or IME. TODO: Implement for 3rd party apps.");
            }

            long lastCheckTime = System.currentTimeMillis();
            while (cursorController.isSwiping) {
                int[] cursorPosition = getPathCursorPosition();
                long now = SystemClock.uptimeMillis();
                try {
                    if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                        lastValidCoords = cursorPosition;
                        MotionEvent event = MotionEvent.obtain(
                            startUptime,
                            now,
                            MotionEvent.ACTION_MOVE,
                            cursorPosition[0],
                            cursorPosition[1],
                            0);
                        injectMotionEvent(event);
                        debugText[0] = "Swiping";
                        debugText[1] = "X, Y: (" + cursorPosition[0] + ", " + cursorPosition[1] + ")";
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error while injecting swipe input event in startRealtimeSwipe: " + e);
                }
                try {
                    Thread.sleep(16); // 60 FPS
                } catch (Exception e) {
                    Log.e(TAG, "Error while sleeping in startRealtimeSwipe: " + e);
                }

            }
        }).start();
    }

    /**
     * Stop realtime swipe event by sending an ACTION_UP event.
     */
    private void stopRealtimeSwipe() {
        endUptime = SystemClock.uptimeMillis();
        endTime = System.currentTimeMillis();
        int[] cursorPosition = getPathCursorPosition();
        int keyWidth = keyboardManager.getKeyboardBounds().width() / 10;
        if (cursorController.startedSwipeFromRightKbd && (cursorPosition[0] < screenSize.x) &&
            (cursorPosition[0] >= screenSize.x - (keyWidth * 2)) /*(cursorPosition[0] (screenSize.x / 2))*/) {
            handleSwipeFromRightKbd();
            cursorController.startedSwipeFromRightKbd = false;
            cursorController.isSwiping = false;
            cursorController.isRealtimeSwipe = false;
            return;
        }

        new Thread(() -> {
            try {
                int action = MotionEvent.ACTION_UP;
                int[] cursorCoords = cursorPosition;

                // Cancel swipe if it ends too close to the right edge
                if (cursorCoords[0] >= screenSize.x - 5) {
                    action = MotionEvent.ACTION_CANCEL;
                    // TODO: visual feedback that indicates the status of conditions required to cancel swype.
                    // !      » i.e. while actively swyping: cursor should turn red while inside of this region
                    // !      » (and/or whatever other conditions we add to cancel a swype)
                }
                // if current cursor position is outside of keyboard bounds, use last valid coords
                else if (!keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                    cursorCoords = lastValidCoords;
                }

                MotionEvent event = MotionEvent.obtain(
                    startUptime,
                    endUptime,
                    action,
                    cursorCoords[0],
                    cursorCoords[1],
                    0);
                injectMotionEvent(event);

                debugText[0] = "Swiping";
                debugText[1] = "X, Y: (" + cursorCoords[0] + ", " + cursorCoords[1] + ")";
                Log.d(TAG, "MotionEvent.ACTION_UP @ (" + cursorCoords[0] + ", " + cursorCoords[1] + ")");
            } catch (Exception e) {
                writeToFile.logError(TAG, "ERROR WHILE ENDING SWIPE!!!: sendPointerSync cannot be called from the main thread." + e);
                Log.e(TAG, "sendPointerSync cannot be called from the main thread.", e);
            }
            cursorController.isSwiping = false;
            cursorController.isRealtimeSwipe = false;
//            displaySwipeInfo();
        }).start();
    }

    /**
     * Handle swipe from right keyboard event.
     */
    /** A bound trigger acting as one of JustType's two switches (see DispatchEventHelper). */
    public void sendJustTypeSwitchEvent(int switchIndex, boolean isDown) {
        keyboardManager.sendJustTypeSwitchEvent(switchIndex, isDown);
    }

    public void handleSwipeFromRightKbd() {
        BlendshapeEventTriggerConfig.EventType eventType =
            keyBindingManager.swipeFromRightKbdAction();
        if (eventType != null) {
            dispatchEvent(
                new BlendshapeEventTriggerConfig.EventDetails(
                    eventType,
                    BlendshapeEventTriggerConfig.Blendshape.SWIPE_FROM_RIGHT_KBD,
                    false),
                null);
        }
    }

    /**
     * Start continuous swipe gesture for touch simulation using ContinuousGestureController.
     * This method uses the accessibility service's dispatchGesture instead of motion events.
     *
     * @param startCoords The starting coordinates for the continuous swipe gesture
     */
    private void startGestureDescSwipe(int[] startCoords) {
        Log.d(TAG, "startContinuousSwipe() - Starting continuous swipe gesture at (" + startCoords[0] + ", " + startCoords[1] + ")");
        
        cursorController.isSwiping = true;
        cursorController.isRealtimeSwipe = true;
        startUptime = SystemClock.uptimeMillis();
        startTime = System.currentTimeMillis();
        
        // Start the continuous swipe gesture and send initial position
        continuousGestureController.startGesture();
        continuousGestureController.updateCursorPosition(startCoords[0], startCoords[1]);
        
        Log.d(TAG, "Continuous swipe gesture started successfully");
        debugText[0] = "Gesture Swipe";
        debugText[1] = "X, Y: (" + startCoords[0] + ", " + startCoords[1] + ")";
    }

    /**
     * Update continuous swipe gesture with current cursor position.
     * This should be called continuously while the continuous swipe gesture is active.
     */
    private void updateGestureDescSwipe() {
        ContinuousGestureController.GestureStatus status = continuousGestureController.getStatus();
        if (status.isActive) {
            int[] cursorPosition = getPathCursorPosition();
            if (cursorPosition != null) {
                continuousGestureController.updateCursorPosition(cursorPosition[0], cursorPosition[1]);
                debugText[0] = "Continuous Gesture Swipe";
                debugText[1] = "X, Y: (" + cursorPosition[0] + ", " + cursorPosition[1] + ")";
            }
        }
    }

    /**
     * End continuous swipe gesture for touch simulation using ContinuousGestureController.
     */
    private void endGestureDescSwipe() {
        Log.d(TAG, "endContinuousSwipe() - Ending continuous swipe gesture");
        
        endUptime = SystemClock.uptimeMillis();
        endTime = System.currentTimeMillis();
        
        ContinuousGestureController.GestureStatus status = continuousGestureController.getStatus();
        if (status.isActive) {
            continuousGestureController.stopGesture();
            Log.d(TAG, "Continuous swipe gesture ended successfully");
        }
        
        cursorController.isSwiping = false;
        cursorController.isRealtimeSwipe = false;
    }

    /**
     * Inject a motion event into the system.
     *
     * @param event The motion event to inject.
     */
    private void injectMotionEvent(MotionEvent event) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S) { // Android 12 (API 31)
            if (cursorController.isDragging) {
                mainHandler.post(() -> {;
                    cancelDragOrHold();
                });
            }

//            Log.d(TAG, "[666] Sending MotionEvent to IME");
//            Log.d(TAG, "MotionEvent sent: (" + event.getX() + ", " + event.getY() + ", action=" + event.getAction() + ")");
            sendMotionEventToIME((int) event.getX(), (int) event.getY(), event.getAction());
        } else {
            try {
                instrumentation.sendPointerSync(event);
                Log.d(TAG, "MotionEvent sent: (" + event.getX() + ", " + event.getY() + ", action=" + event.getAction() + ")");
            } catch (Exception e) {
                Log.e(TAG, "Failed to send MotionEvent(" + event.getX() + ", " + event.getY() + ", action=" + event.getAction() + ")", e);
            }
        }
    }

    /**
     * Send motion event to OpenBoard IME to simulate touch events
     *
     * @param x      The x coordinate of the touch event.
     * @param y      The y coordinate of the touch event.
     * @param action The action of the touch event (e.g., MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).
     */
    private void sendMotionEventToIME(int x, int y, int action) {
        keyboardManager.sendMotionEventToIME(x, y, action);
    }

    /**
     * Send key event to OpenBoard IME to simulate virtual keyboard key presses
     *
     * @param keyCode     The key code to send.
     * @param isDown      Whether the key is pressed down or released.
     * @param isLongPress Whether the key is a long press.
     */
    private void sendKeyEventToIME(int keyCode, boolean isDown, boolean isLongPress) {
        keyboardManager.sendKeyEventToIME(keyCode, isDown, isLongPress);
    }

    /**
     * Send gesture trail color to OpenBoard IME.
     *
     * @param color The color to send. ("green", "red", "orange")
     */
    private void sendGestureTrailColorToIME(String color) {
        keyboardManager.sendGestureTrailColorToIME(color);
    }

    /**
     * Send long press delay to OpenBoard IME.
     *
     * @param delay The long press delay in milliseconds.
     */
    private void sendLongPressDelayToIME(int delay) {
        keyboardManager.sendLongPressDelayToIME(delay);
    }

    /**
     * [OLD MOTION EVENT INJECTION METHOD, requires platform signed app and INJECT_EVENTS perm.]
     * Injects the given input event into the system.
     *
     * @param event The input event to inject.
     */
    private void injectInputEvent(MotionEvent event) {
        try {
            InputManager inputManager = (InputManager) this.getSystemService(Context.INPUT_SERVICE);
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Method injectInputEventMethod = inputManagerClass.getMethod(
                "injectInputEvent",
                InputEvent.class,
                int.class);
            injectInputEventMethod.setAccessible(true);

            // INJECT_INPUT_EVENT_MODE_ASYNC is 0
            injectInputEventMethod.invoke(inputManager, event, 0);
        } catch (Exception e) {
            Log.e(TAG, "Error while injecting input event: " + e);
            e.printStackTrace();
        }
    }

    /* Get settings from cursorMovementConfig */

    public boolean isRealtimeSwipeEnabled() {
        return cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.REALTIME_SWIPE);
    }

    public boolean isPitchYawEnabled() {
        return cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.PITCH_YAW);
    }

    public boolean isNoseTipEnabled() {
        return cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.NOSE_TIP);
    }

    public boolean isDebugSwipeEnabled() {
        return cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementBooleanConfigType.DEBUG_SWIPE);
    }

    public long getDragToggleDelay() {
        return (long) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.DRAG_TOGGLE_DURATION);
    }

    public int getActionStateChangeDelay() {
        return (int) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.ACTION_STATE_CHANGE_DELAY);
    }

//    public int getLongTapThreshold() {
//        return (int) cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.LONG_TAP_THRESHOLD);
//    }

    public int getUiFeedbackDelay() {
        return (int) ((cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.UI_FEEDBACK_DELAY) / 10) *
            cursorController.cursorMovementConfig.get(CursorMovementConfig.CursorMovementConfigType.ACTION_STATE_CHANGE_DELAY));
    }

    /**
     * Check if the current IME package name is "org.continuouspath.justtype".
     * @return true if the current IME package name matches, false otherwise
     */
    /**
     * Cached "is JustType the default IME" answer. The raw check is a cross-process
     * settings-provider read and the tick consults it at up to 60Hz during JustType
     * sessions; the observer below invalidates the cache when the default IME changes.
     */
    private volatile Boolean cachedJustTypeIsDefaultIme = null;
    private android.database.ContentObserver defaultImeObserver;

    private void registerDefaultImeObserver() {
        defaultImeObserver = new android.database.ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                cachedJustTypeIsDefaultIme = null;
            }
        };
        getContentResolver().registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
            false,
            defaultImeObserver);
    }

    boolean isJustTypeNativeAppIME() {
        Boolean cached = cachedJustTypeIsDefaultIme;
        if (cached != null) {
            return cached;
        }
        boolean result;
        try {
            String currentKeyboardStr = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
            // IME ID format is "package/.ServiceName", extract package name
            int slashIndex = currentKeyboardStr != null ? currentKeyboardStr.indexOf('/') : -1;
            result = slashIndex > 0
                && "org.continuouspath.justtype".equals(currentKeyboardStr.substring(0, slashIndex));
        } catch (Exception e) {
            Log.e(TAG, "Error checking IME package name: " + e.getMessage());
            result = false;
        }
        cachedJustTypeIsDefaultIme = result;
        return result;
    }

    /**
     * Handle JustType head tracking enabled broadcast.
     * Arms head tracking mode - cursor will hide when it enters keyboard region.
     */
    private void handleJustTypeHeadTrackingEnabled() {
        Log.d(TAG, "JustType head tracking enabled - arming (cursor will hide on keyboard entry)");
        justTypeHeadTrackingActive = true;
        justTypePauseModeActive = false; // fresh session starts at normal rate
        // Arm head tracking - cursor will hide when entering keyboard region via engagement callback
        cursorController.setJustTypeHeadTrackingArmed(true);
        // Keep sending normalized coordinates while active
    }

    /**
     * Handle JustType head tracking disabled broadcast.
     * Show cursor and exit joystick mode.
     */
    private void handleJustTypeHeadTrackingDisabled() {
        Log.d(TAG, "JustType head tracking disabled - disarming and showing cursor");
        justTypeHeadTrackingActive = false;
        justTypePauseModeActive = false;
        // Disarm will trigger disengage callback if engaged, which shows cursor
        cursorController.setJustTypeHeadTrackingArmed(false);
        isJustTypeJoystickMode = false;
        // Ensure cursor is visible (even if wasn't engaged)
        if (serviceState != ServiceState.DISABLE) {
            serviceUiManager.showCursor();
        }
    }

    /**
     * Handle JustType Pause Mode broadcast: drop the landmarker feed to ~1fps.
     * The camera stays bound and joystick mode stays live, so JustType keeps
     * receiving (now once-per-second) pose frames and can watch for the cursor
     * entering the left Exit Zone — its un-pause zone.
     *
     * @param intent may carry "frame_interval_ms" (long) to override the default interval
     */
    private void handleJustTypePauseMode(Intent intent) {
        long intervalMs = intent.getLongExtra("frame_interval_ms", MIN_PROCESS_JUSTTYPE_PAUSED_DEFAULT);
        // Clamp: never faster than joystick rate, never slower than 5s.
        justTypePauseFrameIntervalMs = (int) Math.max(MIN_PROCESS_JUSTTYPE, Math.min(intervalMs, 5000));
        justTypePauseModeActive = true;
        Log.d(TAG, "JustType Pause Mode - reducing frame rate to one per " + justTypePauseFrameIntervalMs + "ms");
    }

    /**
     * Handle JustType un-pause broadcast: restore the normal joystick frame rate.
     * Sent when the cursor enters the left Exit Zone while paused (so the un-pause
     * dwell gesture gets full-rate samples) and again on a successful un-pause;
     * if the dwell fails JustType re-sends ACTION_HEAD_TRACKING_PAUSE.
     */
    private void handleJustTypeUnpause() {
        Log.d(TAG, "JustType un-pause - restoring normal frame rate");
        justTypePauseModeActive = false;
    }

    /**
     * Handle JustType navigation-overlay head tracking enabled broadcast.
     * Streams pose frames to the nav overlay without requiring an open IME keyboard.
     */
    private void handleJustTypeNavHeadTrackingEnabled() {
        Log.d(TAG, "JustType nav head tracking enabled - streaming pose frames to nav overlay");
        justTypeNavHeadTrackingActive = true;
        justTypeNavHeadTrackingArmed = false; // re-opened; no longer waiting on a down-push
        resetNavDownPushDetect();
    }

    /**
     * Handle JustType navigation-overlay head tracking disabled broadcast.
     * Exit nav joystick mode and show the cursor again.
     */
    private void handleJustTypeNavHeadTrackingDisabled() {
        Log.d(TAG, "JustType nav head tracking disabled - showing cursor");
        justTypeNavHeadTrackingActive = false;
        justTypeNavHeadTrackingArmed = false;
        isJustTypeJoystickMode = false;
        resetNavDownPushDetect();
        if (serviceState != ServiceState.DISABLE) {
            serviceUiManager.showCursor();
        }
    }

    /**
     * Handle JustType nav-overlay head tracking armed broadcast: the nav kbd is
     * minimized. HeadBoard runs normally (cursor visible) but watches for a downward
     * push to re-open the nav kbd (see {@link #handleNavDownPushDetect}). Arming streams
     * no frames; the cursor must leave the bottom band once before a push counts.
     */
    private void handleJustTypeNavHeadTrackingArmed() {
        Log.d(TAG, "JustType nav head tracking armed - normal cursor, watching for down-push");
        justTypeNavHeadTrackingActive = false; // not streaming while armed
        justTypeNavHeadTrackingArmed = true;
        isJustTypeJoystickMode = false;
        resetNavDownPushDetect();
        if (serviceState != ServiceState.DISABLE) {
            serviceUiManager.showCursor();
        }
    }

    /**
     * While the nav kbd is armed, detect a sustained downward cursor push and re-open it.
     * Mirrors the IME edge-hold ({@link CursorController#getHoldDuration}): the cursor must
     * dwell in the bottom band for the configured hold duration. A re-trigger guard
     * ({@link #navDownPushReady}) requires the cursor to leave the band once after arming.
     *
     * @param cursorY current cursor Y in screen pixels (larger = lower on screen)
     */
    private void handleNavDownPushDetect(int cursorY) {
        boolean inBand = cursorY >= screenSize.y * NAV_DOWN_PUSH_BAND;

        if (!navDownPushReady) {
            // Wait for the cursor to leave the band once before a push can count.
            if (!inBand) {
                navDownPushReady = true;
            }
            return;
        }

        if (!inBand) {
            if (navDownHoldStartTime != 0) {
                navDownHoldStartTime = 0;
                serviceUiManager.updateEdgeHoldActive(false);
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (navDownHoldStartTime == 0) {
            navDownHoldStartTime = now;
            serviceUiManager.updateEdgeHoldActive(true);
            return;
        }
        if (now - navDownHoldStartTime > cursorController.getHoldDuration()) {
            Log.d(TAG, "Nav down-push held - re-opening JustType nav kbd");
            justTypeNavHeadTrackingArmed = false;
            resetNavDownPushDetect();
            keyboardManager.sendHeadTrackingResumeToJustType();
        }
    }

    /** Clear the nav down-push edge-hold timer + re-trigger guard. */
    private void resetNavDownPushDetect() {
        if (navDownHoldStartTime != 0) {
            serviceUiManager.updateEdgeHoldActive(false);
        }
        navDownHoldStartTime = 0;
        navDownPushReady = false;
    }

    /**
     * Handle JustType pop-out broadcast.
     * Show cursor in text field region above keyboard.
     * Head tracking remains armed so re-entering keyboard will re-engage.
     */
    private void handleJustTypePopOut() {
        Log.d(TAG, "JustType pop-out triggered - showing cursor in text field (head tracking stays armed)");
        isJustTypeJoystickMode = false;
        justTypePauseModeActive = false;

        // Show cursor (will also be shown by onJustTypeDisengaged callback)
        serviceUiManager.showCursor();

        // Update active region to text field - this will trigger disengagement via setActiveCursorRegion
        // but keeps justTypeHeadTrackingArmed true so re-entering keyboard will re-engage
        Rect kbdBounds = keyboardManager.getKeyboardBounds();
        if (kbdBounds != null && !kbdBounds.isEmpty() && kbdBounds.top > 0) {
            cursorController.setActiveCursorRegionPublic("TOP", new Rect(0, 0, screenSize.x, kbdBounds.top - 1));
        }
    }

    // JustTypeEngagementListener implementation
    @Override
    public void onJustTypeEngaged() {
        Log.d(TAG, "JustType engaged - cursor entered keyboard region, hiding cursor");
        serviceUiManager.hideCursor();
        if (isPathCursorActive) {
            serviceUiManager.hidePathCursor();
            cursorController.setIsPathCursorVisible(false);
        }
    }

    @Override
    public void onJustTypeDisengaged() {
        Log.d(TAG, "JustType disengaged - cursor left keyboard region, showing cursor");
        serviceUiManager.showCursor();
    }

    float maxXValue = 0.0f; // max yaw
    float minXValue = 0.0f; // min yaw
    float maxYValue = 0.0f; // max pitch
    float minYValue = 0.0f; // min pitch

    /**
     * Normalize pitch/yaw degrees to -1.0 to 1.0 range.
     * Clamps values to ±45° bounds then divides by 45.0f.
     * @param pitchYaw Array containing [pitch, yaw] in degrees
     * @return Array containing normalized [x, y] where x maps to yaw and y maps to pitch
     */
    private float[] normalizePitchYaw(float[] pitchYaw) {
        if (pitchYaw == null || pitchYaw.length < 2) {
            return new float[]{0.0f, 0.0f};
        }

        // update min/max for debugging
        if (pitchYaw[1] > maxXValue) {
            maxXValue = pitchYaw[1];
        }
        if (pitchYaw[1] < minXValue) {
            minXValue = pitchYaw[1];
        }
        if (pitchYaw[0] > maxYValue) {
            maxYValue = pitchYaw[0];
        }
        if (pitchYaw[0] < minYValue) {
            minYValue = pitchYaw[0];
        }
//        Log.d(TAG, "Raw Pitch/Yaw - " +
//            "\n\tYaw/X:   " + pitchYaw[1] + " (min: " + minXValue + ", max: " + maxXValue + "), " +
//            "\n\tPitch/Y: " + pitchYaw[0] + " (min: " + minYValue + ", max: " + maxYValue + ")");

        float yaw = pitchYaw[1];
        float pitch = pitchYaw[0];
        
        // Clamp to ±30° bounds
        float clampedYaw = Math.max(-30.0f, Math.min(30.0f, yaw));
        float clampedPitch = Math.max(-30.0f, Math.min(30.0f, pitch));
        
        // Normalize: divide by 30.0f to get -1.0 to 1.0 range
        // x maps to yaw (horizontal movement)
        // y maps to pitch (vertical movement)
        float normalizedX = clampedYaw / 30.0f;
        float normalizedY = clampedPitch / 30.0f;
        
        return new float[]{normalizedX, normalizedY};
    }

    /**
     * Check if the given coordinates are on the suggestion strip.
     * The suggestion strip is at the top of the keyboard window, typically 40-44dp high.
     * @param x X coordinate
     * @param y Y coordinate
     * @return true if the coordinates are on the suggestion strip, false otherwise
     */
    private boolean isOnSuggestionStrip(int x, int y) {
        if (!keyboardManager.isKeyboardOpen()) {
            return false;
        }
        Rect keyboardBounds = keyboardManager.getKeyboardBounds();
        if (keyboardBounds.isEmpty()) {
            return false;
        }
        // Check if point is within keyboard bounds
        if (!keyboardBounds.contains(x, y)) {
            return false;
        }
        // Suggestion strip is at the top of the keyboard window, typically 40-44dp (~120-132px at mdpi)
        // Use a conservative estimate of 150px to account for different screen densities
        int suggestionStripHeight = 150; // pixels
        int distanceFromTop = y - keyboardBounds.top;
        return distanceFromTop <= suggestionStripHeight;
    }

    /**
     * Get the height of the navigation bar.
     *
     * @param context The context of the application.
     * @return The height of the navigation bar in pixels.
     */
    public int getNavigationBarHeight(Context context) {
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId);
        }
        return 0; // Return 0 if no navigation bar is present
    }

    /**
     * Get the current cursor position.
     *
     * @return An array containing the x and y coordinates of the cursor.
     */
    private int[] getCursorPosition() {
        return cursorController.getCursorPositionXY();
    }

    /**
     * Get the current cursor position.
     *
     * @return An array containing the x and y coordinates of the cursor.
     */
    private int[] getPathCursorPosition() {
        return cursorController.getPathCursorPositionXY();
    }

    /**
     * Check if the app is platform signed and has INJECT_EVENTS permission.
     */
    private boolean isPlatformSignedAndCanInjectEvents() {
        boolean isPlatformSigned = isPlatformSigned();
        boolean canInjectEvents = checkCallingOrSelfPermission("android.permission.INJECT_EVENTS") == PackageManager.PERMISSION_GRANTED;

        if (canInjectEvents) {
            Log.d(TAG, "INJECT_EVENTS permission granted!");
        } else {
            Log.d(TAG, "INJECT_EVENTS permission not granted.");
        }

        if (isPlatformSigned) {
            Log.d(TAG, "App is platform signed!");
        } else {
            Log.d(TAG, "App is not platform signed.");
        }
        return canInjectEvents && isPlatformSigned;
    }

    /**
     * Check if the app is platform signed.
     */
    private boolean isPlatformSigned() {
        try {
            PackageManager pm = getPackageManager();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API 28 and above
                PackageInfo packageInfo = pm.getPackageInfo(
                    getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo platformPackageInfo = pm.getPackageInfo(
                    "android",
                    PackageManager.GET_SIGNING_CERTIFICATES);

                if (packageInfo.signingInfo != null && platformPackageInfo.signingInfo != null) {
                    Signature[] appSignatures = packageInfo.signingInfo.getApkContentsSigners();
                    Signature[] platformSignatures = platformPackageInfo.signingInfo.getApkContentsSigners();

                    for (Signature appSignature: appSignatures) {
                        for (Signature platformSignature: platformSignatures) {
                            if (appSignature.equals(platformSignature)) {
                                return true;
                            }
                        }
                    }
                }
            } else { // Below API 28
                PackageInfo packageInfo = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
                PackageInfo platformPackageInfo = pm.getPackageInfo("android", PackageManager.GET_SIGNATURES);

                Signature[] appSignatures = packageInfo.signatures;
                Signature[] platformSignatures = platformPackageInfo.signatures;

                for (Signature appSignature: appSignatures) {
                    for (Signature platformSignature: platformSignatures) {
                        if (appSignature.equals(platformSignature)) {
                            return true;
                        }
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Set image property to match the MediaPipe model. - Using RGBA 8888. - Lowe the resolution.
     * Rebuilt by updateCameraFrameRate() when the target sensor rate changes.
     */
    private ImageAnalysis imageAnalyzer = buildImageAnalysis(false);

    /* ------------------------------ START OF TAP ACTION HANDLING ------------------------------ */
    private int uiFeedbackDelay = 500;
    private int[] tapStartPosition;
    private boolean isInHoverZone = true;
    private boolean wasInHoverZone = true; // Track previous hover state
    private long hoverZoneExitTime = 0; // Track when cursor left hover zone
    private boolean tapEventStarted = false;
    private boolean tapEventEnding = false;
    private long tapStartTime;
    private boolean tapInsideKbd = false;

    /**
     * Handles tap events when the switch is pressed or released.
     *
     * @param isStarting true if the switch is being pressed down, false if released
     */
    public void handleTapEvent(boolean isStarting) {
        // if (!tapEventStarted && !tapEventEnding) {
        if (isStarting && !tapEventStarted && !tapEventEnding) {
            Log.d(TAG, "handleTapEvent Switch pressed");
            cursorController.isCursorTap = true;
            int[] cursorPosition = getCursorPosition();
            startTapSequence(cursorPosition);
        } else if (!isStarting && tapEventStarted && !tapEventEnding) {
            Log.d(TAG, "handleTapEvent Switch released");
            endTapSequence();
        } else {
            Log.d(TAG, "handleTapEvent: Tap event already handled and ending");
        }
    }

    private Runnable showAltPopupRunnable = () -> {
        if (tapInsideKbd && tapEventStarted && !tapEventEnding) {
            Log.d(TAG, "startTapSequence() - Show long press key popup");
            keyboardManager.showAltKeyPopupIME(tapStartPosition[0], tapStartPosition[1]);
        }
    };
    private Runnable animateCursorTapRunnable = () -> {
        if (tapEventEnding) return;
        if (tapInsideKbd) {
            mainHandler.postDelayed(showAltPopupRunnable, getActionStateChangeDelay() - uiFeedbackDelay);
        }
        serviceUiManager.cursorSetColor("YELLOW");
        serviceUiManager.cursorAnimateToColor("BLUE", getActionStateChangeDelay(), uiFeedbackDelay);
        if (!isInHoverZone) serviceUiManager.cursorHideAnimation("RED");
    };

    /**
     * Starts the tap sequence when the switch is pressed down.
     *
     * @param initialPosition The initial cursor position when switch was pressed
     */
    private void startTapSequence(int[] initialPosition) {
        if (initialPosition == null || initialPosition.length < 2 || initialPosition[0] < 0 || initialPosition[1] < 0) {
            Log.e(TAG, "startTapSequence() - initialPosition is invalid");
            return;
        }
        // Store initial position and start time
        tapStartPosition = new int[]{initialPosition[0], initialPosition[1]};
        tapStartTime = System.currentTimeMillis();
        isInHoverZone = true;
        tapEventStarted = true;
        tapEventEnding = false;
        cursorController.isCursorTap = true;
        uiFeedbackDelay = getUiFeedbackDelay();

        tapInsideKbd = keyboardManager.canInjectEvent(tapStartPosition[0], tapStartPosition[1]);
        Log.d(TAG, "startTapSequence() - tapStartPosition: (" + tapStartPosition[0] +
            ", " + tapStartPosition[1] + "), tapInsideKbd: " + tapInsideKbd);

        if (tapInsideKbd && !tapEventEnding) {
            Log.d(TAG, "startTapSequence() - Show key popup & send long press delay to IME");
            keyboardManager.showKeyPopupIME(tapStartPosition[0], tapStartPosition[1], true);
            keyboardManager.sendLongPressDelayToIME(100);
        }

        mainHandler.postDelayed(animateCursorTapRunnable, uiFeedbackDelay);

        // Start monitoring cursor position for hover zone
        startHoverZoneMonitoring();
    }

    /**
     * Starts monitoring the cursor position to check if it stays within the hover zone.
     */
    private void startHoverZoneMonitoring() {
        new Thread(() -> {
            while (tapEventStarted && !tapEventEnding) {
                int[] currentPosition = getPathCursorPosition();
                int[] startPos = tapStartPosition;

                // Skip if either position is null
                if (currentPosition == null || startPos == null) {
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                double distance = Math.sqrt(Math.pow(currentPosition[0] - startPos[0], 2) +
                                            Math.pow(currentPosition[1] - startPos[1], 2));

                boolean newHoverState = distance <= Config.HOVER_ZONE_RADIUS;

                // Only update UI if hover state changed
                if (newHoverState != isInHoverZone) {
                    isInHoverZone = newHoverState;
                    Log.d(TAG, "HOVER ZONE " + (isInHoverZone ? "ENTERED" : "EXITED") + "; Cursor Distance: "
                        + distance + "px; Hover Zone Radius: " + Config.HOVER_ZONE_RADIUS + "px");

                    if (!isInHoverZone) {
                        // Cursor left hover zone
                        hoverZoneExitTime = System.currentTimeMillis();
                        mainHandler.post(() -> {
                            // Hide current animations and show red
                            serviceUiManager.cursorHideAnimation("RED");
                        });
                    } else {
                        // Cursor returned to hover zone
                        mainHandler.post(() -> {
                            // Show animations again
                            serviceUiManager.cursorShowAnimation();
                        });
                    }
                }

                try {
                    Thread.sleep(16); // ~60fps
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    /**
     * Ends the tap sequence when the switch is released.
     */
    private void endTapSequence() {
        // Cancel any pending runnables
        tapEventEnding = true;
        mainHandler.removeCallbacks(showAltPopupRunnable);
        mainHandler.removeCallbacks(animateCursorTapRunnable);

        // Cancel any ongoing animations
        serviceUiManager.cursorCancelAnimation();

        keyboardManager.hideAltKeyPopupIME(tapStartPosition[0], tapStartPosition[1]);

        // If cursor is not in hover zone, cancel the tap
        if (!isInHoverZone) {
            resetTapSequence();
            return;
        }

        int duration = 0;

        // Get current state
        long totalTapDuration = System.currentTimeMillis() - tapStartTime;
        if (totalTapDuration < getActionStateChangeDelay()) {
            if (tapInsideKbd) {
                Log.d(TAG, "endTapSequence() isTap, outputting quick tap");
                duration = 100;
            } else {
                // Use shorter duration for non-keyboard areas (suggestion strip)
                // to avoid triggering long press handler
                duration = Config.QUICK_TAP_DURATION;
            }
        } else {
            if (tapInsideKbd) {
                Log.d(TAG, "endTapSequence() isLongTap, outputting long tap");
                duration = 105;
            } else {
                duration = getSystemLongpressDelay();
            }
        }

        dispatchTapGesture(tapStartPosition, duration);

        mainHandler.postDelayed(
            () -> {
                // Reset after tap
                resetTapSequence();
            }, duration);
    }

    /**
     * Resets all tap sequence state variables.
     */
    private void resetTapSequence() {
        if (tapInsideKbd) {
            Log.d(TAG, "resetTapSequence() sending long press delay to IME");
            sendLongPressDelayToIME(getActionStateChangeDelay());
        }
        serviceUiManager.cursorShowAnimation();
        serviceUiManager.cursorSetColor("WHITE");
        cursorController.isCursorTap = false;
        tapInsideKbd = false;
        tapEventStarted = false;
        tapEventEnding = false;
        tapStartPosition = null;
        isInHoverZone = true;
        wasInHoverZone = true;
        hoverZoneExitTime = 0;
//        animateCursorTapRunnable = null;
//        showAltPopupRunnable = null;
    }
    /* ------------------------------- END OF TAP ACTION HANDLING ------------------------------- */

    /* ----------------------------- START OF SWIPE ACTION HANDLING ----------------------------- */
    private boolean startedInsideKbd;
    private boolean swipeEventStarted = false;
    private boolean swipeEventEnding = false;
    private int[] swipeStartPosition = null;
    private long swipeStartTime = 0;
    private boolean isTap = false;
    private boolean isSwipe = false;
    private boolean isLongTap = false;
    private Rect swipeKeyBounds = null;

    private boolean openboardSwipeStarted = false;

    private boolean isPathCursorActive = false;

    private boolean isJustTypeJoystickMode = false;

    /**
     * Broadcast-driven state: true while JustType's Pause Mode is active. The camera and
     * landmarker keep running, but the analyzer throttles to {@link #justTypePauseFrameIntervalMs}
     * so JustType still receives ~1 pose frame/sec to watch for the un-pause gesture (left
     * Exit Zone dwell). Read on the analyzer thread, written on the main thread → volatile.
     */
    private volatile boolean justTypePauseModeActive = false;

    /** Frame interval (ms) while JustType Pause Mode is active; JustType may override via extra. */
    private volatile int justTypePauseFrameIntervalMs = MIN_PROCESS_JUSTTYPE_PAUSED_DEFAULT;

    /** Broadcast-driven state: true when JustType explicitly enables head tracking mode */
    private boolean justTypeHeadTrackingActive = false;

    /**
     * Broadcast-driven state: true when JustType's navigation overlay enables head
     * tracking. Unlike {@link #justTypeHeadTrackingActive}, this does not require an
     * open JustType IME keyboard — the nav overlay is an AccessibilityService window,
     * so joystick mode streams pose frames regardless of IME/keyboard/cursor region.
     */
    private boolean justTypeNavHeadTrackingActive = false;

    /**
     * Broadcast-driven state: true when JustType's nav overlay is minimized and waiting
     * to be re-opened by a downward cursor push. HeadBoard runs normally (cursor visible)
     * while armed; {@link #handleNavDownPushDetect} watches the cursor and on a sustained
     * downward push broadcasts ACTION_HEAD_TRACKING_RESUME and clears this flag.
     */
    private boolean justTypeNavHeadTrackingArmed = false;

    /** Fraction of screen height below which the cursor counts as a downward push. */
    private static final float NAV_DOWN_PUSH_BAND = 0.88f;

    /** Edge-hold timer for the nav down-push (ms; mirrors the IME edge-hold). 0 = not holding. */
    private long navDownHoldStartTime = 0;

    /**
     * Re-trigger guard: the cursor must leave the bottom band once after arming before a
     * down-push counts. Prevents an instant re-open when the cursor reappears already low.
     */
    private boolean navDownPushReady = false;

    /**
     * Handles swipe actions based on version 3.0 specs
     *
     * @param isStarting true if the action is starting, false if it is ending
     */
    public void handleSwipeEvent(boolean isStarting) {
        if (isStarting && !swipeEventStarted && !swipeEventEnding) {
            Log.d(TAG, "handleSwipeEvent Start");
            int[] cursorPosition = getCursorPosition();
            cursorController.isCursorTouch = true;
            startSwipeSequence(cursorPosition);
        } else if (!isStarting && swipeEventStarted && !swipeEventEnding) {
            Log.d(TAG, "handleSwipeEvent End");
            endSwipeSequence();
        }
    }

//    private Runnable endTouchAnimationRunnable = () -> {
//        // Cursor is blue, indicating long tap is ready to start
//        Log.d(TAG, "endTouchAnimationRunnable called");
//        if (swipeEventEnding || cursorController.isSwiping) return;
//        canStartSwipe = false;
//        isLongTap = true;
//        startSwipeHoverZoneMonitoring();
//        serviceUiManager.pathCursorSetColor("BLUE");
//    };

//    private Runnable touchGreenToBlueRunnable = () -> {
//        // Cursor is green, indicating swipe is ready to start
//        Log.d(TAG, "touchGreenToBlueRunnable called");
//        if (swipeEventEnding) return;
//
//        mainHandler.postDelayed(endTouchAnimationRunnable, getLongTapThreshold());z
//        serviceUiManager.pathCursorSetColor("GREEN");
//        serviceUiManager.pathCursorAnimateToColor("BLUE", getLongTapThreshold());
// //        if (!isInHoverZone) serviceUiManager.pathCursorHideAnimation("RED");
//
//        if (startedInsideKbd) {
//            sendMotionEventToIME(swipeStartPosition[0], swipeStartPosition[1], MotionEvent.ACTION_CANCEL);
//        }
//
//        // start swipe if intentional movement was previously detected.
//        if (isIntentionalMovement) {
//            startSwipe();
//        }
//        else canStartSwipe = true;
//    };

    private KeyboardEventReceiver keyboardEventReceiver;

    public void onKeyboardSwipeStart() {
        Log.d(TAG, "onKeyboardSwipeStart");
        if (swipeEventStarted && !swipeEventEnding) {
            openboardSwipeStarted = true;
            mainHandler.removeCallbacks(animateCursorTouchRunnable);
            serviceUiManager.pathCursorSetColor("GREEN");
        }
    }

    public void onKeyboardLongpressAnimation() {
        Log.d(TAG, "onKeyboardLongpressAnimation");
//        if (!swipeEventEnding) {
//            serviceUiManager.pathCursorAnimateToColor("BLUE", getActionStateChangeDelay());
//        }
    }

    public void onKeyboardStateChanged() {
        Log.d(TAG, "onKeyboardStateChanged");
    }

    private Runnable animateCursorTouchRunnable = () -> {
        Log.d(TAG, "animateCursorTouchRunnable called");
        if (swipeEventEnding) return;

        // if this is a keyboard swype,
        if (startedInsideKbd) {
            if (Config.SHOW_KEY_POPUP) { // and key popup is enabled,
                // show long press popup after user feedback delay
//                keyboardManager.showKeyPopupIME(swipeStartPosition[0], swipeStartPosition[1], true);
            }
            if (openboardSwipeStarted) return;
        }
        serviceUiManager.pathCursorAnimateToColor("BLUE", getActionStateChangeDelay(), uiFeedbackDelay);
//        mainHandler.postDelayed(touchGreenToBlueRunnable, getQuickTapThreshold() - uiFeedbackDelay);

//        if (!isInHoverZone) serviceUiManager.pathCursorHideAnimation("RED");
    };

    /**
     * Starts the swipe sequence
     * @param initialPosition The initial cursor position when swipe sequence started
     */
    private void startSwipeSequence(int[] initialPosition) {
        if (initialPosition == null || initialPosition.length < 2 || initialPosition[0] < 0 || initialPosition[1] < 0) {
            Log.e(TAG, "startSwipeSequence: initialPosition is invalid");
            return;
        }
        cancelDragOrHold();

//        swipeStartPosition = new int[]{initialPosition[0], initialPosition[1]}; // actual raw start pos
        swipeStartPosition = cursorController.getRollingAverage(); // rolling avg from last D1A ms
        cursorController.setPathCursorPosition(swipeStartPosition);
        swipeStartTime = System.currentTimeMillis();
        isInHoverZone = true;
        swipeEventStarted = true;
        swipeEventEnding = false;
        cursorController.isCursorTouch = true;
        uiFeedbackDelay = getUiFeedbackDelay();
        canStartSwipe = false;
        openboardSwipeStarted = false;
        serviceUiManager.pathCursorSetColor("YELLOW");

        startedInsideKbd = keyboardManager.canInjectEvent(swipeStartPosition[0], swipeStartPosition[1]);
        Log.d(TAG, "startSwipeSequence() swipeStartPosition: (" + swipeStartPosition[0] +
            ", " + swipeStartPosition[1] + "), startedInsideKbd: " + startedInsideKbd);

        if (startedInsideKbd && !swipeEventEnding) {
            if (Config.HIGHLIGHT_KEY_ON_TOUCH) {
//                keyboardManager.highlightKeyAt(swipeStartPosition[0], swipeStartPosition[1]);
            }
            keyboardManager.sendLongPressDelayToIME(getActionStateChangeDelay());
            startSwipe(); // start sending touch events immediately for keyboard swype
            mainHandler.postDelayed(animateCursorTouchRunnable, uiFeedbackDelay);
        } else if (!startedInsideKbd && !swipeEventEnding) {
            beginDragOrHold(swipeStartPosition);
//            startGestureDescSwipe(swipeStartPosition);
        }

//        swipeKeyBounds = keyboardManager.getKeyBounds(swipeStartPosition);

        // Start initial hover period (D1A)

//        if (!startedInsideKbd && !swipeEventEnding) {
//            startSwipeHoverZoneMonitoring(); // Start monitoring cursor position for hover zone
//            startMovementMonitoring(); // Start movement monitoring
//        }
    }

    private void endSwipe() {
        cursorController.isSwiping = false;
        if (startedInsideKbd) {
            Log.d(TAG, "cancelSwipe() called, stopping swipe");
            endUptime = SystemClock.uptimeMillis();
            endTime = System.currentTimeMillis();
            int[] cursorPosition = getPathCursorPosition();
            // TODO: if kbd bounds is null, use screen (or active bound) width as default.
            //       AND set actual key width in keyboardManager via searching node tree.
            int keyWidth = keyboardManager.getKeyboardBounds().width() / 10;
            if (cursorController.startedSwipeFromRightKbd && (cursorPosition[0] < screenSize.x) &&
                (cursorPosition[0] >= screenSize.x - (keyWidth * 2))) {
                handleSwipeFromRightKbd();
                cursorController.startedSwipeFromRightKbd = false;
                cursorController.isSwiping = false;
                cursorController.isRealtimeSwipe = false;
                return;
            }

            new Thread(() -> {
                try {
                    int action = MotionEvent.ACTION_UP;
                    int[] cursorCoords = cursorPosition;

                    // Cancel swipe if it ends too close to the right edge
                    if (cursorCoords[0] >= screenSize.x - 5) {
                        action = MotionEvent.ACTION_CANCEL;
                    }
                    // if current cursor position is outside of keyboard bounds, use last valid coords
                    else if (!keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                        cursorCoords = lastValidCoords;
                    }

                    MotionEvent event = MotionEvent.obtain(
                        startUptime,
                        endUptime,
                        action,
                        cursorCoords[0],
                        cursorCoords[1],
                        0);
                    injectMotionEvent(event);

                    debugText[0] = "Swiping";
                    debugText[1] = "X, Y: (" + cursorCoords[0] + ", " + cursorCoords[1] + ")";
                    Log.d(TAG, "MotionEvent.ACTION_UP @ (" + cursorCoords[0] + ", " + cursorCoords[1] + ")");
                } catch (Exception e) {
                    writeToFile.logError(TAG, "ERROR WHILE ENDING SWIPE!!!: sendPointerSync cannot be called from the main thread." + e);
                    Log.e(TAG, "sendPointerSync cannot be called from the main thread.", e);
                }
                cursorController.isSwiping = false;
                cursorController.isRealtimeSwipe = false;
//            displaySwipeInfo();
            }).start();
        } else {
            // Handle non-realtime swipe logic here
            Log.d(TAG, "cancelSwipe() drag toggle");
            endDragOrHold(cursorController.getPathCursorPositionXY());
        }
    }

    private void startSwipe() {
        cursorController.isSwiping = true;
//        mainHandler.removeCallbacks(endTouchAnimationRunnable);

        // disabling to set cursor green when the kbd indicates a swipe has started.

        if (startedInsideKbd) {
            cursorController.isRealtimeSwipe = true;
            startUptime = SystemClock.uptimeMillis();
            startTime = System.currentTimeMillis();
            int[] initialPosition = swipeStartPosition;

            if (cursorController.checkForSwipingFromRightKbd) {
                lastValidCoords = initialPosition;
                cursorController.startedSwipeFromRightKbd = true;
                return;
            } else {
                cursorController.startedSwipeFromRightKbd = false;
            }

            new Thread(() -> {
                if (keyboardManager.canInjectEvent(initialPosition[0], initialPosition[1])) {
                    lastValidCoords = initialPosition;
                    MotionEvent event = MotionEvent.obtain(
                        startUptime,
                        startUptime,
                        MotionEvent.ACTION_DOWN,
                        initialPosition[0],
                        initialPosition[1],
                        0);
                    injectMotionEvent(event);
                    debugText[0] = "Swiping";
                    debugText[1] = "X, Y: (" + initialPosition[0] + ", " + initialPosition[1] + ")";
                } else {
                    Log.d(TAG, "Coords do not belong to either sender app or IME. TODO: Implement for 3rd party apps.");
                }

                while (swipeEventStarted && !swipeEventEnding) {
                    int[] cursorPosition = getPathCursorPosition();
                    long now = SystemClock.uptimeMillis();
                    try {
                        if (keyboardManager.canInjectEvent(cursorPosition[0], cursorPosition[1])) {
                            lastValidCoords = cursorPosition;
                            MotionEvent event = MotionEvent.obtain(
                                startUptime,
                                now,
                                MotionEvent.ACTION_MOVE,
                                cursorPosition[0],
                                cursorPosition[1],
                                0);
                            injectMotionEvent(event);
                            debugText[0] = "Swiping";
                            debugText[1] = "X, Y: (" + cursorPosition[0] + ", " + cursorPosition[1] + ")";
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error while injecting swipe input event in startRealtimeSwipe: " + e);
                    }
                    try {
                        Thread.sleep(16); // 60 FPS
                    } catch (Exception e) {
                        Log.e(TAG, "Error while sleeping in startRealtimeSwipe: " + e);
                    }

                }
            }).start();
        } else {
            // Handle non-realtime swipe logic here
            Log.d(TAG, "startSwipe() drag toggle");
            beginDragOrHold(swipeStartPosition);
            serviceUiManager.pathCursorSetColor("GREEN");
        }
    }

    /**
     * Starts monitoring the cursor position to check if it stays within the hover zone.
     * This is used specifically for swipe events to provide visual feedback.
     */
    private void startSwipeHoverZoneMonitoring() {
        new Thread(() -> {
            while (swipeEventStarted && !swipeEventEnding) {
                int[] currentPosition = getPathCursorPosition();
                int[] startPos = swipeStartPosition; // Get local copy to avoid NPE

                // Skip if either position is null
                if (currentPosition == null || startPos == null) {
                    try {
                        Thread.sleep(16);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                double distance = Math.sqrt(Math.pow(currentPosition[0] - startPos[0], 2) +
                                            Math.pow(currentPosition[1] - startPos[1], 2));

                boolean newHoverState = distance <= Config.HOVER_ZONE_RADIUS;

                // Only update UI if hover state changed
                if (newHoverState != isInHoverZone) {
                    isInHoverZone = newHoverState;
                    Log.d(TAG, "HOVER ZONE " + (isInHoverZone ? "ENTERED" : "EXITED") + "; Cursor Distance: "
                        + distance + "px; Hover Zone Radius: " + Config.HOVER_ZONE_RADIUS + "px");

                    if (!isInHoverZone) {
                        // Cursor left hover zone
                        hoverZoneExitTime = System.currentTimeMillis();
                        mainHandler.post(() -> {
                            // Hide current animations and show red
                            serviceUiManager.pathCursorHideAnimation("RED");
                        });
                    } else {
                        // Cursor returned to hover zone
                        mainHandler.post(() -> {
                            // Show animations again
                            serviceUiManager.pathCursorShowAnimation();
                        });
                    }
                }

                try {
                    Thread.sleep(16); // ~60fps
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    /**
     * Ends the swipe sequence when the switch is released
     */
    private void endSwipeSequence() {
        // Cancel any pending runnables
        swipeEventEnding = true;
        canStartSwipe = false;
        mainHandler.removeCallbacks(animateCursorTouchRunnable);
//        mainHandler.removeCallbacks(touchGreenToBlueRunnable);
//        mainHandler.removeCallbacks(endTouchAnimationRunnable);

        // Cancel any ongoing animations
        serviceUiManager.pathCursorCancelAnimation();

        // If cursor is not in hover zone, cancel the action
//        if (!isInHoverZone && !startedInsideKbd) {
//            resetSwipeSequence();
//            return;
//        }

        int duration = 200;
        try {
            if (cursorController.isRealtimeSwipe) {
                Log.d(TAG, "endSwipeSequence() - Ending openboard swipe");
                endSwipe();
            } else if (!startedInsideKbd) {
                Log.d(TAG, "endSwipeSequence() - ending gesture desc swipe in system");
                endDragOrHold(cursorController.getPathCursorPositionXY());
//                endGestureDescSwipe();
            } else {
                cancelDragOrHold();
            }
//            else if (isLongTap) { // long tap
//                serviceUiManager.pathCursorSetColor("BLUE");
//                if (startedInsideKbd) {
//                    Log.d(TAG, "endSwipeSequence() isLongTap, sending long press to IME");
////                    duration = 105;
////                    dispatchTapGesture(swipeStartPosition, duration);
//
//                } else {
//                    Log.d(TAG, "endSwipeSequence() isLongTap, outputting long press to system");
//                    duration = getSystemLongpressDelay() + 5;
//                    dispatchTapGesture(swipeStartPosition, duration);
//                }
//            } else { // quick tap
//                serviceUiManager.pathCursorSetColor("YELLOW");
//                if (startedInsideKbd) {
//                    Log.d(TAG, "endSwipeSequence() isQuickTap, sending quick tap to IME");
////                    duration = 95;
////                    dispatchTapGesture(swipeStartPosition, duration);
//
//                } else {
//                    Log.d(TAG, "endSwipeSequence() isQuickTap, outputting quick tap to system");
//                    dispatchTapGesture(swipeStartPosition, duration);
//                }
//            }
        } catch (Exception e) {
            Log.e(TAG, "Error while ending swipe sequence: " + e);
            writeToFile.logError(TAG, "Error while ending swipe sequence: " + e);
            cancelDragOrHold();
            if (startedInsideKbd) {
                Log.d(TAG, "endSwipeSequence() err, sending ACTION_CANCEL to IME");
                sendMotionEventToIME(swipeStartPosition[0], swipeStartPosition[1], MotionEvent.ACTION_CANCEL);
            }
        }

        // Reset after tap
        mainHandler.postDelayed( () -> { resetSwipeSequence(); }, duration);
    }

    /**
     * Resets all swipe sequence state variables.
     */
    private void resetSwipeSequence() {
        if (startedInsideKbd) {
            Log.d(TAG, "resetSwipeSequence() sending long press delay to IME");
            sendLongPressDelayToIME(getActionStateChangeDelay());
        }
        serviceUiManager.pathCursorShowAnimation();
        serviceUiManager.pathCursorSetColor("WHITE");
        swipeStartPosition = null;
        isInHoverZone = true;
        wasInHoverZone = true;
        hoverZoneExitTime = 0;
        startedInsideKbd = false;
        swipeEventStarted = false;
        swipeEventEnding = false;
        cursorController.isCursorTouch = false;
        canStartSwipe = false;
        openboardSwipeStarted = false;
        cancelDragOrHold();
    }
    /* ------------------------------ END OF SWIPE ACTION HANDLING ------------------------------ */


    private int getSystemLongpressDelay() {
        return ViewConfiguration.getLongPressTimeout();
    }

    private void cancelMotionEvent(int[] coords) {
        if (coords == null || coords.length < 2) {
            Log.e(TAG, "Invalid coordinates for cancelMotionEvent. " + "Fallback to last valid coordinates");
            coords = lastValidCoords; // Fallback to last valid coordinates
        }
        MotionEvent event = MotionEvent.obtain(
            startUptime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_CANCEL,
            coords[0],
            coords[1],
            0);
        injectMotionEvent(event);
    }

}
