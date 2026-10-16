package io.mesalabs.rezoss.vision;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.system.OsConstants;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;

/** Exercises the original Samsung Proxy and Parcelable DEX across processes. */
public final class ProbeActivity extends Activity {
    TextView view; String image, mode; boolean bound;
    private void report(String text) {
        Log.i("RezossVisionProbe", text);
        try { Files.write(new File(getFilesDir(), "probe-" + image + "-" + mode + ".txt").toPath(), text.getBytes("UTF-8")); }
        catch (Exception ex) { Log.e("RezossVisionProbe", "Writing result", ex); }
        runOnUiThread(() -> view.setText(text));
    }
    final Binder callback = new Binder() {
        @Override protected boolean onTransact(int code, Parcel in, Parcel out, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { out.writeString(Wire.PREFIX + "ITaskCallback"); return true; }
            in.enforceInterface(Wire.PREFIX + "ITaskCallback");
            try {
                if (code == 1 || code == 2) {
                    Object task = Wire.read(in, "TaskIOTensor");
                    List<?> prompts = (List<?>) Wire.get(task, "getMPrompts");
                    report("CALLBACK " + code + " ids=" + Wire.get(task, "getPromptIDs") + " text=" + Wire.get(prompts.get(0), "getMPromptString"));
                } else report("CALLBACK " + code);
                out.writeNoException(); out.writeByte((byte)0);
            } catch (Exception ex) { report("DECODE_FAILED " + ex); out.writeException(new IllegalArgumentException(ex)); }
            return true;
        }
    };
    final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceDisconnected(ComponentName name) { report("DISCONNECTED"); }
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            new Thread(() -> {
                SharedMemory shared = null;
                try {
                    Bitmap original = BitmapFactory.decodeStream(getAssets().open(image + ".jpg"));
                    Bitmap bitmap = Bitmap.createScaledBitmap(original, 512, 512, true);
                    int[] pixels = new int[512*512]; bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512);
                    shared = SharedMemory.create("vision-probe", pixels.length*3);
                    ByteBuffer buffer = shared.mapReadWrite();
                    for (int pixel : pixels) buffer.put((byte)(pixel>>16)).put((byte)(pixel>>8)).put((byte)pixel);
                    SharedMemory.unmap(buffer); shared.setProtect(OsConstants.PROT_READ);
                    Class<?> precision = Wire.type("TensorPrecisionType");
                    Object tensor = Wire.type("ImageTensor").getConstructor(String.class, SharedMemory.class, int.class, int.class, int.class, int.class, precision, byte[].class)
                        .newInstance("image", shared, 1, 512, 512, 3, precision.getField("INT8").get(null), new byte[0]);
                    Object prompt = Wire.type("PromptTensor").getConstructor(String.class, String.class).newInstance("userSpeech", "Describe the image in one sentence.");
                    Object input = Wire.type("TaskIOTensor").getConstructor(List.class, List.class, List.class, List.class)
                        .newInstance(Collections.singletonList("probe-" + image), Collections.singletonList(prompt),
                            mode.equals("bad-input") ? Collections.emptyList() : Collections.singletonList(tensor), Collections.emptyList());
                    Object proxy = Wire.type("IAIOSKernelService$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
                    Object callbackProxy = Wire.type("ITaskCallback$Stub").getMethod("asInterface", IBinder.class).invoke(null, callback);
                    Class<?> priority = Wire.type("TaskPriority");
                    Object task = Wire.type("IAIOSKernelService").getMethod("inference", Wire.type("TaskIOTensor"), priority, Wire.type("ITaskCallback"))
                        .invoke(proxy, input, priority.getField("TASK_PRIO_NORMAL").get(null), callbackProxy);
                    Log.i("RezossVisionProbe", "Samsung proxy returned task=" + task);
                    if (mode.equals("cancel")) Wire.type("IAIOSTask").getMethod("cancel").invoke(task);
                } catch (Exception ex) {
                    report((mode.equals("bad-input") ? "EXPECTED_REJECTION " : "ERROR ") + ex + " cause=" + ex.getCause());
                } finally { if (shared != null) shared.close(); }
            }, "SamsungProxyProbe").start();
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); view = new TextView(this); setContentView(view);
        image = getIntent().getStringExtra("image"); if (!"bus".equals(image)) image = "cat";
        mode = getIntent().getStringExtra("mode"); if (mode == null) mode = "inference";
        report("STARTING " + image + " " + mode);
        bound = bindService(new Intent(this, VisionService.class), connection, BIND_AUTO_CREATE);
        if (!bound) report("BIND_FAILED");
    }
    @Override public void onDestroy() { if (bound) unbindService(connection); super.onDestroy(); }
}
