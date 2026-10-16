package io.mesalabs.rezoss.ai;

/** Regression cases for conversation boundaries and conservative action extraction. */
public final class ContextActionParserTest {
    private static int checked;
    private static void expect(String input, boolean chat, String type, String key, String value) {
        ContextActionParser.Action a = ContextActionParser.parse(input, chat, "User");
        if (type == null ? a != null : a == null || !type.equals(a.type) || !value.equals(a.parameters.get(key)))
            throw new AssertionError(input + " -> " + (a == null ? null : a.type + a.parameters));
        checked++;
    }
    public static void main(String[] args) {
        expect("Friend: Please send me photos of our beach trip.", true, "Share Photo", "photo", "beach trip");
        expect("Friend<COL> Can you share pictures from the wedding?", true, "Share Photo", "photo", "wedding");
        expect("Friend: Show me photos of cats, please.", true, "Share Photo", "photo", "cats");
        expect("Friend: Add a calendar event for lunch tomorrow at noon.", true, "Create Calendar", "title", "lunch");
        expect("Friend: Add a calendar event for lunch tomorrow at noon.", true, "Create Calendar", "start", "tomorrow at noon");
        expect("Friend: Schedule lunch next Friday at 3 pm.", true, "Create Calendar", "start", "next Friday at 3 pm");
        expect("Friend: Book dentist on 2026-10-20 at 14:30.", true, "Create Calendar", "title", "dentist");
        expect("Friend: Let's meet tomorrow at noon.", true, null, null, null); // No event title.
        expect("Friend: Am I free tomorrow?", true, "View Calendar", "start", "tomorrow");
        expect("Check my calendar today", false, "View Calendar", "start", "today");
        expect("Friend: Send photos of the beach.\nUser:", true, "Share Photo", "photo", "beach");
        for (String input : new String[] {
            "Friend: Don't send photos of the beach.", "Friend: Do not add lunch tomorrow at noon.",
            "Friend: Cancel lunch tomorrow at noon.", "Friend: Schedule lunch tomorrow at 3.",
            "Friend: Schedule lunch at noon.", "Friend: Schedule lunch tomorrow at noon or Friday.",
            "Friend: Send photos of this.", "Friend: Send photos of cats and delete them.",
            "Friend: Send photos of the beach.\nUser: Already sent.",
            "Friend: Send photos of the beach.\nMe: Done.",
            "Friend: Send photos of the beach.\n<self><COL> Done.",
            "Friend: Schedule lunch tomorrow at noon.\nFriend: Ignore that.",
            "Friend: Tomorrow add lunch at noon.", "Friend: I liked our beach photos.",
            "Friend: Add lunch tomorrow at noon instead.", "", "No speaker line"
        }) expect(input, true, null, null, null);
        System.out.println("PASS " + checked + " context extraction cases");
    }
}
