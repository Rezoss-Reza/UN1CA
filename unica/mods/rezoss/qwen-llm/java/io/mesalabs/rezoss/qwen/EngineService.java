package io.mesalabs.rezoss.qwen;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import java.util.concurrent.*;

/** Only the separately signed-and-verified ROM bridge may submit inference. */
public final class EngineService extends Service {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(2));
    private NativeEngine engine;
    private Messenger endpoint;

    @Override public void onCreate() {
        super.onCreate();
        engine = new NativeEngine(this);
        endpoint = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) {
                if (message.what != 1 || message.replyTo == null) return;
                try {
                    int bridge = getPackageManager().getApplicationInfo(
                            "io.mesalabs.unica.localsummary", 0).uid;
                    if (message.sendingUid != bridge || getPackageManager().checkSignatures(
                            bridge, android.os.Process.myUid()) != PackageManager.SIGNATURE_MATCH) return;
                    Bundle data = message.getData();
                    final String input = data.getString("input", "");
                    final boolean summary = data.getBoolean("summary");
                    final long deadline = Math.min(data.getLong("deadline", 0),
                            SystemClock.elapsedRealtime() + 60000);
                    final Messenger callback = message.replyTo;
                    if (input.isEmpty() || input.length() > 12000) {
                        respond(callback, null, "invalid"); return;
                    }
                    worker.execute(() -> {
                        String result = null;
                        String status = "unavailable";
                        try {
                            if (deadline - SystemClock.elapsedRealtime() > 1000) {
                                result = engine.generate(input, summary, deadline);
                                status = result == null ? "unavailable" : "generated";
                            } else status = "expired";
                        } catch (Exception failure) {
                            status = failure.getClass().getSimpleName();
                        }
                        Log.i("RezossLLM", "mode=" + (summary ? "summary" : "reply")
                                + " status=" + status);
                        respond(callback, result, status);
                    });
                } catch (RejectedExecutionException busy) {
                    respond(message.replyTo, null, "busy");
                } catch (Exception invalid) {
                    respond(message.replyTo, null, "invalid");
                }
            }
        });
    }

    private static void respond(Messenger target, String content, String status) {
        Message response = Message.obtain(null, 1);
        Bundle data = new Bundle();
        data.putString("content", content);
        data.putString("status", status);
        response.setData(data);
        try { target.send(response); } catch (RemoteException ignored) { }
    }

    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    @Override public void onDestroy() {
        worker.shutdownNow();
        engine.close();
        super.onDestroy();
    }
}
