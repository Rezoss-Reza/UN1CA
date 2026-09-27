package io.mesalabs.rezoss.ai;

import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** Main-thread state scoped to one keyboard editor; never stores message text. */
public final class KeyboardReplyRetention {
    private static WeakReference<InputMethodService> service = new WeakReference<>(null);
    private static WeakReference<Object> receiver = new WeakReference<>(null);
    private static String packageName;
    private static int fieldId;
    private static boolean pinned;
    private static boolean typed;

    public static void start(InputMethodService ime, EditorInfo info, boolean restarting) {
        if (info == null) { finish(); return; }
        if (!restarting || !info.packageName.equals(packageName) || info.fieldId != fieldId) {
            finish();
            typed = false;
        }
        service = new WeakReference<>(ime);
        packageName = info.packageName;
        fieldId = info.fieldId;
    }

    public static void finish() {
        clear();
        packageName = null;
        typed = false;
        service.clear();
    }

    private static boolean hasText(InputMethodService ime) {
        InputConnection connection = ime.getCurrentInputConnection();
        if (connection == null) return true;
        CharSequence before = connection.getTextBeforeCursor(1, 0);
        CharSequence after = connection.getTextAfterCursor(1, 0);
        CharSequence selected = connection.getSelectedText(0);
        return before == null || after == null || before.length() > 0 || after.length() > 0
                || (selected != null && selected.length() > 0);
    }

    public static void selection(InputMethodService ime) {
        if (packageName == null || typed) return;
        if (hasText(ime)) {
            typed = true;
            clear();
            Log.i("RezossKeyboard", "retention status=manual_input");
        }
    }

    public static boolean keep() {
        InputMethodService ime = service.get();
        if (ime != null) selection(ime);
        return pinned && !typed;
    }

    private static void clear() {
        boolean wasPinned = pinned;
        pinned = false;
        Object target = receiver.get();
        receiver.clear();
        if (wasPinned && target != null) {
            try {
                target.getClass().getMethod("q", Intent.class).invoke(target, new Intent());
            } catch (Exception failure) {
                Log.w("RezossKeyboard", "clear status=" + failure.getClass().getSimpleName());
            }
        }
    }

    /** True consumes only conflicting nudge events; normal keyboard behavior is untouched. */
    public static boolean consume(Object target, Intent intent) {
        InputMethodService ime = service.get();
        if (ime == null || packageName == null) return intent.getBooleanExtra("REZOSS_QWEN", false);
        String action = intent.getAction();
        if (action == null) return false;
        if (intent.getBooleanExtra("REZOSS_QWEN", false)) {
            if (!packageName.equals(intent.getStringExtra("PACKAGE_NAME"))) return true;
            selection(ime);
            if (typed) return true;
            ArrayList<String> items = intent.getStringArrayListExtra("SUGGESTIONS");
            if (action.endsWith(".NOW_NUDGE") && items != null && !items.isEmpty()) {
                pinned = true;
                receiver = new WeakReference<>(target);
                Log.i("RezossKeyboard", "retention status=pinned count=" + items.size());
                return false;
            }
            return pinned;
        }
        if (!pinned) return false;
        if (action.endsWith(".USER_SENT")) { typed = true; clear(); return false; }
        if (action.endsWith(".EXIT") || action.endsWith(".NOW_NUDGE")
                || action.endsWith(".SUGGESTED_REPLIES") || action.endsWith(".PREPARE_SUGGESTED_REPLIES")
                || action.endsWith(".FAILED_SUGGESTED_REPLIES")) {
            Log.i("RezossKeyboard", "retention status=preserved");
            return true;
        }
        return false;
    }
}
