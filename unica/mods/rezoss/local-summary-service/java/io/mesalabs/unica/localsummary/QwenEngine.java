package io.mesalabs.unica.localsummary;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import java.util.concurrent.*;

/** System-side adapter only. Model, process, and inference live in the optional user app. */
public final class QwenEngine {
    private static final String ENGINE = "io.mesalabs.rezoss.qwen";
    private static Context app;
    public static void initialize(Context context) { app = context.getApplicationContext(); }
    public static void prioritizeReply() { }
    // Kept for the old smali startup call. Never execute a server in the privileged bridge.
    public static void startServer(ProcessBuilder ignored) { }

    public static String generate(String input, boolean summary, int budgetMs) {
        return generate(input, summary, budgetMs, false);
    }

    public static String generate(String input, boolean summary, int budgetMs, boolean foreground) {
        if (app == null || Looper.myLooper() == Looper.getMainLooper()) return null;
        Context context = app;
        try {
            if (context.getPackageManager().checkSignatures(context.getPackageName(), ENGINE)
                    != PackageManager.SIGNATURE_MATCH) {
                Log.i("RezossAI", "engine status=missing_or_untrusted");
                return null;
            }
        } catch (Exception unavailable) { return null; }
        CountDownLatch done = new CountDownLatch(1);
        String[] result = new String[1];
        long deadline = SystemClock.elapsedRealtime() + budgetMs;
        HandlerThread thread = new HandlerThread("RezossEngineIPC");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        Messenger callback = new Messenger(new Handler(thread.getLooper()) {
            @Override public void handleMessage(Message response) {
                result[0] = response.getData().getString("content");
                Log.i("RezossAI", "engine resultChars=" + (result[0] == null ? 0 : result[0].length()));
                done.countDown();
            }
        });
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                Message request = Message.obtain(null, 1);
                Bundle data = new Bundle();
                data.putString("input", input);
                data.putBoolean("summary", summary);
                data.putBoolean("foreground", foreground);
                data.putLong("deadline", deadline - 200);
                request.setData(data);
                request.replyTo = callback;
                try { new Messenger(binder).send(request); }
                catch (RemoteException gone) { done.countDown(); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { done.countDown(); }
            @Override public void onNullBinding(ComponentName name) { done.countDown(); }
            @Override public void onBindingDied(ComponentName name) { done.countDown(); }
        };
        boolean bound = false;
        long identity = Binder.clearCallingIdentity();
        try {
            bound = context.bindService(new Intent().setClassName(ENGINE, ENGINE + ".EngineService"),
                    connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                Log.i("RezossAI", "engine status=not_installed_or_disabled");
                return null;
            }
            if (!done.await(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS))
                Log.i("RezossAI", "engine status=deadline");
            return result[0];
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.i("RezossAI", "engine status=" + failure.getClass().getSimpleName());
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
            if (bound) {
                // Keep the model warm briefly, while still allowing idle memory reclamation.
                handler.postDelayed(() -> {
                    try { context.unbindService(connection); } catch (IllegalArgumentException ignored) { }
                    thread.quitSafely();
                }, 120000);
            } else thread.quitSafely();
        }
    }

    public static String summary(String input) {
        String result = generate(input, true, 25000);
        if (result != null) return result;
        Log.i("RezossAI", "summary status=fallback");
        return "New messages are waiting.";
    }
}
