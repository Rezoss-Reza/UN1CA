package io.mesalabs.unica.localsummary;

import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;
import org.json.JSONArray;

/** Shared bounded inference for the provider and the reply service. */
public final class QwenEngine {
    private static final ReentrantLock INFERENCE = new ReentrantLock(true);
    private static final String TAG = "RezossAI";

    private static HttpURLConnection active;
    private static boolean activeForeground;
    private static long foregroundUntil;

    public static void prioritizeReply() {
        HttpURLConnection cancel;
        synchronized (QwenEngine.class) {
            foregroundUntil = SystemClock.elapsedRealtime() + 6000;
            cancel = activeForeground ? null : active;
        }
        if (cancel != null) cancel.disconnect();
    }

    public static String generate(String input, boolean summary, int budgetMs) {
        return generate(input, summary, budgetMs, false);
    }

    public static String generate(String input, boolean summary, int budgetMs, boolean foreground) {
        long started = SystemClock.elapsedRealtime();
        HttpURLConnection connection = null;
        boolean locked = false;
        try {
            if (input == null || input.trim().isEmpty()) return null;
            synchronized (QwenEngine.class) {
                if (!foreground && SystemClock.elapsedRealtime() < foregroundUntil) {
                    Log.i(TAG, "qwen status=foreground_reserved");
                    return null;
                }
            }
            locked = INFERENCE.tryLock(foreground ? 800 : 200, TimeUnit.MILLISECONDS);
            if (!locked) {
                Log.i(TAG, "qwen mode=" + (summary ? "summary" : "reply") + " status=busy");
                return null;
            }
            // Bound prefill cost while retaining the most recent conversation text.
            input = input.substring(Math.max(0, input.length() - (summary ? 1200 : 240)));
            input = input.replace("<|", "< | ").replace("|>", " | >");
            String instruction = summary
                    ? "Summarize the messages in one short sentence. Use their language. Return only the summary."
                    : "Reply briefly to the last message, in its language. One reply, no explanation.";
            String prompt = "<|im_start|>system\n" + instruction + "<|im_end|>\n"
                    + "<|im_start|>user\n" + input + "<|im_end|>\n<|im_start|>assistant\n";
            JSONObject body = new JSONObject();
            body.put("prompt", prompt);
            body.put("n_predict", summary ? 40 : 16);
            body.put("temperature", 0.2);
            body.put("cache_prompt", true);
            body.put("stop", new JSONArray().put("<|im_end|>").put("<|im_start|>"));
            connection = (HttpURLConnection) new URL("http://127.0.0.1:8085/completion").openConnection();
            synchronized (QwenEngine.class) {
                active = connection;
                activeForeground = foreground;
            }
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(Math.max(1000, budgetMs - (int) (SystemClock.elapsedRealtime() - started)));
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            if (status != 200) {
                Log.i(TAG, "qwen status=http_" + status);
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream stream = connection.getInputStream()) {
                byte[] chunk = new byte[2048];
                int count;
                while ((count = stream.read(chunk)) != -1) {
                    if (buffer.size() + count > 65536) throw new java.io.IOException("response limit");
                    buffer.write(chunk, 0, count);
                }
            }
            Object content = new JSONObject(buffer.toString("UTF-8")).opt("content");
            String result = content instanceof String ? ((String) content).trim() : "";
            if (result.contains("<|") || result.contains("<think>")) result = "";
            if (summary) result = result.replaceAll("\\s+", " ");
            if (result.length() > 512) result = result.substring(0, 512);
            Log.i(TAG, "qwen mode=" + (summary ? "summary" : "reply") + " status="
                    + (result.isEmpty() ? "empty" : "generated") + " chars=" + result.length()
                    + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
            return result.isEmpty() ? null : result;
        } catch (Exception failure) {
            Log.w(TAG, "qwen status=" + failure.getClass().getSimpleName()
                    + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
            return null;
        } finally {
            synchronized (QwenEngine.class) {
                if (active == connection) { active = null; activeForeground = false; }
            }
            if (connection != null) connection.disconnect();
            if (locked) INFERENCE.unlock();
        }
    }

    public static void startServer(ProcessBuilder builder) throws java.io.IOException {
        // The old undrained stdout pipe could eventually block inference server logging.
        java.util.ArrayList<String> command = new java.util.ArrayList<>(builder.command());
        command.remove("--no-warmup");
        builder.command(command);
        builder.redirectOutput(new java.io.File("/dev/null"));
        builder.redirectError(new java.io.File("/dev/null"));
        builder.start();
        Log.i(TAG, "server status=started");
    }

    public static String summary(String input) {
        String result = generate(input, true, 25000);
        if (result != null) return result;
        Log.i(TAG, "summary status=fallback");
        return "New messages are waiting.";
    }
}
