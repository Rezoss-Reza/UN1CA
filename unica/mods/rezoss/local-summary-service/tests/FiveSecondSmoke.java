import io.mesalabs.unica.localsummary.QwenEngine;
import android.os.SystemClock;

public final class FiveSecondSmoke {
    public static void main(String[] args) {
        String[] prompts = {"Other: Can we meet tomorrow at 3 PM?", "Other: Can we meet tomorrow at 3 PM?", "Other: Please bring the project notes to our meeting."};
        String warm = QwenEngine.generate(prompts[0], false, 25000, true);
        System.out.println("warmup generated=" + (warm != null));
        int successes = 0;
        for (String prompt : prompts) {
            long start = SystemClock.elapsedRealtime();
            QwenEngine.prioritizeReply();
            String result = QwenEngine.generate(prompt, false, 4700, true);
            long elapsed = SystemClock.elapsedRealtime() - start;
            System.out.println("synthetic elapsedMs=" + elapsed + " generated=" + (result != null));
            if (result != null && elapsed <= 5000) successes++;
        }
        System.out.println("withinFiveSeconds=" + successes + "/3");
        System.exit(successes == 3 ? 0 : 1);
    }
}
