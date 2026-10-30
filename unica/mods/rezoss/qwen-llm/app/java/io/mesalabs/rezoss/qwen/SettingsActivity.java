package io.mesalabs.rezoss.qwen;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small user-facing settings screen for an OpenAI-compatible API. */
public final class SettingsActivity extends Activity {
    private EditText endpoint;
    private EditText model;
    private EditText apiKey;
    private TextView keyState;
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Rezoss AI");

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        page.setPadding(pad, pad, pad, pad);
        scroll.addView(page);

        TextView title = new TextView(this);
        title.setText("API settings");
        title.setTextSize(24);
        page.addView(title, matchWrap());

        TextView description = new TextView(this);
        description.setText("The ROM keeps its current screen-text extraction and reply/summary flow. Extracted text is sent to the endpoint configured here when it needs a response.");
        description.setTextSize(14);
        description.setPadding(0, dp(12), 0, dp(18));
        page.addView(description, matchWrap());

        addLabel(page, "OpenAI-compatible chat-completions URL");
        endpoint = field("https://api.openai.com/v1/chat/completions",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setText("https://api.openai.com/v1/chat/completions");
        page.addView(endpoint, matchWrap());

        addLabel(page, "Model name");
        model = field("e.g. gpt-4o-mini", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        page.addView(model, matchWrap());

        addLabel(page, "API key");
        apiKey = field("Paste your API key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.addView(apiKey, matchWrap());
        keyState = new TextView(this);
        keyState.setTextSize(12);
        keyState.setPadding(0, dp(4), 0, dp(12));
        page.addView(keyState, matchWrap());

        Button save = new Button(this);
        save.setText("Save settings");
        save.setOnClickListener(view -> saveSettings());
        page.addView(save, matchWrap());

        Button clear = new Button(this);
        clear.setText("Remove saved API key");
        clear.setOnClickListener(view -> confirmClearKey());
        page.addView(clear, matchWrap());

        TextView privacy = new TextView(this);
        privacy.setText("The API key is encrypted with Android Keystore. Use an HTTPS endpoint so the key and extracted text are encrypted in transit.");
        privacy.setTextSize(12);
        privacy.setPadding(0, dp(18), 0, 0);
        page.addView(privacy, matchWrap());

        setContentView(scroll);
        loadSettings();
    }

    private void loadSettings() {
        try {
            ApiSettingsStore.Settings settings = ApiSettingsStore.display(this);
            endpoint.setText(settings.endpoint);
            model.setText(settings.model);
            apiKey.setText("");
            keyState.setText(!ApiSettingsStore.hasSavedKey(this)
                    ? "No API key saved."
                    : "API key saved. Leave this field empty to keep it.");
        } catch (Exception failure) {
            keyState.setText("Saved API key could not be read. Enter a new key to replace it.");
        }
    }

    private void saveSettings() {
        String endpointValue = endpoint.getText().toString();
        String modelValue = model.getText().toString();
        String keyValue = apiKey.getText().toString();
        storage.execute(() -> {
            String error = null;
            try { ApiSettingsStore.save(this, endpointValue, modelValue, keyValue); }
            catch (Exception failure) {
                error = failure instanceof IllegalArgumentException
                        ? failure.getMessage() : "Could not save settings.";
            }
            String message = error;
            main.post(() -> {
                if (isFinishing()) return;
                if (message != null) {
                    new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show();
                    return;
                }
                apiKey.setText("");
                keyState.setText("API key saved securely. Leave the field empty to keep it.");
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void confirmClearKey() {
        new AlertDialog.Builder(this)
                .setMessage("Remove the saved API key? Reply and summary generation will stop until you add another key.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    ApiSettingsStore.clearKey(this);
                    keyState.setText("No API key saved.");
                    Toast.makeText(this, "API key removed", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private EditText field(String label, int inputType) {
        EditText value = new EditText(this);
        value.setSingleLine(true);
        value.setInputType(inputType);
        value.setHint(label);
        value.setContentDescription(label);
        value.setPadding(dp(12), dp(10), dp(12), dp(10));
        return value;
    }

    private void addLabel(LinearLayout page, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(14);
        label.setPadding(0, dp(8), 0, 0);
        page.addView(label, matchWrap());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onDestroy() {
        storage.shutdown();
        super.onDestroy();
    }
}
