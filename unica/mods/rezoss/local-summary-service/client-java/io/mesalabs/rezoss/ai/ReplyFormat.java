package io.mesalabs.rezoss.ai;

import java.util.*;
import org.json.*;

/** Preserve whole messages; the engine applies the final tokenizer-based context budget. */
public final class ReplyFormat {
    public static void add(List<JSONObject> messages, boolean self, String text) throws JSONException {
        if (text != null && !text.trim().isEmpty())
            messages.add(new JSONObject().put("speaker", self ? "owner" : "sender").put("text", text));
    }

    public static String encode(List<JSONObject> messages) {
        if (messages.isEmpty()) return "";
        ArrayList<JSONObject> recent = new ArrayList<>(messages);
        String result = new JSONArray(recent).toString();
        while (result.length() > 10000 && recent.size() > 1) {
            recent.remove(0);
            result = new JSONArray(recent).toString();
        }
        // Never cut the last message mid-sentence to fit IPC.
        return result.length() > 10000 ? "" : result;
    }

    public static ArrayList<String> parse(String output) {
        ArrayList<String> replies = new ArrayList<>();
        if (output == null) return replies;
        try {
            JSONObject object = new JSONObject(output);
            for (String tone : new String[]{"negative", "neutral", "positive"}) {
                Object value = object.opt(tone);
                if (!(value instanceof String)) return new ArrayList<>();
                String text = ((String) value).trim();
                if (text.isEmpty() || text.length() > 160 || text.contains("<|")
                        || text.matches("(?is)^(me|other|owner|sender|negative|neutral|positive)\\s*:.*")
                        || replies.contains(text)) return new ArrayList<>();
                replies.add(text);
            }
        } catch (JSONException invalid) { replies.clear(); }
        return replies;
    }
}
