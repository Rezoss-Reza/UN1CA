package io.mesalabs.rezoss.qwen;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional MLC Android runtime, loaded reflectively to keep API-only ROM builds compatible. */
final class MlcRuntime {
    private enum State { STOPPED, STARTING, READY, FAILED }
    private static final Object STATE_LOCK = new Object();
    private static final ReentrantLock INFERENCE_LOCK = new ReentrantLock();
    private static final ConcurrentHashMap<String, Pending> REQUESTS = new ConcurrentHashMap<>();
    private static final Set<String> CANCELLED = new HashSet<>();
    private static volatile State state = State.STOPPED;
    private static volatile String failure = "";
    private static volatile Object engine;
    private static volatile Object abortFunction;
    private static volatile Object callbackFunction;

    private static final class Pending {
        final CountDownLatch finished = new CountDownLatch(1);
        final StringBuilder content = new StringBuilder();
        volatile String error;
    }

    private MlcRuntime() { }

    static boolean isPackaged(Context context) {
        if (context == null) return false;
        File runtime = new File(context.getApplicationInfo().nativeLibraryDir,
                "libtvm4j_runtime_packed.so");
        if (!runtime.isFile()) return false;
        try {
            MlcModelStore.modelLibrary(context);
            Class.forName("org.apache.tvm.Function", false, context.getClassLoader());
            return true;
        } catch (Exception | LinkageError unavailable) {
            return false;
        }
    }

    static boolean isReady(Context context) {
        return isPackaged(context) && MlcModelStore.isReady(context);
    }

    /** Starts model initialization asynchronously so the settings and provider threads stay responsive. */
    static void warm(Context context) {
        Context app = context.getApplicationContext();
        if (!isReady(app)) return;
        synchronized (STATE_LOCK) {
            if (state == State.STARTING || state == State.READY || state == State.FAILED) return;
            state = State.STARTING;
            failure = "";
        }
        Thread loader = new Thread(() -> initialize(app), "Rezoss-Mlc-Init");
        loader.setDaemon(true);
        loader.start();
    }

    private static void initialize(Context context) {
        try {
            Class<?> functionClass = Class.forName("org.apache.tvm.Function");
            Object create = invokeStatic(functionClass, "getFunction", "mlc.json_ffi.CreateJSONFFIEngine");
            Object createdValue = invoke(create, "invoke");
            Object module = invoke(createdValue, "asModule");
            Object init = invoke(module, "getFunction", "init_background_engine");
            Object reload = invoke(module, "getFunction", "reload");
            Object runLoop = invoke(module, "getFunction", "run_background_loop");
            Object runStreamLoop = invoke(module, "getFunction", "run_background_stream_back_loop");
            Object abort = invoke(module, "getFunction", "abort");

            Class<?> deviceClass = Class.forName("org.apache.tvm.Device");
            Object device = invokeStatic(deviceClass, "opencl");
            int deviceType = ((Number) readField(device, "deviceType")).intValue();
            int deviceId = ((Number) readField(device, "deviceId")).intValue();
            Object tvmCallback = makeCallback();
            callbackFunction = invokeStatic(functionClass, "convertFunc", tvmCallback);
            invokeFunction(init, deviceType, deviceId, callbackFunction);

            Thread inference = new Thread(() -> runLoop(runLoop), "Rezoss-Mlc-Inference");
            inference.setPriority(Thread.MAX_PRIORITY);
            inference.setDaemon(true);
            inference.start();
            Thread stream = new Thread(() -> runLoop(runStreamLoop), "Rezoss-Mlc-Stream");
            stream.setDaemon(true);
            stream.start();

            JSONObject config = new JSONObject()
                    .put("model", MlcModelStore.modelDirectory(context).getAbsolutePath())
                    .put("model_lib", "system://" + MlcModelStore.modelLibrary(context))
                    .put("mode", "interactive")
                    .put("max_num_sequence", 1)
                    .put("max_total_sequence_length", 2048)
                    .put("max_single_sequence_length", 2048)
                    .put("prefill_chunk_size", 128);
            invokeFunction(reload, config.toString());

            engine = module;
            abortFunction = abort;
            synchronized (STATE_LOCK) {
                state = State.READY;
                STATE_LOCK.notifyAll();
            }
        } catch (Throwable problem) {
            synchronized (STATE_LOCK) {
                state = State.FAILED;
                failure = problem.getMessage() == null ? "MLC initialization failed" : problem.getMessage();
                STATE_LOCK.notifyAll();
            }
        }
    }

    private static Object makeCallback() throws Exception {
        Class<?> callbackType = Class.forName("org.apache.tvm.Function$Callback");
        InvocationHandler handler = (proxy, method, args) -> {
            if ("invoke".equals(method.getName()) && args != null && args.length > 0) {
                try {
                    Object value = args[0];
                    if (value != null && value.getClass().isArray() && Array.getLength(value) > 0)
                        value = Array.get(value, 0);
                    acceptStream(String.valueOf(invoke(value, "asString")));
                }
                catch (Exception ignored) { }
                return 1;
            }
            if ("toString".equals(method.getName())) return "RezossMLCStreamCallback";
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
            return null;
        };
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(),
                new Class<?>[]{callbackType}, handler);
        return callback;
    }

    private static void acceptStream(String raw) {
        try {
            JSONArray responses = new JSONArray(raw);
            for (int i = 0; i < responses.length(); i++) {
                JSONObject response = responses.optJSONObject(i);
                if (response == null) continue;
                Pending pending = REQUESTS.get(response.optString("id", ""));
                if (pending == null) continue;
                JSONArray choices = response.optJSONArray("choices");
                if (choices != null) {
                    for (int choiceIndex = 0; choiceIndex < choices.length(); choiceIndex++) {
                        JSONObject choice = choices.optJSONObject(choiceIndex);
                        if (choice == null) continue;
                        JSONObject delta = choice.optJSONObject("delta");
                        if (delta != null) {
                            String text = delta.optString("content", "");
                            synchronized (pending) {
                                if (pending.content.length() + text.length() > 8192) {
                                    pending.error = "local_output_too_large";
                                    pending.finished.countDown();
                                } else pending.content.append(text);
                            }
                        }
                        String finish = choice.optString("finish_reason", "");
                        if (!finish.isEmpty() && !"null".equals(finish)) {
                            if ("error".equals(finish)) pending.error = "local_generation_error";
                            pending.finished.countDown();
                        }
                    }
                }
                if (!response.isNull("usage")) pending.finished.countDown();
            }
        } catch (Exception ignored) {
            // Native stream callbacks are best-effort; request deadlines remain the final guard.
        }
    }

    private static void runLoop(Object function) {
        try { invoke(function, "invoke"); }
        catch (Exception problem) {
            synchronized (STATE_LOCK) {
                state = State.FAILED;
                failure = "MLC background runtime failed";
                STATE_LOCK.notifyAll();
            }
            for (Pending pending : REQUESTS.values()) {
                pending.error = "local_runtime_unavailable";
                pending.finished.countDown();
            }
        }
    }

    static String complete(Context context, String systemPrompt, String userPrompt,
            int maxTokens, long deadline, String requestId) throws Exception {
        if (!isReady(context)) throw new ApiClient.ApiException("local_model_unavailable");
        warm(context);
        awaitReady(deadline);
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining < 1) throw new ApiClient.ApiException("deadline");
        try {
            if (!INFERENCE_LOCK.tryLock(remaining, TimeUnit.MILLISECONDS))
                throw new ApiClient.ApiException("busy");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiClient.ApiException("cancelled");
        }
        try {
            if (Thread.currentThread().isInterrupted()) throw new ApiClient.ApiException("cancelled");
            synchronized (CANCELLED) {
                if (CANCELLED.remove(requestId)) throw new ApiClient.ApiException("cancelled");
            }
            Pending pending = new Pending();
            if (REQUESTS.putIfAbsent(requestId, pending) != null)
                throw new ApiClient.ApiException("duplicate_request");
            try {
                JSONArray messages = new JSONArray();
                if (systemPrompt != null && !systemPrompt.isEmpty())
                    messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
                messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
                JSONObject request = new JSONObject().put("messages", messages)
                        .put("model", MlcModelStore.MODEL_ID)
                        .put("max_tokens", maxTokens).put("stream", true);
                Object completion = invoke(engine, "getFunction", "chat_completion");
                invokeFunction(completion, request.toString(), requestId);

                while (pending.finished.getCount() != 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        abort(requestId);
                        throw new ApiClient.ApiException("cancelled");
                    }
                    remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining < 1) {
                        abort(requestId);
                        throw new ApiClient.ApiException("deadline");
                    }
                    pending.finished.await(Math.min(100, remaining), TimeUnit.MILLISECONDS);
                }
                if (pending.error != null) throw new ApiClient.ApiException(pending.error);
                String output;
                synchronized (pending) { output = pending.content.toString().trim(); }
                if (output.isEmpty()) throw new ApiClient.ApiException("invalid_output");
                return output;
            } finally {
                REQUESTS.remove(requestId, pending);
            }
        } finally {
            INFERENCE_LOCK.unlock();
        }
    }

    private static void awaitReady(long deadline) throws Exception {
        synchronized (STATE_LOCK) {
            while (state == State.STARTING) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining < 1) throw new ApiClient.ApiException("deadline");
                try { STATE_LOCK.wait(Math.min(100, remaining)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ApiClient.ApiException("cancelled");
                }
            }
            if (state != State.READY)
                throw new ApiClient.ApiException("local_runtime_unavailable");
        }
    }

    static void cancel(String requestId) {
        if (requestId == null || requestId.isEmpty()) return;
        Pending pending = REQUESTS.get(requestId);
        if (pending == null) {
            synchronized (CANCELLED) {
                CANCELLED.add(requestId);
                while (CANCELLED.size() > 128) CANCELLED.remove(CANCELLED.iterator().next());
            }
        } else abort(requestId);
    }

    static void cancelAll() {
        List<String> ids = new ArrayList<>(REQUESTS.keySet());
        for (String id : ids) abort(id);
        synchronized (CANCELLED) { CANCELLED.clear(); }
    }

    private static void abort(String requestId) {
        Object abort = abortFunction;
        if (abort == null) return;
        try { invokeFunction(abort, requestId); }
        catch (Exception ignored) { }
    }

    private static Object invokeStatic(Class<?> type, String name, Object... args) throws Exception {
        return invokeMethod(null, type, name, args);
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        return invokeMethod(target, target.getClass(), name, args);
    }

    private static Object invokeMethod(Object target, Class<?> type, String name, Object... args) throws Exception {
        Method selected = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
            boolean match = true;
            Class<?>[] parameters = method.getParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                if (args[i] != null && !wrap(parameters[i]).isAssignableFrom(args[i].getClass())) {
                    match = false;
                    break;
                }
            }
            if (match) { selected = method; break; }
        }
        if (selected == null) throw new NoSuchMethodException(type.getName() + "." + name);
        try { return selected.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }

    private static Object invokeFunction(Object function, Object... args) throws Exception {
        Object chained = function;
        for (Object arg : args) {
            Object next = invoke(chained, "pushArg", arg);
            if (next != null) chained = next;
        }
        return invoke(chained, "invoke");
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getField(name);
        return field.get(target);
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
