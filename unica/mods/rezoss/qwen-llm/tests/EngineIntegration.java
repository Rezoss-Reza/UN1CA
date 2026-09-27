package io.mesalabs.rezoss.qwentest;

import android.app.Instrumentation;
import android.os.*;
import io.mesalabs.unica.localsummary.QwenEngine;
import io.mesalabs.rezoss.ai.ReplyFormat;
import java.util.*;
import org.json.*;

public final class EngineIntegration extends Instrumentation {
    private Bundle options;
    @Override public void onCreate(Bundle arguments) { options = arguments; start(); }
    private static void require(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            QwenEngine.initialize(getTargetContext());
            ArrayList<JSONObject> input = new ArrayList<>();
            ReplyFormat.add(input, true, "I have finished the report.");
            ReplyFormat.add(input, false, "Can you send it to me this afternoon?");
            String prompt = ReplyFormat.encode(input);
            require(new JSONArray(prompt).length() == 2, "whole_messages");
            require(ReplyFormat.parse("{\"negative\":\"Sorry, I cannot.\",\"neutral\":\"What time?\",\"positive\":\"Yes, I can.\"}").size() == 3, "three_tones");
            require(ReplyFormat.parse("Me: echoed text").isEmpty(), "reject_echo");
            require(ReplyFormat.parse("{\"negative\":\"No\"}").isEmpty(), "reject_partial");
            require(ReplyFormat.parse("{\"negative\":\"Same\",\"neutral\":\"Same\",\"positive\":\"Yes\"}").isEmpty(), "reject_duplicates");
            long start = SystemClock.elapsedRealtime();
            String output = QwenEngine.generate(prompt, false, 60000, true);
            result.putLong("reply_elapsed_ms", SystemClock.elapsedRealtime() - start);
            if ("missing".equals(options.getString("mode"))) {
                require(output == null, "missing_engine_no_reply");
                require("New messages are waiting.".equals(QwenEngine.summary("Synthetic test message.")), "missing_summary_fallback");
            } else {
                require(ReplyFormat.parse(output).size() == 3, "engine_three_replies");
                String summary = QwenEngine.summary("The meeting moved from Monday to Tuesday. The location remains the main office.");
                require(summary != null && !summary.equals("New messages are waiting."), "engine_summary");
                result.putInt("reply_count", ReplyFormat.parse(output).size());
                result.putInt("summary_chars", summary.length());
            }
            result.putString("status", "PASS");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("status", "FAIL");
            result.putString("failure", failure.getClass().getSimpleName() + ":" + failure.getMessage());
            finish(0, result);
        }
    }
}
