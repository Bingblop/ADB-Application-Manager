package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * GoogleFonts, the Google Fonts browser's engine (v7.18.0): the catalog cut down for the page, the css2 preview request and its answer,
 * a family's download plan with its licence and Reserved Font Names, a family's details as plain text, meta.json both ways, the sweep and
 * the preview cache. Everything is read from what Google Fonts really answered (tests/java/fixtures/fonts/googlefonts/, see its README):
 * the css2 addresses this class builds are the very ones those answers were fetched with. A seeded fuzz feeds every parser broken answers:
 * each ends in a GfException with one of the page's codes (or a null / an empty answer where that is the contract), never another throw.
 */
public class GoogleFontsTest {

    static int n = 0, fails = 0;
    static void check(String name, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + name); } }
    static void eq(String name, Object got, Object want) { n++; if (!java.util.Objects.equals(got, want)) { fails++; System.out.println("FAIL " + name + ": got <" + got + "> want <" + want + ">"); } }

    static File fix, tmp;

    public static void main(String[] a) throws Exception {
        String p = System.getProperty("fixtures");
        if (p == null) { System.out.println("FAIL GoogleFontsTest: run with -Dfixtures=tests/java/fixtures"); System.exit(1); }
        fix = new File(p, "fonts/googlefonts");
        tmp = Files.createTempDirectory("gf-").toFile();
        try {
            names();
            catalog();
            css2Requests();
            uniqueText();
            css();
            plans();
            reserved();
            details();
            plainText();
            meta();
            sweep();
            previewCache();
            bridgeParts();
            fuzz(20261026L, 1500);
        } finally {
            FontLibrary.deleteTree(tmp);
        }
        System.out.println((fails == 0 ? "PASS" : "FAIL") + " GoogleFontsTest: " + (n - fails) + "/" + n + " checks");
        if (fails != 0) System.exit(1);
    }

    static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(fix, rel).toPath()), StandardCharsets.UTF_8);
    }

    static String code(Throwing t) {
        try {
            t.run();
            return "none";
        } catch (GoogleFonts.GfException e) {
            return e.code;
        } catch (Throwable e) {
            return "threw " + e;
        }
    }
    interface Throwing { void run() throws Exception; }

    static Map<String, JSONObject> families() throws Exception {
        JSONArray fams = GoogleFonts.compact(read("catalog.json"), 1L).getJSONArray("families");
        Map<String, JSONObject> by = new HashMap<String, JSONObject>();
        for (int i = 0; i < fams.length(); i++) by.put(fams.getJSONObject(i).getString("f"), fams.getJSONObject(i));
        return by;
    }

    // ---------------------------------------------------------------------------------------------
    // Names and addresses
    // ---------------------------------------------------------------------------------------------

    static void names() {
        for (String ok : new String[] { "Roboto", "Open Sans", "Source Sans 3", "M PLUS 1p", "42dot Sans", "Noto Sans JP", "A" }) check("family " + ok, GoogleFonts.isFamily(ok));
        String[] bad = { null, "", " Roboto", "Roboto/x", "../Roboto", "Roboto\n", "Rob\u00f6to", "Roboto-Bold", "Roboto_Flex", "<b>x</b>", "Roboto%20Flex",
                "A" + repeat("b", 64) /* 65 */, "Roboto\u0000", "Roboto\u200b" };
        for (String b : bad) check("not a family: " + b, !GoogleFonts.isFamily(b));
        check("64 chars is a family", GoogleFonts.isFamily("A" + repeat("b", 63)));
        eq("slug", GoogleFonts.slug("Open Sans"), "open-sans");
        eq("slug digits", GoogleFonts.slug("Source Sans 3"), "source-sans-3");
        eq("slug case", GoogleFonts.slug("UnifrakturCook"), "unifrakturcook");

        for (String ok : new String[] { "Roboto-VariableFont_wdth,wght.ttf", "Lato-Regular.ttf", "SourceSans3-Italic-VariableFont_wght.ttf", "X.otf", "Font+1.ttf" }) check("file " + ok, GoogleFonts.isFileName(ok));
        for (String b : new String[] { null, "", ".ttf", ".hidden.ttf", "../x.ttf", "a/b.ttf", "a\\b.ttf", "x.TTF", "x.woff2", "x.ttf.exe", "x ttf.ttf", "%2e%2e.ttf", "x.ttf\u0000", "MaterialSymbols[FILL].ttf" })
            check("not a file name: " + b, !GoogleFonts.isFileName(b));

        eq("xssi", GoogleFonts.stripXssi(")]}'\n{\"a\":1}"), "{\"a\":1}");
        eq("xssi with blank line", GoogleFonts.stripXssi(")]}'\n\n[1]"), "\n[1]");
        eq("xssi with BOM", GoogleFonts.stripXssi("\ufeff)]}'\n{}"), "{}");
        eq("no xssi", GoogleFonts.stripXssi("{}"), "{}");
        eq("xssi alone", GoogleFonts.stripXssi(")]}'"), "");
        eq("xssi null", GoogleFonts.stripXssi(null), "");

        check("allowed gstatic", GoogleFonts.allowed("https://fonts.gstatic.com/s/roboto/v51/KFO.ttf"));
        check("allowed css2", GoogleFonts.allowed("https://fonts.googleapis.com/css2?family=Roboto&text=Ab&display=block"));
        check("allowed metadata", GoogleFonts.allowed("https://fonts.google.com/metadata/fonts"));
        check("allowed :443", GoogleFonts.allowed("https://fonts.gstatic.com:443/x"));
        for (String b : new String[] { null, "http://fonts.gstatic.com/x", "https://fonts.gstatic.com.evil.example/x", "https://evil.example/fonts.gstatic.com/",
                "https://user@fonts.gstatic.com/x", "https://fonts.gstatic.com:8443/x", "https://fonts.gstatic.com/a b", "https://fonts.gstatic.com/\"x",
                "https://fonts.gstatic.com/x'y", "https://fonts.gstatic.com/<x>", "file:///data/x.ttf", "javascript:alert(1)", "https://FONTS.GSTATIC.COM/x",
                "https://fonts.gstatic.com/\u00e9", "//fonts.gstatic.com/x", "https://fonts.gstatic.com\\@evil.example/" })
            check("not allowed: " + b, !GoogleFonts.allowed(b));
    }

    // ---------------------------------------------------------------------------------------------
    // The catalog
    // ---------------------------------------------------------------------------------------------

    static void catalog() throws Exception {
        String src = read("catalog.json");
        JSONObject c = GoogleFonts.compact(src, 1793052000000L);
        eq("v", c.getInt("v"), 1);
        eq("at", c.getLong("at"), 1793052000000L);
        eq("axes registry", c.getJSONObject("axes").length(), 56);
        eq("axis name wght", c.getJSONObject("axes").optString("wght"), "Weight");
        eq("axis name opsz", c.getJSONObject("axes").optString("opsz"), "Optical Size");
        eq("axis name GRAD", c.getJSONObject("axes").optString("GRAD"), "Grade");
        JSONArray fams = c.getJSONArray("families");
        eq("22 families", fams.length(), 22);
        // the source's order, kept
        JSONArray srcList = new JSONObject(src).getJSONArray("familyMetadataList");
        boolean same = true;
        for (int i = 0; i < fams.length(); i++) same &= fams.getJSONObject(i).getString("f").equals(srcList.getJSONObject(i).getString("family"));
        check("families in the source's order", same);

        Map<String, JSONObject> by = families();
        JSONObject r = by.get("Roboto");
        eq("Roboto c", r.getString("c"), "sans");
        eq("Roboto d", r.getJSONArray("d").toString(), "[\"Christian Robertson\",\"ParaType\",\"Font Bureau\"]");
        eq("Roboto p", r.getInt("p"), 2);
        eq("Roboto t", r.getInt("t"), 690);
        eq("Roboto a", r.getString("a"), "2013-01-08");
        eq("Roboto m", r.getString("m"), "2026-02-19");
        eq("Roboto s (no menu)", r.getJSONArray("s").toString(), "[\"cyrillic\",\"cyrillic-ext\",\"greek\",\"greek-ext\",\"latin\",\"latin-ext\",\"math\",\"symbols\",\"vietnamese\"]");
        eq("Roboto w", r.getJSONArray("w").toString(), "[\"100\",\"100i\",\"200\",\"200i\",\"300\",\"300i\",\"400\",\"400i\",\"500\",\"500i\",\"600\",\"600i\",\"700\",\"700i\",\"800\",\"800i\",\"900\",\"900i\"]");
        eq("Roboto x", r.getJSONArray("x").toString(), "[[\"wdth\",75,100,100],[\"wght\",100,900,400]]");
        eq("Roboto k", r.getString("k"), "");
        check("Roboto no flags", !r.has("col") && !r.has("sym") && !r.has("noto"));
        eq("Roboto keys", new java.util.TreeSet<String>(keys(r)).toString(), "[a, c, d, f, k, m, p, s, t, w, x]");

        // styles: weight ascending, upright first; the source lists Sofia Sans' keys as strings sort ("1", "100", "1000", "1000i", ...)
        eq("Sofia Sans w", by.get("Sofia Sans").getJSONArray("w").toString(),
                "[\"1\",\"1i\",\"100\",\"100i\",\"200\",\"200i\",\"300\",\"300i\",\"400\",\"400i\",\"500\",\"500i\",\"600\",\"600i\",\"700\",\"700i\",\"800\",\"800i\",\"900\",\"900i\",\"1000\",\"1000i\"]");
        eq("Roboto Flex w", by.get("Roboto Flex").getJSONArray("w").toString(), "[\"100\",\"200\",\"300\",\"400\",\"500\",\"600\",\"700\",\"800\",\"900\",\"1000\"]");
        eq("Molle (italic only)", by.get("Molle").getJSONArray("w").toString(), "[\"400i\"]");
        eq("UnifrakturCook (700 only)", by.get("UnifrakturCook").getJSONArray("w").toString(), "[\"700\"]");
        eq("Buda (300 only)", by.get("Buda").getJSONArray("w").toString(), "[\"300\"]");
        // axes as given (13 for Roboto Flex, negative values, a 0..1 axis)
        eq("Roboto Flex axes", by.get("Roboto Flex").getJSONArray("x").length(), 13);
        eq("Roboto Flex slnt", by.get("Roboto Flex").getJSONArray("x").getJSONArray(10).toString(), "[\"slnt\",-10,0,0]");
        eq("Fraunces WONK", by.get("Fraunces").getJSONArray("x").getJSONArray(1).toString(), "[\"WONK\",0,1,0]");
        // categories
        eq("serif", by.get("Merriweather").getString("c"), "serif");
        eq("display", by.get("Buda").getString("c"), "display");
        eq("hand", by.get("Caveat").getString("c"), "hand");
        eq("mono", by.get("Roboto Mono").getString("c"), "mono");
        // flags and scripts
        eq("Bungee Spice col", by.get("Bungee Spice").optInt("col"), 1);
        eq("Noto Color Emoji col", by.get("Noto Color Emoji").optInt("col"), 1);
        eq("Noto Color Emoji noto", by.get("Noto Color Emoji").optInt("noto"), 1);
        eq("Noto Color Emoji s", by.get("Noto Color Emoji").getJSONArray("s").toString(), "[\"emoji\"]");
        eq("Libre Barcode sym", by.get("Libre Barcode 128").optInt("sym"), 1);
        eq("Noto Sans JP k", by.get("Noto Sans JP").getString("k"), "Jpan");
        eq("Sunflower k", by.get("Sunflower").getString("k"), "Hang");
        eq("Lato designer", by.get("Lato").getJSONArray("d").getString(0), "\u0141ukasz Dziedzic");
        eq("UnifrakturCook designer", by.get("UnifrakturCook").getJSONArray("d").getString(0), "j. 'mach' wust");

        // what is not taken from an answer
        JSONObject hostile = new JSONObject(src);
        JSONArray list = hostile.getJSONArray("familyMetadataList");
        JSONObject lato = new JSONObject(list.getJSONObject(6).toString());
        eq("fixture order (Lato is 7th)", lato.getString("family"), "Lato");
        list.put(new JSONObject(lato.toString()).put("family", "../evil"));
        list.put(new JSONObject(lato.toString()).put("family", "<img src=x onerror=alert(1)>"));
        list.put(new JSONObject(lato.toString()).put("family", 42));
        list.put(new JSONObject(lato.toString()).put("family", "Lato").put("popularity", 1));   // a second Lato: the first wins
        JSONObject odd = new JSONObject(lato.toString()).put("family", "Odd Family")
                .put("designers", new JSONArray(Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "line\nbreak", repeat("x", 100))))
                .put("subsets", new JSONArray(Arrays.asList("menu", "latin", "LATIN", "latin", "../x", 5)))
                .put("fonts", new JSONObject().put("400", 1).put("abc", 1).put("0", 1).put("1001", 1).put("400x", 1).put("700i", 1).put("00700i", 1))
                .put("axes", new JSONArray().put(new JSONObject().put("tag", "wght").put("min", 100).put("max", 900).put("defaultValue", 400))
                        .put(new JSONObject().put("tag", "wght").put("min", 1).put("max", 2).put("defaultValue", 1))
                        .put(new JSONObject().put("tag", "CRSV").put("min", 0).put("max", 1).put("defaultValue", 0.5))
                        .put(new JSONObject().put("tag", "bad!").put("min", 0).put("max", 1).put("defaultValue", 0))
                        .put(new JSONObject().put("tag", "slnt").put("min", "x").put("max", 1).put("defaultValue", 0))
                        .put(new JSONObject().put("tag", "BIGG").put("min", 0).put("max", 1e12).put("defaultValue", 0)))
                .put("popularity", -3).put("trending", "high").put("dateAdded", "yesterday").put("primaryScript", "jpan")
                .put("colorCapabilities", new JSONArray()).put("classifications", new JSONArray(Arrays.asList("Symbols"))).put("isNoto", "yes");
        list.put(odd);
        c = GoogleFonts.compact(")]}'\n" + hostile.toString(), 2L);
        fams = c.getJSONArray("families");
        eq("hostile names left out, the second Lato too", fams.length(), 23);
        JSONObject o = fams.getJSONObject(22);
        eq("odd f", o.getString("f"), "Odd Family");
        eq("odd designers: 8, one line, 80 chars", o.getJSONArray("d").toString(), "[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\",\"G\",\"H\"]");
        eq("odd subsets", o.getJSONArray("s").toString(), "[\"latin\"]");
        eq("odd styles", o.getJSONArray("w").toString(), "[\"400\",\"700i\"]");
        eq("odd axes", o.getJSONArray("x").toString(), "[[\"wght\",100,900,400],[\"CRSV\",0,1,0.5]]");
        eq("odd p", o.getInt("p"), 0);
        eq("odd t", o.getInt("t"), 0);
        eq("odd a", o.getString("a"), "");
        eq("odd k", o.getString("k"), "");
        check("odd no col (empty), sym, no noto (not true)", !o.has("col") && o.optInt("sym") == 1 && !o.has("noto"));
        eq("first Lato kept", fams.getJSONObject(6).getInt("p"), 10);
        JSONObject longD = new JSONObject(lato.toString()).put("family", "Long D").put("designers", new JSONArray(Arrays.asList("line\nbreak\ttab", repeat("y", 100))));
        JSONObject h2 = new JSONObject().put("familyMetadataList", new JSONArray().put(longD));
        JSONObject ld = GoogleFonts.compact(h2.toString(), 0).getJSONArray("families").getJSONObject(0);
        eq("designer on one line", ld.getJSONArray("d").getString(0), "line break tab");
        eq("designer cut to 80", ld.getJSONArray("d").getString(1).length(), 80);
        eq("no axisRegistry: no axes", GoogleFonts.compact(h2.toString(), 0).getJSONObject("axes").length(), 0);

        eq("not JSON", code(() -> GoogleFonts.compact("<html>", 0)), "gf_bad_answer");
        eq("no list", code(() -> GoogleFonts.compact("{\"axisRegistry\":[]}", 0)), "gf_bad_answer");
        eq("list not an array", code(() -> GoogleFonts.compact("{\"familyMetadataList\":{}}", 0)), "gf_bad_answer");
        eq("null", code(() -> GoogleFonts.compact(null, 0)), "gf_bad_answer");
        eq("empty list", code(() -> GoogleFonts.compact("{\"familyMetadataList\":[]}", 0)), "none");
        eq("bad_answer sentence", new GoogleFonts.GfException("gf_bad_answer").getMessage(), "Google Fonts sent an answer the app cannot read. Try again later.");
    }

    /** A JSON value as text with every object's keys sorted, so two answers compare whatever order a JSONObject keeps. */
    static String canon(Object v) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            List<String> ks = keys(o);
            java.util.Collections.sort(ks);
            StringBuilder b = new StringBuilder("{");
            for (String k : ks) b.append(b.length() > 1 ? "," : "").append(JSONObject.quote(k)).append(':').append(canon(o.opt(k)));
            return b.append('}').toString();
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) b.append(i > 0 ? "," : "").append(canon(a.opt(i)));
            return b.append(']').toString();
        }
        return v instanceof String ? JSONObject.quote((String) v) : String.valueOf(v);
    }

    static List<String> keys(JSONObject o) {
        List<String> k = new ArrayList<String>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) k.add(it.next());
        return k;
    }

    static String repeat(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }

    // ---------------------------------------------------------------------------------------------
    // css2 requests
    // ---------------------------------------------------------------------------------------------

    static void css2Requests() throws Exception {
        Map<String, JSONObject> by = families();
        // each captured answer was fetched with the address css2Url builds (requests.tsv: file, family, mode, text, address)
        int lines = 0;
        for (String line : read("css2/requests.tsv").split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            lines++;
            eq("css2 address for " + f[0], GoogleFonts.css2Url(by.get(f[1]), f[2], f[3]), f[4]);
        }
        eq("eleven captured requests", lines, 11);

        // axes for a family without axes is card; card with 400 is the plain family
        eq("Lato axes = card", GoogleFonts.css2Url(by.get("Lato"), "axes", "Ab"), GoogleFonts.css2Url(by.get("Lato"), "card", "Ab"));
        eq("Lato card", GoogleFonts.css2Url(by.get("Lato"), "card", "Ab"), "https://fonts.googleapis.com/css2?family=Lato&text=Ab&display=block");
        eq("Buda card (300 only)", GoogleFonts.css2Url(by.get("Buda"), "card", "Ab"), "https://fonts.googleapis.com/css2?family=Buda:wght@300&text=Ab&display=block");
        eq("Sunflower card (no 400)", GoogleFonts.css2Url(by.get("Sunflower"), "card", "Ab"), "https://fonts.googleapis.com/css2?family=Sunflower:wght@300&text=Ab&display=block");
        eq("Caveat styles (upright only)", GoogleFonts.css2Url(by.get("Caveat"), "styles", "Ab"), "https://fonts.googleapis.com/css2?family=Caveat:wght@400;500;600;700&text=Ab&display=block");
        eq("Molle styles", GoogleFonts.css2Url(by.get("Molle"), "styles", "Ab"), "https://fonts.googleapis.com/css2?family=Molle:ital,wght@1,400&text=Ab&display=block");
        eq("Merriweather axes order", GoogleFonts.css2Url(by.get("Merriweather"), "axes", "Ab"),
                "https://fonts.googleapis.com/css2?family=Merriweather:opsz,wdth,wght@18..144,87..112,300..900&text=Ab&display=block");
        String sofia = GoogleFonts.css2Url(by.get("Sofia Sans"), "styles", "Ab");
        eq("styles at most 18", sofia.substring(sofia.indexOf('@') + 1, sofia.indexOf('&')).split(";").length, 18);
        // an axis whose range is one value; ital first, lowercase, then uppercase
        JSONObject e = new JSONObject().put("f", "Test Family").put("w", new JSONArray()).put("x", new JSONArray()
                .put(new JSONArray().put("YTUC").put(500).put(500).put(500)).put(new JSONArray().put("wght").put(100).put(900).put(400))
                .put(new JSONArray().put("ital").put(0).put(1).put(0)).put(new JSONArray().put("CRSV").put(0).put(1).put(0.5))
                .put(new JSONArray().put("opsz").put(6.5).put(72).put(14)).put(new JSONArray().put("GRAD").put(150).put(-200).put(0)));
        eq("axis order and values", GoogleFonts.css2Url(e, "axes", "a"),
                "https://fonts.googleapis.com/css2?family=Test+Family:ital,opsz,wght,CRSV,GRAD,YTUC@0..1,6.5..72,100..900,0..1,-200..150,500&text=a&display=block");
        eq("no styles: card is the family", GoogleFonts.css2Url(e, "card", "a"), "https://fonts.googleapis.com/css2?family=Test+Family&text=a&display=block");
        eq("no styles: styles is the family", GoogleFonts.css2Url(e, "styles", "a"), "https://fonts.googleapis.com/css2?family=Test+Family&text=a&display=block");
        // what text= can carry
        eq("text is percent-encoded", GoogleFonts.css2Url(by.get("Lato"), "card", "a&b=c#d+e%f/?"),
                "https://fonts.googleapis.com/css2?family=Lato&text=a%26b%3Dc%23d%2Be%25f%2F%3F&display=block");
        check("every address is allowed", GoogleFonts.allowed(GoogleFonts.css2Url(by.get("Noto Sans JP"), "card", "\u79c1\u306f \ud83d\ude00 <>\"'\\")));

        for (String[] bad : new String[][] { { "Lato", "bold" }, { "Lato", null }, { "Lato", "" } }) {
            boolean threw = false;
            try { GoogleFonts.css2Url(by.get(bad[0]), bad[1], "a"); } catch (IllegalArgumentException x) { threw = true; }
            check("bad mode refused: " + bad[1], threw);
        }
        boolean threw = false;
        try { GoogleFonts.css2Url(new JSONObject().put("f", "../x"), "card", "a"); } catch (IllegalArgumentException x) { threw = true; }
        check("bad family refused", threw);
        threw = false;
        try { GoogleFonts.css2Url(null, "card", "a"); } catch (IllegalArgumentException x) { threw = true; }
        check("no entry refused", threw);
    }

    static void uniqueText() {
        eq("dedupe in order", GoogleFonts.uniqueText("banana bread", "X"), "ban red");
        eq("controls dropped", GoogleFonts.uniqueText("a\u0000b\tc\nd\u007fe\u0085f\u009fg", "X"), "abcdefg");
        eq("emoji is one code point", GoogleFonts.uniqueText("\ud83d\ude00\ud83d\ude00a", "X"), "\ud83d\ude00a");
        eq("lone surrogates dropped", GoogleFonts.uniqueText("a\ud83db\ude00c", "X"), "abc");
        eq("empty: the fallback and Aa", GoogleFonts.uniqueText("", "Roboto"), "Robt Aa");
        eq("only controls: the fallback", GoogleFonts.uniqueText("\n\t", "Lato"), "Lato A");
        eq("null text", GoogleFonts.uniqueText(null, "Molle"), "Mole Aa");
        eq("null fallback", GoogleFonts.uniqueText(null, null), " Aa");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 400; i++) b.appendCodePoint(0x4e00 + i);
        String u = GoogleFonts.uniqueText(b.toString() + b, "X");
        eq("at most 300 code points", u.codePointCount(0, u.length()), 300);
        eq("the first 300", u, b.substring(0, 300));
        StringBuilder e = new StringBuilder();
        for (int i = 0; i < 400; i++) e.appendCodePoint(0x1f300 + i);
        String ue = GoogleFonts.uniqueText(e.toString(), "X");
        eq("300 code points beyond the BMP", ue.codePointCount(0, ue.length()), 300);
    }

    // ---------------------------------------------------------------------------------------------
    // css2 answers
    // ---------------------------------------------------------------------------------------------

    static String faces(List<GoogleFonts.Face> fs) {
        StringBuilder b = new StringBuilder();
        for (GoogleFonts.Face f : fs) b.append(f.style).append('/').append(f.weight).append('/').append(f.stretch).append(' ');
        return b.toString().trim();
    }

    static void css() throws Exception {
        eq("Roboto card", faces(GoogleFonts.parseCss(read("css2/roboto_card.css"))), "normal/400/100%");
        eq("Roboto axes", faces(GoogleFonts.parseCss(read("css2/roboto_axes.css"))), "normal/100 900/75% 100%");
        eq("Inter axes", faces(GoogleFonts.parseCss(read("css2/inter_axes.css"))), "normal/100 900/");
        eq("Fraunces axes", faces(GoogleFonts.parseCss(read("css2/fraunces_axes.css"))), "normal/100 900/");
        // css2 writes a slnt range as "oblique 0deg 10deg": the face is the upright one too
        eq("Roboto Flex axes", faces(GoogleFonts.parseCss(read("css2/robotoflex_axes.css"))), "normal/100 1000/25% 151%");
        eq("Molle card", faces(GoogleFonts.parseCss(read("css2/molle_card.css"))), "italic/400/");
        eq("UnifrakturCook card", faces(GoogleFonts.parseCss(read("css2/unifrakturcook_card.css"))), "normal/700/");
        eq("Noto Sans JP card", faces(GoogleFonts.parseCss(read("css2/notosansjp_card.css"))), "normal/400/");
        eq("Noto Color Emoji card", faces(GoogleFonts.parseCss(read("css2/notocoloremoji_card.css"))), "normal/400/");
        eq("Lato styles", faces(GoogleFonts.parseCss(read("css2/lato_styles.css"))),
                "italic/100/ italic/300/ italic/400/ italic/700/ italic/900/ normal/100/ normal/300/ normal/400/ normal/700/ normal/900/");
        List<GoogleFonts.Face> sofia = GoogleFonts.parseCss(read("css2/sofiasans_styles.css"));
        eq("Sofia Sans styles", sofia.size(), 18);
        eq("Sofia Sans weight 1", sofia.get(0).weight, "1");
        for (String f : new String[] { "roboto_card", "lato_styles", "sofiasans_styles", "notosansjp_card" })
            for (GoogleFonts.Face x : GoogleFonts.parseCss(read("css2/" + f + ".css"))) check(f + " face on gstatic", x.url.startsWith("https://fonts.gstatic.com/l/font?kit=") && GoogleFonts.allowed(x.url));
        eq("Roboto card url", GoogleFonts.parseCss(read("css2/roboto_card.css")).get(0).url, urlIn(read("css2/roboto_card.css")));

        // a source anywhere but fonts.gstatic.com is left out
        String lato = read("css2/lato_styles.css");
        String first = urlIn(lato);
        String moved = lato.replace(first, first.replace("https://fonts.gstatic.com/", "https://evil.example/"));
        eq("evil host dropped", GoogleFonts.parseCss(moved).size(), 9);
        eq("http dropped", GoogleFonts.parseCss(lato.replace(first, first.replace("https://", "http://"))).size(), 9);
        eq("lookalike host dropped", GoogleFonts.parseCss(lato.replace(first, first.replace("fonts.gstatic.com/", "fonts.gstatic.com.evil.example/"))).size(), 9);
        eq("quoted url taken", GoogleFonts.parseCss("@font-face { font-style: normal; src: url('https://fonts.gstatic.com/a.woff2') format('woff2'); }").get(0).url, "https://fonts.gstatic.com/a.woff2");
        eq("second src taken when the first is elsewhere", GoogleFonts.parseCss("@font-face { src: url(https://evil.example/a.woff2), url(https://fonts.gstatic.com/b.woff2); }").get(0).url, "https://fonts.gstatic.com/b.woff2");
        eq("local() ignored", GoogleFonts.parseCss("@font-face { src: local(Roboto), url(https://fonts.gstatic.com/b.woff2); }").get(0).url, "https://fonts.gstatic.com/b.woff2");
        eq("odd weight and stretch", faces(GoogleFonts.parseCss("@font-face { font-style: bold; font-weight: bolder; font-stretch: condensed; src: url(https://fonts.gstatic.com/b.woff2); }")), "normal/400/");
        eq("oblique is italic", faces(GoogleFonts.parseCss("@font-face { font-style: oblique; src: url(https://fonts.gstatic.com/b.woff2); }")), "italic/400/");
        eq("oblique 10deg 20deg is italic", faces(GoogleFonts.parseCss("@font-face { font-style: oblique 10deg 20deg; src: url(https://fonts.gstatic.com/b.woff2); }")), "italic/400/");
        eq("oblique -10deg 0deg is normal", faces(GoogleFonts.parseCss("@font-face { font-style: oblique -10deg 0deg; src: url(https://fonts.gstatic.com/b.woff2); }")), "normal/400/");
        eq("url with a quote in it dropped", code(() -> GoogleFonts.parseCss("@font-face { src: url(https://fonts.gstatic.com/a\"onload=x.woff2); }")), "gf_no_preview");
        eq("empty", code(() -> GoogleFonts.parseCss("")), "gf_no_preview");
        eq("null", code(() -> GoogleFonts.parseCss(null)), "gf_no_preview");
        eq("an error page", code(() -> GoogleFonts.parseCss("<html><body>400. That's an error.</body></html>")), "gf_no_preview");
        eq("only elsewhere", code(() -> GoogleFonts.parseCss("@font-face { src: url(https://evil.example/a.woff2); }")), "gf_no_preview");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 100; i++) many.append("@font-face { font-weight: ").append(100 + i).append("; src: url(https://fonts.gstatic.com/").append(i).append(".woff2); }\n");
        eq("at most 64 faces", GoogleFonts.parseCss(many.toString()).size(), 64);
        eq("no_preview sentence", new GoogleFonts.GfException("gf_no_preview").getMessage(), "Google Fonts sent no preview for this family.");
    }

    static String urlIn(String css) {
        int a = css.indexOf("url(") + 4;
        return css.substring(a, css.indexOf(')', a));
    }

    // ---------------------------------------------------------------------------------------------
    // Download plans
    // ---------------------------------------------------------------------------------------------

    static String names(GoogleFonts.Plan p) {
        StringBuilder b = new StringBuilder();
        for (GoogleFonts.FileRef f : p.files) b.append(f.name).append(f.variable ? "[v]" : "").append(f.italic ? "[i]" : "").append(' ');
        return b.toString().trim();
    }

    static void plans() throws Exception {
        GoogleFonts.Plan p = GoogleFonts.plan(read("list/roboto.json"));
        eq("Roboto: the variable fonts", names(p), "Roboto-VariableFont_wdth,wght.ttf[v] Roboto-Italic-VariableFont_wdth,wght.ttf[v][i]");
        eq("Roboto licence", p.licenseFile + " " + p.licenseKind, "OFL.txt ofl");
        check("Roboto licence text", p.licenseText.startsWith("Copyright 2011 The Roboto Project Authors") && p.licenseText.contains("SIL OPEN FONT LICENSE Version 1.1"));
        eq("Roboto reserved", p.reserved.toString(), "[]");
        for (GoogleFonts.FileRef f : p.files) check("Roboto url " + f.name, f.url.startsWith("https://fonts.gstatic.com/s/roboto/") && GoogleFonts.allowed(f.url));
        eq("Inter", names(GoogleFonts.plan(read("list/inter.json"))), "Inter-VariableFont_opsz,wght.ttf[v] Inter-Italic-VariableFont_opsz,wght.ttf[v][i]");
        eq("Open Sans", names(GoogleFonts.plan(read("list/open-sans.json"))), "OpenSans-VariableFont_wdth,wght.ttf[v] OpenSans-Italic-VariableFont_wdth,wght.ttf[v][i]");
        p = GoogleFonts.plan(read("list/lato.json"));
        eq("Lato: the static fonts", names(p), "Lato-Thin.ttf Lato-ThinItalic.ttf[i] Lato-Light.ttf Lato-LightItalic.ttf[i] Lato-Regular.ttf Lato-Italic.ttf[i] Lato-Bold.ttf Lato-BoldItalic.ttf[i] Lato-Black.ttf Lato-BlackItalic.ttf[i]");
        eq("Lato reserved", p.reserved.toString(), "[Lato]");
        eq("Bungee Spice", names(GoogleFonts.plan(read("list/bungee-spice.json"))), "BungeeSpice-Regular.ttf");
        eq("Noto Color Emoji", names(GoogleFonts.plan(read("list/noto-color-emoji.json"))), "NotoColorEmoji-Regular.ttf");
        eq("Molle", names(GoogleFonts.plan(read("list/molle.json"))), "Molle-Italic.ttf[i]");
        p = GoogleFonts.plan(read("list/ubuntu.json"));
        eq("Ubuntu files", p.files.size(), 8);
        eq("Ubuntu licence", p.licenseFile + " " + p.licenseKind, "UFL.txt ufl");
        p = GoogleFonts.plan(read("list/permanent-marker.json"));
        eq("Permanent Marker licence", p.licenseFile + " " + p.licenseKind + " " + p.reserved, "LICENSE.txt apache2 []");
        p = GoogleFonts.plan(read("list/material-symbols-outlined.json"));
        eq("Material Symbols", names(p) + " " + p.licenseKind, "MaterialSymbolsOutlined-VariableFont_FILL,GRAD,opsz,wght.ttf[v] apache2");
        eq("unknown family", code(() -> GoogleFonts.plan(read("list/nonexistent-family-x.json"))), "gf_unknown");
        eq("unknown sentence", new GoogleFonts.GfException("gf_unknown").getMessage(), "This family is not in the Google Fonts list. Update the list and try again.");

        // names and addresses that are not kept
        JSONObject lato = new JSONObject(GoogleFonts.stripXssi(read("list/lato.json")));
        JSONArray refs = lato.getJSONObject("manifest").getJSONArray("fileRefs");
        String url0 = refs.getJSONObject(0).getString("url");
        refs.put(ref("../x.ttf", url0)).put(ref("a/b/../c.otf", url0)).put(ref("Lato-Regular.ttf", url0 + "?dup"))
                .put(ref("evil.ttf", "https://evil.example/evil.ttf")).put(ref("http.ttf", url0.replace("https://", "http://")))
                .put(ref(".hidden.ttf", url0)).put(ref("x.woff2", url0)).put(ref("a\\b.ttf", url0)).put(new JSONObject().put("filename", 5).put("url", url0))
                .put(new JSONObject().put("filename", "nourl.ttf")).put("not an object");
        p = GoogleFonts.plan(lato.toString());
        eq("Lato with hostile refs: the same ten", names(p), names(GoogleFonts.plan(read("list/lato.json"))));
        eq("first of a duplicate wins", p.files.get(4).url, refs.getJSONObject(4).getString("url"));
        // everything under a folder: the names after the last "/"
        JSONObject nested = new JSONObject().put("manifest", new JSONObject().put("files", new JSONArray()).put("fileRefs", new JSONArray()
                .put(ref("../x.ttf", url0)).put(ref("a/b/../c.otf", url0)).put(ref("static/x.ttf", url0)).put(ref("..", url0)).put(ref("a/", url0))));
        p = GoogleFonts.plan(nested.toString());
        eq("nested names", names(p), "x.ttf c.otf");
        for (GoogleFonts.FileRef f : p.files) check("no path in " + f.name, f.name.indexOf('/') < 0 && f.name.indexOf('\\') < 0 && !f.name.startsWith("."));
        eq("no licence", p.licenseFile + "|" + p.licenseKind + "|" + p.licenseText + "|" + p.reserved, "|||[]");
        // static fonts are taken only when the top level has none
        JSONObject statics = new JSONObject(GoogleFonts.stripXssi(read("list/roboto.json")));
        JSONArray sr = statics.getJSONObject("manifest").getJSONArray("fileRefs"), only = new JSONArray();
        for (int i = 0; i < sr.length(); i++) if (sr.getJSONObject(i).getString("filename").startsWith("static/")) only.put(sr.getJSONObject(i));
        statics.getJSONObject("manifest").put("fileRefs", only);
        p = GoogleFonts.plan(statics.toString());
        eq("Roboto statics only", p.files.size(), 54);
        eq("too many", code(() -> GoogleFonts.plan(listOf(65, url0))), "gf_too_big");
        eq("64 is fine", code(() -> GoogleFonts.plan(listOf(64, url0))), "none");
        eq("too_big sentence", new GoogleFonts.GfException("gf_too_big").getMessage(), "This family is larger than the app downloads (160 MB or 64 files).");
        eq("no fonts", code(() -> GoogleFonts.plan(listOf(0, url0))), "gf_no_files");
        eq("only refused names", code(() -> GoogleFonts.plan(new JSONObject().put("manifest", new JSONObject().put("fileRefs", new JSONArray().put(ref("a.woff", url0)))).toString())), "gf_no_files");
        eq("no_files sentence", new GoogleFonts.GfException("gf_no_files").getMessage(), "Google Fonts lists no font files for this family.");
        eq("not JSON", code(() -> GoogleFonts.plan("<html>")), "gf_bad_answer");
        eq("other error", code(() -> GoogleFonts.plan("{\"error\":\"Quota exceeded\"}")), "gf_bad_answer");
        eq("no manifest", code(() -> GoogleFonts.plan("{\"zipName\":\"x.zip\"}")), "gf_bad_answer");
        eq("fileRefs not a list", code(() -> GoogleFonts.plan("{\"manifest\":{\"fileRefs\":{}}}")), "gf_bad_answer");
        eq("null", code(() -> GoogleFonts.plan(null)), "gf_bad_answer");
        // the licence: the first licence file of the list, its text at most 64 KB
        JSONObject big = new JSONObject(GoogleFonts.stripXssi(read("list/lato.json")));
        JSONArray files = big.getJSONObject("manifest").getJSONArray("files");
        String ofl = files.getJSONObject(0).getString("contents");
        files.getJSONObject(0).put("contents", ofl + repeat("x", 70000));
        files.put(new JSONObject().put("filename", "LICENSE.txt").put("contents", "Apache"));
        p = GoogleFonts.plan(big.toString());
        eq("licence capped", p.licenseText.length(), 65536);
        eq("first licence file wins", p.licenseFile, "OFL.txt");
        JSONObject readmeFirst = new JSONObject(GoogleFonts.stripXssi(read("list/inter.json")));
        JSONArray inl = readmeFirst.getJSONObject("manifest").getJSONArray("files");
        eq("Inter lists OFL.txt, then README.txt", inl.getJSONObject(0).getString("filename") + " " + inl.getJSONObject(1).getString("filename"), "OFL.txt README.txt");
        readmeFirst.getJSONObject("manifest").put("files", new JSONArray().put(inl.getJSONObject(1)).put(inl.getJSONObject(0)));
        eq("README is not a licence", GoogleFonts.plan(readmeFirst.toString()).licenseFile, "OFL.txt");
    }

    static JSONObject ref(String name, String url) throws Exception { return new JSONObject().put("filename", name).put("url", url).put("date", "2025-01-01"); }

    static String listOf(int count, String url) throws Exception {
        JSONArray refs = new JSONArray();
        for (int i = 0; i < count; i++) refs.put(ref("Font-" + i + ".ttf", url));
        return ")]}'\n" + new JSONObject().put("manifest", new JSONObject().put("files", new JSONArray()).put("fileRefs", refs));
    }

    // ---------------------------------------------------------------------------------------------
    // Reserved Font Names
    // ---------------------------------------------------------------------------------------------

    static String licence(String list) throws Exception {
        return GoogleFonts.plan(read("list/" + list + ".json")).licenseText;
    }

    static void reserved() throws Exception {
        eq("Lato", GoogleFonts.reservedNames(licence("lato")).toString(), "[Lato]");
        eq("Andika and SIL", GoogleFonts.reservedNames(licence("andika")).toString(), "[Andika, SIL]");
        eq("Charis and SIL", GoogleFonts.reservedNames(licence("charis-sil")).toString(), "[Charis, SIL]");
        eq("Gentium and SIL", GoogleFonts.reservedNames(licence("gentium-book-plus")).toString(), "[Gentium, SIL]");
        eq("Molle", GoogleFonts.reservedNames(licence("molle")).toString(), "[Molle]");
        eq("Source, single quotes", GoogleFonts.reservedNames(licence("source-sans-3")).toString(), "[Source]");
        eq("UnifrakturCook, no quotes", GoogleFonts.reservedNames(licence("unifrakturcook")).toString(), "[UnifrakturCook]");
        eq("Roboto, none", GoogleFonts.reservedNames(licence("roboto")).toString(), "[]");
        eq("Inter, none", GoogleFonts.reservedNames(licence("inter")).toString(), "[]");
        eq("Apache text, none", GoogleFonts.reservedNames(licence("permanent-marker")).toString(), "[]");
        eq("Ubuntu Font Licence, none", GoogleFonts.reservedNames(licence("ubuntu")).toString(), "[]");
        // how other live headers write it (R1-01): a colon, a comma or "is" after the phrase, a straight quote closed by a curly one, a name
        // over a line break, <...>, a period inside the quotes, and the name before the phrase
        eq("Orbitron, a colon", GoogleFonts.reservedNames(licence("orbitron")).toString(), "[Orbitron]");
        eq("Chango, a comma", GoogleFonts.reservedNames(licence("chango")).toString(), "[Chango]");
        eq("Julee, is", GoogleFonts.reservedNames(licence("julee")).toString(), "[Julee]");
        eq("Encode Sans, \" closed by a curly quote", GoogleFonts.reservedNames(licence("encode-sans")).toString(), "[Encode Sans]");
        eq("Homenaje, ' closed by a curly quote", GoogleFonts.reservedNames(licence("homenaje")).toString(), "[Homenaje]");
        eq("Stint Ultra Expanded, over a line break", GoogleFonts.reservedNames(licence("stint-ultra-expanded")).toString(), "[Stint Ultra Expanded]");
        eq("GFS Didot, <...>", GoogleFonts.reservedNames(licence("gfs-didot")).toString(), "[GFS Didot]");
        eq("Numans, no trailing period", GoogleFonts.reservedNames(licence("numans")).toString(), "[Numans]");
        eq("Jomolhari, the name before the phrase", GoogleFonts.reservedNames(licence("jomolhari")).toString(), "[Jomolhari]");
        check("…those are the headers as sent", licence("orbitron").contains("Reserved Font Name: \"Orbitron\"") && licence("chango").contains("Reserved Font Names, \"Chango\"")
                && licence("julee").contains("Reserved Font Name is \"Julee\"") && licence("encode-sans").contains("\"Encode Sans\u201d.")
                && licence("homenaje").contains("'Homenaje\u2019.") && licence("stint-ultra-expanded").contains("\"Stint Ultra\r\nExpanded\"")
                && licence("gfs-didot").contains("<GFS Didot>.") && licence("numans").contains("\"Numans.\"")
                && licence("jomolhari").contains("\"Jomolhari\" is a Reserved Font Name for this Font Software."));
        eq("a comma, single quotes", GoogleFonts.reservedNames("with Reserved Font Names, 'Passion'").toString(), "[Passion]");
        eq("is", GoogleFonts.reservedNames("with Reserved Font Name is \"Kite\"").toString(), "[Kite]");
        eq("a bare name that starts with Is", GoogleFonts.reservedNames("with Reserved Font Name Istok.").toString(), "[Istok]");
        eq("the name before, not the words after", GoogleFonts.reservedNames("\"X\" is a Reserved Font Name for this Font Software.").toString(), "[X]");
        eq("no name, no words", GoogleFonts.reservedNames("(with no Reserved Font Name for this Font Software)").toString(), "[]");
        eq("names before and after, in order", GoogleFonts.reservedNames("\"A\" is a Reserved Font Name for this Font Software. Reserved Font Names: \"B\" and \u201cA\u201d").toString(), "[A, B]");
        String body = licence("lato");
        body = body.substring(body.indexOf("\r\n") + 2);
        check("the OFL's own text names \"Reserved Font Name\"", body.contains("\"Reserved Font Name\" refers to") && body.contains("Reserved Font\r\nName(s)"));
        eq("the OFL's own text names none", GoogleFonts.reservedNames(body).toString(), "[]");
        eq("curly quotes", GoogleFonts.reservedNames("with Reserved Font Names \u201cAlpha\u201d, \u2018Beta\u2019 and \u201cGamma\u201d.").toString(), "[Alpha, Beta, Gamma]");
        eq("ampersand", GoogleFonts.reservedNames("with Reserved Font Names \"A\" & \"B\"").toString(), "[A, B]");
        eq("twice, once", GoogleFonts.reservedNames("Reserved Font Name \"Lato\". Reserved Font Name \"Lato\".").toString(), "[Lato]");
        eq("trimmed", GoogleFonts.reservedNames("Reserved Font Name \" Lato \"").toString(), "[Lato]");
        eq("an unclosed quote", GoogleFonts.reservedNames("Reserved Font Name \"Lato").toString(), "[]");
        eq("a quote over one line break", GoogleFonts.reservedNames("Reserved Font Name \"La\r\nto\"").toString(), "[La to]");
        eq("a blank line ends a quote", GoogleFonts.reservedNames("Reserved Font Name \"La\r\n\r\nto\"").toString(), "[]");
        eq("…and so does a blank \\n line", GoogleFonts.reservedNames("Reserved Font Name \"La\n\nto\"").toString(), "[]");
        eq("case", GoogleFonts.reservedNames("with RESERVED FONT NAME \"Big\"").toString(), "[Big]");
        eq("null", GoogleFonts.reservedNames(null).toString(), "[]");
        StringBuilder lots = new StringBuilder("Reserved Font Names ");
        for (int i = 0; i < 40; i++) lots.append(i > 0 ? ", " : "").append('"').append("N").append(i).append('"');
        eq("at most 16", GoogleFonts.reservedNames(lots.toString()).size(), 16);
        eq("a name over 80 chars is not one", GoogleFonts.reservedNames("Reserved Font Name \"" + repeat("x", 100) + "\"").toString(), "[]");
    }

    // ---------------------------------------------------------------------------------------------
    // Family details
    // ---------------------------------------------------------------------------------------------

    static void details() throws Exception {
        GoogleFonts.Info i = GoogleFonts.info(read("detail/lato.json"));
        eq("Lato licence", i.license + " | " + i.licenseName, "ofl | SIL Open Font License 1.1");
        eq("Lato designers", i.designers.toString(), "[\u0141ukasz Dziedzic]");
        check("Lato description, plain", i.description.startsWith("Lato means \u201cSummer\u201d in Polish") && !i.description.contains("<") && i.description.split("\n").length == 2);
        i = GoogleFonts.info(read("detail/ubuntu.json"));
        eq("Ubuntu licence", i.license + " | " + i.licenseName, "ufl | Ubuntu Font Licence 1.0");
        check("Ubuntu description, links as text", i.description.startsWith("The Ubuntu Font Family are a set of matching") && !i.description.contains("href") && i.description.contains("undertaken by Dalton Maag."));
        i = GoogleFonts.info(read("detail/permanent-marker.json"));
        eq("Permanent Marker", i.license + " | " + i.licenseName + " | " + i.designers + " | " + i.description,
                "apache2 | Apache License 2.0 | [Font Diner] | Permanent Marker represents the look and feel of a favorite writing instrument.");
        i = GoogleFonts.info(read("detail/roboto.json"));
        eq("Roboto designers", i.designers.toString(), "[Christian Robertson, ParaType, Font Bureau]");
        check("Roboto bold as text", i.description.contains("\nUpdated July 2020: Upgraded"));
        i = GoogleFonts.info(read("detail/noto-sans-jp.json"));
        check("Noto Sans JP em as text", i.description.contains("design for the Japanese language") && i.designers.contains("Google"));
        // a list's items on lines of their own, and named entities as text (R2-02)
        i = GoogleFonts.info(read("detail/caladea.json"));
        eq("Caladea's list", i.description.substring(i.description.indexOf("\nCaladea has")), "\nCaladea has the following differences:\n\u2022 More condensed width\n\u2022 New metrics"
                + "\n\u2022 4 styles. Regular, Bold, Italic, and Bold Italic\n\u2022 Character set expanded to WGL (418 glyphs)\nTo contribute, see github.com/huertatipografica/Caladea.");
        i = GoogleFonts.info(read("detail/neuton.json"));
        check("Neuton's dashes", i.description.contains("Two italics \u2014 one will be called \"italic\", and the other \"cursive\" \u2014 are uncommon") && !i.description.contains("&"));
        // the newer families send "description": "" and their About text as "article", an array of HTML strings (R3-01)
        i = GoogleFonts.info(read("detail/afacad.json"));
        check("Afacad: an empty description, the article's text instead", i.description.startsWith("The \u2019Afacad typeface project\u2019 commenced in 2017")
                && !i.description.contains("<") && i.description.split("\n").length == 3 && i.description.endsWith("\nTo contribute, please see github.com/Dicotype/Afacad."));
        eq("Afacad's licence and designers", i.licenseName + " | " + i.designers, "SIL Open Font License 1.1 | [Kristian M\u00f6ller, Dicotype]");
        eq("the description first, the article only when it has no text", GoogleFonts.info("{\"family\":\"X\",\"description\":\"<p>D</p>\",\"article\":[\"<p>A</p>\"]}").description, "D");
        eq("a description of empty markup is no text", GoogleFonts.info("{\"family\":\"X\",\"description\":\"<p> </p>\",\"article\":[\"<p>A</p>\"]}").description, "A");
        eq("article items, each a block", GoogleFonts.info("{\"family\":\"X\",\"description\":\"\",\"article\":[\"<p>A</p>\",\"B\",\"C\"]}").description, "A\nB\nC");
        eq("article items that are not text skipped", GoogleFonts.info("{\"family\":\"X\",\"article\":[5,null,{\"p\":\"x\"},\"  \",\"<p>A</p>\",[\"y\"],\"B\"]}").description, "A\nB");
        eq("article not an array", GoogleFonts.info("{\"family\":\"X\",\"description\":\"\",\"article\":\"<p>A</p>\"}").description, "");
        eq("description not text, an article", GoogleFonts.info("{\"family\":\"X\",\"description\":5,\"article\":[\"A\"]}").description, "A");
        eq("article capped", GoogleFonts.info(new JSONObject().put("family", "X").put("description", "").put("article", new JSONArray().put("<p>" + repeat("w ", 5000) + "</p>")).toString())
                .description.length(), 4000);
        JSONArray many = new JSONArray();
        for (int k = 0; k < 100; k++) many.put("<p>" + repeat("w ", 5000) + "</p>");
        eq("an article of many long items capped", GoogleFonts.info(new JSONObject().put("family", "X").put("article", many).toString()).description.length(), 4000);
        eq("Google's not-found answer", code(() -> GoogleFonts.info(read("detail/nonexistent-family-x.json"))), "gf_unknown");
        eq("a family the metadata does not have", code(() -> GoogleFonts.info(read("detail/material-symbols-outlined.json"))), "gf_unknown");
        // the envelope carries its own status: only 404 is "no details page", anything else is no answer about the family (R1-09)
        eq("Google's 405 answer (to a POST) is not 'no details'", code(() -> GoogleFonts.info(read("detail/lato-post-405.json"))), "gf_bad_answer");
        for (String[] e : new String[][] { { "503", "14" }, { "500", "13" }, { "429", "8" } })
            eq("a " + e[0] + " in Google's envelope is not 'no details'", code(() -> GoogleFonts.info(")]}'\n\n[[\"er\",null,null,null,null," + e[0] + ",null,null,null," + e[1]
                    + "],[\"di\",7],[\"af.httprm\",7,\"1\",0]]")), "gf_bad_answer");
        eq("an er row cut short", code(() -> GoogleFonts.info("[[\"er\"]]")), "gf_bad_answer");
        eq("an er row with no status", code(() -> GoogleFonts.info("[[\"er\",null,null,null,null,null],[\"di\",7]]")), "gf_bad_answer");
        eq("an er that does not parse", code(() -> GoogleFonts.info("[[\"er\",null,null,null,null,404")), "gf_bad_answer");
        eq("the 404 row anywhere in the envelope", code(() -> GoogleFonts.info("[[\"di\",7],[\"er\",null,null,null,null,404]]")), "gf_unknown");
        eq("not JSON", code(() -> GoogleFonts.info("<html>")), "gf_bad_answer");
        eq("no family", code(() -> GoogleFonts.info(")]}'\n{\"description\":\"x\"}")), "gf_bad_answer");
        eq("an array without er", code(() -> GoogleFonts.info("[[\"di\",7]]")), "gf_bad_answer");
        eq("null", code(() -> GoogleFonts.info(null)), "gf_bad_answer");
        i = GoogleFonts.info("{\"family\":\"X\",\"license\":\"GPL\",\"designers\":[{\"name\":\"A\"},\"B\",{\"name\":\"A\"},5,{}],\"description\":5}");
        eq("an unknown licence", i.license + "|" + i.licenseName, "|");
        eq("designers", i.designers.toString(), "[A, B]");
        eq("description not text", i.description, "");
        i = GoogleFonts.info(new JSONObject().put("family", "X").put("license", "OFL").put("description", "<p>" + repeat("w ", 5000) + "</p>").toString());
        eq("licence case", i.license, "ofl");
        eq("description capped", i.description.length(), 4000);
    }

    static void plainText() {
        eq("paragraphs", GoogleFonts.plainText("<p>One</p>\n<p>Two</p>", 100), "One\nTwo");
        eq("br", GoogleFonts.plainText("a<br>b<br/>c<BR />d", 100), "a\nb\nc\nd");
        eq("tags", GoogleFonts.plainText("<a href=\"x\">link</a> and <b>bold</b><script>s</script>", 100), "link and bolds");
        eq("an unclosed tag", GoogleFonts.plainText("text <img src=x onerror=alert(1)", 100), "text");
        eq("entities", GoogleFonts.plainText("&amp;&lt;b&gt;&quot;&apos;&nbsp;x &#65;&#x42;&#x1F600;", 100), "&<b>\"' x AB\ud83d\ude00");
        eq("not entities", GoogleFonts.plainText("&notaname; &#0; &#xD800; &#99999999; &amp", 100), "&notaname; &#0; &#xD800; &#99999999; &amp");
        eq("an entity does not make a tag", GoogleFonts.plainText("&lt;script&gt;", 100), "<script>");
        eq("spaces", GoogleFonts.plainText("  a \t\n b  ", 100), "a b");
        eq("empty lines dropped", GoogleFonts.plainText("<p></p><p> </p><p>x</p>", 100), "x");
        eq("max", GoogleFonts.plainText("abcdef", 3), "abc");
        eq("max never splits a pair", GoogleFonts.plainText("ab\ud83d\ude00", 3), "ab");
        eq("null", GoogleFonts.plainText(null, 10), "");
        // a list's items each on a line of their own, with a bullet, and the named entities a description is written with (R2-02)
        eq("a list (Caladea's markup)", GoogleFonts.plainText("<p>Caladea has the following differences:</p>\n<ul>\n <li>More condensed width</li>\n <li>New metrics</li>\n</ul>\n"
                + "<p>To contribute, see <a href=\"x\">github.com/x</a>.</p>", 300), "Caladea has the following differences:\n\u2022 More condensed width\n\u2022 New metrics\nTo contribute, see github.com/x.");
        eq("an ordered list, an item with attributes, an empty item", GoogleFonts.plainText("<OL><li class=\"a\">One<LI>Two</li><li> </li></ol>after", 100), "\u2022 One\n\u2022 Two\nafter");
        eq("blocks", GoogleFonts.plainText("intro<p>para</p><div>div</div><h2>head</h2>x<blockquote>quote</blockquote><dl><dt>term<dd>def</dl><hr>end", 200),
                "intro\npara\ndiv\nhead\nx\nquote\nterm\ndef\nend");
        eq("tags that are not blocks", GoogleFonts.plainText("a<link rel=x>b<u>c</u><object>d</object><pre>e</pre><track>f<param>g<olx>h<bdo>i", 100), "abcdefghi");
        eq("named entities", GoogleFonts.plainText("Two italics &mdash; one &ndash; Jim&eacute;nez &Eacute; &hellip; &rsquo;quoted&rsquo; &ldquo;x&rdquo; &bull; &euro;5", 200),
                "Two italics \u2014 one \u2013 Jim\u00e9nez \u00c9 \u2026 \u2019quoted\u2019 \u201cx\u201d \u2022 \u20ac5");
        eq("Latin-1 by name, in code point order", GoogleFonts.plainText("&iexcl;&cent;&deg;&iquest;&Agrave;&times;&szlig;&agrave;&divide;&thorn;&yuml;&shy;", 100),
                "\u00a1\u00a2\u00b0\u00bf\u00c0\u00d7\u00df\u00e0\u00f7\u00fe\u00ff\u00ad");
        eq("copy, reg, trade as text", GoogleFonts.plainText("&copy; 2020 X&reg; Y&trade;", 100), "(c) 2020 X(R) Y(tm)");
        eq("names are case-sensitive, unknown ones stay", GoogleFonts.plainText("&EACUTE; &eacutex; &Mdash; &x;", 100), "&EACUTE; &eacutex; &Mdash; &x;");
        eq("decoded once", GoogleFonts.plainText("&amp;eacute; &amp;lt;", 100), "&eacute; &lt;");
        // a figure's caption on a line of its own (Google Sans' article: figures side by side in a div) (R3-01)
        eq("figures and captions", GoogleFonts.plainText("<div><h2>Head</h2><p>Para</p></div> <figure><img src=\"a.svg\"> <figcaption> One </figcaption></figure>"
                + " <figure><video src=\"b.mp4\"></video><figcaption>Two</figcaption></figure>after<figurex>x<figcaptions>y", 200), "Head\nPara\nOne\nTwo\nafterxy");
    }

    // ---------------------------------------------------------------------------------------------
    // meta.json, the sweep
    // ---------------------------------------------------------------------------------------------

    static final String SHA_A = repeat("a", 64), SHA_B = repeat("b", 64);

    static File family(File fam, String family, String meta, String[] files, int[] sizes, String licence) throws Exception {
        File d = GoogleFonts.familyDir(fam, family);
        FontLibrary.deleteTree(d);
        d.mkdirs();
        for (int i = 0; i < files.length; i++) Files.write(new File(d, files[i]).toPath(), new byte[sizes[i]]);
        if (licence != null) Files.write(new File(d, licence).toPath(), "licence".getBytes(StandardCharsets.UTF_8));
        if (meta != null) Files.write(new File(d, GoogleFonts.META).toPath(), meta.getBytes(StandardCharsets.UTF_8));
        return d;
    }

    static String goodMeta(String family) throws Exception {
        List<GoogleFonts.MetaFile> fs = new ArrayList<GoogleFonts.MetaFile>();
        fs.add(new GoogleFonts.MetaFile("Lato-Regular.ttf", 10, SHA_A, false, false, 400, null));
        fs.add(new GoogleFonts.MetaFile("Lato-Italic.ttf", 20, SHA_B, false, true, 400, Arrays.asList("wght")));
        return GoogleFonts.metaJson(family, 1793052000000L, "ofl", "OFL.txt", Arrays.asList("Lato"), fs).toString();
    }

    static void meta() throws Exception {
        File fam = new File(tmp, "fam");
        String[] names = { "Lato-Regular.ttf", "Lato-Italic.ttf" };
        int[] sizes = { 10, 20 };
        File d = family(fam, "Lato", goodMeta("Lato"), names, sizes, "OFL.txt");
        eq("folder", d.getName(), "lato");
        eq("part folder", GoogleFonts.partDir(fam, "Open Sans").getName(), ".open-sans.part");
        JSONObject m = GoogleFonts.readMeta(d);
        check("round trip", m != null);
        eq("family", m.getString("family"), "Lato");
        eq("meta file", GoogleFonts.metaFile(m, "Lato-Italic.ttf").getString("sha256"), SHA_B);
        check("not a meta file", GoogleFonts.metaFile(m, "OFL.txt") == null && GoogleFonts.metaFile(m, null) == null && GoogleFonts.metaFile(null, "x") == null);
        long size = GoogleFonts.folderSize(d);
        eq("folder size", size, 10L + 20 + 7 + new File(d, GoogleFonts.META).length());
        JSONObject row = GoogleFonts.downloadsRow(m, size);
        eq("row", canon(row), canon(new JSONObject().put("family", "Lato").put("at", 1793052000000L).put("size", size).put("license", "ofl")
                .put("licenseName", "SIL Open Font License 1.1").put("reserved", new JSONArray().put("Lato"))
                .put("files", new JSONArray().put(new JSONObject().put("name", "Lato-Regular.ttf").put("size", 10).put("variable", false).put("italic", false).put("weight", 400))
                        .put(new JSONObject().put("name", "Lato-Italic.ttf").put("size", 20).put("variable", false).put("italic", true).put("weight", 400)))));
        // a family with no licence file in its list
        JSONObject noLic = new JSONObject(goodMeta("Lato"));
        noLic.getJSONObject("license").put("kind", "").put("file", "").put("reserved", new JSONArray());
        check("no licence is fine", GoogleFonts.readMeta(family(fam, "Lato", noLic.toString(), names, sizes, null)) != null);

        // damaged ones
        String[][] damage = {
                { "v", "{\"v\":2}" }, { "family of another folder", "family=Roboto" }, { "family not a name", "family=../lato" }, { "at", "at=-1" },
                { "licence kind", "license.kind=gpl" }, { "licence file", "license.file=README.txt" }, { "reserved not text", "license.reserved=[5]" },
                { "no files", "files=[]" }, { "size", "files.0.size=11" }, { "zero size", "files.0.size=0" }, { "sha", "files.0.sha256=xyz" },
                { "name", "files.0.name=../x.ttf" }, { "name twice", "files.1.name=Lato-Regular.ttf" }, { "weight", "files.0.weight=0" },
                { "variable", "files.0.variable=yes" }, { "axes", "files.0.axes=[1]" }, { "not JSON", "{" }, { "missing font file", "rm Lato-Italic.ttf" },
                { "missing licence file", "rm OFL.txt" }, { "no meta", "rm meta.json" } };
        for (String[] dm : damage) {
            File dd = family(fam, "Lato", goodMeta("Lato"), names, sizes, "OFL.txt");
            apply(dd, dm[1]);
            check("damaged meta refused: " + dm[0], GoogleFonts.readMeta(dd) == null);
        }
        File dir = family(fam, "Lato", goodMeta("Lato"), names, sizes, "OFL.txt");
        check("a file, not a folder", GoogleFonts.readMeta(new File(dir, "OFL.txt")) == null);
        // a family larger than 160 MB in all (sparse files: nothing is written)
        File bigDir = GoogleFonts.familyDir(fam, "Big Family");
        bigDir.mkdirs();
        List<GoogleFonts.MetaFile> fs = new ArrayList<GoogleFonts.MetaFile>();
        for (int i = 0; i < 3; i++) {
            String nm = "Big-" + i + ".ttf";
            RandomAccessFile raf = new RandomAccessFile(new File(bigDir, nm), "rw");
            raf.setLength(60L << 20);
            raf.close();
            fs.add(new GoogleFonts.MetaFile(nm, 60L << 20, SHA_A, false, false, 400, null));
        }
        Files.write(new File(bigDir, GoogleFonts.META).toPath(), GoogleFonts.metaJson("Big Family", 1, "", "", null, fs).toString().getBytes(StandardCharsets.UTF_8));
        check("over 160 MB refused", GoogleFonts.readMeta(bigDir) == null);
        fs.remove(2);
        new File(bigDir, "Big-2.ttf").delete();
        Files.write(new File(bigDir, GoogleFonts.META).toPath(), GoogleFonts.metaJson("Big Family", 1, "", "", null, fs).toString().getBytes(StandardCharsets.UTF_8));
        check("120 MB is fine", GoogleFonts.readMeta(bigDir) != null);
        FontLibrary.deleteTree(bigDir);
        FontLibrary.deleteTree(fam);
    }

    /** Damages a family folder: "{...}" replaces meta.json, "rm name" deletes a file, "path=value" sets one field. */
    static void apply(File d, String what) throws Exception {
        File mf = new File(d, GoogleFonts.META);
        if (what.startsWith("{")) { Files.write(mf.toPath(), what.getBytes(StandardCharsets.UTF_8)); return; }
        if (what.startsWith("rm ")) { new File(d, what.substring(3)).delete(); return; }
        JSONObject m = new JSONObject(new String(Files.readAllBytes(mf.toPath()), StandardCharsets.UTF_8));
        String path = what.substring(0, what.indexOf('=')), value = what.substring(what.indexOf('=') + 1);
        Object v = value.startsWith("[") ? new JSONArray(value) : value.matches("-?\\d+") ? (Object) Long.parseLong(value) : value;
        String[] parts = path.split("\\.");
        Object o = m;
        for (int i = 0; i < parts.length - 1; i++) o = o instanceof JSONObject ? ((JSONObject) o).get(parts[i]) : ((JSONArray) o).get(Integer.parseInt(parts[i]));
        String last = parts[parts.length - 1];
        if (o instanceof JSONObject) ((JSONObject) o).put(last, v); else ((JSONArray) o).put(Integer.parseInt(last), v);
        Files.write(mf.toPath(), m.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void sweep() throws Exception {
        File fam = new File(tmp, "sweep");
        String[] names = { "Lato-Regular.ttf", "Lato-Italic.ttf" };
        int[] sizes = { 10, 20 };
        family(fam, "Lato", goodMeta("Lato"), names, sizes, "OFL.txt");
        File part = GoogleFonts.partDir(fam, "Roboto");
        part.mkdirs();
        Files.write(new File(part, "Roboto-VariableFont_wdth,wght.ttf").toPath(), new byte[5]);
        File aside = new File(fam, ".lato.old");
        aside.mkdirs();
        File stray = new File(fam, "stray.txt");
        Files.write(stray.toPath(), new byte[1]);
        File half = family(fam, "Inter", null, new String[] { "Inter-VariableFont_opsz,wght.ttf" }, new int[] { 9 }, "OFL.txt");
        File wrong = family(fam, "Molle", goodMeta("Lato"), names, sizes, "OFL.txt");
        GoogleFonts.sweep(fam);
        check("a whole family kept", GoogleFonts.readMeta(new File(fam, "lato")) != null);
        check("a .part folder swept", !part.exists());
        check("a folder moved aside swept", !aside.exists());
        check("a stray file swept", !stray.exists());
        check("a folder without meta.json swept", !half.exists());
        check("a folder with another family's meta.json swept", !wrong.exists());
        GoogleFonts.sweep(new File(tmp, "no-such-folder"));
        GoogleFonts.sweep(null);
        check("sweeping nothing is fine", true);
        FontLibrary.deleteTree(fam);
    }

    // ---------------------------------------------------------------------------------------------
    // The preview cache
    // ---------------------------------------------------------------------------------------------

    static void previewCache() throws Exception {
        String k = GoogleFonts.previewKey("Roboto", "card", "Robt Aa");
        check("key is 64 hex", k.matches("[0-9a-f]{64}"));
        eq("key is stable", GoogleFonts.previewKey("Roboto", "card", "Robt Aa"), k);
        check("key depends on the mode", !k.equals(GoogleFonts.previewKey("Roboto", "styles", "Robt Aa")));
        check("key depends on the address", !k.equals(GoogleFonts.previewKey("Roboto", "card", "Robt A")));
        check("fields do not run together", !GoogleFonts.previewKey("Ab", "card", "c").equals(GoogleFonts.previewKey("A", "bcard", "c")));

        // a cached answer is named by the css2 request it stands for: a family that turned variable, or gained an axis, in a newer list is asked
        // for again, never drawn with the faces of the old request (R2-01)
        File dir = new File(tmp, "gfpv");
        JSONObject st = new JSONObject("{\"f\":\"Roboto\",\"w\":[\"400\",\"700\"]}");
        JSONObject va = new JSONObject("{\"f\":\"Roboto\",\"w\":[\"100\",\"200\",\"300\",\"400\",\"500\",\"600\",\"700\",\"800\",\"900\"],\"x\":[[\"wght\",100,900,400]]}");
        JSONObject vo = new JSONObject(va.toString());
        vo.getJSONArray("x").put(new JSONArray("[\"opsz\",14,32,14]"));
        JSONObject sx = new JSONObject(st.toString()).put("x", new JSONArray("[[\"wght\",100,900,400]]"));
        String t = "Robt Aa";
        for (String m : new String[] { "card", "styles", "axes" }) {
            File a = GoogleFonts.previewFile(dir, st, m, t);
            eq("previewFile " + m + ": in the folder, 64 hex", a.getParentFile().equals(dir) && a.getName().matches("[0-9a-f]{64}\\.json"), true);
            eq("previewFile " + m + ": the same entry, mode and text, the same file", GoogleFonts.previewFile(dir, new JSONObject(st.toString()), m, t), a);
            eq("previewFile " + m + ": the key of the family, the mode and the css2 address", a.getName(), GoogleFonts.previewKey("Roboto", m, GoogleFonts.css2Url(st, m, t)) + ".json");
            check("previewFile " + m + ": another text, another file", !a.equals(GoogleFonts.previewFile(dir, st, m, "Robt A")));
        }
        check("static 400/700 and variable 100-900: the styles differ", !GoogleFonts.previewFile(dir, st, "styles", t).equals(GoogleFonts.previewFile(dir, va, "styles", t)));
        check("…and the axes", !GoogleFonts.previewFile(dir, st, "axes", t).equals(GoogleFonts.previewFile(dir, va, "axes", t)));
        check("the same styles, an axis added: the axes differ", !GoogleFonts.previewFile(dir, st, "axes", t).equals(GoogleFonts.previewFile(dir, sx, "axes", t)));
        check("an axis added to a variable family (opsz): the axes differ", !GoogleFonts.previewFile(dir, va, "axes", t).equals(GoogleFonts.previewFile(dir, vo, "axes", t)));
        check("card and axes of a static family: two files (the same request)", GoogleFonts.css2Url(st, "card", t).equals(GoogleFonts.css2Url(st, "axes", t))
                && !GoogleFonts.previewFile(dir, st, "card", t).equals(GoogleFonts.previewFile(dir, st, "axes", t)));
        check("card and styles: two files", !GoogleFonts.previewFile(dir, st, "card", t).equals(GoogleFonts.previewFile(dir, st, "styles", t)));
        check("no text: the family name's, as css2Url asks", GoogleFonts.previewFile(dir, st, "card", "").equals(GoogleFonts.previewFile(dir, st, "card", "Roboto Aa")));
        eq("previewFile of a name that is not a family: css2Url's refusal", code(() -> GoogleFonts.previewFile(dir, new JSONObject().put("f", "../x"), "card", t)), "threw java.lang.IllegalArgumentException: not a family");

        File f = new File(dir, k + ".json");
        String answer = "{\"ok\":true,\"family\":\"Roboto\",\"mode\":\"card\",\"faces\":[{\"style\":\"normal\",\"weight\":\"400\",\"stretch\":\"\",\"b64\":\"d09GMg==\"}]}";
        GoogleFonts.writeWhole(f, answer.getBytes(StandardCharsets.UTF_8));
        check("written whole, no .part left", f.isFile() && !new File(dir, k + ".json.part").exists());
        long now = f.lastModified();
        eq("read back", GoogleFonts.readCached(f, now, GoogleFonts.PREVIEW_CACHE_MS), answer);
        check("too old", GoogleFonts.readCached(f, now + GoogleFonts.PREVIEW_CACHE_MS + 1, GoogleFonts.PREVIEW_CACHE_MS) == null);
        check("missing", GoogleFonts.readCached(new File(dir, "none.json"), now, GoogleFonts.PREVIEW_CACHE_MS) == null);
        GoogleFonts.writeWhole(f, "{\"ok\":false,\"code\":\"network\"}".getBytes(StandardCharsets.UTF_8));
        check("not an answer", GoogleFonts.readCached(f, now, GoogleFonts.PREVIEW_CACHE_MS) == null);
        GoogleFonts.writeWhole(f, "garbage".getBytes(StandardCharsets.UTF_8));
        check("not JSON", GoogleFonts.readCached(f, now, GoogleFonts.PREVIEW_CACHE_MS) == null);
        GoogleFonts.writeWhole(f, answer.getBytes(StandardCharsets.UTF_8));
        eq("replaced", GoogleFonts.readCached(f, f.lastModified(), GoogleFonts.PREVIEW_CACHE_MS), answer);

        // trimmed oldest first
        FontLibrary.deleteTree(dir);
        dir.mkdirs();
        long t0 = 1700000000000L;
        for (int i = 0; i < 10; i++) {
            File e = new File(dir, "e" + i + ".json");
            Files.write(e.toPath(), new byte[1000]);
            e.setLastModified(t0 + (i * 7 % 10) * 1000L);   // ages out of name order
        }
        eq("trim deletes", GoogleFonts.trimCache(dir, 6500), 4);
        List<String> left = new ArrayList<String>();
        File[] kids = dir.listFiles();
        for (File e : kids) left.add(e.getName());
        java.util.Collections.sort(left);
        // the ages: e0 0, e1 7, e2 4, e3 1, e4 8, e5 5, e6 2, e7 9, e8 6, e9 3 -> the four oldest are e0 e3 e6 e9
        eq("the four oldest went", left.toString(), "[e1.json, e2.json, e4.json, e5.json, e7.json, e8.json]");
        eq("under the cap: nothing", GoogleFonts.trimCache(dir, 6000), 0);
        eq("no folder", GoogleFonts.trimCache(new File(tmp, "none"), 0), 0);
        FontLibrary.deleteTree(dir);
    }

    // ---------------------------------------------------------------------------------------------
    // What the bridge reads and writes: catalog.json, the addresses, the details answer, the downloads, the zip, a download put in place
    // ---------------------------------------------------------------------------------------------

    static void bridgeParts() throws Exception {
        Map<String, JSONObject> by = families();
        // the longest css2 address the app can ask for (32 axes, 300 code points of 4 UTF-8 bytes) is still one allowed() takes
        JSONArray axes = new JSONArray();
        for (int i = 0; i < 32; i++) axes.put(new JSONArray().put("a" + (char) ('a' + i / 26) + (char) ('a' + i % 26) + "x").put(-1000.5).put(1000.25).put(0));
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 400; i++) emoji.appendCodePoint(0x1F300 + i);
        String longest = GoogleFonts.css2Url(new JSONObject().put("f", repeat("W", 64)).put("w", new JSONArray().put("400")).put("x", axes), "axes", emoji.toString());
        check("the longest css2 address is allowed (" + longest.length() + " characters)", GoogleFonts.allowed(longest) && longest.length() < 6000);
        check("…and a longer one is not", !GoogleFonts.allowed("https://fonts.gstatic.com/" + repeat("a", 8192)));

        eq("list address", GoogleFonts.listUrl("Open Sans"), "https://fonts.google.com/download/list?family=Open+Sans");
        eq("details address", GoogleFonts.detailUrl("Source Sans 3"), "https://fonts.google.com/metadata/fonts/Source%20Sans%203");
        for (String bad : new String[] { "../x", "a&b", "", null, "A?family=B" }) {
            boolean threw = false;
            try { GoogleFonts.listUrl(bad); } catch (IllegalArgumentException x) { threw = true; }
            boolean threw2 = false;
            try { GoogleFonts.detailUrl(bad); } catch (IllegalArgumentException x) { threw2 = true; }
            check("no address for the name " + bad, threw && threw2);
        }

        long now = 1793052000000L, day = 24L * 3600 * 1000;
        check("no list is stale", GoogleFonts.isStale(0, now));
        check("an hour old is not", !GoogleFonts.isStale(now - 3600000, now));
        check("7 days is not", !GoogleFonts.isStale(now - 7 * day, now));
        check("7 days and a second is", GoogleFonts.isStale(now - 7 * day - 1000, now));
        check("a list from ahead of the clock is", GoogleFonts.isStale(now + 3600000, now));

        // catalog.json both ways
        File cf = new File(tmp, "gfonts/catalog.json");
        JSONObject cat = GoogleFonts.compact(read("catalog.json"), now);
        GoogleFonts.writeWhole(cf, cat.toString().getBytes(StandardCharsets.UTF_8));
        check("no .part left", !new File(cf.getParentFile(), "catalog.json.part").exists());
        eq("catalog.json round trip", canon(GoogleFonts.readCatalog(cf)), canon(cat));
        Map<String, JSONObject> idx = GoogleFonts.index(GoogleFonts.readCatalog(cf));
        eq("index", idx.size(), by.size());
        eq("index entry", canon(idx.get("Roboto Flex")), canon(by.get("Roboto Flex")));
        String[][] broken = { { "not JSON", "{" }, { "another version", "v=2" }, { "no time", "at=0" }, { "no axes", "axes=null" }, { "families not a list", "families={}" },
                { "a family not a name", "families.0.f=../x" }, { "a family's name not text", "families.0.f=5" }, { "an entry not an object", "families.0=5" } };
        for (String[] b : broken) {
            JSONObject o = new JSONObject(cat.toString());
            String s;
            if (b[1].startsWith("{")) s = b[1];
            else {
                String path = b[1].substring(0, b[1].indexOf('=')), value = b[1].substring(b[1].indexOf('=') + 1);
                Object v = "null".equals(value) ? JSONObject.NULL : value.equals("{}") ? new JSONObject() : value.matches("-?\\d+") ? (Object) Long.parseLong(value) : value;
                String[] parts = path.split("\\.");
                Object at = o;
                for (int i = 0; i < parts.length - 1; i++) at = at instanceof JSONObject ? ((JSONObject) at).get(parts[i]) : ((JSONArray) at).get(Integer.parseInt(parts[i]));
                String last = parts[parts.length - 1];
                if (at instanceof JSONObject) ((JSONObject) at).put(last, v); else ((JSONArray) at).put(Integer.parseInt(last), v);
                s = o.toString();
            }
            Files.write(cf.toPath(), s.getBytes(StandardCharsets.UTF_8));
            check("a damaged catalog.json is no list: " + b[0], GoogleFonts.readCatalog(cf) == null);
        }
        RandomAccessFile big = new RandomAccessFile(cf, "rw");
        big.setLength((16L << 20) + 1);
        big.close();
        check("a catalog.json over 16 MB is no list", GoogleFonts.readCatalog(cf) == null);
        check("no catalog.json", GoogleFonts.readCatalog(new File(tmp, "gfonts/none.json")) == null && GoogleFonts.readCatalog(null) == null);
        JSONObject dup = new JSONObject().put("families", new JSONArray().put(new JSONObject().put("f", "Lato").put("p", 1)).put(new JSONObject().put("f", "Lato").put("p", 2))
                .put(new JSONObject().put("f", "../x")).put(5));
        Map<String, JSONObject> di = GoogleFonts.index(dup);
        eq("index: the first of a name, plain names only", di.size() + " " + di.get("Lato").getInt("p"), "1 1");
        check("index of nothing", GoogleFonts.index(null).isEmpty() && GoogleFonts.index(new JSONObject()).isEmpty());

        // the details answer (onGfInfo)
        GoogleFonts.Plan lato = GoogleFonts.plan(read("list/lato.json"));
        JSONObject info = GoogleFonts.infoJson("Lato", lato, GoogleFonts.info(read("detail/lato.json")), by.get("Lato"));
        eq("info keys", canon(new JSONArray(sortedKeys(info))), "[\"description\",\"designers\",\"family\",\"files\",\"license\",\"licenseName\",\"licenseText\",\"ok\",\"reserved\"]");
        eq("info", info.getBoolean("ok") + " " + info.getString("family") + " " + info.getString("license") + " | " + info.getString("licenseName") + " | "
                + info.getJSONArray("reserved") + " " + info.getJSONArray("designers") + " " + info.getJSONArray("files").length(),
                "true Lato ofl | SIL Open Font License 1.1 | [\"Lato\"] [\"Łukasz Dziedzic\"] 10");
        eq("info file", canon(info.getJSONArray("files").getJSONObject(1)), canon(new JSONObject().put("name", "Lato-ThinItalic.ttf").put("variable", false).put("italic", true)));
        check("info texts", info.getString("description").startsWith("Lato means") && info.getString("licenseText").equals(lato.licenseText));
        GoogleFonts.Plan ms = GoogleFonts.plan(read("list/material-symbols-outlined.json"));
        JSONObject msEntry = new JSONObject().put("f", "Material Symbols Outlined").put("d", new JSONArray().put("Google"));
        JSONObject msInfo = GoogleFonts.infoJson("Material Symbols Outlined", ms, null, msEntry);
        eq("info without a details page", msInfo.getString("description") + "|" + msInfo.getString("license") + "|" + msInfo.getJSONArray("designers"), "|apache2|[\"Google\"]");
        eq("…and without a list entry", GoogleFonts.infoJson("Material Symbols Outlined", ms, null, null).getJSONArray("designers").length(), 0);
        JSONObject noLic = new JSONObject(GoogleFonts.stripXssi(read("list/lato.json")));
        noLic.getJSONObject("manifest").put("files", new JSONArray());
        GoogleFonts.Plan bare = GoogleFonts.plan(noLic.toString());
        eq("no licence file: the details' licence", GoogleFonts.infoJson("Lato", bare, GoogleFonts.info(read("detail/permanent-marker.json")), null).getString("licenseName"), "Apache License 2.0");
        eq("no licence anywhere", GoogleFonts.infoJson("Lato", bare, null, null).getString("license") + "|" + GoogleFonts.infoJson("Lato", bare, null, null).getString("licenseText"), "|");
        // a family whose description is empty and its text in "article" has its About text on the page (R3-01)
        check("info: Afacad's article as the description", GoogleFonts.infoJson("Afacad", bare, GoogleFonts.info(read("detail/afacad.json")), null).getString("description")
                .startsWith("The ’Afacad typeface project’"));

        // the downloads, newest first; only a whole family of the name asked for
        File fam = new File(tmp, "fam-rows");
        String[] names = { "Lato-Regular.ttf", "Lato-Italic.ttf" };
        int[] sizes = { 10, 20 };
        family(fam, "Lato", goodMeta("Lato"), names, sizes, "OFL.txt");
        JSONObject older = new JSONObject(goodMeta("Molle"));
        older.put("at", 1L);
        family(fam, "Molle", older.toString(), names, sizes, "OFL.txt");
        JSONObject same = new JSONObject(goodMeta("Andika"));
        family(fam, "Andika", same.toString(), names, sizes, "OFL.txt");
        family(fam, "Inter", null, names, sizes, "OFL.txt");                              // no meta.json: not whole
        File hidden = new File(fam, ".roboto.part");
        hidden.mkdirs();
        Files.write(new File(hidden, GoogleFonts.META).toPath(), goodMeta("Roboto").getBytes(StandardCharsets.UTF_8));
        JSONArray rows = GoogleFonts.downloads(fam);
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < rows.length(); i++) order.append(i > 0 ? " " : "").append(rows.getJSONObject(i).getString("family"));
        eq("downloads, newest first, then by name", order.toString(), "Andika Lato Molle");
        eq("a row", canon(rows.getJSONObject(1)), canon(GoogleFonts.downloadsRow(GoogleFonts.readMeta(new File(fam, "lato")), GoogleFonts.folderSize(new File(fam, "lato")))));
        eq("no folder, no rows", GoogleFonts.downloads(new File(tmp, "none")).length() + GoogleFonts.downloads(null).length(), 0);
        check("downloaded", GoogleFonts.downloaded(fam, "Lato") != null);
        check("…by its own name only (\"lato\" is Lato's folder)", GoogleFonts.downloaded(fam, "lato") == null);
        check("…not a name, not downloaded", GoogleFonts.downloaded(fam, "../lato") == null && GoogleFonts.downloaded(fam, null) == null && GoogleFonts.downloaded(fam, "Roboto") == null);

        // the zip Save writes: each font checked against meta.json, then the licence, flat
        File zd = new File(tmp, "fam-zip");
        File dir = GoogleFonts.familyDir(zd, "Zip Family");
        dir.mkdirs();
        byte[] a = "font file A".getBytes(StandardCharsets.UTF_8), b = new byte[200000];
        new Random(7).nextBytes(b);
        Files.write(new File(dir, "Zip-Regular.ttf").toPath(), a);
        Files.write(new File(dir, "Zip-Bold.ttf").toPath(), b);
        Files.write(new File(dir, "OFL.txt").toPath(), "licence text".getBytes(StandardCharsets.UTF_8));
        List<GoogleFonts.MetaFile> zfs = new ArrayList<GoogleFonts.MetaFile>();
        zfs.add(new GoogleFonts.MetaFile("Zip-Regular.ttf", a.length, FontLibrary.sha256(a), false, false, 400, null));
        zfs.add(new GoogleFonts.MetaFile("Zip-Bold.ttf", b.length, FontLibrary.sha256(b), false, false, 700, null));
        JSONObject zm = GoogleFonts.metaJson("Zip Family", 5, "ofl", "OFL.txt", Arrays.asList("Zip"), zfs);
        Files.write(new File(dir, GoogleFonts.META).toPath(), zm.toString().getBytes(StandardCharsets.UTF_8));
        check("the zip family is whole", GoogleFonts.readMeta(dir) != null);
        Closing out = new Closing();
        GoogleFonts.writeZip(dir, zm, out);
        check("the zip closes the document", out.closed);
        Map<String, byte[]> got = unzip(out.toByteArray());
        eq("zip entries, in order", got.keySet().toString(), "[Zip-Regular.ttf, Zip-Bold.ttf, OFL.txt]");
        check("zip contents", Arrays.equals(got.get("Zip-Regular.ttf"), a) && Arrays.equals(got.get("Zip-Bold.ttf"), b)
                && new String(got.get("OFL.txt"), StandardCharsets.UTF_8).equals("licence text"));
        JSONObject noLicMeta = new JSONObject(zm.toString());
        noLicMeta.getJSONObject("license").put("file", "");
        Closing out2 = new Closing();
        GoogleFonts.writeZip(dir, noLicMeta, out2);
        eq("no licence file, no licence entry", unzip(out2.toByteArray()).keySet().toString(), "[Zip-Regular.ttf, Zip-Bold.ttf]");
        Files.write(new File(dir, "Zip-Bold.ttf").toPath(), flip(b, 1000));
        Closing out3 = new Closing();
        eq("a font changed on the phone", code(() -> GoogleFonts.writeZip(dir, zm, out3)), "gf_changed");
        check("…the document is closed", out3.closed);
        Files.write(new File(dir, "Zip-Bold.ttf").toPath(), Arrays.copyOf(b, b.length + 1));
        eq("a font grown", code(() -> GoogleFonts.writeZip(dir, zm, new Closing())), "gf_changed");
        Files.write(new File(dir, "Zip-Bold.ttf").toPath(), Arrays.copyOf(b, b.length - 1));
        eq("a font cut short", code(() -> GoogleFonts.writeZip(dir, zm, new Closing())), "gf_changed");
        JSONObject badName = new JSONObject(zm.toString());
        badName.getJSONArray("files").getJSONObject(0).put("name", "../x.ttf");
        eq("a name that is not a file name", code(() -> GoogleFonts.writeZip(dir, badName, new Closing())), "gf_changed");
        new File(dir, "Zip-Bold.ttf").delete();
        check("a font gone is the write's failure", code(() -> GoogleFonts.writeZip(dir, zm, new Closing())).startsWith("threw java.io.FileNotFoundException"));
        Closing failing = new Closing();
        failing.failAt = 10;
        Files.write(new File(dir, "Zip-Bold.ttf").toPath(), b);
        check("a document that cannot be written is the write's failure", code(() -> GoogleFonts.writeZip(dir, zm, failing)).startsWith("threw java.io.IOException"));

        // a download put in place: new, again (the old folder replaced), and a rename that fails
        File cd = new File(tmp, "fam-commit");
        File part = GoogleFonts.partDir(cd, "Open Sans");
        part.mkdirs();
        Files.write(new File(part, "new.ttf").toPath(), new byte[3]);
        File dest = GoogleFonts.commitDownload(cd, "Open Sans");
        check("in place", dest.equals(new File(cd, "open-sans")) && new File(dest, "new.ttf").isFile() && !part.exists());
        part.mkdirs();
        Files.write(new File(part, "newer.ttf").toPath(), new byte[4]);
        GoogleFonts.commitDownload(cd, "Open Sans");
        check("again: the old folder replaced", new File(dest, "newer.ttf").isFile() && !new File(dest, "new.ttf").exists());
        eq("…and nothing left aside", Arrays.toString(cd.list()), "[open-sans]");
        eq("no .part folder", code(() -> GoogleFonts.commitDownload(cd, "Open Sans")), "threw java.io.IOException: The download could not be put in place.");
        check("…the family's folder back in place", new File(dest, "newer.ttf").isFile() && Arrays.toString(cd.list()).equals("[open-sans]"));
        FontLibrary.deleteTree(fam);
        FontLibrary.deleteTree(zd);
        FontLibrary.deleteTree(cd);
    }

    static List<String> sortedKeys(JSONObject o) {
        List<String> ks = keys(o);
        java.util.Collections.sort(ks);
        return ks;
    }

    static byte[] flip(byte[] b, int at) {
        byte[] c = b.clone();
        c[at] ^= 1;
        return c;
    }

    static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new java.util.LinkedHashMap<String, byte[]>();
        java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip));
        java.util.zip.ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = z.read(buf)) > 0) b.write(buf, 0, n);
            out.put(e.getName(), b.toByteArray());
        }
        return out;
    }

    /** A document: remembers that it was closed, and can fail after some bytes (a provider that stops taking them). */
    static final class Closing extends java.io.OutputStream {
        final java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        boolean closed;
        int failAt = -1;
        @Override public void write(int x) throws java.io.IOException { write(new byte[] { (byte) x }, 0, 1); }
        @Override public void write(byte[] x, int off, int len) throws java.io.IOException {
            if (failAt >= 0 && b.size() + len > failAt) throw new java.io.IOException("no room");
            b.write(x, off, len);
        }
        @Override public void close() { closed = true; }
        byte[] toByteArray() { return b.toByteArray(); }
    }

    // ---------------------------------------------------------------------------------------------
    // Fuzz: every parser, broken answers
    // ---------------------------------------------------------------------------------------------

    static final List<String> CODES = Arrays.asList("gf_bad_answer", "gf_unknown", "gf_no_files", "gf_too_big", "gf_no_preview");

    static final String JUNK = "{[\",:}]\\\u0000\ud800<>'";

    static String head(String s, int n) { return s.substring(0, Math.min(n, s.length())); }

    static String mutate(Random r, String s) {
        StringBuilder b = new StringBuilder(s);
        int edits = 1 + r.nextInt(8);
        for (int e = 0; e < edits && b.length() > 0; e++) {
            int at = r.nextInt(b.length());
            switch (r.nextInt(7)) {
                case 0: b.setLength(at); break;                                                               // cut short
                case 1: b.deleteCharAt(at); break;
                case 2: b.insert(at, JUNK.charAt(r.nextInt(JUNK.length()))); break;
                case 3: b.setCharAt(at, (char) r.nextInt(0x3000)); break;
                case 4: b.insert(at, "9999999999999999999999"); break;                                       // a huge number
                case 5: { int to = Math.min(b.length(), at + r.nextInt(200)); b.insert(r.nextInt(b.length()), b.substring(at, to)); break; }
                default: b.insert(at, r.nextBoolean() ? "\"../../x.ttf\"" : "\"https://evil.example/\""); break;
            }
        }
        return b.toString();
    }

    static void fuzz(long seed, int rounds) throws Exception {
        Random r = new Random(seed);
        String catalog = read("catalog.json"), compacted = GoogleFonts.compact(catalog, 1).toString();
        String[] lists = { read("list/roboto.json"), read("list/lato.json"), read("list/andika.json"), read("list/unifrakturcook.json") };
        String[] details = { read("detail/lato.json"), read("detail/ubuntu.json"), read("detail/nonexistent-family-x.json"), read("detail/afacad.json") };
        String[] css = { read("css2/lato_styles.css"), read("css2/robotoflex_axes.css"), read("css2/molle_card.css") };
        int bad = 0;
        for (int i = 0; i < rounds; i++) {
            final String cat = mutate(r, i % 10 == 0 ? catalog : catalog.substring(0, Math.min(catalog.length(), 3000 + r.nextInt(6000))));
            final String list = mutate(r, lists[r.nextInt(lists.length)]);
            final String det = mutate(r, details[r.nextInt(details.length)]);
            final String cs = mutate(r, css[r.nextInt(css.length)]);
            for (String c : new String[] {
                    code(() -> { JSONObject o = GoogleFonts.compact(cat, 1); JSONArray f = o.getJSONArray("families");
                        for (int k = 0; k < f.length(); k++) { if (!GoogleFonts.isFamily(f.getJSONObject(k).getString("f"))) throw new IllegalStateException("bad name kept"); GoogleFonts.css2Url(f.getJSONObject(k), "axes", head(cat, 50)); } }),
                    code(() -> { GoogleFonts.Plan p = GoogleFonts.plan(list);
                        for (GoogleFonts.FileRef f : p.files) if (!GoogleFonts.isFileName(f.name) || !GoogleFonts.allowed(f.url) || !f.url.startsWith(GoogleFonts.GSTATIC)) throw new IllegalStateException("bad ref kept " + f.name + " " + f.url); }),
                    code(() -> GoogleFonts.info(det)),
                    code(() -> { for (GoogleFonts.Face f : GoogleFonts.parseCss(cs)) if (!GoogleFonts.allowed(f.url)) throw new IllegalStateException("bad face kept " + f.url); }),
                    code(() -> { GoogleFonts.reservedNames(list); GoogleFonts.plainText(det, 4000); GoogleFonts.uniqueText(cs, head(cat, 20)); GoogleFonts.stripXssi(list); }) }) {
                if (!c.equals("none") && !CODES.contains(c)) { bad++; if (bad < 5) System.out.println("FAIL fuzz round " + i + ": " + c); }
            }
            if (i % 50 == 0) {
                // a damaged catalog.json is no list, never a throw
                File cf = new File(tmp, "fz/catalog.json");
                GoogleFonts.writeWhole(cf, mutate(r, compacted).getBytes(StandardCharsets.UTF_8));
                try { JSONObject o = GoogleFonts.readCatalog(cf); if (o != null) GoogleFonts.index(o); } catch (Throwable t) { bad++; System.out.println("FAIL fuzz readCatalog threw " + t); }
                // a damaged meta.json is null, never a throw
                File d = family(new File(tmp, "fz"), "Lato", mutate(r, goodMeta("Lato")), new String[] { "Lato-Regular.ttf", "Lato-Italic.ttf" }, new int[] { 10, 20 }, "OFL.txt");
                try { GoogleFonts.readMeta(d); } catch (Throwable t) { bad++; System.out.println("FAIL fuzz readMeta threw " + t); }
            }
        }
        eq("fuzz: " + rounds + " rounds, only the page's codes", bad, 0);
    }
}
