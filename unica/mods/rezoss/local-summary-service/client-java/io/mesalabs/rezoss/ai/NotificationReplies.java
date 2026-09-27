package io.mesalabs.rezoss.ai;

import android.content.Context;
import android.util.Log;
import android.view.textclassifier.ConversationAction;
import android.view.textclassifier.ConversationActions;
import java.util.*;

public final class NotificationReplies {
    public static ConversationActions suggest(Context context, ConversationActions.Request request) {
        ArrayList<ConversationAction> actions = new ArrayList<>();
        try {
            ArrayList<org.json.JSONObject> input = new ArrayList<>();
            List<ConversationActions.Message> messages = request.getConversation();
            for (int i = Math.max(0, messages.size() - 12); i < messages.size(); i++) {
                CharSequence text = messages.get(i).getText();
                if (text != null) ReplyFormat.add(input,
                        ConversationActions.Message.PERSON_USER_SELF.equals(messages.get(i).getAuthor()),
                        text.toString());
            }
            String prompt = ReplyFormat.encode(input);
            if (!prompt.isEmpty()) {
                for (String reply : ReplyFormat.parse(QwenClient.request(context, prompt, 28000))) {
                    actions.add(new ConversationAction.Builder(ConversationAction.TYPE_TEXT_REPLY)
                            .setTextReply(reply).setConfidenceScore(1.0f).build());
                }
            }
        } catch (Exception error) {
            Log.w("RezossQwen", "notification status=" + error.getClass().getSimpleName());
        }
        Log.i("RezossQwen", "notification actionCount=" + actions.size());
        return new ConversationActions(actions, "rezoss-qwen");
    }
}
