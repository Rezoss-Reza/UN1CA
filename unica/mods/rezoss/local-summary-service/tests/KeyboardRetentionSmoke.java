import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Looper;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import io.mesalabs.rezoss.ai.KeyboardReplyRetention;
import java.util.ArrayList;
import java.util.Arrays;

public final class KeyboardRetentionSmoke {
    public static final class Receiver {
        int clears;
        public void q(Intent intent) { clears++; }
    }
    public static final class Ime extends InputMethodService {
        String text = "";
        final InputConnection connection = (InputConnection) java.lang.reflect.Proxy.newProxyInstance(
                InputConnection.class.getClassLoader(), new Class<?>[]{InputConnection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getTextBeforeCursor")) return text;
                    if (method.getName().equals("getTextAfterCursor") || method.getName().equals("getSelectedText")) return "";
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        @Override public InputConnection getCurrentInputConnection() { return connection; }
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Ime ime = new Ime();
        Receiver receiver = new Receiver();
        EditorInfo info = new EditorInfo();
        info.packageName = "com.whatsapp";
        info.fieldId = 1;
        KeyboardReplyRetention.start(ime, info, false);
        Intent result = new Intent("com.samsung.android.intent.action.NOW_NUDGE")
                .putExtra("REZOSS_QWEN", true).putExtra("PACKAGE_NAME", info.packageName)
                .putStringArrayListExtra("SUGGESTIONS", new ArrayList<>(Arrays.asList("synthetic")));
        check(!KeyboardReplyRetention.consume(receiver, result), "accept generated item");
        check(KeyboardReplyRetention.keep(), "pin reply");
        Intent exit = new Intent("com.samsung.android.intent.action.EXIT");
        check(KeyboardReplyRetention.consume(receiver, exit), "ignore native exit");
        KeyboardReplyRetention.start(ime, info, true);
        check(KeyboardReplyRetention.keep(), "retain same editor restart");
        ime.text = "x";
        KeyboardReplyRetention.selection(ime);
        check(receiver.clears == 1 && !KeyboardReplyRetention.keep(), "manual typing clears");
        check(KeyboardReplyRetention.consume(receiver, result), "late result cannot replace draft");
        ime.text = "";
        check(KeyboardReplyRetention.consume(receiver, result), "deleting draft does not restore old replies");
        KeyboardReplyRetention.start(ime, info, false);
        check(!KeyboardReplyRetention.consume(receiver, result), "new editor can receive");
        KeyboardReplyRetention.finish();
        check(receiver.clears == 2 && !KeyboardReplyRetention.keep(), "editor exit clears");
        check(KeyboardReplyRetention.consume(receiver, result), "reject result with no editor");
        System.out.println("Keyboard retention lifecycle checks passed");
    }
}
