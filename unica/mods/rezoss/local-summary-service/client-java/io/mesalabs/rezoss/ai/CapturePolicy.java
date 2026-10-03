package io.mesalabs.rezoss.ai;

import android.content.ComponentName;
import android.util.Log;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Extend only messenger capture; preserve Samsung's per-view parsing and sender checks. */
public final class CapturePolicy {
    private static final String[] PACKAGES = new String[] {
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging"
    };

    public static Set<String> packages(Set<String> original) {
        Set<String> result = original == null ? new HashSet<>() : new HashSet<>(original);
        for (String pkg : PACKAGES) result.add(pkg);
        Log.i("RezossQwen", "capture status=allowlist_installed count=" + result.size()
                + " whatsapp=" + result.contains("com.whatsapp")
                + " whatsappBusiness=" + result.contains("com.whatsapp.w4b")
                + " telegram=" + result.contains("org.telegram.messenger")
                + " ayugram=" + result.contains("org.telegram.messenger.web")
                + " samsungMessages=" + result.contains("com.samsung.android.messaging")
                + " googleMessages=" + result.contains("com.google.android.apps.messaging"));
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Set activities(Set original) {
        Set result = original == null ? new HashSet() : new HashSet(original);
        addActivity(result, "com.whatsapp", "com.whatsapp.Conversation");
        addActivity(result, "com.whatsapp.w4b", "com.whatsapp.Conversation");
        addActivity(result, "org.telegram.messenger", "org.telegram.ui.LaunchActivity");
        addActivity(result, "org.telegram.messenger.web", "com.exteragram.messenger.DefaultIcon");
        addActivity(result, "org.telegram.messenger.web", "org.telegram.ui.LaunchActivity");
        Log.i("RezossQwen", "capture status=activity_allowlist count=" + result.size()
                + " telegramActivity=" + result.contains(new ComponentName("org.telegram.messenger", "org.telegram.ui.LaunchActivity"))
                + " ayugramAliasActivity=" + result.contains(new ComponentName("org.telegram.messenger.web", "com.exteragram.messenger.DefaultIcon"))
                + " ayugramLaunchActivity=" + result.contains(new ComponentName("org.telegram.messenger.web", "org.telegram.ui.LaunchActivity")));
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addActivity(Set result, String pkg, String cls) {
        result.add(new ComponentName(pkg, cls));
    }

    public static void inspect(JSONObject specs, String pkg) {
        if (!InAppReplies.supports(pkg)) return;
        // Do not invent view IDs for custom-drawn clients or treat composer text as messages.
        Log.i("RezossQwen", "capture package=" + pkg + " parserSpec=" + specs.has(pkg));
    }
}
