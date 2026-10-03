package io.mesalabs.rezoss.qwen;

import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Bounded SSE decoding; logs measurements only, never generated content. */
final class CompletionProgress {
    static JSONObject read(InputStream input, long deadline) throws Exception {
        long started = SystemClock.elapsedRealtime();
        int events = 0, total = 0;
        StringBuilder content = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder line = new StringBuilder();
        Log.i("RezossLLM", "completion status=stream_open");
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                if (SystemClock.elapsedRealtime() >= deadline) throw new java.net.SocketTimeoutException();
                int value = reader.read();
                if (value < 0) throw new EOFException("incomplete completion");
                if (++total > 262144 || line.length() >= 32768) throw new IOException("stream bound");
                if (value != '\n') { line.append((char) value); continue; }
                String event = line.toString().trim();
                line.setLength(0);
                if (!event.startsWith("data:")) continue;
                String data = event.substring(5).trim();
                if (data.equals("[DONE]")) throw new EOFException("missing final metrics");
                JSONObject chunk = new JSONObject(data);
                if (chunk.has("error")) throw new IOException("completion error");
                String piece = chunk.optString("content", "");
                if (!piece.isEmpty()) {
                    content.append(piece);
                    events++;
                    if (content.length() > 8192) throw new IOException("content bound");
                    if (events == 1 || events % 16 == 0)
                        Log.i("RezossLLM", "completion elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                                + " chunks=" + events + " chars=" + content.length());
                }
                if (chunk.optBoolean("stop")) {
                    JSONObject timing = chunk.optJSONObject("timings");
                    if (timing != null) {
                        Log.i("RezossLLM", "completion promptTokens=" + timing.optInt("prompt_n", -1)
                                + " promptMs=" + timing.optDouble("prompt_ms", -1)
                                + " predictedTokens=" + timing.optInt("predicted_n", -1)
                                + " predictedMs=" + timing.optDouble("predicted_ms", -1)
                                + " tokensPerSecond=" + timing.optDouble("predicted_per_second", -1));
                    }
                    chunk.put("content", content.toString());
                    return chunk;
                }
            }
        } finally {
            Log.i("RezossLLM", "completion endedMs=" + (SystemClock.elapsedRealtime() - started)
                    + " chunks=" + events + " chars=" + content.length());
        }
    }
}
