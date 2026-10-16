package io.mesalabs.rezoss.vision;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Experimental image-to-text endpoint; does not advertise Samsung model loading. */
public final class VisionService extends Service {
    private static final String TAG = "RezossVision";
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(1));
    private final Set<Task> tasks = Collections.synchronizedSet(new HashSet<Task>());
    private final Binder endpoint = new Binder() {
        @Override public String getInterfaceDescriptor() { return Wire.PREFIX + "IAIOSKernelService"; }
        @Override protected boolean onTransact(int code, Parcel in, Parcel out, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { out.writeString(getInterfaceDescriptor()); return true; }
            in.enforceInterface(getInterfaceDescriptor());
            if (code != 8) {
                out.writeException(new UnsupportedOperationException("SmolVLM probe supports image-to-text inference only"));
                return true;
            }
            try {
                Object input = Wire.read(in, "TaskIOTensor");
                Wire.read(in, "TaskPriority");
                IBinder callback = in.readStrongBinder();
                in.enforceNoDataAvail();
                if (callback == null) throw new IllegalArgumentException("Missing callback");
                Task task = new Task(Binder.getCallingUid(), input, callback);
                tasks.add(task);
                try { callback.linkToDeath(task, 0); keepAlive(); }
                catch (Exception ex) { task.binderDied(); throw ex; }
                try { worker.execute(task); }
                catch (RejectedExecutionException ex) { task.finish(3, null); }
                out.writeNoException(); out.writeStrongBinder(task);
            } catch (Exception ex) {
                Log.e(TAG, "Rejected inference", ex);
                out.writeException(new IllegalArgumentException(ex.getMessage()));
            }
            return true;
        }
    };
    private synchronized void keepAlive() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("vision", "Image analysis", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "vision")
            .setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("Analyzing image")
            .setContentText("Processing on this device").setOngoing(true).build());
    }
    private synchronized void releaseIfIdle() {
        if (tasks.isEmpty()) stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public IBinder onBind(Intent intent) { return endpoint; }
    @Override public void onDestroy() {
        synchronized (tasks) { for (Task task : new ArrayList<Task>(tasks)) task.binderDied(); }
        worker.shutdownNow(); super.onDestroy();
    }

    private final class Task extends Binder implements Runnable, IBinder.DeathRecipient {
        final int owner; final IBinder callback; final List<?> ids; final String prompt, responseName;
        final byte[] raw; final AtomicBoolean done = new AtomicBoolean();
        volatile java.lang.Process process;
        Task(int owner, Object input, IBinder callback) throws Exception {
            this.owner = owner; this.callback = callback;
            if (input == null) throw new IllegalArgumentException("Missing input");
            ids = new ArrayList<Object>((List<?>) Wire.get(input, "getPromptIDs"));
            List<?> prompts = (List<?>) Wire.get(input, "getMPrompts");
            List<?> images = (List<?>) Wire.get(input, "getMTensors");
            if (prompts.size() != 1 || images.size() != 1)
                throw new IllegalArgumentException("Requires exactly one prompt and one RGB image");
            prompt = (String) Wire.get(prompts.get(0), "getMPromptString");
            responseName = (String) Wire.get(prompts.get(0), "getMPromptName");
            if (prompt.length() == 0 || prompt.length() > 1024 || prompt.contains("<image>"))
                throw new IllegalArgumentException("Invalid prompt");
            raw = imageInput(images.get(0));
        }
        @Override public String getInterfaceDescriptor() { return Wire.PREFIX + "IAIOSTask"; }
        @Override protected boolean onTransact(int code, Parcel in, Parcel out, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { out.writeString(getInterfaceDescriptor()); return true; }
            if (Binder.getCallingUid() != owner) throw new SecurityException("Task belongs to another UID");
            in.enforceInterface(getInterfaceDescriptor());
            if (code == 1) { stop(); finish(4, null); out.writeNoException(); out.writeByte((byte)0); return true; }
            out.writeException(new UnsupportedOperationException("Only cancellation is supported")); return true;
        }
        void stop() { java.lang.Process running = process; if (running != null) running.destroyForcibly(); }
        @Override public void binderDied() {
            if (done.compareAndSet(false, true)) { stop(); tasks.remove(this); releaseIfIdle(); }
        }
        void finish(int event, Parcelable output) {
            if (!done.compareAndSet(false, true)) return;
            tasks.remove(this); callback.unlinkToDeath(this, 0);
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(Wire.PREFIX + "ITaskCallback");
                if (event == 1) data.writeTypedObject(output, 0);
                if (!callback.transact(event, data, reply, 0)) throw new RemoteException("Callback rejected");
                reply.readException(); reply.readByte();
            } catch (Exception ex) { Log.e(TAG, "Callback failed", ex); }
            finally { data.recycle(); reply.recycle(); releaseIfIdle(); }
        }
        @Override public void run() {
            if (done.get()) return;
            File job = null;
            try {
                job = Files.createTempDirectory(getCacheDir().toPath(), "vision-").toFile();
                File models = new File(getFilesDir(), "models");
                String libs = getApplicationInfo().nativeLibraryDir;
                File image = new File(job, "image.raw"), list = new File(job, "inputs.txt"), output = new File(job, "output.txt");
                Files.write(image.toPath(), raw);
                Files.write(list.toPath(), (image.getAbsolutePath() + "\n").getBytes("UTF-8"));
                ProcessBuilder builder = new ProcessBuilder(libs + "/libsmol_runner.so",
                    "--tok_embedding_path", new File(models, "text_embedding_qnn.pte").toString(),
                    "--encoder_path", new File(models, "vision_encoder_qnn.pte").toString(),
                    "--decoder_path", new File(models, "hybrid_llama_qnn.pte").toString(),
                    "--tokenizer_path", new File(models, "tokenizer.json").toString(),
                    "--decoder_model_version", "smolvlm", "--image_path", list.toString(),
                    "--prompt", prompt, "--seq_len", "192", "--temperature", "0",
                    "--output_path", output.toString(), "--performance_output_path", new File(job, "performance.txt").toString());
                builder.directory(job).redirectErrorStream(true).redirectOutput(new File(job, "runner.txt"));
                builder.environment().put("LD_LIBRARY_PATH", libs + ":/vendor/lib64");
                builder.environment().put("ADSP_LIBRARY_PATH", libs);
                builder.environment().put("REZOSS_LEGACY_FLOAT_IO", "1");
                synchronized (this) {
                    if (done.get()) return;
                    process = builder.start();
                    if (done.get()) { stop(); return; }
                }
                if (!process.waitFor(120, TimeUnit.SECONDS)) { stop(); throw new IOException("Inference timeout"); }
                if (done.get()) return;
                if (process.exitValue() != 0) throw new IOException("Runner exit " + process.exitValue() + "; log " + job);
                String text = new String(Files.readAllBytes(output.toPath()), "UTF-8");
                int marker = text.lastIndexOf("Assistant:");
                if (marker >= 0) text = text.substring(marker + "Assistant:".length());
                text = text.replace("<end_of_utterance>", "").trim();
                if (text.isEmpty()) throw new IOException("Empty generated response");
                Log.i(TAG, "COMPLETE " + text);
                finish(1, Wire.result(ids, responseName, text));
                // Keep small logs/results for inspection; remove the large input copy.
                image.delete();
            } catch (Exception ex) { Log.e(TAG, "Inference failed; job=" + job, ex); finish(3, null); }
        }
    }

    private static byte[] imageInput(Object image) throws Exception {
        int height = (Integer) Wire.get(image, "getHeight"), width = (Integer) Wire.get(image, "getWidth");
        if ((Integer) Wire.get(image, "getBatch") != 1 || (Integer) Wire.get(image, "getChannel") != 3 ||
            height < 1 || width < 1 || height > 4096 || width > 4096 || (long)height * width > 4194304)
            throw new IllegalArgumentException("Unsupported image shape");
        if (!Wire.get(image, "getDataType").toString().equals("INT8"))
            throw new IllegalArgumentException("Probe expects unsigned RGB bytes tagged INT8, NHWC");
        byte[] rgb = (byte[]) Wire.get(image, "getByteData");
        SharedMemory shared = (SharedMemory) Wire.get(image, "getTensorData");
        try {
            if (shared != null) {
                if (shared.getSize() != height * width * 3) throw new IllegalArgumentException("Invalid shared image size");
                ByteBuffer mapped = shared.mapReadOnly();
                try { rgb = new byte[mapped.remaining()]; mapped.get(rgb); } finally { SharedMemory.unmap(mapped); }
            }
        } finally { if (shared != null) shared.close(); }
        if (rgb == null || rgb.length != height * width * 3) throw new IllegalArgumentException("Invalid RGB byte count");
        int[] colors = new int[height * width];
        for (int i = 0; i < colors.length; i++) colors[i] = 0xff000000 | ((rgb[i*3]&255)<<16) | ((rgb[i*3+1]&255)<<8) | (rgb[i*3+2]&255);
        Bitmap bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 512, 512, true);
        int[] pixels = new int[512*512]; resized.getPixels(pixels, 0, 512, 0, 0, 512, 512);
        ByteBuffer out = ByteBuffer.allocate(3*512*512*4).order(ByteOrder.LITTLE_ENDIAN);
        for (int c = 0; c < 3; c++) for (int pixel : pixels) out.putFloat((((pixel >> (16-c*8)) & 255) / 255f - .5f) / .5f);
        if (resized != bitmap) resized.recycle(); bitmap.recycle(); return out.array();
    }
}
