import android.os.Looper;
import io.mesalabs.unica.localsummary.QwenEngine;
import io.mesalabs.rezoss.ai.InAppReplies;

/** Run with app_process on the test phone; all input here is synthetic. */
public final class QwenSmoke {
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        if (InAppReplies.parse(null).size() != 0) throw new AssertionError("null output");
        if (InAppReplies.parse("1. Yes\n2. No\nYes\n3. Maybe\n4. Later").size() != 3)
            throw new AssertionError("reply deduplication/limit");
        if (!InAppReplies.parse("<|im_end|>").isEmpty()) throw new AssertionError("template token");
        if (QwenEngine.generate("", false, 2000) != null) throw new AssertionError("empty input");
        System.out.println("Parser and invalid-input checks passed");
        String reply = QwenEngine.generate("Other: Can we meet tomorrow at 3 PM?", false, 25000);
        System.out.println("SYNTHETIC reply=" + reply);
        System.out.println("Reply actionCount=" + InAppReplies.parse(reply).size());
        String summary = QwenEngine.summary("The team meeting is tomorrow at 3 PM. Meet at the library. Bring the project notes.");
        System.out.println("SYNTHETIC summary=" + summary);
        System.out.println("Summary fallback=" + "New messages are waiting.".equals(summary));
        System.exit(reply == null || "New messages are waiting.".equals(summary) ? 1 : 0);
    }
}
