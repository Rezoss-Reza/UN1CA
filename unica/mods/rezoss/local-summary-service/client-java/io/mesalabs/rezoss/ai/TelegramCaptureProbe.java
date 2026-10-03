package io.mesalabs.rezoss.ai;

import android.content.ComponentName;
import android.util.Log;
import java.util.*;
import org.json.JSONObject;

/** Temporary bounded metadata probe. Never logs message text or titles. */
public final class TelegramCaptureProbe {
    private static final int MAX_SESSIONS = 16;
    private static final int MAX_EVENTS = 96;
    private static final int MAX_MESSAGES = 12;
    private static final int TYPE_VIEW_TEXT_CHANGED = 3;
    private static final int TYPE_CONTEXT_UPDATED = 6;
    private static final Map<Object, State> sessions = new HashMap<>();

    private static final class State {
        String pkg;
        String activity;
        int events;
        final ArrayList<String> messages = new ArrayList<>();
    }

    public static synchronized void start(Object context, Object session) {
        try {
            ComponentName component = componentFrom(context);
            if (component == null) return;
            register(session, component, "session");
        } catch (Exception ignored) { Log.i("RezossQwen", "telegram_capture status=context_unavailable"); }
    }

    public static synchronized void event(Object session, Object event) {
        State state = sessions.get(session);
        if (state == null) state = startFromEvent(session, event);
        if (state == null || state.events >= MAX_EVENTS) return;
        state.events++;
        try {
            int type = readType(event);
            String eventText = textFrom(event);
            Object node = invoke(event, "getViewNode");
            String id = "unavailable";
            String cls = "unavailable";
            String text = eventText;
            boolean nodeText = false;
            if (node != null) {
                id = cleanId(String.valueOf(invoke(node, "getIdEntry")));
                cls = cleanClass(String.valueOf(invoke(node, "getClassName")));
                String value = textFrom(node);
                if (hasText(value)) {
                    text = value;
                    nodeText = true;
                }
            }
            boolean candidate = isMessageCandidate(id, cls, text, node != null, type);
            if (candidate) addMessage(state, text);
            Log.i("RezossQwen", "telegram_capture event_type=" + type
                    + " node=" + (node != null) + " node_class=" + cls + " resource_id=" + id
                    + " event_text=" + hasText(eventText) + " node_text=" + nodeText
                    + " candidate=" + candidate + " messageCount=" + state.messages.size()
                    + " direction=incoming_structural package=" + state.pkg);
        } catch (Exception ignored) { Log.i("RezossQwen", "telegram_capture status=node_unavailable"); }
    }

    public static synchronized void end(Object session) { sessions.remove(session); }

    public static synchronized boolean hasIncoming(Object session) {
        State state = sessions.get(session);
        return state != null && !state.messages.isEmpty();
    }

    public static synchronized String prompt(Object session) {
        State state = sessions.get(session);
        if (state == null || state.messages.isEmpty()) return "";
        ArrayList<JSONObject> input = new ArrayList<>();
        int start = Math.max(0, state.messages.size() - 3);
        for (int i = start; i < state.messages.size(); i++) {
            try { ReplyFormat.add(input, false, state.messages.get(i)); }
            catch (org.json.JSONException ignored) { }
        }
        Log.i("RezossQwen", "telegram_capture prompt messageCount=" + input.size()
                + " direction=incoming_structural");
        return ReplyFormat.encode(input);
    }

    public static boolean isTelegram(String pkg) {
        return "org.telegram.messenger.web".equals(pkg) || "org.telegram.messenger".equals(pkg);
    }

    private static State startFromEvent(Object session, Object event) {
        ComponentName component = componentFrom(invoke(event, "getContentCaptureContext"));
        if (component == null) return null;
        return register(session, component, "event_context");
    }

    private static State register(Object session, ComponentName component, String source) {
        String pkg = component.getPackageName();
        String cls = cleanClass(component.getClassName());
        if (!isTelegram(pkg)) return null;
        if (sessions.size() >= MAX_SESSIONS) sessions.clear();
        State state = new State();
        state.pkg = pkg;
        state.activity = cls;
        sessions.put(session, state);
        Log.i("RezossQwen", "telegram_capture status=" + source
                + " package=" + pkg + " activity=" + cls);
        return state;
    }

    private static ComponentName componentFrom(Object context) {
        Object value = invoke(context, "getActivityComponent");
        return value instanceof ComponentName ? (ComponentName) value : null;
    }

    private static int readType(Object event) {
        Object value = invoke(event, "getType");
        return value instanceof Integer ? ((Integer) value).intValue() : -1;
    }

    private static String textFrom(Object target) {
        Object value = invoke(target, "getText");
        if (value == null) return null;
        return String.valueOf(value).trim();
    }

    private static Object invoke(Object target, String method) {
        if (target == null) return null;
        try { return target.getClass().getMethod(method).invoke(target); }
        catch (Exception ignored) { return null; }
    }

    private static void addMessage(State state, String text) {
        if (!state.messages.isEmpty() && state.messages.get(state.messages.size() - 1).equals(text)) return;
        state.messages.add(text);
        while (state.messages.size() > MAX_MESSAGES) state.messages.remove(0);
    }

    private static boolean isMessageCandidate(String id, String cls, String text, boolean nodePresent, int type) {
        if (!hasText(text) || text.length() > 600) return false;
        if (!nodePresent) return type != TYPE_VIEW_TEXT_CHANGED && type != TYPE_CONTEXT_UPDATED;
        String lower = id.toLowerCase(Locale.US) + " " + cls.toLowerCase(Locale.US);
        if (lower.contains("edit") || lower.contains("input") || lower.contains("composer")
                || lower.contains("caption") || lower.contains("emoji") || lower.contains("button")) return false;
        return lower.contains("text") || lower.contains("message") || lower.contains("view");
    }

    private static boolean hasText(String value) {
        return value != null && value.length() >= 2;
    }

    private static String cleanId(String value) {
        if (value == null || "null".equals(value) || !value.matches("[A-Za-z0-9_]{1,80}")) return "unavailable";
        return value;
    }

    private static String cleanClass(String value) {
        if (value == null || "null".equals(value) || !value.matches("[A-Za-z0-9_.$]{1,160}")) return "unavailable";
        return value;
    }
}
