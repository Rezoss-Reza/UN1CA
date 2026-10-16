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
        if(!"Sure.\nWhat time?\nSorry.".equals(ReplyText.lines("Me: Sure.\nMe:\nWhat time? Me:\nMe: Sorry.")))throw new AssertionError("reply lines");
        System.out.println("ReplyText passed "+(checked+1)+" boundary cases");
    }
}
