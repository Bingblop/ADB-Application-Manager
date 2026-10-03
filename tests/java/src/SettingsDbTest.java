package com.bloatware.bingblop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class SettingsDbTest {
    static int n = 0, fails = 0;
    static void eq(String what, Object got, Object want) { n++; if (!Objects.equals(want, got)) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }
    static void is(String what, boolean got) { n++; if (!got) { fails++; System.out.println("FAIL " + what); } }
    static boolean throwsFlag(Runnable r) { try { r.run(); return false; } catch (IllegalArgumentException e) { return true; } }
    static void throwsIae(String what, Runnable r) { n++; try { r.run(); fails++; System.out.println("FAIL " + what + ": no exception"); } catch (IllegalArgumentException e) { /* ok */ } }

    // what AndroidBridge's reader does with a process's output: line by line, "\n" after each, trimmed
    static String viaReader(String raw) throws IOException {
        BufferedReader r = new BufferedReader(new StringReader(raw));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        return sb.toString().trim();
    }

    static Path fakeBin, fakeDb;

    static String runShell(String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", script);
        pb.environment().put("PATH", fakeBin + ":" + System.getenv("PATH"));
        pb.environment().put("SDB_FAKE", fakeDb.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int k;
        InputStream in = p.getInputStream();
        while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
        p.waitFor();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void main(String[] a) throws Exception {
        // ---- namespaces / names / values ----
        for (String ns : new String[] {"global", "secure", "system"}) eq("ns " + ns, SettingsDb.isNamespace(ns), true);
        for (String ns : new String[] {"", "Global", "config", "global ", null, "secure;ls", "../system"}) eq("bad ns " + ns, SettingsDb.isNamespace(ns), false);
        for (String k : new String[] {"adb_enabled", "a", "a.b:c", "wifi_on1", "ünï", "weird$key", "with'quote", "a/b", "A-B", "x@y+z", "x".repeat(256)}) eq("good key " + k, SettingsDb.keyProblem(k), null);
        for (String k : new String[] {"", null, "-n", "--user", "a b", "a\tb", "a\nb", "a=b", "\u0000", "x".repeat(257), "a b", "a b"}) is("bad key " + String.valueOf(k).replace("\n", "\\n"), SettingsDb.keyProblem(k) != null);
        for (String v : new String[] {"", "0", "a b", "it's", "$HOME", "`date`", "\"q\"", "a;b", "line1\nline2", "tab\there", "héllo ☕", "x".repeat(20000)}) eq("good value " + v.length(), SettingsDb.valueProblem(v), null);
        for (String v : new String[] {null, "a\u0000b", "x".repeat(20001), "bell\u0007", "esc\u001b[0m"}) is("bad value", SettingsDb.valueProblem(v) != null);

        // ---- exact commands ----
        eq("list", SettingsDb.listCommand("global"), "settings list global; echo @@SDB-END@@");
        eq("get", SettingsDb.getCommand("secure", "ui_night_mode"), "settings get secure 'ui_night_mode'");
        eq("put", SettingsDb.putCommand("system", "screen_brightness", "128"), "settings put system 'screen_brightness' '128'");
        eq("put empty", SettingsDb.putCommand("global", "k", ""), "settings put global 'k' ''");
        eq("put quote", SettingsDb.putCommand("global", "k", "it's"), "settings put global 'k' 'it'\\''s'");
        eq("delete", SettingsDb.deleteCommand("global", "k"), "settings delete global 'k'");
        eq("script put", SettingsDb.writeScript("put", "global", "k", "v"), "settings put global 'k' 'v'; echo @@SDB-GET@@$?; settings get global 'k'; echo @@SDB-END@@$?");
        eq("script put null asks for a listing", SettingsDb.writeScript("put", "global", "k", "null"), "settings put global 'k' 'null'; echo @@SDB-GET@@$?; settings get global 'k'; echo @@SDB-LIST@@$?; settings list global; echo @@SDB-END@@$?");
        eq("script delete", SettingsDb.writeScript("delete", "global", "k", null), "settings delete global 'k'; echo @@SDB-GET@@$?; settings get global 'k'; echo @@SDB-LIST@@$?; settings list global; echo @@SDB-END@@$?");
        eq("script get", SettingsDb.writeScript("get", "secure", "k", null), "echo @@SDB-GET@@; settings get secure 'k'; echo @@SDB-END@@$?");
        throwsIae("script bad op", () -> SettingsDb.writeScript("reset", "global", "k", "v"));
        throwsIae("list bad ns", () -> SettingsDb.listCommand("nope"));
        throwsIae("put bad key", () -> SettingsDb.putCommand("global", "a b", "v"));
        throwsIae("put bad value", () -> SettingsDb.putCommand("global", "k", "a\u0000"));
        throwsIae("get bad key", () -> SettingsDb.getCommand("global", ""));
        throwsIae("delete bad ns", () -> SettingsDb.deleteCommand("x", "k"));

        // ---- parseList ----
        List<String[]> l = SettingsDb.parseList("a=1\nb=hello world\nc=\nd=x=y\ne.f:g=null\n");
        eq("list size", l.size(), 5);
        eq("list a", l.get(0)[0] + "|" + l.get(0)[1], "a|1");
        eq("list b", l.get(1)[0] + "|" + l.get(1)[1], "b|hello world");
        eq("list empty value", l.get(2)[0] + "|" + l.get(2)[1], "c|");
        eq("list = in value", l.get(3)[0] + "|" + l.get(3)[1], "d|x=y");
        eq("list dotted", l.get(4)[0] + "|" + l.get(4)[1], "e.f:g|null");
        l = SettingsDb.parseList("* daemon not running; starting now at tcp:5037\n* daemon started successfully\nlong=first line\nsecond line\nthird: x\nnext=ok");
        eq("noise dropped, multi-line", l.size(), 2);
        eq("multi-line value", l.get(0)[1], "first line\nsecond line\nthird: x");
        eq("entry after multi-line", l.get(1)[0] + "|" + l.get(1)[1], "next|ok");
        eq("windows line ends", SettingsDb.parseList("a=1\r\nb=2\r\n").size(), 2);
        eq("windows value", SettingsDb.parseList("a=1\r\nb=2\r\n").get(0)[1], "1");
        eq("blank", SettingsDb.parseList("").size(), 0);
        eq("null", SettingsDb.parseList(null).size(), 0);
        eq("error text is not entries", SettingsDb.parseList("cmd: Can't find service: settings").size(), 0);
        eq("= first char is not an entry", SettingsDb.parseList("=oops\nfoo=bar").size(), 1);
        eq("name with a space is a continuation", SettingsDb.parseList("a=1\nsome text=more\nb=2").get(0)[1], "1\nsome text=more");

        // ---- parseWrite ----
        SettingsDb.WriteResult w = SettingsDb.parseWrite("@@SDB-GET@@\n1\n@@SDB-END@@");
        eq("write plain value", w.value, "1"); eq("write plain answer", w.answer, "");
        w = SettingsDb.parseWrite("@@SDB-GET@@\n\n@@SDB-END@@");
        eq("write empty value", w.value, "");
        w = SettingsDb.parseWrite("@@SDB-GET@@\n a b \n@@SDB-END@@");
        eq("write keeps spaces", w.value, " a b ");
        w = SettingsDb.parseWrite("@@SDB-GET@@\nl1\nl2\n\n@@SDB-END@@");
        eq("write value with trailing newline", w.value, "l1\nl2\n");
        w = SettingsDb.parseWrite("Exception occurred while executing 'put':\njava.lang.SecurityException: Permission denial: writing to settings requires:android.permission.WRITE_SECURE_SETTINGS\n\tat x.y\n@@SDB-GET@@\n0\n@@SDB-END@@");
        is("write refused answer", w.answer.startsWith("Exception occurred")); eq("write refused value", w.value, "0");
        is("refusal is a failure", SettingsDb.looksLikeFailure(w.answer));
        is("refusal advice mentions security settings", SettingsDb.advice(w.answer).contains("Security settings"));
        eq("refusal summary", SettingsDb.summary(w.answer), "java.lang.SecurityException: Permission denial: writing to settings requires:android.permission.WRITE_SECURE_SETTINGS");
        w = SettingsDb.parseWrite("Deleted 1 rows\n@@SDB-GET@@\nnull\n@@SDB-END@@");
        eq("delete count", w.deleted, 1); eq("delete value", w.value, "null");
        w = SettingsDb.parseWrite("Deleted 0 rows\n@@SDB-GET@@\nnull\n@@SDB-END@@");
        eq("delete none", w.deleted, 0);
        w = SettingsDb.parseWrite("[Process timed out after 15000ms]");
        eq("timeout: no value", w.value, null); is("timeout is a failure", SettingsDb.looksLikeFailure(w.answer));
        is("timeout advice", SettingsDb.advice(w.answer).contains("Working Modes"));
        w = SettingsDb.parseWrite("error: device offline");
        is("offline is a failure", SettingsDb.looksLikeFailure(w.answer)); is("offline advice", SettingsDb.advice(w.answer).contains("connection"));
        w = SettingsDb.parseWrite("@@SDB-GET@@\nvalue\n");
        eq("end marker missing: unknown", w.value, null);
        w = SettingsDb.parseWrite(null);
        eq("null output", w.value, null);
        is("plain output is not a failure", !SettingsDb.looksLikeFailure("Deleted 1 rows") && !SettingsDb.looksLikeFailure("") && !SettingsDb.looksLikeFailure(null));
        is("invalid value advice", SettingsDb.advice("java.lang.IllegalArgumentException: Invalid value for system setting").contains("rejected"));
        eq("no advice for nothing", SettingsDb.advice(""), "");
        is("long summary is cut", SettingsDb.summary("Exception: " + "x".repeat(400)).length() <= 262);

        // ---- the verdict ----
        SettingsDb.Verdict vd = SettingsDb.judge("put", "1", SettingsDb.parseWrite("@@SDB-GET@@\n1\n@@SDB-END@@"));
        is("put verified", vd.ok); eq("put verified error", vd.error, "");
        vd = SettingsDb.judge("put", "1", SettingsDb.parseWrite("@@SDB-GET@@\n0\n@@SDB-END@@"));
        is("put silently reverted is not ok", !vd.ok); eq("reverted text", vd.error, "Android did not keep the new value");
        vd = SettingsDb.judge("put", "1", SettingsDb.parseWrite("Exception occurred while executing 'put':\njava.lang.SecurityException: Permission denial: x\n@@SDB-GET@@\n0\n@@SDB-END@@"));
        is("put refused is not ok", !vd.ok); eq("refused text", vd.error, "java.lang.SecurityException: Permission denial: x");
        vd = SettingsDb.judge("put", "1", SettingsDb.parseWrite("[Process timed out after 20000ms]"));
        is("timeout is not ok", !vd.ok); eq("timeout text", vd.error, "[Process timed out after 20000ms]");
        vd = SettingsDb.judge("put", "1", SettingsDb.parseWrite(""));
        is("no answer is not ok", !vd.ok); eq("no answer text", vd.error, "No answer from the device");
        vd = SettingsDb.judge("put", "", SettingsDb.parseWrite("@@SDB-GET@@\n\n@@SDB-END@@"));
        is("empty value verified", vd.ok);
        vd = SettingsDb.judge("put", "null", SettingsDb.parseWrite("@@SDB-GET@@\nnull\n@@SDB-END@@"));
        is("the word null verified", vd.ok);
        vd = SettingsDb.judge("delete", null, SettingsDb.parseWrite("Deleted 1 rows\n@@SDB-GET@@\nnull\n@@SDB-END@@"));
        is("delete ok", vd.ok);
        vd = SettingsDb.judge("delete", null, SettingsDb.parseWrite("Deleted 0 rows\n@@SDB-GET@@\nnull\n@@SDB-END@@"));
        is("delete of a missing key is fine (it is gone)", vd.ok);
        vd = SettingsDb.judge("delete", null, SettingsDb.parseWrite("Exception occurred while executing 'delete':\njava.lang.SecurityException: no\n@@SDB-GET@@\n7\n@@SDB-END@@"));
        is("delete refused is not ok", !vd.ok); eq("delete refused text", vd.error, "java.lang.SecurityException: no");
        vd = SettingsDb.judge("delete", null, SettingsDb.parseWrite("@@SDB-GET@@\n7\n@@SDB-END@@"));
        is("delete that left the value is not ok", !vd.ok); eq("delete left text", vd.error, "Android still has a value for it");
        vd = SettingsDb.judge("get", null, SettingsDb.parseWrite("@@SDB-GET@@\nabc\n@@SDB-END@@"));
        is("get ok", vd.ok);
        vd = SettingsDb.judge("get", null, SettingsDb.parseWrite("error: device offline"));
        is("get while offline is not ok", !vd.ok);

        // ---- the commands against a fake `settings` run by a real sh ----
        fakeBin = Files.createTempDirectory("sdbbin"); fakeDb = Files.createTempDirectory("sdbdb");
        Path script = fakeBin.resolve("settings");
        Files.write(script, ("#!/bin/sh\n"
            + "cmd=\"$1\"; ns=\"$2\"; dir=\"$SDB_FAKE/$ns\"; mkdir -p \"$dir\"\n"
            + "case \"$3\" in refused*) case \"$cmd\" in put|delete) echo \"Exception occurred while executing '$cmd':\"; echo 'java.lang.SecurityException: Permission denial: writing to settings requires:android.permission.WRITE_SECURE_SETTINGS'; exit 255;; esac;; esac\n"
            + "case \"$cmd\" in\n"
            + "  list) for f in \"$dir\"/*; do [ -e \"$f\" ] || continue; printf '%s=' \"$(basename \"$f\")\"; cat \"$f\"; echo; done ;;\n"
            + "  get) f=\"$dir/$3\"; if [ -e \"$f\" ]; then cat \"$f\"; echo; else echo null; fi ;;\n"
            + "  put) if [ \"$#\" -ne 4 ]; then echo \"Error: wrong argument count $#\"; else printf '%s' \"$4\" > \"$dir/$3\"; fi ;;\n"
            + "  delete) if [ -e \"$dir/$3\" ]; then rm \"$dir/$3\"; echo \"Deleted 1 rows\"; else echo \"Deleted 0 rows\"; fi ;;\n"
            + "  *) echo \"Unknown command: $cmd\" ;;\n"
            + "esac\n").getBytes(StandardCharsets.UTF_8));
        script.toFile().setExecutable(true);
        String[] values = {"1", "0", "true", "", " ", "a b", "  lead and trail  ", "it's", "'", "''", "'; rm -rf /tmp/never; '", "$HOME", "${PATH}", "$(echo pwned)", "`echo pwned`",
                "\"double\"", "a;b", "a&&b", "a|b", ">sdb_redirect_probe", "*", "?", "back\\slash", "\\n", "-n", "-e", "--user", "null", "-1", "é☕日本語", "tab\tin", "two\nlines", "ends with newline\n", "\n", "a\n\nb",
                "x".repeat(15000), "%s%d", "~", "!bang", "#hash", "{a,b}", "a=b"};
        for (String v : values) {
            String out = viaReader(runShell(SettingsDb.writeScript("put", "global", "probe", v)));
            SettingsDb.WriteResult r = SettingsDb.parseWrite(out);
            eq("put round trip [" + (v.length() > 20 ? v.length() + " chars" : v.replace("\n", "\\n")) + "]", r.value, v);
            eq("put answer is empty [" + (v.length() > 20 ? "long" : v.replace("\n", "\\n")) + "]", r.answer, "");
        }
        is("nothing was executed by a value", !Files.exists(Paths.get("/tmp/never")) && !Files.exists(Paths.get("sdb_redirect_probe")));
        // names with shell characters
        for (String k : new String[] {"plain", "a.b:c", "with'quote", "weird$key", "ünï", "semi;colon", "star*", "back`tick`", "amp&er"}) {
            String out = viaReader(runShell(SettingsDb.writeScript("put", "secure", k, "v-" + k)));
            SettingsDb.WriteResult r = SettingsDb.parseWrite(out);
            eq("key round trip [" + k + "]", r.value, "v-" + k);
        }
        // the table is listed and parsed back
        String listed = viaReader(runShell(SettingsDb.listCommand("secure")));
        List<String[]> entries = SettingsDb.parseList(listed);
        Map<String, String> got = new HashMap<>(); for (String[] e : entries) got.put(e[0], e[1]);
        eq("listed secure count", got.size(), 9);
        eq("listed value", got.get("a.b:c"), "v-a.b:c"); eq("listed weird key", got.get("weird$key"), "v-weird$key");
        // delete
        SettingsDb.WriteResult d = SettingsDb.parseWrite(viaReader(runShell(SettingsDb.writeScript("delete", "secure", "plain", null))));
        eq("delete answered", d.deleted, 1); eq("delete read back", d.value, "null");
        d = SettingsDb.parseWrite(viaReader(runShell(SettingsDb.writeScript("delete", "secure", "plain", null))));
        eq("delete again", d.deleted, 0);
        // get of something never set
        d = SettingsDb.parseWrite(viaReader(runShell(SettingsDb.writeScript("get", "system", "never_set", null))));
        eq("get missing", d.value, "null");
        // tables are separate
        d = SettingsDb.parseWrite(viaReader(runShell(SettingsDb.writeScript("get", "system", "probe", null))));
        eq("other table untouched", d.value, "null");
        // a put the system refuses (wrong arg count in the fake) is read as a failure
        d = SettingsDb.parseWrite(viaReader(runShell("settings put global onlykey; echo @@SDB-GET@@; settings get global 'onlykey'; echo @@SDB-END@@")));
        is("refused put is a failure text", SettingsDb.looksLikeFailure(d.answer));

        // ================= the independent review's findings =================

        // (1) the word null cannot be vouched for by a read-back: Android's error text, the exit status and the listing decide
        String refusedText = "Exception occurred while executing 'put':\njava.lang.SecurityException: Permission denial: writing to settings requires:android.permission.WRITE_SECURE_SETTINGS\n";
        SettingsDb.WriteResult wr = SettingsDb.parseWrite(refusedText + "@@SDB-GET@@255\nnull\n@@SDB-LIST@@0\na=1\nb=2\n@@SDB-END@@0\n", "newkey");
        eq("refused put of null: status read", wr.rc, 255); eq("refused put of null: listing says absent", wr.present, Boolean.FALSE);
        vd = SettingsDb.judge("put", "null", wr);
        is("a refused put of null is NOT ok (the key is absent, null is what absent prints)", !vd.ok); is("and says why", vd.error.contains("Permission denial"));
        wr = SettingsDb.parseWrite("@@SDB-GET@@0\nnull\n@@SDB-LIST@@0\na=1\n@@SDB-END@@0\n", "newkey");
        vd = SettingsDb.judge("put", "null", wr);
        is("a silent refusal of null: the listing has no such key, so not ok", !vd.ok); eq("silent refusal text", vd.error, "Android did not create it");
        wr = SettingsDb.parseWrite("@@SDB-GET@@0\nnull\n@@SDB-LIST@@0\na=1\nnewkey=null\n@@SDB-END@@0\n", "newkey");
        eq("listing finds the key", wr.present, Boolean.TRUE); is("putting null that really took is ok", SettingsDb.judge("put", "null", wr).ok);
        wr = SettingsDb.parseWrite("@@SDB-GET@@0\nnull\n@@SDB-LIST@@0\nnewkey=null\n@@SDB-END@@0\n", "new");
        eq("the key is matched whole, not as a prefix", wr.present, Boolean.FALSE);
        wr = SettingsDb.parseWrite("@@SDB-GET@@0\nnull\n@@SDB-LIST@@0\nmulti=first\nnewkey=second line of multi\n@@SDB-END@@0\n", "newkey");
        eq("a listing line shaped like an entry counts as one (a known limit of the listing)", wr.present, Boolean.TRUE);
        wr = SettingsDb.parseWrite("@@SDB-GET@@0\nnull\n@@SDB-END@@0\n", "k");
        is("put of null with no listing at all falls back to the plain read-back", SettingsDb.judge("put", "null", wr).ok);
        wr = SettingsDb.parseWrite("@@SDB-GET@@0\nnull\n@@SDB-LIST@@0\nk=null\n", "k");
        eq("a listing that never finished says nothing", wr.present, null); eq("but the read-back before it is still known", wr.value, "null");
        is("so putting null is judged on the read-back alone", SettingsDb.judge("put", "null", wr).ok);
        wr = SettingsDb.parseWrite("@@SDB-GET@@0\nnull\n@@SDB-LIST@@2\nCan't find service: settings\n@@SDB-END@@2\n", "k");
        eq("a failed listing says nothing", wr.present, null);
        // delete of the key holding the text "null"
        wr = SettingsDb.parseWrite(refusedText.replace("'put'", "'delete'") + "@@SDB-GET@@255\nnull\n@@SDB-LIST@@0\nenabled=null\n@@SDB-END@@0\n", "enabled");
        vd = SettingsDb.judge("delete", null, wr);
        is("a refused delete of a key holding the text null is NOT ok", !vd.ok); is("and says why", vd.error.contains("Permission denial"));
        wr = SettingsDb.parseWrite("Deleted 0 rows\n@@SDB-GET@@0\nnull\n@@SDB-LIST@@0\nenabled=null\n@@SDB-END@@0\n", "enabled");
        vd = SettingsDb.judge("delete", null, wr);
        is("a silent refusal (0 rows, the key still listed) is not ok", !vd.ok); eq("silent delete text", vd.error, "Android did not delete it");
        wr = SettingsDb.parseWrite("Deleted 1 rows\n@@SDB-GET@@0\nnull\n@@SDB-LIST@@0\nother=1\n@@SDB-END@@0\n", "enabled");
        is("a delete that took is ok", SettingsDb.judge("delete", null, wr).ok);
        wr = SettingsDb.parseWrite("Deleted 0 rows\n@@SDB-GET@@0\nnull\n@@SDB-LIST@@0\nother=1\n@@SDB-END@@0\n", "enabled");
        is("deleting what was already gone is ok", SettingsDb.judge("delete", null, wr).ok);
        wr = SettingsDb.parseWrite("Deleted 1 rows\n@@SDB-GET@@0\n42\n@@SDB-LIST@@0\nenabled=42\n@@SDB-END@@0\n", "enabled");
        vd = SettingsDb.judge("delete", null, wr);
        is("'Deleted 1 rows' with a value still there is not ok", !vd.ok); eq("still-there text", vd.error, "Android still has a value for it");
        wr = SettingsDb.parseWrite("@@SDB-GET@@0\nnull\n@@SDB-LIST@@0\n@@SDB-END@@0\n", "enabled");
        eq("an empty listing is a complete listing", wr.present, Boolean.FALSE);
        // the status of the change
        wr = SettingsDb.parseWrite("@@SDB-GET@@1\n5\n@@SDB-END@@0\n");
        eq("status 1", wr.rc, 1); is("a put that already holds the value is ok even when the command complained", SettingsDb.judge("put", "5", wr).ok);
        wr = SettingsDb.parseWrite("@@SDB-GET@@1\n4\n@@SDB-END@@0\n");
        is("status 1 and another value: refused", !SettingsDb.judge("put", "5", wr).ok);
        wr = SettingsDb.parseWrite("@@SDB-GET@@0\n5\n@@SDB-END@@0\n");
        eq("status 0", wr.rc, 0);

        // (2) a list that was cut short is not a table
        SettingsDb.ListResult lr = SettingsDb.readList("a=1\nb=2\nc=hello\n\n[Process timed out after 25000ms]");
        is("a timed-out listing is incomplete", !lr.complete);
        lr = SettingsDb.readList("a=1\nb=2\nc=hel");
        is("a listing cut mid-value is incomplete", !lr.complete);
        lr = SettingsDb.readList("a=1\nb=2\nerror: closed");
        is("a closed connection is incomplete", !lr.complete);
        lr = SettingsDb.readList("a=1\nb=2\n@@SDB-END@@");
        is("a finished listing is complete", lr.complete); eq("with its entries", lr.entries.size(), 2); eq("last entry", lr.entries.get(1)[0] + "|" + lr.entries.get(1)[1], "b|2");
        lr = SettingsDb.readList("a=1\nc=hello\n\n@@SDB-END@@\n");
        eq("the last value keeps its own trailing line break", lr.entries.get(1)[1], "hello\n");
        lr = SettingsDb.readList("a=1\nc=  padded  \n@@SDB-END@@");
        eq("and its trailing spaces", lr.entries.get(1)[1], "  padded  ");
        lr = SettingsDb.readList("@@SDB-END@@");
        is("an empty table is complete", lr.complete && lr.entries.isEmpty());
        lr = SettingsDb.readList("* daemon started successfully\na=1\n@@SDB-END@@");
        is("noise before the first entry is dropped", lr.complete && lr.entries.size() == 1);
        lr = SettingsDb.readList("a=x\n@@SDB-END@@\n@@SDB-END@@");
        eq("a value whose last line is the marker text is kept; only the last marker closes", lr.entries.get(0)[1], "x\n@@SDB-END@@");
        lr = SettingsDb.readList("a=1@@SDB-END@@");
        is("a marker glued to a value is not the closing line", !lr.complete);
        lr = SettingsDb.readList(null);
        is("no answer is incomplete", !lr.complete && lr.entries.isEmpty());
        lr = SettingsDb.readList("a=1\r\nb=2\r\n@@SDB-END@@\r\n");
        is("Windows line ends close it too", lr.complete && lr.entries.size() == 2 && lr.entries.get(1)[1].equals("2"));

        // (5) a long run of line breaks must not overflow the stack
        String blanks = "\n".repeat(60000);
        long t0 = System.currentTimeMillis();
        lr = SettingsDb.readList("a=1\nb=" + blanks + "\nc=3\n@@SDB-END@@");
        is("60000 blank lines in a value: complete, fast", lr.complete && lr.entries.size() == 3 && lr.entries.get(1)[1].length() == 60000 && System.currentTimeMillis() - t0 < 2000);
        is("parseList of a long run of line breaks", SettingsDb.parseList("a=1" + blanks).size() == 1 && SettingsDb.parseList("a=1" + blanks).get(0)[1].equals("1"));

        // (3) a read-back that failed is an error, not the value
        wr = SettingsDb.parseWrite("@@SDB-GET@@0\nException occurred while executing 'get':\njava.lang.SecurityException: not allowed\n@@SDB-END@@255\n");
        eq("failed read-back: no value", wr.value, null); is("its text is kept as the error", wr.readError.contains("not allowed"));
        vd = SettingsDb.judge("put", "1", wr);
        is("so the put is not ok", !vd.ok && !vd.unknown); is("with the error as the reason", vd.error.contains("not allowed"));
        vd = SettingsDb.judge("get", null, wr);
        is("and a get is not ok either", !vd.ok); is("(with its reason)", vd.error.contains("not allowed"));
        wr = SettingsDb.parseWrite("@@SDB-GET@@0\nnull\n@@SDB-END@@1\n");
        eq("a read-back that says null with a status of 1 is still the value null", wr.value, "null");
        vd = SettingsDb.judge("get", null, SettingsDb.parseWrite("@@SDB-GET@@\nhello\n@@SDB-END@@0\n"));
        is("a good get", vd.ok);

        // (6) no answer at all: the outcome is unknown, not "refused"
        vd = SettingsDb.judge("put", "0", SettingsDb.parseWrite("[Process timed out after 20000ms]"));
        is("a timeout is not ok and is unknown", !vd.ok && vd.unknown);
        vd = SettingsDb.judge("put", "0", SettingsDb.parseWrite("@@SDB-GET@@0\n"));
        is("a cut line is unknown", !vd.ok && vd.unknown);
        vd = SettingsDb.judge("put", "0", SettingsDb.parseWrite(""));
        is("silence is unknown", !vd.ok && vd.unknown);
        vd = SettingsDb.judge("put", "1", SettingsDb.parseWrite("@@SDB-GET@@0\n0\n@@SDB-END@@0\n"));
        is("a clear 'not kept' is not unknown", !vd.ok && !vd.unknown);

        // (8) carriage returns, (9) size on the wire
        is("a value with a carriage return is refused", SettingsDb.valueProblem("a\rb") != null && SettingsDb.valueProblem("a\r") != null && SettingsDb.valueProblem("\r") != null);
        is("and cannot become a command", throwsFlag(() -> SettingsDb.putCommand("global", "k", "a\rb")));
        eq("lone line feeds and tabs are still fine", SettingsDb.valueProblem("a\nb\tc"), null);
        eq("15000 apostrophes (60002 bytes quoted) fit", SettingsDb.valueProblem("'".repeat(15000)), null);
        is("20000 apostrophes (80002 bytes quoted) do not", SettingsDb.valueProblem("'".repeat(20000)) != null && SettingsDb.valueProblem("'".repeat(20000)).contains("adb"));
        eq("15499 apostrophes (61998 bytes) are the last that fit", SettingsDb.valueProblem("'".repeat(15499)), null);
        is("15500 are one too many", SettingsDb.valueProblem("'".repeat(15500)) != null);
        eq("20000 CJK characters (60002 bytes) fit", SettingsDb.valueProblem("日".repeat(20000)), null);
        eq("20000 plain characters fit", SettingsDb.valueProblem("x".repeat(20000)), null);
        is("the whole put-and-read-back line for the largest value stays under adb's 65535-byte frame", (SettingsDb.writeScript("put", "global", "x".repeat(256), "'".repeat(15499)).getBytes(StandardCharsets.UTF_8).length + 6) < 65535);
        is("even with a 256-apostrophe name and a probe", (SettingsDb.writeScript("put", "secure", "'".repeat(0) + "k".repeat(256), "日".repeat(20000)).getBytes(StandardCharsets.UTF_8).length + 6) < 65535);
        is("quotedBytes counts UTF-8", SettingsDb.quotedBytes("日") == 5 && SettingsDb.quotedBytes("'") == 6 && SettingsDb.quotedBytes("") == 2);

        // ---- the same through a real sh: status, probe, refusals ----
        for (String f : new String[] {"global", "secure", "system"}) { Path dir = fakeDb.resolve(f); if (Files.exists(dir)) try (java.util.stream.Stream<Path> st = Files.list(dir)) { st.forEach(x -> x.toFile().delete()); } }
        String o1 = viaReader(runShell(SettingsDb.writeScript("put", "global", "fresh", "null")));
        wr = SettingsDb.parseWrite(o1, "fresh");
        eq("real sh: put null status", wr.rc, 0); eq("real sh: key listed", wr.present, Boolean.TRUE); is("real sh: put null verified", SettingsDb.judge("put", "null", wr).ok);
        String o2 = viaReader(runShell(SettingsDb.writeScript("put", "global", "refused_new", "null")));
        wr = SettingsDb.parseWrite(o2, "refused_new");
        is("real sh: refused put null has a failing status", wr.rc == 255); eq("real sh: refused put null: key not listed", wr.present, Boolean.FALSE);
        vd = SettingsDb.judge("put", "null", wr);
        is("real sh: refused put null is NOT ok", !vd.ok); is("real sh: with Android's words", vd.error.contains("Permission denial"));
        Files.write(fakeDb.resolve("global").resolve("refused_keep"), "null".getBytes(StandardCharsets.UTF_8));
        String o3 = viaReader(runShell(SettingsDb.writeScript("delete", "global", "refused_keep", null)));
        wr = SettingsDb.parseWrite(o3, "refused_keep");
        eq("real sh: refused delete: key still listed", wr.present, Boolean.TRUE);
        vd = SettingsDb.judge("delete", null, wr);
        is("real sh: refused delete of the text null is NOT ok", !vd.ok);
        String o4 = viaReader(runShell(SettingsDb.writeScript("delete", "global", "fresh", null)));
        wr = SettingsDb.parseWrite(o4, "fresh");
        is("real sh: a delete that took is ok", SettingsDb.judge("delete", null, wr).ok && wr.present == Boolean.FALSE && wr.deleted == 1);
        String o5 = viaReader(runShell(SettingsDb.writeScript("delete", "global", "never_there", null)));
        wr = SettingsDb.parseWrite(o5, "never_there");
        is("real sh: deleting what is not there is ok", SettingsDb.judge("delete", null, wr).ok);
        // a table whose last value ends with blank space survives the real listing
        runShell(SettingsDb.writeScript("put", "system", "zz_last", "tail\n\n  "));
        lr = SettingsDb.readList(viaReader(runShell(SettingsDb.listCommand("system"))));
        is("real sh: the listing is complete", lr.complete);
        String lastVal = null; for (String[] e : lr.entries) if (e[0].equals("zz_last")) lastVal = e[1];
        eq("real sh: the last value keeps its trailing blank space", lastVal, "tail\n\n  ");
        // a listing killed half way (the process is cut) is not complete
        String half = viaReader(runShell("settings list system | head -c 20"));
        is("real sh: a listing cut after 20 bytes is not complete", !SettingsDb.readList(half).complete);

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
