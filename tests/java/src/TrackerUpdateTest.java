package com.bloatware.bingblop;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;

/** "Update the tracker list": the feed is checked and cut down before it can replace the list in use. A local server stands in for Exodus Privacy. */
public class TrackerUpdateTest {
    static int n = 0, fails = 0;
    static void check(String what, boolean ok, String extra) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + (extra == null ? "" : " :: " + extra)); } }
    static void check(String what, boolean ok) { check(what, ok, null); }

    static String feed(int count, boolean withSignatures) throws Exception {
        JSONObject trackers = new JSONObject();
        for (int i = 1; i <= count; i++) {
            trackers.put(String.valueOf(i), new JSONObject().put("id", i).put("name", "Tracker " + i).put("code_signature", withSignatures ? "com.tracker" + i + ".sdk.|net.t" + i + ".Agent" : "")
                    .put("network_signature", "t" + i + "\\.example\\.com").put("website", "https://t" + i + ".example.com").put("creation_date", "2020-01-01")
                    .put("categories", new org.json.JSONArray().put("Analytics")).put("description", "x").put("documentation", new org.json.JSONArray()));
        }
        return new JSONObject().put("trackers", trackers).toString();
    }

    static String body = "", mode = "ok";

    public static void main(String[] a) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/trackers", ex -> {
            if (mode.equals("500")) { ex.sendResponseHeaders(500, -1); ex.close(); return; }
            byte[] b = body.getBytes(Charset.forName("UTF-8"));
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            OutputStream o = ex.getResponseBody(); o.write(b); o.close();
        });
        srv.start();
        TrackerUpdate.apiUrl = "http://127.0.0.1:" + srv.getAddress().getPort() + "/api/trackers";
        try {
            body = feed(420, true); mode = "ok";
            String json = TrackerUpdate.fetch("2026-11-01");
            JSONObject o = new JSONObject(json);
            check("a good feed gives a list of the same size, dated", o.getJSONObject("trackers").length() == 420 && "2026-11-01".equals(o.getJSONObject("meta").getString("retrieved")) && o.getJSONObject("meta").getInt("count") == 420, o.getJSONObject("meta").toString());
            JSONObject t = o.getJSONObject("trackers").getJSONObject("7");
            check("only what the app uses is kept (no network signature, description, dates)", t.has("code_signature") && t.has("name") && t.has("categories") && t.has("website") && !t.has("network_signature") && !t.has("description") && !t.has("creation_date"), t.toString());
            check("the licence and the credit travel with the copy", o.getJSONObject("meta").getString("license").equals("ODbL-1.0") && o.getJSONObject("meta").getString("attribution").contains("exodus-privacy.eu.org"));
            Trackers tr = Trackers.load(json);
            check("the app can load it and finds a tracker by a class name in it", tr.db.n == 420 && tr.dbVersion().equals("2026-11-01:420"), tr.dbVersion());

            body = feed(100, true);
            try { TrackerUpdate.fetch("2026-11-01"); check("a feed with too few trackers is refused", false); } catch (java.io.IOException e) { check("a feed with too few trackers is refused, with the number in the message", e.getMessage().contains("100 trackers"), e.getMessage()); }
            body = feed(420, false);
            try { TrackerUpdate.fetch("2026-11-01"); check("a feed whose trackers have no code names is refused", false); } catch (java.io.IOException e) { check("a feed whose trackers have no code names is refused", e.getMessage().contains("not used"), e.getMessage()); }
            body = "<html>maintenance</html>";
            try { TrackerUpdate.fetch("2026-11-01"); check("a page that is not JSON is refused", false); } catch (java.io.IOException e) { check("a page that is not JSON is refused", e.getMessage().contains("not a tracker list"), e.getMessage()); }
            body = "{\"error\":\"nope\"}";
            try { TrackerUpdate.fetch("2026-11-01"); check("JSON without trackers is refused", false); } catch (java.io.IOException e) { check("JSON without trackers is refused", e.getMessage().contains("no list of trackers"), e.getMessage()); }
            mode = "500";
            try { TrackerUpdate.fetch("2026-11-01"); check("an HTTP error is reported with its number", false); } catch (java.io.IOException e) { check("an HTTP error is reported with its number", e.getMessage().contains("HTTP 500"), e.getMessage()); }
            TrackerUpdate.apiUrl = "http://127.0.0.1:1/api/trackers";
            try { TrackerUpdate.fetch("2026-11-01"); check("no connection is reported in words", false); } catch (java.io.IOException e) { check("no connection is reported in words", e.getMessage().startsWith("Could not reach Exodus Privacy"), e.getMessage()); }
        } finally {
            srv.stop(0);
        }
        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
