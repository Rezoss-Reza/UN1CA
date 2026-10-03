package io.mesalabs.rezoss.ai;

import android.content.*;
import android.os.*;
import android.util.Log;
import java.util.concurrent.*;

public final class QwenClient {
    public static String request(Context context, String input, int timeoutMs) {
        return request(context, input, timeoutMs, false);
    }

    public static String request(Context context, String input, int timeoutMs, boolean foreground) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w("RezossQwen", "ipc status=main_thread_rejected");
            return null;
        }
        final CountDownLatch done = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Messenger> remote = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        final String[] result = new String[1];
        final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        HandlerThread thread = new HandlerThread("RezossQwenCallback");
        thread.start();
        final Messenger callback = new Messenger(new Handler(thread.getLooper()) {
            @Override public void handleMessage(Message message) {
                finished.set(true);
                result[0] = message.getData().getString("content");
                // Never log remote strings: even diagnostics must not contain model output.
                Log.i("RezossQwen", "ipc resultChars=" + (result[0] == null ? 0 : result[0].length()));
                done.countDown();
            }
        });
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                synchronized (remote) {
                if (finished.get()) return;
                Messenger server = new Messenger(binder);
                remote.set(server);
                Message message = Message.obtain(null, 1);
                Bundle data = new Bundle();
                data.putString("input", input);
                data.putBoolean("foreground", foreground);
                data.putLong("deadline", deadline - 300);
                message.setData(data);
                message.replyTo = callback;
                try { server.send(message); }
                catch (RemoteException error) { done.countDown(); }
            }
            }
            @Override public void onServiceDisconnected(ComponentName name) { done.countDown(); }
            @Override public void onNullBinding(ComponentName name) { done.countDown(); }
            @Override public void onBindingDied(ComponentName name) { done.countDown(); }
        };
        boolean bound = false;
        try {
            Intent intent = new Intent().setClassName("io.mesalabs.unica.localsummary",
                    "io.mesalabs.unica.localsummary.QwenService");
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) { Log.w("RezossQwen", "ipc status=bind_failed"); return null; }
            if (!done.await(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)) {
                Log.w("RezossQwen", "ipc status=deadline");
                return null;
            }
            return result[0];
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.w("RezossQwen", "ipc status=" + error.getClass().getSimpleName());
            return null;
        } finally {
            boolean completed;
            synchronized (remote) {
            completed = finished.getAndSet(true);
            Messenger server = remote.get();
            if (!completed && server != null) {
                Message cancel = Message.obtain(null, 2);
                cancel.replyTo = callback;
                try { server.send(cancel); } catch (RemoteException ignored) { }
            }
            }
            if (bound) context.unbindService(connection);
            thread.quitSafely();
        }
    }
}
