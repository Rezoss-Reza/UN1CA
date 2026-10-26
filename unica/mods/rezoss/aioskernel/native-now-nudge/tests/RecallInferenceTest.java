package io.mesalabs.rezoss.ai;

/** Contract isolation/validation tests; these do not establish model accuracy. */
public final class RecallInferenceTest {
    private static int assertions;
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("assertion "+assertions);}
    private static RecallPrediction photo(){return RecallPrediction.parse("send the beach photo","Share Photo\tphoto beach");}
    public static void main(String[] args)throws Exception{
        check(photo()!=null);
        check(RecallPrediction.parse("send photos of our wedding","Share Photo\tphoto wedding")!=null);
        check(RecallPrediction.parse("envoyez la photo de plage","Share Photo\tphoto plage")!=null);
        check(RecallPrediction.parse("hello","Unsafe\t").action.equals("Unsafe"));
        check(RecallPrediction.parse("beach","Share Photo\tphoto mountain")==null);
        check(RecallPrediction.parse("beach","Share Photo\tphoto beach|photo beach")==null);
        check(RecallPrediction.parse("beach","Share Photo\tlocation beach")==null);
        check(RecallPrediction.parse("beach","Share Photo\tphoto beach\nmore")==null);
        check(RecallPrediction.parse("beach","Share Photo\tphoto beach\textra")==null);
        check(RecallPrediction.parse("beach","Call\tphoto beach")==null);
        check(RecallPrediction.parse("beach","Share Photo\tphoto ")==null);
        check(RecallPrediction.parse("beach","Share Photo\tphoto beach|")==null);
        check(RecallPrediction.parse("beach","Share Photo photo beach")==null);
        check(RecallPrediction.parse("schedule lunch tomorrow at noon","Create Calendar\ttype event|title lunch|start tomorrow at noon")!=null);
        check(RecallPrediction.parse("schedule lunch tomorrow","Create Calendar\ttitle lunch|start tomorrow")==null);
        check(RecallPrediction.parse("schedule lunch tomorrow","Create Calendar\ttype fake|title lunch|start tomorrow")==null);
        check(RecallPrediction.parse("check calendar tomorrow","View Calendar\tstart tomorrow")!=null);
        check(RecallPrediction.parse("check calendar","View Calendar\t").parameters.isEmpty());
        check(RecallPrediction.parse("check calendar","View Calendar\t\n").parameters.isEmpty());
        check(RecallPrediction.parse("check calendar","View Calendar")==null);
        check(RecallPrediction.parse("check calendar","View Calendar\tstart tomorrow")==null);
        RecallSession.put("send the beach photo",photo(),200);
        check(RecallSession.consume("send the beach photo <Action> Share Photo <상대> Others",100).equals("photo beach"));
        check(RecallSession.consume("send the beach photo <Action> Share Photo",100).isEmpty());
        RecallSession.put("send the beach photo",photo(),200);
        check(RecallSession.consume("other <Action> Share Photo",100).isEmpty());
        check(RecallSession.consume("send the beach photo <Action> Share Photo",100).isEmpty());
        RecallSession.put("send the beach photo",photo(),200);
        check(RecallSession.consume("send the beach photo <Action> Share Photo",200).isEmpty());
        RecallSession.put("send the beach photo",photo(),200);
        check(RecallSession.consume("send the beach photo <Action> Create Calendar",100).isEmpty());
        RecallSession.put("send the beach photo",photo(),200);
        java.util.concurrent.atomic.AtomicBoolean isolated=new java.util.concurrent.atomic.AtomicBoolean();
        Thread thread=new Thread(()->isolated.set(RecallSession.consume("send the beach photo <Action> Share Photo",100).isEmpty()));
        thread.start();thread.join();
        check(isolated.get());
        check(RecallSession.consume("send the beach photo <Action> Share Photo",100).equals("photo beach"));
        RecallSession.put("send the beach photo",photo(),200);RecallSession.clear();
        check(RecallSession.consume("send the beach photo <Action> Share Photo",100).isEmpty());
        RecallSession.put("send the beach photo",photo(),200);
        RecallSession.put("hello",RecallPrediction.parse("hello","Unsafe"),200);
        check(RecallSession.consume("send the beach photo <Action> Share Photo",100).isEmpty());
        System.out.println("Recall contract passed "+assertions+" assertions plus cross-thread isolation");
    }
}
