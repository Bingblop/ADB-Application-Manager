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
        @Override public void cancel(File events) { cancels.incrementAndGet(); CountDownLatch h = hold; if (h != null) { stopped = true; h.countDown(); } }
        @Override public boolean alive() {
            if (alive) return true;
            if (aliveForPolls > 0) { aliveForPolls--; return true; }
            return false;
        }
    }

    /**
     * The patcher service as one process shared by every runner of the app (the real one is a single service in the :morphe process): it runs one
     * command at a time, answers a RUN that arrives meanwhile and a Cancel that is not for the running command with the same rules the real service
     * uses ({@link MorpheServiceRules}). {@code dropBusy} makes it behave like the old service, which ignored the second RUN without a word.
     */
    static class SharedService implements MorpheRunner.Launcher {
        volatile boolean running, dropBusy, cancelledRunning;
        volatile File runningEvents;
        volatile CountDownLatch hold = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger(), cancelsSeen = new AtomicInteger();

        @Override public boolean start(final String cmd, File job, final File events, String title) {
            if (running) {
                if (!dropBusy) MorpheServiceRules.answerBusy(events, runningEvents);
                return true;
            }
            running = true; runningEvents = events; runs.incrementAndGet();
            final CountDownLatch h = hold;
            new Thread(new Runnable() { @Override public void run() {
                try {
                    append(events, "LOG INFO started " + cmd + "\n");
                    h.await();
                    if (!cancelledRunning) append(events, "RESULT {\"success\":true,\"cmd\":\"" + cmd + "\"}\n");
                } catch (Exception ignored) {
                } finally { running = false; }
            } }, "shared-service").start();
            return true;
        }
        @Override public void cancel(File events) {
            cancelsSeen.incrementAndGet();
            if (!MorpheServiceRules.cancelApplies(running, runningEvents, events == null ? null : events.getAbsolutePath())) return;
            cancelledRunning = true;
            try { append(runningEvents, "RESULT {\"success\":false,\"cancelled\":true,\"error\":\"Cancelled\"}\n"); } catch (IOException ignored) {}
            hold.countDown();
        }
        @Override public boolean alive() { return running; }
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


        // D1 (1)+(2): a second window of the app has a runner of its own while the first one's job still goes in the service
        final SharedService svc1 = new SharedService();
        final MorpheRunner first1 = new MorpheRunner(svc1, FAST), second1 = new MorpheRunner(svc1, FAST);
        final JSONObject[] firstAns = new JSONObject[1];
        Thread t1 = new Thread(new Runnable() { @Override public void run() {
            try { firstAns[0] = first1.run("patch", new JSONObject(), new File(root, "n1"), "t", "old", null, 20000); } catch (Exception e) { firstAns[0] = new JSONObject(); }
        } });
        t1.start();
        long until1 = System.currentTimeMillis() + 3000;
        while (svc1.runs.get() < 1 && System.currentTimeMillis() < until1) Thread.sleep(5);
        long t0b = System.currentTimeMillis();
        JSONObject busy = second1.run("list", new JSONObject(), new File(root, "n2"), "t", null, null, 20000);
        long busyMs = System.currentTimeMillis() - t0b;
        check("a command that meets a running job is told so at once, with the service's words", !busy.optBoolean("success") && MorpheServiceRules.BUSY.equals(busy.optString("error")) && busy.optBoolean("busy") && busyMs < 3000, busy.toString() + " in " + busyMs + " ms");
        check("... and the running job goes on", svc1.running && !svc1.cancelledRunning && svc1.cancelsSeen.get() == 0 && firstAns[0] == null, "running=" + svc1.running + " cancels=" + svc1.cancelsSeen.get());
        check("... its events file is untouched (no line of the other command in it)", read(new File(root, "n1/events.log")).equals("LOG INFO started patch\n"), read(new File(root, "n1/events.log")));
        svc1.hold.countDown();
        t1.join(5000);
        check("the running job ends with its own answer", firstAns[0] != null && firstAns[0].optBoolean("success") && "patch".equals(firstAns[0].optString("cmd")), String.valueOf(firstAns[0]));

        // the old service dropped the second RUN: that caller waits until its own time-out, and its Stop must not reach the job that is going
        final SharedService old = new SharedService(); old.dropBusy = true;
        final MorpheRunner first2 = new MorpheRunner(old, FAST), second2 = new MorpheRunner(old, FAST);
        final JSONObject[] firstAns2 = new JSONObject[1];
        Thread t2 = new Thread(new Runnable() { @Override public void run() {
            try { firstAns2[0] = first2.run("patch", new JSONObject(), new File(root, "n3"), "t", "old2", null, 20000); } catch (Exception e) { firstAns2[0] = new JSONObject(); }
        } });
        t2.start();
        long until2 = System.currentTimeMillis() + 3000;
        while (old.runs.get() < 1 && System.currentTimeMillis() < until2) Thread.sleep(5);
        JSONObject slow2 = second2.run("list", new JSONObject(), new File(root, "n4"), "t", null, null, 300);
        check("a command whose RUN was dropped times out", !slow2.optBoolean("success") && slow2.optString("error").contains("too long"), slow2.toString());
        check("... its time-out stop names its own events file and leaves the running job alone", old.cancelsSeen.get() == 1 && !old.cancelledRunning && old.running && firstAns2[0] == null, "cancelsSeen=" + old.cancelsSeen.get() + " cancelledRunning=" + old.cancelledRunning);
        first2.cancel("old2");
        t2.join(5000);
        check("a Stop for the running job itself still stops it", firstAns2[0] != null && firstAns2[0].optBoolean("cancelled") && old.cancelledRunning, String.valueOf(firstAns2[0]));

        // the service's own rules
        File evA = new File(root, "ra/events.log"), evB = new File(root, "rb/events.log");
        check("a Cancel for the running command applies", MorpheServiceRules.cancelApplies(true, evA, evA.getAbsolutePath()), null);
        check("a Cancel for another command does not", !MorpheServiceRules.cancelApplies(true, evA, evB.getAbsolutePath()), null);
        check("a Cancel when nothing runs does not (the service then ends itself)", !MorpheServiceRules.cancelApplies(false, null, evB.getAbsolutePath()) && !MorpheServiceRules.cancelApplies(false, evA, evA.getAbsolutePath()), null);
        check("the notification's Cancel (no events file) always applies", MorpheServiceRules.cancelApplies(true, evA, null) && MorpheServiceRules.cancelApplies(true, evA, "") && MorpheServiceRules.cancelApplies(false, null, null), null);
        evB.getParentFile().mkdirs();
        check("the busy answer is written into the new run's file, as a RESULT line the tail reads", MorpheServiceRules.answerBusy(evB, evA), null);
        MorpheEvents.Tail bt = new MorpheEvents.Tail(evB); bt.poll();
        check("... which reads as a failed result with the busy words", bt.sawResult() && !bt.result().optBoolean("success") && MorpheServiceRules.BUSY.equals(bt.result().optString("error")), read(evB));
        evA.getParentFile().mkdirs();
        append(evA, "LOG INFO running\n");
        check("the busy answer is never written into the file of the running job", !MorpheServiceRules.answerBusy(evA, evA) && read(evA).equals("LOG INFO running\n"), read(evA));

        // MOR-3: Stop pressed while the engine writes its answer: the run returns the engine's success, and the Stop is still known to the caller (the bridge
        // checks it before Saving and before Installing, see the source check below)
        final MorpheRunner[] lateRunner = new MorpheRunner[1];
        lateRunner[0] = new MorpheRunner(new MorpheRunner.Launcher() {
            @Override public boolean start(String cmd, File job, File events, String title) {
                try { append(events, "RESULT {\"success\":true,\"applied\":[\"x\"]}\n"); } catch (IOException e) { return false; }
                lateRunner[0].markCancelled("jlate");                                       // the page's Stop, a moment after the engine's last word
                return true;
            }
            @Override public void cancel(File events) {}
            @Override public boolean alive() { return false; }
        }, FAST);
        JSONObject lateRes = lateRunner[0].run("patch", new JSONObject(), new File(root, "late"), "t", "jlate", null, 5000);
        check("a Stop that comes with the engine's answer: the run returns that answer, and the Stop is still marked for the bridge to see", lateRes.optBoolean("success") && lateRunner[0].isCancelled("jlate"), lateRes.toString());

        // the bridge uses all this
        File src = null;
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null && src == null; d = d.getParentFile()) { File f = new File(d, "src/com/bloatware/bingblop/MorpheBridge.java"); if (f.isFile()) src = f; }
        check("the bridge source was found", src != null, null);
        if (src != null) {
            String m = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
            int c = m.indexOf("private JSONObject catalog(");
            String cat = c < 0 ? "" : m.substring(c, Math.min(m.length(), c + 400));
            check("catalog reads run one at a time (the second finds the first one's answer in the cache)", cat.contains("synchronized (catalogLock)") && cat.contains("catalogLocked("), cat);
            // MOR-2 (full main review): every window has a bridge of its own on the same jobs/list folder, so the lock is the process's, not the bridge's
            check("the catalog lock is one for the process (two windows' reads do not delete each other's bundle copies and answer)", m.contains("private static final Object catalogLock = new Object();"), null);
            check("the bridge runs the engine through MorpheRunner and keeps no engine semaphore of its own", m.contains("runner.run(") && !m.contains("engine.acquire()"), null);
            File svcSrc = new File(src.getParentFile(), "MorpheService.java");
            String sv = new String(java.nio.file.Files.readAllBytes(svcSrc.toPath()), "UTF-8");
            int rp = sv.indexOf("if (running) {");
            check("the service answers a RUN that arrives while it is busy (it no longer drops it) and the runner's Stop names its events file", rp > 0 && sv.substring(rp, Math.min(sv.length(), rp + 400)).contains("MorpheServiceRules.answerBusy(") && sv.contains("MorpheServiceRules.cancelApplies(running, ev, intent.getStringExtra(\"events\"))") && sv.contains("i.putExtra(\"events\", events.getAbsolutePath())"), null);
            int cp = m.indexOf("case \"cancel\":");
            String cb = cp < 0 ? "" : m.substring(cp, Math.min(m.length(), cp + 400));
            // MOR-3: a Stop that comes in after the engine's RESULT (while it signs, during Saving) is not ignored
            int pp = m.indexOf("private void patch(JSONObject a");
            String pb = pp < 0 ? "" : m.substring(pp, Math.min(m.length(), pp + 7000));
            int eng = pb.indexOf("runEngine(\"patch\""), stop1 = pb.indexOf("runner.isCancelled(jobId)", eng), sav = pb.indexOf("step(jobId, \"Saving\", \"RUNNING\")");
            String stopBlock = stop1 < 0 ? "" : pb.substring(stop1, Math.min(pb.length(), stop1 + 500));
            check("after the engine's answer and before Saving the run checks for a Stop, and then deletes the unsaved APK and reports cancelled (MOR-3)",
                    eng > 0 && stop1 > eng && stop1 < sav && stopBlock.contains("output.delete()") && stopBlock.contains(".put(\"cancelled\", true)"), "engine=" + eng + " check=" + stop1 + " saving=" + sav);
            int inst = pb.indexOf("step(jobId, \"Installing\", \"RUNNING\")"), stop2 = pb.indexOf("runner.isCancelled(jobId)", stop1 + 1);
            check("...and again before Installing: a Stop during Saving installs nothing", stop2 > sav && stop2 < inst && pb.indexOf(".install(new File(saved.optString(\"file\"))") > inst, "check=" + stop2 + " installing=" + inst);
            check("the cancel call checks the job id and that it is the running job, then goes through runner.cancel", cb.contains("validJobId(job)") && cb.contains("slot.current()") && cb.contains("runner.cancel(job)") && !cb.contains("MorpheService.cancel"), cb);
        }
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
