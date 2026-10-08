package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class BatchVerifyTest {
    static int fails = 0, n = 0;
    static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL: " + what); } }
    static Set<String> set(String... a) { return new HashSet<String>(Arrays.asList(a)); }
    static BatchVerify.State st(Set<String> inst, Set<String> dis) { return new BatchVerify.State(inst, dis, null, null); }
    static JSONArray rows(boolean cmdOk, String... pkgs) throws Exception {
        JSONArray r = new JSONArray();
        for (String p : pkgs) r.put(new JSONObject().put("pkg", p).put("output", "Failure [x]").put("success", cmdOk));
        return r;
    }

    public static void main(String[] a) throws Exception {
        // the output of `pm list packages --user 0`
        Set<String> p = BatchVerify.parse("package:com.a\npackage:com.b  uid:10123\r\n\npackage:com.c\n");
        check("parses package lines", p != null && p.equals(set("com.a", "com.b", "com.c")));
        check("a list that is not a list is null", BatchVerify.parse("Error: no shell") == null && BatchVerify.parse("") == null && BatchVerify.parse(null) == null);

        check("what can be checked", BatchVerify.verifiable("uninstall") && BatchVerify.verifiable("uninstall_keep_data") && BatchVerify.verifiable("reinstall")
                && BatchVerify.verifiable("freeze") && BatchVerify.verifiable("unfreeze")
                && BatchVerify.verifiable("suspend") && BatchVerify.verifiable("unsuspend") && !BatchVerify.verifiable("clear_data") && !BatchVerify.verifiable("force_stop") && !BatchVerify.verifiable("custom:ls") && !BatchVerify.verifiable(null));
        check("only freeze and unfreeze need the disabled list", BatchVerify.needsDisabled("freeze") && BatchVerify.needsDisabled("unfreeze") && !BatchVerify.needsDisabled("uninstall"));

        // every command said "failed" but all three apps are gone: the phone's answer wins
        JSONArray r = rows(false, "com.a", "com.b", "com.c");
        Set<String> installed = set("com.other");
        check("not all in the wanted state is detected while one is still there", !BatchVerify.allOk("uninstall", rows(false, "com.a", "com.other"), st(installed, set())));
        check("all gone is settled", BatchVerify.allOk("uninstall", r, st(installed, set())));
        int ok = BatchVerify.apply("uninstall", r, st(installed, set()));
        check("all three count as uninstalled", ok == 3);
        JSONObject o0 = r.getJSONObject(0);
        check("row says Uninstalled and succeeded", o0.getBoolean("success") && "Uninstalled".equals(o0.getString("label")) && o0.getBoolean("verified") && !o0.getBoolean("commandOk"));
        check("the note says the command reported a failure but the phone says it worked", o0.getString("output").startsWith("Checked afterwards: uninstalled. The command reported a failure, but the phone says it worked.")
                && o0.getString("output").endsWith("Failure [x]"));

        // the command said "Success" but the app is still there
        JSONArray r2 = rows(true, "com.a", "com.b");
        ok = BatchVerify.apply("uninstall", r2, st(set("com.a"), set()));
        check("one gone, one still installed", ok == 1 && r2.getJSONObject(0).getString("label").equals("Still installed") && !r2.getJSONObject(0).getBoolean("success")
                && r2.getJSONObject(1).getBoolean("success"));
        check("a note when the command claimed success", r2.getJSONObject(0).getString("output").contains("although the command reported success"));
        check("a confirmed success has a plain note", r2.getJSONObject(1).getString("output").startsWith("Checked afterwards: uninstalled.\n\n"));

        // keep data works like uninstall
        JSONArray r3 = rows(false, "com.a");
        check("uninstall_keep_data checks the same way", BatchVerify.apply("uninstall_keep_data", r3, st(set(), set())) == 1);

        // reinstall
        JSONArray r4 = rows(true, "com.a", "com.b");
        ok = BatchVerify.apply("reinstall", r4, st(set("com.a"), set()));
        check("reinstall: installed counts, not installed does not", ok == 1 && r4.getJSONObject(0).getString("label").equals("Installed") && r4.getJSONObject(1).getString("label").equals("Not installed"));

        // freeze / unfreeze use the disabled list
        JSONArray r5 = rows(true, "com.a", "com.b", "com.gone");
        ok = BatchVerify.apply("freeze", r5, st(set("com.a", "com.b"), set("com.a")));
        check("freeze: disabled counts; enabled and missing do not", ok == 1 && r5.getJSONObject(0).getBoolean("success") && !r5.getJSONObject(1).getBoolean("success") && !r5.getJSONObject(2).getBoolean("success")
                && r5.getJSONObject(1).getString("label").equals("Not frozen"));
        JSONArray r6 = rows(true, "com.a", "com.b");
        ok = BatchVerify.apply("unfreeze", r6, st(set("com.a", "com.b"), set("com.a")));
        check("unfreeze: enabled counts; still disabled does not", ok == 1 && r6.getJSONObject(1).getBoolean("success") && r6.getJSONObject(0).getString("label").equals("Still frozen"));

        // suspend / unsuspend use each app's own flag from `dumpsys package <pkg>`
        String dumpOn = "Packages:\n  Package [com.a] (abc):\n    userId=10123\n    User 0: ceDataInode=123 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0\n";
        String dumpOff = "    User 0: ceDataInode=123 installed=true hidden=false suspended=false stopped=false enabled=0\n    User 10: ceDataInode=1 installed=true hidden=false suspended=true\n";
        check("suspended=true read from the user 0 line", Boolean.TRUE.equals(BatchVerify.suspendedFrom(dumpOn)));
        check("suspended=false read from the user 0 line, not from another user's", Boolean.FALSE.equals(BatchVerify.suspendedFrom(dumpOff)));
        check("no user line: unknown", BatchVerify.suspendedFrom("nothing here") == null && BatchVerify.suspendedFrom(null) == null && BatchVerify.suspendedFrom("    User 0: installed=true hidden=false") == null);
        check("only suspend / unsuspend need the flags", BatchVerify.needsSuspended("suspend") && BatchVerify.needsSuspended("unsuspend") && !BatchVerify.needsSuspended("freeze"));
        JSONArray r7 = rows(true, "com.a", "com.b", "com.c");
        BatchVerify.State sus = new BatchVerify.State(set("com.a", "com.b", "com.c"), null, set("com.a"), set("com.c"));
        ok = BatchVerify.apply("suspend", r7, sus);
        check("suspend: suspended counts, not suspended does not, unreadable keeps the command's answer", ok == 2 && r7.getJSONObject(0).getString("label").equals("Suspended") && !r7.getJSONObject(1).getBoolean("success")
                && r7.getJSONObject(1).getString("label").equals("Not suspended") && !r7.getJSONObject(2).has("verified") && r7.getJSONObject(2).getBoolean("success"));
        check("unreadable apps do not hold up the retry", BatchVerify.allOk("suspend", rows(true, "com.a", "com.c"), sus) && !BatchVerify.allOk("suspend", rows(true, "com.a", "com.b"), sus));
        JSONArray r8 = rows(true, "com.a", "com.b");
        ok = BatchVerify.apply("unsuspend", r8, new BatchVerify.State(set("com.a", "com.b"), null, set("com.a"), null));
        check("unsuspend: a suspended app is Still suspended", ok == 1 && r8.getJSONObject(0).getString("label").equals("Still suspended") && r8.getJSONObject(1).getString("label").equals("Not suspended"));

        System.out.println(fails == 0 ? "ALL PASS (" + n + " checks)" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
