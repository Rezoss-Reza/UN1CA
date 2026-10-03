package io.mesalabs.rezoss.qwen;

import android.app.Instrumentation;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** One synthetic request in the engine UID; not an end-to-end bridge test. */
public final class EngineRuntimeTest extends Instrumentation {
    private boolean parserOnly;
    private int batchThreads = 2;
    private boolean warmFollowup;
    @Override public void onCreate(Bundle args) {
        parserOnly = args != null && "true".equals(args.getString("parserOnly"));
        if (args != null && "4".equals(args.getString("batchThreads"))) batchThreads = 4;
        warmFollowup = args != null && "true".equals(args.getString("warmFollowup"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        NativeEngine engine = new NativeEngine(getTargetContext(), batchThreads);
        result.putInt("batch_threads", batchThreads);
        ScheduledExecutorService guard = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<String> stopped = new AtomicReference<>();
        long started = SystemClock.elapsedRealtime();
        long deadline = started + 30000;
        try {
            CompletionProgressSmoke.run();
            result.putString("stream_tests", "PASS");
            if (parserOnly) {
                result.putString("status", "PASS");
                finish(-1, result);
                return;
            }
            String blocked = ThermalGuard.blocked(getTargetContext());
            if (blocked != null) throw new IllegalStateException(blocked);
            guard.scheduleAtFixedRate(() -> {
                String reason = ThermalGuard.blocked(getTargetContext());
                if (reason == null && SystemClock.elapsedRealtime() >= deadline) reason = "deadline";
                if (reason != null) { stopped.compareAndSet(null, reason); engine.cancel(); }
            }, 0, 1, TimeUnit.SECONDS);
            JSONArray input = new JSONArray()
                    .put(new JSONObject().put("speaker", "owner").put("text", "I finished the report."))
                    .put(new JSONObject().put("speaker", "sender").put("text", "Can you send it this afternoon?"));
            String output = engine.generate(input.toString(), false, deadline);
            result.putLong("reply_elapsed_ms", SystemClock.elapsedRealtime() - started);
            if (output == null || stopped.get() != null) throw new AssertionError("reply_missing");
            JSONObject replies = new JSONObject(output);
            for (String tone : new String[]{"negative", "neutral", "positive"})
                if (replies.getString(tone).isEmpty()) throw new AssertionError("tone_missing");
            result.putInt("reply_count", 3);
            if (warmFollowup) {
                input.getJSONObject(1).put("text", "Can you send it this evening?");
                long warmStarted = SystemClock.elapsedRealtime();
                String warm = engine.generate(input.toString(), false, deadline);
                result.putLong("warm_reply_elapsed_ms", SystemClock.elapsedRealtime() - warmStarted);
                if (warm == null || stopped.get() != null) throw new AssertionError("warm_reply_missing");
                JSONObject warmReplies = new JSONObject(warm);
                for (String tone : new String[]{"negative", "neutral", "positive"})
                    if (warmReplies.getString(tone).isEmpty()) throw new AssertionError("warm_tone_missing");
                result.putInt("warm_reply_count", 3);
            }
            result.putString("status", "PASS");
        } catch (Throwable error) {
            result.putString("status", "FAIL");
            result.putString("failure", error.getClass().getSimpleName());
            if (stopped.get() != null) result.putString("stop_reason", stopped.get());
        } finally {
            engine.close();
            guard.shutdownNow();
        }
        finish("PASS".equals(result.getString("status")) ? -1 : 0, result);
    }
}
