package io.mesalabs.rezoss.ai;

import android.util.Log;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Extend only messenger capture; preserve Samsung's per-view parsing and sender checks. */
public final class CapturePolicy {
    public static Set<String> packages(Set<String> original) {
        Set<String> result = original == null ? new HashSet<>() : new HashSet<>(original);
        result.add("com.whatsapp");
        result.add("com.whatsapp.w4b");
        result.add("org.telegram.messenger");
        result.add("org.telegram.messenger.web");
        result.add("com.samsung.android.messaging");
        result.add("com.google.android.apps.messaging");
        Log.i("RezossQwen", "capture status=allowlist_installed count=" + result.size());
        return result;
    }

    public static void inspect(JSONObject specs, String pkg) {
        if (!InAppReplies.supports(pkg)) return;
        // Do not invent view IDs for custom-drawn clients or treat composer text as messages.
        Log.i("RezossQwen", "capture package=" + pkg + " parserSpec=" + specs.has(pkg));
    }
}
