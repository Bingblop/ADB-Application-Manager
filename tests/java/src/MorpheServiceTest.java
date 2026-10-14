package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * The patcher service's events file: the engine writes its protocol lines into it through a stream of its own while Cancel appends its lines through
 * another descriptor, so neither may overwrite the other; and the notification text made from the engine's lines keeps every character.
 */
public class MorpheServiceTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static String read(File f) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    /** What the notification shows for this line when the service reads it as bytes. */
    static String note(String line) throws IOException {
        MorpheServiceRules.LineDecoder d = new MorpheServiceRules.LineDecoder();
        String got = null;
        for (byte b : (line + "\n").getBytes("UTF-8")) { String l = d.feed(b); if (l != null) got = MorpheServiceRules.noteText(l); }
        return got;
    }

    public static void main(String[] args) throws Exception {
        File root = File.createTempFile("msvc", "dir"); root.delete(); root.mkdirs();

        // Cancel appends its lines while the engine is still printing
        File ev = new File(root, "events.log");
        java.nio.file.Files.write(ev.toPath(), "RESULT {\"success\":true,\"old\":\"a previous run's answer\"}\n".getBytes("UTF-8"));
        OutputStream fos = MorpheServiceRules.openEvents(ev);
        PrintStream engine = new PrintStream(fos, true, "UTF-8");
        engine.println("LOG INFO the patcher starts");
        engine.println("STEP Applying patches to the app");
        check("the events file of a new run starts empty (the old answer is gone)", read(ev).equals("LOG INFO the patcher starts\nSTEP Applying patches to the app\n"), read(ev));
        MorpheServiceRules.append(ev, MorpheServiceRules.CANCELLED_LINES);
        engine.println("PATCH OK Hide Shorts shelf and then some more words to be longer than the answer");
        engine.println("LOG INFO the engine goes on until the process is killed");
        engine.close();
        String text = read(ev);
        check("the engine's lines written after Cancel do not overwrite the Cancel lines", text.contains(MorpheServiceRules.CANCELLED_LINES), text);
        check("... and no engine line is lost either", text.contains("PATCH OK Hide Shorts shelf and then some more words to be longer than the answer\n") && text.endsWith("LOG INFO the engine goes on until the process is killed\n"), text);
        MorpheEvents.Tail tail = new MorpheEvents.Tail(ev);
        tail.poll();
        check("the tail reads the Cancel as the result at once", tail.sawResult() && tail.result().optBoolean("cancelled") && !tail.result().optBoolean("success"), text);

        // the notification text from the engine's UTF-8 lines
        String shelf = "PATCH OK Hide \u2018Shorts\u2019 shelf";
        check("a patch name with curly quotes reaches the notification as it was written", note(shelf).equals("Applied: Hide \u2018Shorts\u2019 shelf"), note(shelf));
        String cjk = "PATCH FAIL \u5e7f\u544a\u5c4f\u853d \ud83d\ude80 \u00e9t\u00e9";
        check("... also Chinese, an emoji and accents", note(cjk).equals("Failed: \u5e7f\u544a\u5c4f\u853d \ud83d\ude80 \u00e9t\u00e9"), note(cjk));
        check("a STEP line is shown and a LOG line is not", note("STEP Reading the app").equals("Reading the app") && note("LOG INFO x\u2019y") == null, note("STEP Reading the app") + "|" + note("LOG INFO x\u2019y"));
        StringBuilder longName = new StringBuilder("PATCH OK ");
        for (int i = 0; i < 300; i++) longName.append('\u00e9');
        String cut = note(longName.toString());
        check("a very long line is cut without a broken character at the end", cut != null && cut.startsWith("Applied: \u00e9\u00e9") && cut.length() < 400 && cut.indexOf('\ufffd') < 0, cut);
        MorpheServiceRules.LineDecoder two = new MorpheServiceRules.LineDecoder();
        byte[] both = "STEP one\nSTEP two\n".getBytes("UTF-8");
        int lines = 0; String last = null;
        for (byte b : both) { String l = two.feed(b); if (l != null) { lines++; last = l; } }
        check("two lines come out as two lines", lines == 2 && "STEP two".equals(last), lines + " " + last);

        File src = null;
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null && src == null; d = d.getParentFile()) { File f = new File(d, "src/com/bloatware/bingblop/MorpheService.java"); if (f.isFile()) src = f; }
        check("the service source was found", src != null, null);
        if (src != null) {
            String sv = read(src);
            check("the service opens the engine's stream and writes the Cancel lines through the same rules", sv.contains("MorpheServiceRules.openEvents(events)") && sv.contains("MorpheServiceRules.append(ev, MorpheServiceRules.CANCELLED_LINES)") && sv.contains("MorpheServiceRules.LineDecoder") && sv.contains("MorpheServiceRules.noteText(l)") && !sv.contains("(char) (b & 0xff)") && !sv.contains("new FileOutputStream(events, false)"), null);
        }

        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
