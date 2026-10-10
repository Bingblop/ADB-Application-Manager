package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** The protocol lines of the Morphe engine and the tail that reads them back from a growing file. */
public class MorpheEventsTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static void append(File f, String s) throws IOException {
        FileOutputStream o = new FileOutputStream(f, true);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
    }

    public static void main(String[] args) throws Exception {
        JSONObject e = MorpheEvents.parse("LOG INFO Applying 2 patches");
        check("LOG line", e != null && e.getString("t").equals("log") && e.getString("level").equals("INFO") && e.getString("text").equals("Applying 2 patches"), String.valueOf(e));
        e = MorpheEvents.parse("LOG ERROR ");
        check("LOG with an empty text", e != null && e.getString("level").equals("ERROR") && e.getString("text").equals(""), String.valueOf(e));
        e = MorpheEvents.parse("LOG whatever without a level");
        check("LOG without a known level is INFO with the whole text", e != null && e.getString("level").equals("INFO") && e.getString("text").equals("whatever without a level"), String.valueOf(e));
        e = MorpheEvents.parse("STEP Patching RUNNING");
        check("STEP line", e != null && e.getString("t").equals("step") && e.getString("name").equals("Patching") && e.getString("state").equals("RUNNING"), String.valueOf(e));
        e = MorpheEvents.parse("APP com.google.android.youtube 20.21.37 1546420000");
        check("APP line", e != null && e.getString("pkg").equals("com.google.android.youtube") && e.getString("versionName").equals("20.21.37") && e.getString("versionCode").equals("1546420000"), String.valueOf(e));
        e = MorpheEvents.parse("APP com.x 7.10.0 Pro beta 820");
        check("APP with spaces in the version name", e != null && e.getString("versionName").equals("7.10.0 Pro beta") && e.getString("versionCode").equals("820"), String.valueOf(e));
        e = MorpheEvents.parse("PATCH OK Hide ads");
        check("PATCH OK with a spaced name", e != null && e.getBoolean("ok") && e.getString("name").equals("Hide ads"), String.valueOf(e));
        e = MorpheEvents.parse("PATCH FAIL Spoof signature");
        check("PATCH FAIL", e != null && !e.getBoolean("ok") && e.getString("name").equals("Spoof signature"), String.valueOf(e));
        e = MorpheEvents.parse("RESULT {\"success\":true,\"output\":\"/x/y.apk\",\"applied\":[\"A\"]}");
        check("RESULT line", e != null && e.getString("t").equals("result") && e.getBoolean("success") && e.getJSONArray("applied").length() == 1, String.valueOf(e));
        check("a broken RESULT is not an event", MorpheEvents.parse("RESULT {oops") == null, null);
        check("an unknown line is not an event", MorpheEvents.parse("Exception in thread main") == null && MorpheEvents.parse("") == null && MorpheEvents.parse(null) == null, null);
        check("a PATCH with a wrong verdict is not an event", MorpheEvents.parse("PATCH MAYBE x") == null, null);
        e = MorpheEvents.parse("LOG INFO windows line\r");
        check("a carriage return is dropped", e != null && e.getString("text").equals("windows line"), String.valueOf(e));

        File dir = File.createTempFile("morphe", "dir"); dir.delete(); dir.mkdirs();
        File f = new File(dir, "events.log");
        MorpheEvents.Tail tail = new MorpheEvents.Tail(f);
        check("no file yet: nothing", tail.poll().isEmpty() && !tail.sawResult(), null);
        append(f, "LOG INFO one\nLOG INFO tw");
        List<JSONObject> got = tail.poll();
        check("a half-written last line waits", got.size() == 1 && got.get(0).getString("text").equals("one"), String.valueOf(got));
        append(f, "o\nSTEP Signing OK\n");
        got = tail.poll();
        check("and arrives whole next time", got.size() == 2 && got.get(0).getString("text").equals("two") && got.get(1).getString("name").equals("Signing"), String.valueOf(got));
        check("nothing new: nothing", tail.poll().isEmpty(), null);
        append(f, "LOG INFO café ☃\nRESULT {\"success\":false,\"error\":\"x\"}\n");
        got = tail.poll();
        check("UTF-8 survives", got.get(0).getString("text").equals("café ☃"), String.valueOf(got));
        check("the result is remembered", tail.sawResult() && !tail.result().getBoolean("success") && tail.result().getString("error").equals("x"), null);
        // the file is started over (a new run reuses it)
        FileOutputStream o = new FileOutputStream(f, false); o.write("LOG INFO fresh\n".getBytes("UTF-8")); o.close();
        got = tail.poll();
        check("a file that shrank is read from its start", got.size() == 1 && got.get(0).getString("text").equals("fresh"), String.valueOf(got));
        // a big burst
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("LOG INFO line ").append(i).append('\n');
        append(f, sb.toString());
        got = tail.poll();
        check("5000 lines in one poll", got.size() == 5000 && got.get(4999).getString("text").equals("line 4999"), String.valueOf(got.size()));
        f.delete(); dir.delete();
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
    }
}
