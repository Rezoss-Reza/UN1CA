import io.mesalabs.rezoss.ai.ReplyFormat;
import org.json.*;
import java.util.*;

public final class ReplyFormatSmoke {
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        check(ReplyFormat.parse(null).isEmpty(), "null");
        check(ReplyFormat.parse("Me: echo").isEmpty(), "echo");
        check(ReplyFormat.parse("{\"negative\":\"No\"}").isEmpty(), "partial");
        check(ReplyFormat.parse("{\"negative\":\"No\",\"neutral\":\"Maybe\",\"positive\":\"Yes\"}").size() == 3, "three");
        check(ReplyFormat.parse("{\"negative\":\"No\",\"neutral\":\"No\",\"positive\":\"Yes\"}").isEmpty(), "duplicate");
        ArrayList<JSONObject> input = new ArrayList<>();
        ReplyFormat.add(input, true, new String(new char[9000]).replace('\0', 'a'));
        ReplyFormat.add(input, false, new String(new char[2000]).replace('\0', 'b'));
        JSONArray result = new JSONArray(ReplyFormat.encode(input));
        check(result.length() == 1 && result.getJSONObject(0).getString("text").length() == 2000, "whole_latest");
        input.clear();
        ReplyFormat.add(input, false, new String(new char[11000]).replace('\0', 'c'));
        check(ReplyFormat.encode(input).isEmpty(), "oversized_last_not_truncated");
        System.out.println("ReplyFormatSmoke PASS checks=7");
    }
}
