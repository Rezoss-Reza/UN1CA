package io.mesalabs.rezoss.qwen;

import android.app.Instrumentation;
import android.os.*;
import org.json.*;

/** Executes in the engine application's UID, not root or the system bridge. */
public final class EngineRuntimeTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        NativeEngine engine = new NativeEngine(getTargetContext());
        try {
            JSONArray input = new JSONArray()
                    .put(new JSONObject().put("speaker", "owner").put("text", "I finished the report."))
                    .put(new JSONObject().put("speaker", "sender").put("text", "Can you send it this afternoon?"));
            long start = SystemClock.elapsedRealtime();
            String output = engine.generate(input.toString(), false, start + 60000);
            result.putLong("reply_elapsed_ms", SystemClock.elapsedRealtime() - start);
            if (output == null) throw new AssertionError("reply_missing");
            JSONObject replies = new JSONObject(output);
            for (String tone : new String[]{"negative", "neutral", "positive"})
                if (replies.getString(tone).isEmpty()) throw new AssertionError("tone_missing");
            result.putInt("reply_count", 3);
            String summary = engine.generate("The meeting moved from Monday to Tuesday. The location remains the main office.",
                    true, SystemClock.elapsedRealtime() + 25000);
            if (summary == null) throw new AssertionError("summary_missing");
            result.putInt("summary_chars", summary.length());
            result.putString("status", "PASS");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("status", "FAIL");
            result.putString("failure", error.getClass().getSimpleName());
            finish(0, result);
        } finally { engine.close(); }
    }
}
