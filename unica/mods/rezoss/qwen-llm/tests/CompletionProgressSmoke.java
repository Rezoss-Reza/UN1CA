package io.mesalabs.rezoss.qwen;

import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

final class CompletionProgressSmoke {
    private static JSONObject read(String stream, long deadline) throws Exception {
        return CompletionProgress.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), deadline);
    }
    static void run() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 3000;
        JSONObject result = read(": keepalive\n\ndata: {\"content\":\"one\"}\n\n"
                + "data: {\"content\":\" two\",\"stop\":true,\"stopped_limit\":true}\n\n", deadline);
        if (!"one two".equals(result.getString("content")) || !result.getBoolean("stopped_limit"))
            throw new AssertionError("stream_assembly");
        try {
            read("data: {\"content\":\"partial\"}\n\n", deadline);
            throw new AssertionError("partial_accepted");
        } catch (EOFException expected) { }
        try {
            read("data: [DONE]\n\n", deadline);
            throw new AssertionError("missing_final_accepted");
        } catch (EOFException expected) { }
        try {
            read("data: {\"stop\":true}\n\n", 0);
            throw new AssertionError("expired_accepted");
        } catch (java.net.SocketTimeoutException expected) { }
        char[] oversized = new char[32769];
        java.util.Arrays.fill(oversized, 'x');
        try {
            read(new String(oversized), deadline);
            throw new AssertionError("oversized_accepted");
        } catch (IOException expected) { }
    }
}
