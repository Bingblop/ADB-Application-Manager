package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How the app runs the Morphe engine: one run at a time, the run's job file and events file belong to it alone (a second caller, for example
 * a second catalog read, waits before it touches them), and the run ends with the engine's answer, a service that died, a time-out or Stop.
 * The service is a fake that writes the engine's protocol lines.
 */
public class MorpheRunnerTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static String read(File f) throws IOException {
        if (!f.isFile()) return null;
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    static void append(File f, String s) throws IOException {
        FileOutputStream o = new FileOutputStream(f, true);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
    }

    /** A service that follows a script: it writes lines to the events file the run gave it and finishes when the test lets it. */
    static class FakeService implements MorpheRunner.Launcher {
        final AtomicInteger starts = new AtomicInteger(), cancels = new AtomicInteger();
        volatile boolean alive;
        volatile boolean refuse;
        volatile boolean silent;                                 // dies without an answer
        volatile CountDownLatch hold;                            // the "engine" works until this opens
        volatile Runnable onStart;                               // runs inside start(): something that happens while the service is being started
        volatile boolean stopped;                                // cancel() ended the engine
        volatile int aliveForPolls;                              // alive() is true this many times after a finish (a service that is still going down)
        final List<String> jobsSeen = Collections.synchronizedList(new ArrayList<String>());

        @Override public boolean start(final String cmd, final File job, final File events, String title) {
            if (refuse) return false;
            starts.incrementAndGet();
            Runnable hook = onStart;
            if (hook != null) hook.run();
            try { jobsSeen.add(read(job)); } catch (IOException e) { jobsSeen.add("?"); }
            alive = true;
            final CountDownLatch h = hold;
            new Thread(new Runnable() { @Override public void run() {
                try {
                    append(events, "LOG INFO started " + cmd + "\n");
                    if (h != null) h.await();
                    if (stopped) append(events, "RESULT {\"success\":false,\"cancelled\":true,\"error\":\"Cancelled\"}\n");
                    else if (!silent) append(events, "RESULT {\"success\":true,\"cmd\":\"" + cmd + "\"}\n");
                } catch (Exception ignored) {
                } finally { alive = false; }
            } }, "fake-service").start();
            return true;
        }
        @Override public void cancel() { cancels.incrementAndGet(); CountDownLatch h = hold; if (h != null) { stopped = true; h.countDown(); } }
        @Override public boolean alive() {
            if (alive) return true;
            if (aliveForPolls > 0) { aliveForPolls--; return true; }
            return false;
        }
    }

    static final MorpheRunner.Timing FAST = new MorpheRunner.Timing(10, 10, 30, 150, 60, 80);

    static File dir() throws Exception {
        File d = File.createTempFile("mrun", "dir"); d.delete(); d.mkdirs(); return d;
    }

    public static void main(String[] args) throws Exception {
        File root = dir();
        FakeService svc = new FakeService();
        MorpheRunner runner = new MorpheRunner(svc, FAST);
        final List<JSONObject> got = Collections.synchronizedList(new ArrayList<JSONObject>());
        MorpheRunner.EventSink sink = new MorpheRunner.EventSink() { @Override public void accept(JSONObject e) { got.add(e); } };

        JSONObject r = runner.run("list", new JSONObject().put("n", 1), new File(root, "a"), "t", null, sink, 5000);
        check("a run returns the engine's answer", r.optBoolean("success") && "list".equals(r.optString("cmd")), r.toString());
        check("the events (not the result) go to the sink", got.size() == 1 && "log".equals(got.get(0).optString("t")) && got.get(0).optString("text").contains("started list"), got.toString());
        check("the job file holds the job", read(new File(root, "a/job.json")).contains("\"n\":1"), null);

        // M-2: a second caller waits for the engine before it touches the job file or the events file
        final FakeService s2 = new FakeService();
        s2.hold = new CountDownLatch(1);
        final MorpheRunner r2 = new MorpheRunner(s2, FAST);
        final File shared = new File(root, "shared");
        final JSONObject[] ans = new JSONObject[2];
        Thread first = new Thread(new Runnable() { @Override public void run() {
            try { ans[0] = r2.run("first", new JSONObject().put("who", "first"), shared, "t", null, null, 10000); } catch (Exception e) { ans[0] = new JSONObject(); }
        } });
        first.start();
        long until = System.currentTimeMillis() + 3000;
        while (s2.starts.get() < 1 && System.currentTimeMillis() < until) Thread.sleep(5);
        Thread second = new Thread(new Runnable() { @Override public void run() {
            try { ans[1] = r2.run("second", new JSONObject().put("who", "second"), shared, "t", null, null, 10000); } catch (Exception e) { ans[1] = new JSONObject(); }
        } });
        second.start();
        Thread.sleep(400);
        check("while a run is in progress the second caller has not started", s2.starts.get() == 1, "starts=" + s2.starts.get());
        check("its job file is still the first run's", read(new File(shared, "job.json")).contains("\"first\""), read(new File(shared, "job.json")));
        check("its events file is still there with the first run's lines", read(new File(shared, "events.log")) != null && read(new File(shared, "events.log")).contains("started first"), read(new File(shared, "events.log")));
        s2.hold.countDown();
        first.join(5000);
        s2.hold = null;
        second.join(5000);
        check("the first run ends with its own answer", ans[0] != null && "first".equals(ans[0].optString("cmd")), String.valueOf(ans[0]));
        check("then the second runs, with its own job file and answer", ans[1] != null && "second".equals(ans[1].optString("cmd")) && s2.starts.get() == 2 && s2.jobsSeen.get(1).contains("\"second\""), String.valueOf(ans[1]) + " " + s2.jobsSeen);

        // the service would not start: said in words, and the engine is free again
        FakeService s3 = new FakeService(); s3.refuse = true;
        MorpheRunner r3 = new MorpheRunner(s3, FAST);
        String msg = "";
        try { r3.run("x", new JSONObject(), new File(root, "c"), "t", null, null, 1000); } catch (IOException e) { msg = e.getMessage(); }
        check("a service that will not start is said in words", msg.contains("would not start"), msg);
        s3.refuse = false;
        JSONObject again = r3.run("x", new JSONObject(), new File(root, "c"), "t", null, null, 2000);
        check("and the next run is not stuck behind it", again.optBoolean("success"), again.toString());

        // the service died with no answer
        FakeService s4 = new FakeService(); s4.silent = true;
        JSONObject died = new MorpheRunner(s4, FAST).run("x", new JSONObject(), new File(root, "d"), "t", null, null, 5000);
        check("a service that ends without an answer gives the 'ended without an answer' words", !died.optBoolean("success") && died.optString("error").contains("ended without an answer"), died.toString());

        // time-out
        FakeService s5 = new FakeService(); s5.hold = new CountDownLatch(1);
        JSONObject late = new MorpheRunner(s5, FAST).run("x", new JSONObject(), new File(root, "e"), "t", null, null, 200);
        s5.hold.countDown();
        check("a run that takes too long is stopped and says so", !late.optBoolean("success") && late.optString("error").contains("too long") && s5.cancels.get() == 1, late.toString() + " cancels=" + s5.cancels.get());

        // Stop
        FakeService s6 = new FakeService(); s6.hold = new CountDownLatch(1);
        final MorpheRunner r6 = new MorpheRunner(s6, FAST);
        r6.markCancelled("job1");
        JSONObject stopped = r6.run("x", new JSONObject(), new File(root, "f"), "t", "job1", null, 5000);
        s6.hold.countDown();
        check("Stop ends the run with 'Cancelled'", !stopped.optBoolean("success") && stopped.optBoolean("cancelled"), stopped.toString());
        check("a Stop for another job does not end this one", !new MorpheRunner(new FakeService(), FAST).run("x", new JSONObject(), new File(root, "g"), "t", "job2", null, 2000).optBoolean("cancelled"), null);

        // M-4: Stop before the service starts is not lost, a Stop reaches the service, and a Stop for another job changes nothing
        FakeService s8 = new FakeService();
        final MorpheRunner r8 = new MorpheRunner(s8, FAST);
        check("cancel() marks the job and reports it", r8.cancel("early") && r8.isCancelled("early") && !r8.cancel("") && !r8.cancel(null), null);
        JSONObject early = r8.run("x", new JSONObject(), new File(root, "i"), "t", "early", null, 3000);
        check("Stop pressed before the engine started: the engine is never started", early.optBoolean("cancelled") && s8.starts.get() == 0 && s8.cancels.get() == 0, early.toString() + " starts=" + s8.starts.get());

        FakeService s9 = new FakeService(); s9.hold = new CountDownLatch(1);
        final MorpheRunner r9 = new MorpheRunner(s9, FAST);
        s9.onStart = new Runnable() { @Override public void run() { r9.cancel("gap"); } };      // Stop arrives while the service is being started
        JSONObject gap = r9.run("x", new JSONObject(), new File(root, "j"), "t", "gap", null, 5000);
        check("Stop that arrives while the service starts reaches the service once", gap.optBoolean("cancelled") && s9.cancels.get() == 1 && s9.stopped, gap.toString() + " cancels=" + s9.cancels.get());

        final FakeService s10 = new FakeService(); s10.hold = new CountDownLatch(1);
        final MorpheRunner r10 = new MorpheRunner(s10, FAST);
        final JSONObject[] run10 = new JSONObject[1];
        Thread t10 = new Thread(new Runnable() { @Override public void run() {
            try { run10[0] = r10.run("x", new JSONObject(), new File(root, "k"), "t", "live", null, 10000); } catch (Exception e) { run10[0] = new JSONObject(); }
        } });
        t10.start();
        long until10 = System.currentTimeMillis() + 3000;
        while (s10.starts.get() < 1 && System.currentTimeMillis() < until10) Thread.sleep(5);
        Thread.sleep(50);
        r10.cancel("other");
        Thread.sleep(150);
        check("a Stop for another job changes nothing for the running one", s10.cancels.get() == 0 && run10[0] == null, "cancels=" + s10.cancels.get());
        r10.cancel("live");
        r10.cancel("live");
        t10.join(5000);
        check("Stop during the run tells the service (once, however often it is pressed) and ends with 'Cancelled'", run10[0] != null && run10[0].optBoolean("cancelled") && s10.cancels.get() == 1 && s10.stopped, String.valueOf(run10[0]) + " cancels=" + s10.cancels.get());
        s10.hold.countDown();

        // the next run of the same job id is not affected by the earlier Stop being remembered as sent
        FakeService s11 = new FakeService();
        MorpheRunner r11 = new MorpheRunner(s11, FAST);
        r11.cancel("again"); r11.clearCancelled("again");
        JSONObject fresh = r11.run("x", new JSONObject(), new File(root, "l"), "t", "again", null, 3000);
        check("after clearCancelled the same job id runs normally", fresh.optBoolean("success") && s11.starts.get() == 1, fresh.toString());

        // a service still going down delays the start of the next run
        FakeService s7 = new FakeService(); s7.aliveForPolls = 5;
        long t0 = System.currentTimeMillis();
        JSONObject slow = new MorpheRunner(s7, FAST).run("x", new JSONObject(), new File(root, "h"), "t", null, null, 5000);
        check("a run waits for the service of the earlier one to go away", slow.optBoolean("success") && System.currentTimeMillis() - t0 >= 40, null);

        // the bridge uses all this
        File src = null;
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null && src == null; d = d.getParentFile()) { File f = new File(d, "src/com/bloatware/bingblop/MorpheBridge.java"); if (f.isFile()) src = f; }
        check("the bridge source was found", src != null, null);
        if (src != null) {
            String m = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
            int c = m.indexOf("private JSONObject catalog(");
            String cat = c < 0 ? "" : m.substring(c, Math.min(m.length(), c + 400));
            check("catalog reads run one at a time (the second finds the first one's answer in the cache)", cat.contains("synchronized (catalogLock)") && cat.contains("catalogLocked("), cat);
            check("the bridge runs the engine through MorpheRunner and keeps no engine semaphore of its own", m.contains("runner.run(") && !m.contains("engine.acquire()"), null);
            int cp = m.indexOf("case \"cancel\":");
            String cb = cp < 0 ? "" : m.substring(cp, Math.min(m.length(), cp + 400));
            check("the cancel call checks the job id and that it is the running job, then goes through runner.cancel", cb.contains("validJobId(job)") && cb.contains("slot.current()") && cb.contains("runner.cancel(job)") && !cb.contains("MorpheService.cancel"), cb);
        }
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
