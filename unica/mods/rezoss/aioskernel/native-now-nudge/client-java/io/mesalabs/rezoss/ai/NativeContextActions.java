package io.mesalabs.rezoss.ai;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import java.util.*;

/** Runs after Samsung's recall intent/safety gate; unsupported inputs use stock extraction. */
public final class NativeContextActions {
    private NativeContextActions() {}
    private static boolean enabled(Context context) throws Exception {
        return context != null && Settings.Global.getInt(context.getContentResolver(),
            "rezoss_now_nudge_context_actions", 0) == 1 &&
            (Boolean) Class.forName("io.mesalabs.rezoss.ai.NativeNowNudgeRoute")
                .getMethod("enabled", Context.class).invoke(null, context);
    }
    /** Explicit POC exception to the recall-action package blocklist, not the safety gate. */
    public static boolean allowRecall(Context context, String packageName) {
        if (!"com.whatsapp".equals(packageName) && !"com.whatsapp.w4b".equals(packageName)) return false;
        try { return enabled(context); }
        catch (Exception | LinkageError e) { return false; }
    }
    public static List<?> extract(String input, boolean chat, String user) {
        try {
            Context context = (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
            if (!enabled(context)) return null;
            ContextActionParser.Action action = ContextActionParser.parse(input, chat, user);
            if (action == null) return null;
            Object result = Class.forName("com.samsung.android.smartsuggestions.feature.recall.v1.RecallAction")
                .getConstructor(String.class, Map.class).newInstance(action.type, action.parameters);
            // Log action type only; conversation text, media paths and event details stay private.
            Log.i("RezossNudgeContext", "Extracted " + action.type);
            return Collections.singletonList(result);
        } catch (Exception | LinkageError e) {
            Log.e("RezossNudgeContext", "Parameter extraction unavailable", e);
            return null;
        }
    }
}
