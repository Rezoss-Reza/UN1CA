package io.mesalabs.rezoss.qwentest;

import android.app.Instrumentation;
import android.os.*;
import io.mesalabs.unica.localsummary.QwenEngine;
import org.json.*;

/** Links to the installed bridge class; the test APK must not package QwenEngine. */
public final class BridgeSafetyTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            JSONArray input = new JSONArray().put(new JSONObject().put("speaker", "sender")
                    .put("text", "Can we meet tomorrow afternoon?"));
            long started = SystemClock.elapsedRealtime();
            String output = QwenEngine.generate(input.toString(), false, 32000, true);
            result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started);
            if (output == null) throw new AssertionError("no_reply_check_engine_diagnostics");
            JSONObject replies = new JSONObject(output);
            for (String tone : new String[]{"negative", "neutral", "positive"})
                if (replies.getString(tone).trim().isEmpty()) throw new AssertionError("missing_tone");
            result.putInt("reply_count", 3);
            result.putString("status", "PASS");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("status", "FAIL");
            result.putString("failure", error.getClass().getSimpleName());
            finish(0, result);
        }
    }
}
