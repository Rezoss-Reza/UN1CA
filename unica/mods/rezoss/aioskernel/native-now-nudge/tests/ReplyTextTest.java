package io.mesalabs.rezoss.nudge;
public final class ReplyTextTest {
    private static int checked;
    private static void check(String input,String expected){String actual=ReplyText.clean(input);if(!expected.equals(actual))throw new AssertionError(input+" -> "+actual);checked++;}
    public static void main(String[] args){
        check("Me: Yes, that works!","Yes, that works!");
        check("Yes, that works! me:","Yes, that works!");
        check("  ME : Sure. Me:  ","Sure.");
        check("Me:","");check("1. Me: Sure.","Sure.");
        check("\"Me: Sure.\"","Sure.");
        check("Count me in!","Count me in!");
        check("Tell me: what time?","Tell me: what time?");
        check("The time: noon.","The time: noon.");
        check("Some: text","Some: text");
        check("Positive: Sure.","Sure.");
        check("Postive: Sure.","Sure.");
        check("UNCERTAIN : Maybe.","Maybe.");
        check("Negative: Sorry.","Sorry.");
        check("Neutral: Okay.","Okay.");
        check("Positive: Me: Sure.","Sure.");
        check("Me: Positive: Sure. Negative:","Sure.");
        check("Sure. Positive: me:","Sure.");
        check("User: Sure.","Sure.");
        check("Assistant: Sure.","Sure.");
        check("Friend: Sure.","Sure.");
        check("Others: Sure.","Sure.");
        check("1. Positive: \"Me: Sure.\"","Sure.");
        check("Positive: Me:","");
        check("Postive:","");
        check("Positive: Tell me: what time?","Tell me: what time?");
        check("I'm positive: it will work.","I'm positive: it will work.");
        check("Ask the user: what time?","Ask the user: what time?");
        check("The assistant: helpful as always.","The assistant: helpful as always.");
        check("My friend: thank you!","My friend: thank you!");
        check("Be positive!","Be positive!");
        check("positively: sure","positively: sure");
        check("A negative: more waiting.","A negative: more waiting.");
        check("I chose Neutral: as the title.","I chose Neutral: as the title.");
        if(!"Sure.\nWhat time?\nSorry.".equals(ReplyText.lines("Me: Sure.\nMe:\nWhat time? Me:\nMe: Sorry.")))throw new AssertionError("reply lines");
        if(!"Sure.\nTell me: what time?\nSorry.".equals(ReplyText.lines("Positive: Me: Sure.\nPostive:\nUncertain: Tell me: what time?\nNegative: Sorry. me:")))throw new AssertionError("labelled reply lines");
        String template="generate three natural responses\n<dialogue>Others: Hi</dialogue>\nOutput only the three responses in English, separated by line breaks";
        for(String input:new String[]{template,"<|turn_start|>User Hi<|turn_end|>"}) {
            String prompt=PromptAdapter.adapt(input);
            if(!prompt.contains("Never add tone labels")||!prompt.contains("before or after any reply")||!prompt.endsWith("/no_think"))throw new AssertionError("prompt labels");
        }
        System.out.println("ReplyText passed "+(checked+2)+" boundary cases and both prompt paths");
    }
}
