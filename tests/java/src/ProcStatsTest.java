package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ProcStatsTest {
    static int n = 0, fails = 0;
    static void eq(String what, Object got, Object want) { n++; if (!Objects.equals(want, got)) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }
    static void is(String what, boolean got) { n++; if (!got) { fails++; System.out.println("FAIL " + what); } }

    static String lines(String... ls) { return String.join("\n", ls); }

    static ProcStats.Proc proc(int pid, int ppid, String user, long rss, double cpu, String name) {
        ProcStats.Proc p = new ProcStats.Proc();
        p.pid = pid; p.ppid = ppid; p.user = user; p.rssKb = rss; p.cpuPercent = cpu; p.name = name;
        return p;
    }

    static ProcStats.Proc byPid(List<ProcStats.Proc> procs, int pid) {
        for (ProcStats.Proc p : procs) if (p.pid == pid) return p;
        return null;
    }

    static String pids(List<ProcStats.Proc> procs) {
        StringBuilder sb = new StringBuilder();
        for (ProcStats.Proc p : procs) sb.append(p.pid).append(',');
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {

        // ================= (a) full toybox header+rows, varying spacing, app / secondary / native rows mixed =================
        // the header line exactly as toybox prints it (PID and PPID right-aligned, USER left-aligned, RSS and %CPU right-aligned)
        String toybox = lines(
            "PID PPID USER        RSS %CPU NAME",
            "    1    0 root       2104  0.0 /init",
            "    2    0 root          0  0.0 [kthreadd]",
            "   47    2 root          0  0.0 kworker/0:1",
            "  615    1 system     48212  0.4 system_server",
            "  616    1 system      9400  0.1 /system/bin/surfaceflinger",
            " 1522  615 u0_a55     98344  1.2 com.whatsapp",
            " 1560 1522 u0_a55     21216  0.1 com.whatsapp:sandboxed_process0",
            " 1733  615 u0_a12    152300  6.8 com.android.chrome",
            " 1799 1733 u0_a12     60112  0.9 com.android.chrome:sandboxed_process0",
            " 2051  615 u0_a3       8800  0.0 com.spotify.music"
        );
        ProcStats.Result ra = ProcStats.parse(toybox);
        is("toybox: full format detected", ra.fullFormat);
        eq("toybox: row count", ra.procs.size(), 10);
        ProcStats.Proc init = byPid(ra.procs, 1);
        eq("toybox: /init ppid", init.ppid, 0); eq("toybox: /init user", init.user, "root"); eq("toybox: /init rss", init.rssKb, 2104L); eq("toybox: /init cpu", init.cpuPercent, 0.0); eq("toybox: /init name", init.name, "/init");
        eq("toybox: [kthreadd] parsed as a row", byPid(ra.procs, 2).name, "[kthreadd]");
        eq("toybox: kworker/0:1 parsed as a row", byPid(ra.procs, 47).name, "kworker/0:1");
        ProcStats.Proc ss = byPid(ra.procs, 615);
        eq("toybox: system_server user", ss.user, "system"); eq("toybox: system_server rss", ss.rssKb, 48212L); eq("toybox: system_server cpu", ss.cpuPercent, 0.4);
        eq("toybox: /system/bin/surfaceflinger name kept whole", byPid(ra.procs, 616).name, "/system/bin/surfaceflinger");
        ProcStats.Proc wa = byPid(ra.procs, 1522);
        eq("toybox: whatsapp ppid", wa.ppid, 615); eq("toybox: whatsapp user", wa.user, "u0_a55"); eq("toybox: whatsapp rss", wa.rssKb, 98344L); eq("toybox: whatsapp cpu", wa.cpuPercent, 1.2); eq("toybox: whatsapp name", wa.name, "com.whatsapp");
        ProcStats.Proc waSandbox = byPid(ra.procs, 1560);
        eq("toybox: whatsapp sandbox ppid is whatsapp's pid", waSandbox.ppid, 1522); eq("toybox: whatsapp sandbox name kept whole (with colon)", waSandbox.name, "com.whatsapp:sandboxed_process0");
        ProcStats.Proc chrome = byPid(ra.procs, 1733);
        eq("toybox: chrome rss", chrome.rssKb, 152300L); eq("toybox: chrome cpu", chrome.cpuPercent, 6.8);
        eq("toybox: chrome sandbox name", byPid(ra.procs, 1799).name, "com.android.chrome:sandboxed_process0");
        eq("toybox: spotify name", byPid(ra.procs, 2051).name, "com.spotify.music");
        // packageOf against the names this sample actually produced
        eq("toybox: /init is not a package", ProcStats.packageOf(init.name), null);
        eq("toybox: [kthreadd] is not a package", ProcStats.packageOf(byPid(ra.procs, 2).name), null);
        eq("toybox: kworker/0:1 is not a package", ProcStats.packageOf(byPid(ra.procs, 47).name), null);
        eq("toybox: system_server is not a package", ProcStats.packageOf(ss.name), null);
        eq("toybox: /system/bin/surfaceflinger is not a package", ProcStats.packageOf(byPid(ra.procs, 616).name), null);
        eq("toybox: whatsapp package", ProcStats.packageOf(wa.name), "com.whatsapp");
        eq("toybox: whatsapp sandbox resolves to the base package, not the full string", ProcStats.packageOf(waSandbox.name), "com.whatsapp");
        eq("toybox: chrome sandbox resolves to the base package", ProcStats.packageOf(byPid(ra.procs, 1799).name), "com.android.chrome");

        // ================= (b) degraded/old busybox-style `ps -A` (no -o): different header, different column order, no %CPU =================
        String busybox = lines(
            "USER     PID  PPID VSZ      RSS WCHAN           ADDR     S NAME",
            "root       1     0    3456    728 ffffffff       00000000 S /init",
            "root      44     2       0      0 kworker        00000000 S kworker/u8:3",
            "system   615     1   896000  41200 ffffffff      00000000 S system_server",
            "u0_a21  1121   615  1452000  77400 SyS_epoll_wait 00000000 S com.android.vending",
            "u0_a21  1180  1121   980000  30212 SyS_epoll_wait 00000000 S com.android.vending:remote"
        );
        ProcStats.Result rb = ProcStats.parse(busybox);
        is("busybox: NOT full format (no %CPU column at all)", !rb.fullFormat);
        eq("busybox: row count", rb.procs.size(), 5);
        ProcStats.Proc bInit = byPid(rb.procs, 1);
        eq("busybox: USER column found despite the odd order", bInit.user, "root");
        eq("busybox: RSS column found despite the odd order", bInit.rssKb, 728L);
        eq("busybox: name is still the last column", bInit.name, "/init");
        eq("busybox: cpu stays at the default (no such column)", bInit.cpuPercent, 0.0);
        ProcStats.Proc vending = byPid(rb.procs, 1121);
        eq("busybox: vending ppid", vending.ppid, 615); eq("busybox: vending rss", vending.rssKb, 77400L);
        eq("busybox: vending:remote resolves to the base package", ProcStats.packageOf(byPid(rb.procs, 1180).name), "com.android.vending");
        eq("busybox: kworker/u8:3 is not a package", ProcStats.packageOf(byPid(rb.procs, 44).name), null);

        // ================= (c) truncated / corrupted: a data line cut short mid-row =================
        String truncated = lines(
            "PID PPID USER        RSS %CPU NAME",
            "    1    0 root       2104  0.0 /init",
            " 1522  615 u0_a55",                                           // cut short: only 3 of the 6 fields made it
            "  615    1 system     48212  0.4 system_server"
        );
        ProcStats.Result rc = ProcStats.parse(truncated);
        is("truncated: the header is still fully recognized", rc.fullFormat);
        eq("truncated: the short row is skipped, not fatal to the rest", rc.procs.size(), 2);
        eq("truncated: the rows around it still come through, in order", pids(rc.procs), "1,615,");
        eq("truncated: the last good row is still fully parsed", byPid(rc.procs, 615).rssKb, 48212L);

        // ================= (d) blank / empty input =================
        for (String blank : new String[] {"", "   \n\t\n   \n", "\n\n\n"}) {
            ProcStats.Result rd = ProcStats.parse(blank);
            is("blank input [" + blank.replace("\n", "\\n") + "]: no rows", rd.procs.isEmpty());
            is("blank input [" + blank.replace("\n", "\\n") + "]: not full format", !rd.fullFormat);
        }
        ProcStats.Result rNull = ProcStats.parse(null);
        is("null input: no rows, no exception", rNull.procs.isEmpty() && !rNull.fullFormat);

        // ================= (e) header-only: a valid, empty device snapshot =================
        ProcStats.Result re1 = ProcStats.parse("PID PPID USER        RSS %CPU NAME");
        is("header-only (no trailing newline): no rows", re1.procs.isEmpty());
        is("header-only: still a recognized full format, just an empty snapshot", re1.fullFormat);
        ProcStats.Result re2 = ProcStats.parse("PID PPID USER        RSS %CPU NAME\n");
        is("header-only (with trailing newline): no rows either", re2.procs.isEmpty());
        is("header-only (with trailing newline): still full format", re2.fullFormat);

        // ================= bonus: a header this class does not recognize at all falls back to guessing =================
        String weird = lines(
            "COL1 COL2 COL3",
            "widget 500 50 proc-a",
            "widget 501 500 proc-b",
            "nothing numeric here at all"                                  // no usable PID: skipped, never throws
        );
        ProcStats.Result rw = ProcStats.parse(weird);
        is("unrecognized header: last-resort, not full format", !rw.fullFormat);
        eq("unrecognized header: the two usable rows, the unusable one skipped", rw.procs.size(), 2);
        ProcStats.Proc gA = byPid(rw.procs, 500), gB = byPid(rw.procs, 501);
        eq("unrecognized header: left-most numeric token is pid", gA.ppid, 50); eq("unrecognized header: name is the right-most token", gA.name, "proc-a");
        eq("unrecognized header: second row pid", gB.pid, 501); eq("unrecognized header: second row ppid", gB.ppid, 500); eq("unrecognized header: second row name", gB.name, "proc-b");
        is("unrecognized header: user/rss/cpu stay at their defaults", gA.user.isEmpty() && gA.rssKb == 0 && gA.cpuPercent == 0.0);

        // ================= packageOf =================
        eq("packageOf: plain package", ProcStats.packageOf("com.whatsapp"), "com.whatsapp");
        eq("packageOf: secondary process resolves to the base package, not the full string", ProcStats.packageOf("com.whatsapp:sandboxed_process0"), "com.whatsapp");
        eq("packageOf: another app's secondary process", ProcStats.packageOf("com.android.chrome:sandboxed_process0"), "com.android.chrome");
        eq("packageOf: a package with several segments and no colon", ProcStats.packageOf("com.google.android.gms.persistent"), "com.google.android.gms.persistent");
        eq("packageOf: kworker/0:1 has a colon but is NOT a package (the slash rules it out)", ProcStats.packageOf("kworker/0:1"), null);
        eq("packageOf: another kworker thread", ProcStats.packageOf("kworker/u8:3"), null);
        eq("packageOf: bracketed kernel thread name", ProcStats.packageOf("[kthreadd]"), null);
        eq("packageOf: an absolute path is not a package", ProcStats.packageOf("/system/bin/surfaceflinger"), null);
        eq("packageOf: a bare daemon name with no dot", ProcStats.packageOf("logd"), null);
        eq("packageOf: another dot-less native name", ProcStats.packageOf("system_server"), null);
        eq("packageOf: empty name", ProcStats.packageOf(""), null);
        eq("packageOf: null name", ProcStats.packageOf(null), null);
        eq("packageOf: unusual casing still matches the shape and is returned as-is", ProcStats.packageOf("Com.Example.App"), "Com.Example.App");

        // ================= topByCpu / topByMem / totalRssKb =================
        ProcStats.Proc p1 = proc(1, 0, "u0_a1", 100, 5.0, "com.a");
        ProcStats.Proc p2 = proc(2, 0, "u0_a2", 500, 9.0, "com.b");
        ProcStats.Proc p3 = proc(3, 0, "u0_a3", 500, 9.0, "com.c");            // ties p2 on both cpu and rss: must stay after p2
        ProcStats.Proc p4 = proc(4, 0, "u0_a4", 50, 1.0, "com.d");
        List<ProcStats.Proc> all = new ArrayList<ProcStats.Proc>();
        all.add(p1); all.add(p2); all.add(p3); all.add(p4);

        eq("topByCpu: descending, ties keep original order (limit 0 = everyone)", pids(ProcStats.topByCpu(all, 0)), "2,3,1,4,");
        eq("topByCpu: limit 2", pids(ProcStats.topByCpu(all, 2)), "2,3,");
        eq("topByCpu: limit >= size returns everyone sorted", pids(ProcStats.topByCpu(all, 100)), "2,3,1,4,");
        eq("topByCpu: negative limit returns everyone sorted", pids(ProcStats.topByCpu(all, -5)), "2,3,1,4,");
        eq("topByCpu: null input", ProcStats.topByCpu(null, 5).size(), 0);
        eq("topByCpu: empty input", ProcStats.topByCpu(new ArrayList<ProcStats.Proc>(), 5).size(), 0);

        eq("topByMem: descending, ties keep original order", pids(ProcStats.topByMem(all, 0)), "2,3,1,4,");
        eq("topByMem: limit 1", pids(ProcStats.topByMem(all, 1)), "2,");
        eq("topByMem: limit == size returns everyone sorted", pids(ProcStats.topByMem(all, 4)), "2,3,1,4,");
        eq("topByMem: null input", ProcStats.topByMem(null, 5).size(), 0);

        eq("totalRssKb: sum", ProcStats.totalRssKb(all), 1150L);
        eq("totalRssKb: null", ProcStats.totalRssKb(null), 0L);
        eq("totalRssKb: empty", ProcStats.totalRssKb(new ArrayList<ProcStats.Proc>()), 0L);
        is("the original list passed to topByCpu/topByMem is left untouched", all.get(0) == p1 && all.get(1) == p2 && all.get(2) == p3 && all.get(3) == p4);

        // ================= sumThreads =================
        String grepOut = "/proc/1/status:Threads:\t1\n"
            + "/proc/2345/status:Threads:\t7\n"
            + "/proc/9999/status:Threads:\t12\n";
        eq("sumThreads: adds the trailing number on every matching line", ProcStats.sumThreads(grepOut), 20L);
        eq("sumThreads: a single process", ProcStats.sumThreads("/proc/1/status:Threads:\t3\n"), 3L);
        eq("sumThreads: tolerates extra spaces around the number", ProcStats.sumThreads("/proc/1/status:Threads:   4  \n"), 4L);
        eq("sumThreads: a line with no Threads: match at all is ignored, not a crash", ProcStats.sumThreads("/proc/1/status:PPid:\t0\n"), 0L);
        eq("sumThreads: empty input", ProcStats.sumThreads(""), 0L);
        eq("sumThreads: null input", ProcStats.sumThreads(null), 0L);
        eq("sumThreads: a race where a process exited mid-grep (empty/partial line) is skipped, not fatal",
            ProcStats.sumThreads("/proc/1/status:Threads:\t2\n\n/proc/2/status:Threads:\t5\n"), 7L);

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
