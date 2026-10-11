package com.bloatware.bingblop;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** What the Helper downloads' notification says: counts, percent, speed, time left, the title, and how it all ended. */
public class DownloadNoticeTest {
    static int n = 0, fails = 0;
    static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
    static void is(String what, boolean ok, String d) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + d); } }

    static BrowserDownload.Job job(String id, String name, BrowserDownload.State st, long done, long total) {
        BrowserDownload.Job j = new BrowserDownload.Job(id, "https://x/" + id, "", "", new File("/tmp"));
        j.name = name; j.state = st; j.done = done; j.total = total;
        return j;
    }

    public static void main(String[] args) {
        // bytes and time
        is("bytes: B, KB, MB, GB", DownloadNotice.bytes(900).equals("900 B") && DownloadNotice.bytes(2048).equals("2 KB") && DownloadNotice.bytes(5 * 1048576L).equals("5.0 MB") && DownloadNotice.bytes(3L * 1073741824L).equals("3.00 GB"));
        is("time: seconds, minutes, hours", DownloadNotice.eta(45).equals("45 s") && DownloadNotice.eta(125).equals("2 min 5 s") && DownloadNotice.eta(7500).equals("2 h 5 min"));

        // one running download: title with its name, percent, sizes
        DownloadNotice dn = new DownloadNotice();
        BrowserDownload.Job a = job("a", "keyboard.apkm", BrowserDownload.State.RUNNING, 0, 40L * 1048576);
        List<BrowserDownload.Job> l = new ArrayList<BrowserDownload.Job>(); l.add(a);
        DownloadNotice.Snapshot s = dn.update(l, 1000);
        is("one running: active, title names the file, 0%", s.active() && s.running == 1 && s.queued == 0 && s.title.equals("Downloading keyboard.apkm") && s.percent == 0, s.title + " " + s.percent);
        a.done = 10L * 1048576;
        s = dn.update(l, 2000);
        is("one running: 25%, the text shows 10.0 MB of 40.0 MB", s.percent == 25 && s.text.startsWith("25% · 10.0 MB of 40.0 MB"), s.text);
        is("speed: about 10 MB/s from what arrived in a second, and a time left", s.speed > 9L * 1048576 && s.speed < 11L * 1048576 && s.etaSec >= 2 && s.etaSec <= 4 && s.text.contains("MB/s") && s.text.contains("left"), s.speed + " " + s.etaSec + " " + s.text);
        a.done = 20L * 1048576;
        s = dn.update(l, 2400);
        is("speed: a look less than a second after the last one keeps the speed it had", s.speed > 9L * 1048576 && s.speed < 11L * 1048576, String.valueOf(s.speed));
        a.done = 30L * 1048576;
        s = dn.update(l, 3000);
        is("speed: the next second brought 20 MB, so it rises, but only part of the way (smoothed: 0.6 of the old plus 0.4 of the new, 14 MB/s)", s.speed > 13L * 1048576 && s.speed < 15L * 1048576 && s.percent == 75, s.speed + " " + s.percent);

        // an unknown size: no percent, the bytes so far
        DownloadNotice dn2 = new DownloadNotice();
        List<BrowserDownload.Job> l2 = new ArrayList<BrowserDownload.Job>();
        l2.add(job("u", "x.apk", BrowserDownload.State.RUNNING, 3L * 1048576, -1));
        s = dn2.update(l2, 1000);
        is("unknown size: no percent (the bar is the moving kind), the text has the bytes so far and no time left", s.percent == -1 && s.etaSec == -1 && s.text.startsWith("3.0 MB") && !s.text.contains("%"), s.text + " " + s.percent);

        // several: count in the title, waiting ones counted, a mix of known and unknown sizes gives no percent
        DownloadNotice dn3 = new DownloadNotice();
        List<BrowserDownload.Job> l3 = new ArrayList<BrowserDownload.Job>();
        l3.add(job("1", "a.apkm", BrowserDownload.State.RUNNING, 1L * 1048576, 10L * 1048576));
        l3.add(job("2", "b.apkm", BrowserDownload.State.RUNNING, 2L * 1048576, 10L * 1048576));
        l3.add(job("3", "c.apkm", BrowserDownload.State.QUEUED, 0, -1));
        s = dn3.update(l3, 1000);
        is("three (two running, one waiting): 'Downloading 3 files', 15%, '1 waiting'", s.title.equals("Downloading 3 files") && s.running == 2 && s.queued == 1 && s.percent == 15 && s.text.endsWith("1 waiting"), s.title + " | " + s.text);
        l3.get(1).total = -1;
        s = dn3.update(l3, 2000);
        is("one running size unknown: no percent for the lot", s.percent == -1 && s.running == 2, String.valueOf(s.percent));
        DownloadNotice dn4 = new DownloadNotice();
        List<BrowserDownload.Job> l4 = new ArrayList<BrowserDownload.Job>();
        l4.add(job("w", "w.apkm", BrowserDownload.State.QUEUED, 0, -1));
        s = dn4.update(l4, 1000);
        is("only waiting: still active, says it waits for a free place", s.active() && s.running == 0 && s.text.equals("Waiting for a free place") && s.title.equals("Downloading w.apkm"), s.title + " | " + s.text);

        // paused and finished ones are not active
        DownloadNotice dn5 = new DownloadNotice();
        List<BrowserDownload.Job> l5 = new ArrayList<BrowserDownload.Job>();
        l5.add(job("p", "p.apkm", BrowserDownload.State.PAUSED, 5, 10));
        l5.add(job("d", "d.apkm", BrowserDownload.State.DONE, 10, 10));
        s = dn5.update(l5, 1000);
        is("paused and done only: not active, and nothing to say about how it went (they were not seen going)", !s.active() && dn5.summary() == null);

        // how it all ended
        DownloadNotice e = new DownloadNotice();
        List<BrowserDownload.Job> le = new ArrayList<BrowserDownload.Job>();
        BrowserDownload.Job x = job("x", "keyboard.apkm", BrowserDownload.State.RUNNING, 1, 10);
        le.add(x);
        e.update(le, 1000);
        x.state = BrowserDownload.State.DONE; x.done = 10;
        s = e.update(le, 2000);
        is("one finished: not active, 'keyboard.apkm is saved'", !s.active() && "keyboard.apkm is saved".equals(e.summary()) && !e.hadFailure(), String.valueOf(e.summary()));
        e.update(le, 3000);
        is("a finished job is counted once, however often the list is read", "keyboard.apkm is saved".equals(e.summary()));
        e.reset();
        is("reset: forgets", e.summary() == null && !e.hadFailure());

        DownloadNotice f = new DownloadNotice();
        List<BrowserDownload.Job> lf = new ArrayList<BrowserDownload.Job>();
        BrowserDownload.Job y1 = job("y1", "a.apkm", BrowserDownload.State.RUNNING, 1, 10), y2 = job("y2", "b.apkm", BrowserDownload.State.RUNNING, 1, 10), y3 = job("y3", "c.apkm", BrowserDownload.State.QUEUED, 0, -1);
        lf.add(y1); lf.add(y2); lf.add(y3);
        f.update(lf, 1000);
        y1.state = BrowserDownload.State.DONE; y2.state = BrowserDownload.State.FAILED; y3.state = BrowserDownload.State.DONE;
        f.update(lf, 2000);
        is("two finished, one failed: counted, with where to retry, and flagged as a failure", "2 finished, 1 stopped. Open the app to retry".equals(f.summary()) && f.hadFailure(), String.valueOf(f.summary()));
        DownloadNotice g = new DownloadNotice();
        List<BrowserDownload.Job> lg = new ArrayList<BrowserDownload.Job>();
        BrowserDownload.Job z = job("z", "z.apkm", BrowserDownload.State.RUNNING, 1, 10);
        lg.add(z); g.update(lg, 1000);
        z.state = BrowserDownload.State.FAILED; g.update(lg, 2000);
        is("one failed: says to open the app and retry", "A download stopped. Open the app to retry it".equals(g.summary()) && g.hadFailure(), String.valueOf(g.summary()));
        DownloadNotice h = new DownloadNotice();
        List<BrowserDownload.Job> lh = new ArrayList<BrowserDownload.Job>();
        BrowserDownload.Job h1 = job("h1", "", BrowserDownload.State.RUNNING, 1, 10), h2 = job("h2", "", BrowserDownload.State.RUNNING, 1, 10);
        lh.add(h1); lh.add(h2); h.update(lh, 1000);
        h1.state = BrowserDownload.State.DONE; h2.state = BrowserDownload.State.DONE; h.update(lh, 2000);
        is("two finished: '2 downloads finished'", "2 downloads finished".equals(h.summary()), String.valueOf(h.summary()));
        DownloadNotice c = new DownloadNotice();
        List<BrowserDownload.Job> lc = new ArrayList<BrowserDownload.Job>();
        BrowserDownload.Job c1 = job("c1", "c.apkm", BrowserDownload.State.RUNNING, 1, 10);
        lc.add(c1); c.update(lc, 1000);
        c1.state = BrowserDownload.State.CANCELLED; c.update(lc, 2000);
        is("cancelled or paused by the person is not a result to announce", c.summary() == null);
        DownloadNotice p = new DownloadNotice();
        List<BrowserDownload.Job> lp = new ArrayList<BrowserDownload.Job>();
        BrowserDownload.Job p1 = job("p1", "p.apkm", BrowserDownload.State.RUNNING, 1, 10);
        lp.add(p1); p.update(lp, 1000);
        p1.state = BrowserDownload.State.PAUSED; s = p.update(lp, 2000);
        is("pausing everything makes it inactive with nothing to announce", !s.active() && p.summary() == null);

        // speed: no running job, no speed
        is("no running job: speed 0", s.speed == 0 && s.running == 0);
        System.out.println(fails == 0 ? "ALL PASS (" + n + " checks)" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
