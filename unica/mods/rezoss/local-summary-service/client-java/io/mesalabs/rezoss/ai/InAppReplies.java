package io.mesalabs.rezoss.ai;

import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import java.util.*;
import java.util.concurrent.*;

public final class InAppReplies {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static Object session;
    private static long generation;
    private static Future<?> pending;
    private static String lastInput;
    private static boolean hasReplies;
    private static final String PREFIX = "com.samsung.android.intent.action.";

    public static boolean supports(String pkg) {
        return Arrays.asList("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
                "org.telegram.messenger.web", "com.kakao.talk", "com.samsung.android.messaging",
                "com.google.android.apps.messaging").contains(pkg);
    }

    public static boolean start(Context context, String pkg, Object sessionId, Object conversation) {
        if (!supports(pkg)) return false;
        final Context app = context.getApplicationContext();
        if (Settings.Global.getInt(app.getContentResolver(), "now_nudge_enabled", -1) != 1
                || Settings.Global.getInt(app.getContentResolver(), "now_nudge_setting", -1) == 0) {
            Log.i("RezossQwen", "inapp status=disabled");
            exit(app, sessionId, pkg);
            return true;
        }
        try {
            // These stable Kotlin accessors are checked against the exact input APK at build time.
            List<?> messages = (List<?>) conversation.getClass().getMethod("getMessages").invoke(conversation);
            StringBuilder input = new StringBuilder();
            boolean lastFromSelf = false;
            for (int i = Math.max(0, messages.size() - 6); i < messages.size(); i++) {
                Object message = messages.get(i);
                if ((Boolean) message.getClass().getMethod("isNonTextMessage").invoke(message)) continue;
                String text = (String) message.getClass().getMethod("getMessage").invoke(message);
                Object person = message.getClass().getMethod("getPerson").invoke(message);
                if (text != null && !text.trim().isEmpty()) {
                    lastFromSelf = "USER".equals(String.valueOf(person));
                    input.append("USER".equals(String.valueOf(person)) ? "Me: " : "Other: ").append(text).append('\n');
                }
            }
            if (lastFromSelf) {
                exit(app, sessionId, pkg);
                Log.i("RezossQwen", "inapp status=user_sent");
                return true;
            }
            final String prompt = input.substring(Math.max(0, input.length() - 700));
            if (prompt.trim().isEmpty()) { Log.i("RezossQwen", "inapp status=empty_context"); return true; }
            MAIN.post(new Runnable() {
                @Override public void run() { enqueue(app, pkg, sessionId, prompt); }
            });
        } catch (Exception error) {
            Log.w("RezossQwen", "inapp status=" + error.getClass().getSimpleName());
        }
        return true;
    }

    private static void enqueue(Context app, String pkg, Object id, String prompt) {
        if (id.equals(session) && prompt.equals(lastInput)
                && (hasReplies || (pending != null && !pending.isDone()))) return;
        if (pending != null) pending.cancel(true);
        if (!id.equals(session)) hasReplies = false;
        session = id;
        lastInput = prompt;
        final long token = ++generation;
        if (!hasReplies) send(app, pkg, "PREPARE_SUGGESTED_REPLIES", null);
        final long started = SystemClock.elapsedRealtime();
        Log.i("RezossQwen", "inapp status=request package=" + pkg + " chars=" + prompt.length());
        MAIN.postDelayed(() -> {
            if (generation == token && id.equals(session) && pending != null && !pending.isDone())
                Log.i("RezossQwen", "inapp status=target_missed continuing=true package=" + pkg);
        }, 5000);
        pending = WORKER.submit(new Runnable() {
            @Override public void run() {
                final ArrayList<String> replies = parse(QwenClient.request(app, prompt, 28000, true));
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (generation != token || !id.equals(session)) {
                            Log.i("RezossQwen", "inapp status=stale"); return;
                        }
                        if (replies.isEmpty()) {
                            Log.i("RezossQwen", "inapp status=unavailable_preserve");
                            if (!hasReplies) send(app, pkg, "EXIT", null);
                            lastInput = null;
                            return;
                        }
                        hasReplies = true;
                        send(app, pkg, "NOW_NUDGE", replies);
                        Log.i("RezossQwen", "inapp delivered package=" + pkg + " actionCount=" + replies.size()
                                + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
                    }
                });
            }
        });
    }

    public static void exit(Context context, Object id, String pkg) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (!id.equals(session)) return;
                ++generation;
                session = null;
                hasReplies = false;
                lastInput = null;
                if (pending != null) pending.cancel(true);
                send(context, pkg, "EXIT", null);
            }
        });
    }

    private static void send(Context context, String pkg, String action, ArrayList<String> replies) {
        String ime = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime == null || !ime.startsWith("com.samsung.android.honeyboard/")) return;
        Intent intent = new Intent(PREFIX + action).setPackage("com.samsung.android.honeyboard");
        intent.putExtra("PACKAGE_NAME", pkg);
        intent.putExtra("REZOSS_QWEN", true);
        if (replies != null) {
            ArrayList<String> items = new ArrayList<>();
            for (String reply : replies) {
                try {
                    items.add(new org.json.JSONObject()
                            .put("action_type", "Text Reply(Recall Nudge)")
                            .put("title", reply).toString());
                } catch (org.json.JSONException impossible) {
                    Log.w("RezossQwen", "inapp status=encoding_failed");
                }
            }
            intent.putStringArrayListExtra("SUGGESTIONS", items);
        }
        if (action.equals("PREPARE_SUGGESTED_REPLIES")) {
            intent.putExtra("IS_REPLY_PREPARING", false);
            intent.putExtra("IS_NUDGE_PREPARING", true);
        }
        context.sendBroadcast(intent, "com.samsung.android.permission.SUGGESTED_REPLIES");
    }

    public static ArrayList<String> parse(String output) {
        ArrayList<String> replies = new ArrayList<>();
        if (output == null) return replies;
        for (String line : output.split("\\r?\\n")) {
            line = line.replaceFirst("^\\s*(?:[-*]|[0-9]+[.)])\\s*", "").trim();
            if (line.isEmpty() || line.length() > 120 || line.contains("<|") || replies.contains(line)) continue;
            replies.add(line);
            if (replies.size() == 3) break;
        }
        return replies;
    }
}
