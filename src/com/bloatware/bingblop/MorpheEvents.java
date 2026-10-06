package com.bloatware.bingblop;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * What the Morphe engine says while it works, and how the app reads it back. The engine (engine/, run by MorpheService in a process of its own)
 * writes one line per event to a file; the page shows them as the patch log. The lines (see EngineMain.kt):
 *   LOG LEVEL text | STEP name state | APP package versionName versionCode | PATCH OK|FAIL name | RESULT json
 * A file (not a pipe) so a run survives the app being left and reopened: {@link Tail} just continues where it stopped.
 */
public final class MorpheEvents {
    private MorpheEvents() {}

    /** One protocol line as an event object ({t:"log"|"step"|"app"|"patch"|"result", ...}), or null when the line is not one. */
    public static JSONObject parse(String line) {
        if (line == null) return null;
        String l = line;
        if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
        try {
            if (l.startsWith("LOG ")) {
                String rest = l.substring(4);
                int sp = rest.indexOf(' ');
                String level = sp < 0 ? rest : rest.substring(0, sp);
                String text = sp < 0 ? "" : rest.substring(sp + 1);
                if (!level.equals("INFO") && !level.equals("WARN") && !level.equals("ERROR") && !level.equals("TRACE")) { level = "INFO"; text = rest; }
                return new JSONObject().put("t", "log").put("level", level).put("text", text);
            }
            if (l.startsWith("STEP ")) {
                String[] p = l.substring(5).split(" ", 2);
                if (p.length < 2) return null;
                return new JSONObject().put("t", "step").put("name", p[0]).put("state", p[1].trim());
            }
            if (l.startsWith("APP ")) {
                String rest = l.substring(4).trim();
                int first = rest.indexOf(' ');
                int last = rest.lastIndexOf(' ');
                if (first < 0 || last <= first) return null;
                return new JSONObject().put("t", "app").put("pkg", rest.substring(0, first))
                        .put("versionName", rest.substring(first + 1, last)).put("versionCode", rest.substring(last + 1));
            }
            if (l.startsWith("PATCH ")) {
                String rest = l.substring(6);
                int sp = rest.indexOf(' ');
                if (sp < 0) return null;
                String verdict = rest.substring(0, sp);
                if (!verdict.equals("OK") && !verdict.equals("FAIL")) return null;
                return new JSONObject().put("t", "patch").put("ok", verdict.equals("OK")).put("name", rest.substring(sp + 1));
            }
            if (l.startsWith("RESULT ")) {
                JSONObject r = new JSONObject(l.substring(7));
                r.put("t", "result");
                return r;
            }
        } catch (JSONException e) {
            return null;
        }
        return null;
    }

    /** Reads the new complete lines of a growing events file. A half-written last line waits for the next poll. */
    public static final class Tail {
        private final File file;
        private long offset;
        private boolean sawResult;
        private JSONObject result;

        public Tail(File file) { this.file = file; }

        public long offset() { return offset; }
        public boolean sawResult() { return sawResult; }
        public JSONObject result() { return result; }

        /** The events that arrived since the last poll (empty when there are none or the file does not exist yet). */
        public synchronized List<JSONObject> poll() throws IOException {
            List<JSONObject> out = new ArrayList<JSONObject>();
            if (!file.exists()) return out;
            long len = file.length();
            if (len < offset) offset = 0;                      // the file was started over
            if (len == offset) return out;
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                raf.seek(offset);
                byte[] buf = new byte[(int) Math.min(len - offset, 4 * 1024 * 1024)];
                int n = raf.read(buf);
                if (n <= 0) return out;
                int end = n;
                while (end > 0 && buf[end - 1] != '\n') end--;  // only whole lines
                if (end == 0) {
                    if (n >= buf.length) end = n;               // one enormous line: take it as it is
                    else return out;
                }
                String text = new String(buf, 0, end, Charset.forName("UTF-8"));
                offset += end;
                for (String line : text.split("\n")) {
                    if (line.isEmpty()) continue;
                    JSONObject e = parse(line);
                    if (e == null) continue;
                    if ("result".equals(e.optString("t"))) { sawResult = true; result = e; }
                    out.add(e);
                }
            } finally { raf.close(); }
            return out;
        }
    }
}
