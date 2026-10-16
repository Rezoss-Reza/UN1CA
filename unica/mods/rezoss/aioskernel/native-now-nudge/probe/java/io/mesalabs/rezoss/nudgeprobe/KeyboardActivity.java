package io.mesalabs.rezoss.nudgeprobe;
import android.app.Activity;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
/** Local editor for testing the existing Samsung keyboard-visible lifecycle. */
public final class KeyboardActivity extends Activity {
 public void onCreate(Bundle state){super.onCreate(state);LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(24,24,24,24);
 TextView label=new TextView(this);label.setText("Local Now Nudge test\nNothing is sent to another person.");layout.addView(label);
 EditText editor=new EditText(this);editor.setHint("Local draft");layout.addView(editor);setContentView(layout);editor.requestFocus();
 getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
 editor.postDelayed(()->getSystemService(InputMethodManager.class).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT),500);
 }
}
