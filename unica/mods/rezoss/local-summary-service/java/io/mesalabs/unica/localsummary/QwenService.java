package io.mesalabs.unica.localsummary;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import java.util.concurrent.*;

/** Signature-protected IPC avoids the vendor's content-provider association gate. */
public final class QwenService extends Service {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1));
    private Messenger endpoint;
    private final java.util.LinkedHashMap<String, CachedReply> cache = new java.util.LinkedHashMap<>();
    private static final class CachedReply {
        final String content;
        final long until = SystemClock.elapsedRealtime() + 120000;
        CachedReply(String content) { this.content = content; }
    }

    @Override public void onCreate() {
        super.onCreate();
        endpoint = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message request) {
                if (request.what != 1 || request.replyTo == null) return;
                if (checkPermission(
                        "io.mesalabs.unica.permission.LOCAL_SUMMARY", -1, request.sendingUid)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
                final Messenger callback = request.replyTo;
                Bundle data = request.getData();
                final String input = data.getString("input", "");
                final boolean foreground = data.getBoolean("foreground", false);
                final long deadline = data.getLong("deadline", 0);
                if (input.isEmpty() || input.length() > 12000) { respond(callback, null, "invalid"); return; }
                final String key = request.sendingUid + ":" + foreground + ":" + input;
                cache.entrySet().removeIf(entry -> entry.getValue().until < SystemClock.elapsedRealtime());
                CachedReply cached = cache.get(key);
                if (cached != null) {
                    Log.i("RezossAI", "reply status=cache_hit");
                    respond(callback, cached.content, "cached");
                    return;
                }
                try {
                    worker.execute(new Runnable() {
                        @Override public void run() {
                            if (foreground) QwenEngine.prioritizeReply();
                            long remaining = deadline - SystemClock.elapsedRealtime();
                            if (remaining < 1000) { respond(callback, null, "expired"); return; }
                            String result = QwenEngine.generate(input, false, (int) Math.min(foreground ? 60000 : 25000, remaining), foreground);
                            if (result != null) {
                                final String value = result;
                                new Handler(Looper.getMainLooper()).post(() -> {
                                    if (cache.size() >= 4) cache.remove(cache.keySet().iterator().next());
                                    cache.put(key, new CachedReply(value));
                                });
                            }
                            respond(callback, result, result == null ? "unavailable" : "generated");
                        }
                    });
                } catch (RejectedExecutionException busy) { respond(callback, null, "busy"); }
            }
        });
    }

    private static void respond(Messenger target, String content, String status) {
        Message response = Message.obtain(null, 1);
        Bundle data = new Bundle();
        data.putString("content", content);
        data.putString("status", status);
        response.setData(data);
        try { target.send(response); } catch (RemoteException gone) {
            Log.i("RezossAI", "reply status=client_gone");
        }
    }

    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
