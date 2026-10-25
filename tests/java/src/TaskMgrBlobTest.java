package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;

/** The Task Manager poll's shell output: the ten marker-ended sections, a cut-off read, and a backend that answered with its error
 *  text instead of running the script (review MA3-3: that text parsed as zeros and was shown as a good reading); and a scan that the
 *  poll in MainActivity reads CPU, RAM and network itself in that case. */
public class TaskMgrBlobTest {
    static int n = 0, fails = 0;
    static void eq(String what, Object got, Object want) { n++; if (!Objects.equals(want, got)) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }
    static void is(String what, boolean got) { n++; if (!got) { fails++; System.out.println("FAIL " + what); } }

    static final String STAT = "cpu  140305 2043 46213 1566940 8604 0 1420 0\n"
        + "cpu0 70000 1000 23000 783000 4300 0 700 0\n"
        + "cpu1 70305 1043 23213 783940 4304 0 720 0\n"
        + "intr 59832914 32 0 0 0\n";
    static final String MEM = "MemTotal:        7812340 kB\nMemFree:          301234 kB\nMemAvailable:    3456789 kB\n";
    static final String NET = "Inter-|   Receive                                                |  Transmit\n"
        + " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
        + "    lo:  733384    4537    0    0    0     0          0         0   733384    4537    0    0    0     0       0          0\n"
        + " wlan0: 9876543   12345    0    0    0     0          0         0  1234567    6789    0    0    0     0       0          0\n";
    static final String PS = "  PID  PPID USER            RSS %CPU NAME\n"
        + "    1     0 root           2104  0.0 init\n"
        + " 1522   615 u0_a55        98344  1.2 com.whatsapp\n";
    static final String THREADS = "/proc/1/status:Threads:\t1\n/proc/1522/status:Threads:\t60\n";

    /** What the shell prints for the whole script: each section, then its marker on a line of its own. */
    static String full() {
        return STAT + TaskMgrBlob.M1 + "\n" + MEM + TaskMgrBlob.M2 + "\n" + NET + TaskMgrBlob.M3 + "\n" + PS + TaskMgrBlob.M4 + "\n"
            + "12 100\n" + TaskMgrBlob.M5 + "\n" + TaskMgrBlob.M6 + "\n" + TaskMgrBlob.M7 + "\n" + "585000000\n" + TaskMgrBlob.M8 + "\n"
            + "900000000\n" + TaskMgrBlob.M9 + "\n" + THREADS + TaskMgrBlob.M10 + "\n";
    }

    static String bodyOf(String src, String sig) {
        int i = src.indexOf(sig);
        if (i < 0) return null;
        int open = src.indexOf('{', src.indexOf(')', i)), depth = 0;
        for (int j = open; j >= 0 && j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, j + 1);
        }
        return null;
    }

    public static void main(String[] a) throws Exception {
        // ---- the whole script ran ----
        String[] p = TaskMgrBlob.split(full());
        is("full: the script ran", TaskMgrBlob.scriptRan(full()));
        eq("full: ten sections", p.length, 10);
        eq("full: 2 cores", CpuStats.coreCount(CpuStats.parse(p[0])), 2);
        eq("full: RAM total", MemStats.parse(p[1]).totalKb, 7812340L);
        eq("full: 2 interfaces", NetStats.parse(p[2]).ifaces.size(), 2);
        eq("full: 2 processes", ProcStats.parse(p[3]).procs.size(), 2);
        eq("full: threads", ProcStats.sumThreads(p[9]), 61L);

        // ---- a slow process list cut off by the timeout: the shell keeps what it had read and adds its note ----
        String cut = STAT + TaskMgrBlob.M1 + "\n" + MEM + TaskMgrBlob.M2 + "\n" + NET + TaskMgrBlob.M3 + "\n" + PS.substring(0, 60) + "\n[Process timed out after 8000ms]";
        p = TaskMgrBlob.split(cut);
        is("cut off: the script ran (CPU, RAM and network are there)", TaskMgrBlob.scriptRan(cut));
        eq("cut off: CPU kept", CpuStats.coreCount(CpuStats.parse(p[0])), 2);
        eq("cut off: RAM kept", MemStats.parse(p[1]).totalKb, 7812340L);
        eq("cut off: network kept", NetStats.parse(p[2]).ifaces.size(), 2);
        eq("cut off: no process list without its marker", p[3], "");
        eq("cut off: nothing after it", p[9], "");

        // ---- the backend is down: executeShell answers with its error text (or nothing), never with the script's output ----
        String[] errors = {
            "error: device '192.168.1.20:37099' not found\n",
            "adb: device offline\n",
            "Error: Wireless Debugging is not configured. Connect it in Working Modes.",
            "Error: Shizuku is not authorized for this app",
            "Error: root access is not granted to this app (su refused). Allow it in the root manager and check Working Modes again.",
            "[Process timed out after 8000ms]",
            "",
            null,
        };
        for (String e : errors) {
            is("error " + e + ": the script did not run", !TaskMgrBlob.scriptRan(e));
            p = TaskMgrBlob.split(e);
            // this is what the poll used to report as a good reading: no cores, no RAM, no network, no processes
            is("error " + e + ": every section is empty", p.length == 10 && String.join("", p).isEmpty());
        }

        // ---- the direct read the poll falls back to: CPU, RAM and network from the files themselves, the rest empty ----
        String direct = TaskMgrBlob.direct(STAT, MEM, NET);
        p = TaskMgrBlob.split(direct);
        eq("direct: CPU", p[0], STAT);
        eq("direct: RAM", p[1], MEM);
        eq("direct: network", p[2], NET);
        is("direct: no process list, GPU or threads", String.join("", java.util.Arrays.copyOfRange(p, 3, 10)).isEmpty());
        eq("direct of unreadable files: all empty, no marker text in a section", String.join("", TaskMgrBlob.split(TaskMgrBlob.direct("", "", ""))), "");

        // ---- the poll in MainActivity: a privileged shell that did not run the script is treated as no privileged shell ----
        File src = null;
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null && src == null; d = d.getParentFile()) {
            File f = new File(d, "src/com/bloatware/bingblop/MainActivity.java");
            if (f.isFile()) src = f;
        }
        if (src == null) System.out.println("SKIP MainActivity.java not found");
        else {
            String m = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);
            String tick = bodyOf(m, "private void tmTickOnce(");
            is("MainActivity: tmTickOnce found", tick != null);
            if (tick != null) {
                int shellAt = tick.indexOf("executeShell(");
                int ranAt = tick.indexOf("if (!TaskMgrBlob.scriptRan(blob)) {");
                int dropAt = ranAt < 0 ? -1 : tick.indexOf("havePriv = false;", ranAt);
                int saidAt = ranAt < 0 ? -1 : tick.indexOf("res.put(\"privileged\", false);", ranAt);
                int fallbackAt = tick.indexOf("if (!havePriv) {");
                int directAt = fallbackAt < 0 ? -1 : tick.indexOf("TaskMgrBlob.direct(readWorldReadable(\"/proc/stat\"), readWorldReadable(\"/proc/meminfo\"), readWorldReadable(\"/proc/net/dev\"))", fallbackAt);
                int splitAt = tick.indexOf("TaskMgrBlob.split(blob)");
                is("MainActivity: after the shell, a blob without the script's output turns the tick unprivileged (and says so)", shellAt >= 0 && ranAt > shellAt && dropAt > ranAt && saidAt > ranAt);
                is("MainActivity: an unprivileged tick (also one whose shell failed) reads CPU, RAM and network itself before splitting", fallbackAt > ranAt && directAt > fallbackAt && splitAt > directAt);
            }
        }

        System.out.println(fails == 0 ? "PASS TaskMgrBlobTest: " + n + " checks" : "FAILED TaskMgrBlobTest: " + fails + " of " + n);
        if (fails != 0) System.exit(1);
    }
}
