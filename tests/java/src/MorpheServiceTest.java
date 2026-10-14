package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * The patcher service's events file: the engine writes its protocol lines into it through a stream of its own while Cancel appends its lines through
 * another descriptor, so neither may overwrite the other.
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

        File src = null;
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null && src == null; d = d.getParentFile()) { File f = new File(d, "src/com/bloatware/bingblop/MorpheService.java"); if (f.isFile()) src = f; }
        check("the service source was found", src != null, null);
        if (src != null) {
            String sv = read(src);
            check("the service opens the engine's stream and writes the Cancel lines through the same rules", sv.contains("MorpheServiceRules.openEvents(events)") && sv.contains("MorpheServiceRules.append(ev, MorpheServiceRules.CANCELLED_LINES)") && !sv.contains("new FileOutputStream(events, false)"), null);
        }

        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
