package com.bloatware.bingblop;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** The one-patch-at-a-time slot of the Morphe tab: taking it is atomic, only its holder frees it, and the bridge uses it. */
public class MorpheSlotTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    public static void main(String[] args) throws Exception {
        MorpheSlot s = new MorpheSlot();
        check("a new slot is free", !s.busy() && s.current().isEmpty(), null);
        check("the first run takes it", s.tryStart("a") && s.busy() && s.current().equals("a"), null);
        check("a second run is refused while it is held, whatever its id", !s.tryStart("b") && !s.tryStart("a") && s.current().equals("a"), null);
        check("an empty or missing id never takes the slot", !new MorpheSlot().tryStart("") && !new MorpheSlot().tryStart(null), null);
        check("a run that does not hold the slot cannot free it", !s.finish("b") && !s.finish(null) && !s.finish("") && s.current().equals("a"), null);
        check("the holder frees it, and then another run can take it", s.finish("a") && !s.busy() && s.tryStart("b") && s.current().equals("b"), null);
        check("a late finish of an old run does not free the newer run", !s.finish("a") && s.current().equals("b"), null);
        check("finishing twice is harmless", s.finish("b") && !s.finish("b") && !s.busy(), null);

        // many callers at the same moment: exactly one is accepted, in every round (the old read-then-write let two in about 3 rounds in 100)
        int rounds = 3000, threads = 8, doubles = 0, none = 0;
        for (int r = 0; r < rounds; r++) {
            final MorpheSlot slot = new MorpheSlot();
            final CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(threads);
            final AtomicInteger won = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                final String id = "j" + t;
                new Thread(new Runnable() { @Override public void run() {
                    try { go.await(); } catch (InterruptedException e) { return; }
                    if (slot.tryStart(id)) won.incrementAndGet();
                    done.countDown();
                } }).start();
            }
            go.countDown();
            done.await();
            if (won.get() > 1) doubles++;
            if (won.get() == 0) none++;
        }
        check("8 callers at once: exactly one wins, in " + rounds + " rounds", doubles == 0 && none == 0, "two winners in " + doubles + " rounds, none in " + none);

        File src = null;
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null && src == null; d = d.getParentFile()) { File f = new File(d, "src/com/bloatware/bingblop/MorpheBridge.java"); if (f.isFile()) src = f; }
        check("the bridge source was found", src != null, null);
        if (src != null) {
            String m = new String(java.nio.file.Files.readAllBytes(src.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            int sp = m.indexOf("private JSONObject startPatch("), pa = m.indexOf("private void ev(");
            String body = sp < 0 || pa < sp ? "" : m.substring(sp, pa);
            check("startPatch takes the slot with tryStart and gives it back with finish", body.contains("slot.tryStart(jobId)") && body.contains("slot.finish(jobId)"), null);
            check("startPatch no longer reads a name and then writes it (runningJob)", !m.contains("runningJob"), null);
            check("startPatch frees the slot when the pool refuses the run", body.contains("catch (RuntimeException") && body.indexOf("slot.finish(jobId);\n            throw") > 0, null);
        }
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
