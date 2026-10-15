package com.tsuyu.messenger.crypto;

import android.app.Activity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.tsuyu.messenger.R;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Local app lock. The PIN never leaves the device: only a salted SHA-256 hash is stored,
 * and it is kept inside the same AES-GCM/Keystore box that protects the signal state.
 */
public final class PinLock {
    private static final String PIN_KEY = "app_pin_hash";

    /** Kept in memory only — a lock must be re-entered after the app returns from the background. */
    private static long unlockedAt;
    private static String unlockedUid;

    public interface Callback {
        void onUnlocked();
        void onCancelled();
    }

    private PinLock() { }

    public static boolean isEnabled(Context context, String uid) {
        if (uid == null) return false;
        try {
            return new SecurePrefs(context, uid).get(PIN_KEY) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static void setPin(Context context, String uid, String pin) {
        if (uid == null || pin == null || pin.length() < 4) return;
        new SecurePrefs(context, uid).put(PIN_KEY, hash(uid, pin));
        unlockedAt = System.currentTimeMillis();
        unlockedUid = uid;
    }

    public static void clearPin(Context context, String uid) {
        if (uid == null) return;
        try {
            new SecurePrefs(context, uid).remove(PIN_KEY);
        } catch (Exception ignored) { }
        unlockedAt = System.currentTimeMillis();
        unlockedUid = uid;
    }

    public static boolean verify(Context context, String uid, String pin) {
        if (uid == null || pin == null) return false;
        try {
            String stored = new SecurePrefs(context, uid).get(PIN_KEY);
            return stored != null && stored.equals(hash(uid, pin));
        } catch (Exception e) {
            return false;
        }
    }

    /** Called from the activity when the app leaves the foreground. */
    public static void onBackground() {
        unlockedAt = 0L;
        unlockedUid = null;
    }

    /**
     * Asks for the PIN when a lock exists and the current session has not been unlocked yet.
     * Without a PIN the callback fires immediately, so the normal app flow is untouched.
     */
    public static void requireUnlock(final Activity activity, final String uid, final Callback callback) {
        if (activity == null || activity.isFinishing()) {
            if (callback != null) callback.onCancelled();
            return;
        }
        if (uid == null || !isEnabled(activity, uid)) {
            if (callback != null) callback.onUnlocked();
            return;
        }
        if (uid.equals(unlockedUid) && System.currentTimeMillis() - unlockedAt < 30_000L) {
            if (callback != null) callback.onUnlocked();
            return;
        }
        if (activity.isFinishing()) {
            if (callback != null) callback.onCancelled();
            return;
        }
        final View content = activity.getLayoutInflater().inflate(R.layout.dialog_pin, null, false);
        final EditText input = content.findViewById(R.id.pin_input);
        final TextView error = content.findViewById(R.id.pin_error);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(content)
                .setCancelable(false)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setDimAmount(0.72f);
        }
        content.findViewById(R.id.pin_cancel).setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) callback.onCancelled();
        });
        View.OnClickListener confirm = v -> {
            String entered = input.getText() == null ? "" : input.getText().toString();
            if (verify(activity, uid, entered)) {
                unlockedAt = System.currentTimeMillis();
                unlockedUid = uid;
                dialog.dismiss();
                if (callback != null) callback.onUnlocked();
            } else {
                error.setVisibility(View.VISIBLE);
                input.setText("");
            }
        };
        content.findViewById(R.id.pin_confirm).setOnClickListener(confirm);
        dialog.setOnShowListener(ignored -> {
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            input.requestFocus();
        });
        dialog.show();
    }

    /** Small helper for the drawer row: sets or removes the lock with a confirmation toast. */
    public static void toggleFromDrawer(Activity activity, String uid, boolean enable, String pin) {
        if (uid == null) return;
        if (enable) {
            setPin(activity, uid, pin);
            Toast.makeText(activity, R.string.pin_saved, Toast.LENGTH_SHORT).show();
        } else {
            clearPin(activity, uid);
            Toast.makeText(activity, R.string.pin_removed, Toast.LENGTH_SHORT).show();
        }
    }

    private static String hash(String uid, String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest((uid + "::tsuyu-pin::" + pin).getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(value, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
