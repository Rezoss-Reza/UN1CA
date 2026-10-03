package io.mesalabs.rezoss.ai;

import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import java.util.*;
import java.util.concurrent.*;

public final class InAppReplies {
    private static final int RECENT_MESSAGE_LIMIT = 3;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static Object session;
    private static long generation;
    private static Future<?> pending;
    private static String lastInput;
    private static String lastPackage;
    private static String lastKey;
    private static String queuedPkg;
    private static Object queuedSession;
    private static String queuedPrompt;
    private static String queuedKey;
    private static ArrayList<String> lastReplies;
    private static boolean hasReplies;
    private static final String PREFIX = "com.samsung.android.intent.action.";
    private static final String WAITING_REPLY = "Generating reply, please wait...";

    public static boolean supports(String pkg) {
        return Arrays.asList("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
                "org.telegram.messenger.web", "com.kakao.talk", "com.samsung.android.messaging",
                "com.google.android.apps.messaging").contains(pkg);
    }

    public static void screen(Context context, String pkg, Object sessionId, java.util.List<?> messages) {
        if (!supports(pkg) || messages == null || messages.isEmpty()) return;
        final Context app = context.getApplicationContext();
        if (Settings.Global.getInt(app.getContentResolver(), "now_nudge_enabled", -1) != 1
                || Settings.Global.getInt(app.getContentResolver(), "now_nudge_setting", -1) == 0) {
            Log.i("RezossQwen", "screen status=disabled package=" + pkg);
            return;
        }
        try {
            ArrayList<org.json.JSONObject> input = new ArrayList<>();
            boolean lastFromSelf = false;
            int start = Math.max(0, messages.size() - RECENT_MESSAGE_LIMIT);
            for (int i = start; i < messages.size(); i++) {
                Object message = messages.get(i);
                String text = invokeString(message, "getMessageText");
                if (text.length() == 0) text = invokeString(message, "getMessage");
                if (text.trim().isEmpty()) continue;
                boolean nonText = booleanValue(message, "isNonTextMessage");
                if (nonText) continue;
                lastFromSelf = booleanValue(message, "getSent");
                ReplyFormat.add(input, lastFromSelf, text);
            }
            String prompt = ReplyFormat.encode(input);
            if (TelegramCaptureProbe.isTelegram(pkg)) {
                String capturedPrompt = TelegramCaptureProbe.prompt(sessionId);
                if (!capturedPrompt.trim().isEmpty() && (prompt.trim().isEmpty() || lastFromSelf)) {
                    prompt = capturedPrompt;
                    Log.i("RezossQwen", "screen status=telegram_context package=" + pkg);
                }
            }
            if (prompt.trim().isEmpty()) {
                Log.i("RezossQwen", "screen status=empty package=" + pkg + " count=" + messages.size());
                return;
            }
            if (lastFromSelf && !TelegramCaptureProbe.hasIncoming(sessionId)) {
                Log.i("RezossQwen", "screen status=last_self_preserve package=" + pkg + " count=" + input.size());
            }
            final String finalPrompt = prompt;
            final String cacheKey = pkg + ":screen:" + String.valueOf(sessionId) + ":" + Integer.toHexString(prompt.hashCode());
            Log.i("RezossQwen", "screen status=request package=" + pkg + " count=" + input.size()
                    + " lastSelf=" + lastFromSelf);
            MAIN.post(new Runnable() {
                @Override public void run() { enqueue(app, pkg, sessionId, finalPrompt, cacheKey); }
            });
        } catch (Exception error) {
            Log.w("RezossQwen", "screen status=" + error.getClass().getSimpleName());
        }
    }

    private static boolean booleanValue(Object target, String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value instanceof Boolean && ((Boolean) value).booleanValue();
        } catch (Exception ignored) {
            return false;
        }
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
            List<?> messages = (List<?>) conversation.getClass().getMethod("getMessages").invoke(conversation);
            ArrayList<org.json.JSONObject> input = new ArrayList<>();
            boolean lastFromSelf = false;
            for (int i = Math.max(0, messages.size() - RECENT_MESSAGE_LIMIT); i < messages.size(); i++) {
                Object message = messages.get(i);
                if ((Boolean) message.getClass().getMethod("isNonTextMessage").invoke(message)) continue;
                String text = (String) message.getClass().getMethod("getMessage").invoke(message);
                Object person = message.getClass().getMethod("getPerson").invoke(message);
                if (text != null && !text.trim().isEmpty()) {
                    lastFromSelf = "USER".equals(String.valueOf(person));
                    ReplyFormat.add(input, lastFromSelf, text);
                }
            }
            if (lastFromSelf && !TelegramCaptureProbe.hasIncoming(sessionId)) {
                clear(app, pkg);
                Log.i("RezossQwen", "inapp status=user_sent");
                return true;
            }
            if (lastFromSelf) Log.i("RezossQwen", "inapp status=user_sent_preserved package=" + pkg);
            String prompt = ReplyFormat.encode(input);
            if (TelegramCaptureProbe.isTelegram(pkg)) {
                String capturedPrompt = TelegramCaptureProbe.prompt(sessionId);
                if (!capturedPrompt.trim().isEmpty() && (prompt.trim().isEmpty() || lastFromSelf)) {
                    prompt = capturedPrompt;
                    Log.i("RezossQwen", "inapp status=telegram_context package=" + pkg);
                }
            }
            if (prompt.trim().isEmpty()) { Log.i("RezossQwen", "inapp status=empty_context"); return true; }
            final String finalPrompt = prompt;
            final String cacheKey = key(pkg, sessionId, conversation);
            MAIN.post(new Runnable() {
                @Override public void run() { enqueue(app, pkg, sessionId, finalPrompt, cacheKey); }
            });
        } catch (Exception error) {
            Log.w("RezossQwen", "inapp status=" + error.getClass().getSimpleName());
        }
        return true;
    }

    private static String key(String pkg, Object id, Object conversation) {
        String titleHash = valueHash(conversation, "getTitle");
        String conversationHash = invokeString(conversation, "hash");
        if (conversationHash.length() == 0) {
            conversationHash = Integer.toHexString(System.identityHashCode(conversation));
        }
        return pkg + ":" + String.valueOf(id) + ":" + titleHash + ":" + conversationHash;
    }

    private static String valueHash(Object target, String method) {
        String value = invokeString(target, method);
        return value.length() == 0 ? "0" : Integer.toHexString(value.hashCode());
    }

    private static String invokeString(Object target, String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void enqueue(Context app, String pkg, Object id, String prompt, String key) {
        if (key.equals(lastKey) && prompt.equals(lastInput)
                && (hasReplies || (pending != null && !pending.isDone()))) {
            session = id;
            if (hasReplies && lastReplies != null) rebroadcast(app, pkg, lastReplies);
            else rebroadcastWaiting(app, pkg);
            Log.i("RezossQwen", "inapp status=session_restored package=" + pkg);
            return;
        }
        if (pending != null && !pending.isDone()) {
            queuedPkg = pkg;
            queuedSession = id;
            queuedPrompt = prompt;
            queuedKey = key;
            session = id;
            if (!hasReplies) {
                send(app, pkg, "PREPARE_SUGGESTED_REPLIES", null);
                rebroadcastWaiting(app, pkg);
            }
            Log.i("RezossQwen", "inapp status=queued package=" + pkg + " chars=" + prompt.length());
            return;
        }
        if (!id.equals(session)) hasReplies = false;
        session = id;
        lastInput = prompt;
        lastPackage = pkg;
        lastKey = key;
        lastReplies = null;
        final long token = ++generation;
        if (!hasReplies) {
            send(app, pkg, "PREPARE_SUGGESTED_REPLIES", null);
            rebroadcastWaiting(app, pkg);
        }
        final long started = SystemClock.elapsedRealtime();
        Log.i("RezossQwen", "inapp status=request package=" + pkg + " chars=" + prompt.length());
        MAIN.postDelayed(() -> {
            if (generation == token && id.equals(session) && pending != null && !pending.isDone())
                Log.i("RezossQwen", "inapp status=target_missed continuing=true package=" + pkg);
        }, 5000);
        pending = WORKER.submit(new Runnable() {
            @Override public void run() {
                final ArrayList<String> replies = parse(QwenClient.request(app, prompt, 65000, true));
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (generation != token || !id.equals(session)) {
                            Log.i("RezossQwen", "inapp status=stale");
                            drainQueue(app);
                            return;
                        }
                        if (replies.isEmpty()) {
                            Log.i("RezossQwen", "inapp status=unavailable_preserve");
                            if (!hasReplies && queuedPrompt == null) send(app, pkg, "EXIT", null);
                            lastInput = null;
                            drainQueue(app);
                            return;
                        }
                        hasReplies = true;
                        lastReplies = new ArrayList<>(replies);
                        rebroadcast(app, pkg, lastReplies);
                        Log.i("RezossQwen", "inapp delivered package=" + pkg + " actionCount=" + replies.size()
                                + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
                        drainQueue(app);
                    }
                });
            }
        });
    }

    private static void drainQueue(Context app) {
        if (queuedPrompt == null) return;
        String pkg = queuedPkg;
        Object id = queuedSession;
        String prompt = queuedPrompt;
        String key = queuedKey;
        queuedPkg = null;
        queuedSession = null;
        queuedPrompt = null;
        queuedKey = null;
        enqueue(app, pkg, id, prompt, key);
    }

    public static void exit(Context context, Object id, String pkg) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (!id.equals(session)) return;
                if (hasReplies && lastReplies != null) rebroadcast(context.getApplicationContext(), pkg, lastReplies);
                Log.i("RezossQwen", "inapp status=session_exit_retained package=" + pkg);
            }
        });
    }

    private static void clear(Context context, String pkg) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                ++generation;
                session = null;
                hasReplies = false;
                lastInput = null;
                lastPackage = null;
                lastKey = null;
                lastReplies = null;
                queuedPkg = null;
                queuedSession = null;
                queuedPrompt = null;
                queuedKey = null;
                if (pending != null) pending.cancel(true);
                send(context, pkg, "EXIT", null);
            }
        });
    }

    private static void rebroadcast(Context context, String pkg, ArrayList<String> replies) {
        send(context, pkg, "NOW_NUDGE", replies);
        MAIN.postDelayed(() -> send(context, pkg, "NOW_NUDGE", replies), 350);
        MAIN.postDelayed(() -> send(context, pkg, "NOW_NUDGE", replies), 1200);
    }

    private static void rebroadcastWaiting(Context context, String pkg) {
        ArrayList<String> waiting = new ArrayList<>();
        waiting.add(WAITING_REPLY);
        send(context, pkg, "NOW_NUDGE", waiting, true);
        MAIN.postDelayed(() -> send(context, pkg, "NOW_NUDGE", waiting, true), 350);
        MAIN.postDelayed(() -> send(context, pkg, "NOW_NUDGE", waiting, true), 1200);
    }

    private static void send(Context context, String pkg, String action, ArrayList<String> replies) {
        send(context, pkg, action, replies, false);
    }

    private static void send(Context context, String pkg, String action, ArrayList<String> replies, boolean waiting) {
        String ime = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime == null || !ime.startsWith("com.samsung.android.honeyboard/")) return;
        Intent intent = new Intent(PREFIX + action).setPackage("com.samsung.android.honeyboard");
        intent.putExtra("PACKAGE_NAME", pkg);
        intent.putExtra("REZOSS_QWEN", true);
        if (waiting) intent.putExtra("REZOSS_WAITING", true);
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
        return ReplyFormat.parse(output);
    }
}
