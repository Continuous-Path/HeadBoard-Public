package org.continuouspath.headboard;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.text.HtmlCompat;

import org.continuouspath.headboard.BlendshapeEventTriggerConfig.EventType;
import org.continuouspath.headboard.utils.KeyCaptureGate;
import org.continuouspath.headboard.utils.KeyLabels;

import java.util.HashSet;
import java.util.Set;

/**
 * Modal "press your switch" dialog that captures the next physical key press and hands the
 * keycode back to the host. Capture only — assignment decisions (add to the switch list,
 * conflict prompts, binding writes) belong to the host activity.
 *
 * The dialog owns its window, so all key events arrive at this class's dispatchKeyEvent —
 * including keys the accessibility service normally consumes, because KeyCaptureGate suppresses
 * the service's handling while the dialog is showing. The gate is kept alive by a heartbeat;
 * the capture itself never times out (users with motor impairments may need minutes).
 *
 * Capture fires on the UP edge: held switches auto-repeat DOWN, and the UP is the one reliable
 * signal that a deliberate press finished. The DOWN must have been seen by this dialog first,
 * which discards the stray UP of whatever press opened the dialog.
 */
public class KeyCaptureDialog extends Dialog {

    /** Exactly one terminal callback fires. */
    public interface Listener {
        /** A key was captured. Fired after the confirmation text has been announced. */
        void onKeyCaptured(int keyCode);
        /** Dismissed without capturing (cancel button, outside touch, virtual back, host pause). */
        default void onCancelled() {}
    }

    private static final long GATE_HEARTBEAT_MS = 5_000L;
    private static final long CONFIRM_DISMISS_DELAY_MS = 1_200L;

    private final EventType targetAction;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> downSeen = new HashSet<>();

    private boolean waiting = true;
    private boolean captured = false;

    private TextView captureStatus;

    private final Runnable gateHeartbeat = new Runnable() {
        @Override
        public void run() {
            KeyCaptureGate.refresh();
            handler.postDelayed(this, GATE_HEARTBEAT_MS);
        }
    };

    public KeyCaptureDialog(@NonNull Context context, EventType targetAction, Listener listener) {
        // The alert-style theme provides the dialog min-width; a bare Dialog collapses to
        // wrap_content, and the app theme's global button style renders buttons unreadable.
        super(context, R.style.HeadBoardDialog);
        this.targetAction = targetAction;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_key_capture);

        captureStatus = findViewById(R.id.captureStatus);
        Button cancelButton = findViewById(R.id.captureCancel);
        cancelButton.setOnClickListener(v -> cancel());
        // Tappable but never key-focusable: a switch press belongs to the capture.
        cancelButton.setFocusable(false);

        setCanceledOnTouchOutside(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        KeyCaptureGate.begin();
        handler.postDelayed(gateHeartbeat, GATE_HEARTBEAT_MS);
        String actionName = BlendshapeEventTriggerConfig.BEATIFY_EVENT_TYPE_NAME.get(targetAction);
        captureStatus.announceForAccessibility(HtmlCompat.fromHtml(
            getContext().getString(R.string.key_capture_purpose, actionName),
            HtmlCompat.FROM_HTML_MODE_LEGACY));
    }

    @Override
    protected void onStop() {
        handler.removeCallbacksAndMessages(null);
        KeyCaptureGate.end();
        super.onStop();
        if (!captured && listener != null) {
            listener.onCancelled();
        }
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (!waiting) {
            return true; // Inert while the confirmation is showing.
        }

        KeyCaptureGate.refresh();
        int keyCode = event.getKeyCode();

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                downSeen.add(keyCode);
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) {
            return true;
        }

        // The press that opened this dialog releases after we're showing; its DOWN never
        // reached us, so its UP must not bind.
        if (!downSeen.remove(keyCode)) {
            return true;
        }
        if (KeyEvent.isModifierKey(keyCode)) {
            return true;
        }
        // The on-screen navigation bar's back "key" can't work as a switch; treat it as cancel.
        // A physical BACK key (no virtual flag) is assignable like anything else.
        if (keyCode == KeyEvent.KEYCODE_BACK
                && (event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            cancel();
            return true;
        }

        confirmCapture(keyCode);
        return true;
    }

    private void confirmCapture(int keyCode) {
        waiting = false;
        captured = true;
        String confirmation = getContext().getString(
            R.string.captured_confirmation, KeyLabels.labelFor(keyCode));
        captureStatus.setText(confirmation);
        captureStatus.setTextColor(getContext().getColor(R.color.green));
        // Give TalkBack time to announce the result before the window disappears.
        handler.postDelayed(() -> {
            dismiss();
            if (listener != null) {
                listener.onKeyCaptured(keyCode);
            }
        }, CONFIRM_DISMISS_DELAY_MS);
    }
}
