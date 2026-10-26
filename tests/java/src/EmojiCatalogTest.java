package com.bloatware.bingblop;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * EmojiCatalog: the five emoji-font entries as data, the streaming verifier ({@link EmojiCatalog#download}) and the Twemoji zip-entry
 * extraction ({@link EmojiCatalog#extractEntry}). The catalogue values were checked against the real files at development time (the sizes
 * and hashes here are those measured values, recorded so CI, which has no network, still catches an accidental edit). The verifier is
 * driven with correct, short, long, wrong-hash, over-cap and cancelled streams, and a seeded fuzz feeds it random, truncated and huge
 * inputs: every failure ends in an {@link java.io.IOException}, never another throw, a hang or an allocation sized by an unchecked count.
 */
public class EmojiCatalogTest {

    static int n = 0, fails = 0;
    static void check(String name, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + name); } }
    static void eq(String name, Object got, Object want) { n++; if (!java.util.Objects.equals(got, want)) { fails++; System.out.println("FAIL " + name + ": got <" + got + "> want <" + want + ">"); } }

    static File tmp;

    public static void main(String[] a) throws Exception {
        if (a.length >= 3 && a[0].equals("--fuzz")) { tmp = Files.createTempDirectory("ec-").toFile(); fuzz(Long.parseLong(a[1]), Integer.parseInt(a[2])); report(); return; }
        tmp = Files.createTempDirectory("ec-").toFile();
        catalogData();
        downloadVerifier();
        zipExtraction();
        fuzz(987654321, 3000);
        report();
    }

    static void report() {
        System.out.println((fails == 0 ? "PASS" : "FAIL") + " EmojiCatalogTest: " + (n - fails) + "/" + n + " checks");
        if (fails != 0) System.exit(1);
    }

    static String sha256(byte[] b) throws Exception {
        return EmojiCatalog.hex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    static byte[] bytesOf(int len, int seed) { byte[] b = new byte[len]; new Random(seed).nextBytes(b); return b; }

    // ---------------------------------------------------------------------------------------------
    // The catalogue
    // ---------------------------------------------------------------------------------------------

    static void catalogData() throws Exception {
        eq("five items", EmojiCatalog.ITEMS.size(), 5);
        List<String> ids = new ArrayList<String>();
        for (EmojiCatalog.Item it : EmojiCatalog.ITEMS) ids.add(it.id);
        eq("order", ids, Arrays.asList("noto", "twemoji", "blobmoji", "openmoji", "apple"));

        for (EmojiCatalog.Item it : EmojiCatalog.ITEMS) {
            check(it.id + ": url https", it.url.startsWith("https://"));
            check(it.id + ": size > 0", it.size > 0);
            check(it.id + ": sha 64 hex", it.sha256 != null && it.sha256.matches("[0-9a-f]{64}"));
            check(it.id + ": name", it.name != null && !it.name.isEmpty());
            check(it.id + ": license", it.license != null && !it.license.isEmpty());
            check(it.id + ": licenseUrl https", it.licenseUrl.startsWith("https://"));
            check(it.id + ": sourceUrl https", it.sourceUrl.startsWith("https://"));
            check(it.id + ": attribution", it.attribution != null && !it.attribution.isEmpty());
            check(it.id + ": note non-null", it.note != null);
            check(it.id + ": format", it.format.equals("COLRv0") || it.format.equals("COLRv1") || it.format.equals("CBDT"));
        }

        // the measured values (verified against the live files at dev time; see the fixtures README / make_fixtures)
        EmojiCatalog.Item noto = EmojiCatalog.byId("noto");
        eq("noto size", noto.size, 5038236L);
        eq("noto sha", noto.sha256, "b8e25ea68db82f9e4d0aee921f4420be2be39887bd5c893a2ad98710531f9d0c");
        eq("noto format", noto.format, "COLRv1");
        eq("noto minApi", noto.minApiForMerge, 33);
        check("noto mergeable", noto.mergeable);
        check("noto not a zip", !noto.isZip());

        EmojiCatalog.Item tw = EmojiCatalog.byId("twemoji");
        eq("twemoji size (extracted ttf)", tw.size, 2003200L);
        eq("twemoji sha (ttf)", tw.sha256, "d2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e");
        eq("twemoji format", tw.format, "COLRv0");
        eq("twemoji minApi", tw.minApiForMerge, 26);
        check("twemoji is a zip", tw.isZip());
        eq("twemoji zip entry", tw.zipEntry, "fonts/twemoji.ttf");
        eq("twemoji zip size", tw.zipSize, 1541484L);
        eq("twemoji zip sha", tw.zipSha256, "d4e9b55a4e57dcedeab2b024d790a10d59656a21804957aefb9a3cccec865bd2");
        check("twemoji url is the zip", tw.url.endsWith("twemoji-font-1.0.4.zip"));

        EmojiCatalog.Item blob = EmojiCatalog.byId("blobmoji");
        eq("blobmoji size", blob.size, 4444184L);
        eq("blobmoji sha", blob.sha256, "be12a85b4ac069ef07541e861f96e99710ad4f5264600aff2dd396a640dbfb7f");
        eq("blobmoji minApi", blob.minApiForMerge, 33);

        EmojiCatalog.Item om = EmojiCatalog.byId("openmoji");
        eq("openmoji size", om.size, 2401396L);
        eq("openmoji sha", om.sha256, "b6c86e3d95f557dae9a7d59a993b5a6f2274c401d6d975747d43612c6fab236d");
        eq("openmoji minApi", om.minApiForMerge, 33);
        check("openmoji has a COLRv0 fallback", om.fallback != null);
        eq("fallback format", om.fallback.format, "COLRv0");
        eq("fallback minApi", om.fallback.minApiForMerge, 26);
        eq("fallback size", om.fallback.size, 2681320L);
        eq("fallback sha", om.fallback.sha256, "8376fee074649ece235faba0d157d851f3d93632f0540ad1165feba1aba9ba37");
        check("fallback url is COLRv0", om.fallback.url.contains("glyf_colr_0"));

        EmojiCatalog.Item ap = EmojiCatalog.byId("apple");
        eq("apple size", ap.size, 43877492L);
        eq("apple sha", ap.sha256, "e7b5f998812d9ab50d32f5626cb1e88edea345d725f8edf44a7ec3333b81043d");
        eq("apple format", ap.format, "CBDT");
        check("apple not mergeable", !ap.mergeable);
        check("apple note names the third-party build", ap.note.toLowerCase().contains("third-party"));

        // forSdk / usableForMerge
        eq("openmoji on API 31 uses the COLRv0 fallback", om.forSdk(31).format, "COLRv0");
        eq("openmoji on API 33 is itself", om.forSdk(33).format, "COLRv1");
        eq("openmoji on API 26 uses the fallback", om.forSdk(26).format, "COLRv0");
        check("openmoji usable on API 31 (via fallback)", om.usableForMerge(31));
        check("openmoji usable on API 33", om.usableForMerge(33));
        eq("noto on API 31 stays itself (no fallback)", noto.forSdk(31).format, "COLRv1");
        check("noto not usable to merge on API 31", !noto.usableForMerge(31));
        check("noto usable on API 33", noto.usableForMerge(33));
        check("twemoji usable on API 26", tw.usableForMerge(26));
        check("apple always 'usable' (never merged)", ap.usableForMerge(21));
        eq("apple forSdk unchanged", ap.forSdk(21).id, "apple");

        check("byId unknown is null", EmojiCatalog.byId("nope") == null);
        check("byId null is null", EmojiCatalog.byId(null) == null);

        manifestAgreement();
    }

    /** The committed manifest (measured against the live files at dev time) matches the class. Optional: skipped if the fixture is absent. */
    static void manifestAgreement() throws Exception {
        File dir = fixturesDir();
        File manifest = dir == null ? null : new File(dir, "manifest.txt");
        if (manifest == null || !manifest.isFile()) {
            System.out.println("NOTE manifestAgreement: fixture not found (tests/java/fixtures/fonts/emojicatalog/manifest.txt); skipped");
            return;
        }
        for (String line : new String(Files.readAllBytes(manifest.toPath()), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            String tr = line.trim();
            if (tr.isEmpty() || tr.startsWith("#")) continue;
            String[] p = tr.split("\\s+");
            String id = p[0];
            long size = Long.parseLong(p[1]);
            String sha = p[2];
            EmojiCatalog.Item it = id.equals("openmoji-colrv0") ? EmojiCatalog.byId("openmoji").fallback : EmojiCatalog.byId(id);
            check("manifest has " + id, it != null);
            if (it == null) continue;
            eq("manifest size " + id, it.size, size);
            eq("manifest sha " + id, it.sha256, sha);
            if (p.length >= 6) {               // twemoji: zip size, zip sha, entry
                eq("manifest zip size " + id, it.zipSize, Long.parseLong(p[3]));
                eq("manifest zip sha " + id, it.zipSha256, p[4]);
                eq("manifest zip entry " + id, it.zipEntry, p[5]);
            }
        }
    }

    static File fixturesDir() {
        String p = System.getProperty("fixtures");
        if (p != null && new File(p, "fonts/emojicatalog").isDirectory()) return new File(p, "fonts/emojicatalog");
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; d != null && i < 8; i++, d = d.getParentFile())
            if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return new File(d, "tests/java/fixtures/fonts/emojicatalog");
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // The streaming verifier
    // ---------------------------------------------------------------------------------------------

    /** A stream that remembers whether the caller closed it (the helper must not). */
    static final class TrackingStream extends ByteArrayInputStream {
        boolean closed = false;
        TrackingStream(byte[] b) { super(b); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    static final class Prog implements EmojiCatalog.Progress {
        long last = -1; boolean monotonic = true; int calls = 0; boolean cancel;
        Prog(boolean cancel) { this.cancel = cancel; }
        @Override public void onProgress(long done, long total) { calls++; if (done < last) monotonic = false; last = done; }
        @Override public boolean cancelled() { return cancel; }
    }

    static void downloadVerifier() throws Exception {
        byte[] data = bytesOf(200000, 42);
        String sha = sha256(data);

        // success
        File dest = new File(tmp, "ok.ttf");
        TrackingStream in = new TrackingStream(data);
        Prog pr = new Prog(false);
        long wrote = EmojiCatalog.download(in, dest, data.length, sha, 50L << 20, pr);
        eq("download returns size", wrote, (long) data.length);
        check("dest written", dest.isFile());
        eq("dest size", dest.length(), (long) data.length);
        eq("dest content sha", sha256(Files.readAllBytes(dest.toPath())), sha);
        check("no .part left", !part(dest).exists());
        check("caller's stream not closed by the helper", !in.closed);
        check("progress reported", pr.calls > 0);
        check("progress monotonic", pr.monotonic);

        // too few bytes: declare more than we provide
        File d2 = new File(tmp, "short.ttf");
        String code = codeOf(() -> EmojiCatalog.download(new ByteArrayInputStream(data), d2, data.length + 10, sha, 50L << 20, null));
        eq("short -> size_mismatch", code, "size_mismatch");
        check("short: no dest", !d2.isFile());
        check("short: no .part", !part(d2).exists());

        // too many bytes: declare fewer than we provide (caught mid-stream)
        File d3 = new File(tmp, "long.ttf");
        code = codeOf(() -> EmojiCatalog.download(new ByteArrayInputStream(data), d3, 1000, sha, 50L << 20, null));
        eq("long -> size_mismatch", code, "size_mismatch");
        check("long: no dest", !d3.isFile());

        // wrong hash, right size
        File d4 = new File(tmp, "badsha.ttf");
        code = codeOf(() -> EmojiCatalog.download(new ByteArrayInputStream(data), d4, data.length, "f".repeat(64), 50L << 20, null));
        eq("wrong hash -> sha_mismatch", code, "sha_mismatch");
        check("badsha: no dest", !d4.isFile());
        check("badsha: no .part", !part(d4).exists());

        // over the cap: expected size above the cap is refused up front
        File d5 = new File(tmp, "big.ttf");
        code = codeOf(() -> EmojiCatalog.download(new ByteArrayInputStream(data), d5, data.length, sha, 1000, null));
        eq("over cap -> too_big", code, "too_big");
        check("cap: no dest", !d5.isFile());

        // cancelled mid-stream
        File d6 = new File(tmp, "cancel.ttf");
        code = codeOf(() -> EmojiCatalog.download(new ByteArrayInputStream(data), d6, data.length, sha, 50L << 20, new Prog(true)));
        eq("cancelled", code, "cancelled");
        check("cancel: no dest", !d6.isFile());
        check("cancel: no .part", !part(d6).exists());

        // an invalid expected hash is refused before any write
        File d7 = new File(tmp, "x.ttf");
        eq("invalid expected sha", codeOf(() -> EmojiCatalog.download(new ByteArrayInputStream(data), d7, data.length, "zz", 50L << 20, null)), "sha_mismatch");
        eq("negative size", codeOf(() -> EmojiCatalog.download(new ByteArrayInputStream(data), d7, -1, sha, 50L << 20, null)), "size_mismatch");

        // an empty file verifies too
        File d8 = new File(tmp, "empty.ttf");
        long w8 = EmojiCatalog.download(new ByteArrayInputStream(new byte[0]), d8, 0, sha256(new byte[0]), 1 << 20, null);
        eq("empty writes 0", w8, 0L);
        check("empty dest exists", d8.isFile());
    }

    static File part(File dest) { return new File(dest.getAbsoluteFile().getParentFile(), "." + dest.getName() + ".part"); }

    // ---------------------------------------------------------------------------------------------
    // The Twemoji zip-entry extraction
    // ---------------------------------------------------------------------------------------------

    static void zipExtraction() throws Exception {
        byte[] ttf = bytesOf(123456, 7);
        String ttfSha = sha256(ttf);
        File src = Files.createTempDirectory("ecz-").toFile();
        File fontFile = new File(src, "twemoji.ttf");
        Files.write(fontFile.toPath(), ttf);
        File lic = new File(src, "LICENSE.md");
        Files.write(lic.toPath(), "license".getBytes("UTF-8"));

        File zip = new File(tmp, "twemoji.zip");
        List<ZipWriter.Item> items = Arrays.asList(
                new ZipWriter.Item("LICENSE.md", lic),
                new ZipWriter.Item("fonts/twemoji.ttf", fontFile));
        ZipWriter.create(zip, items, 6, null, ZipWriter.NONE, null);

        // success
        File dest = new File(tmp, "out-twemoji.ttf");
        long wrote = EmojiCatalog.extractEntry(zip, "fonts/twemoji.ttf", dest, ttf.length, ttfSha, 50L << 20, null);
        eq("extract returns size", wrote, (long) ttf.length);
        eq("extracted content sha", sha256(Files.readAllBytes(dest.toPath())), ttfSha);
        check("no .part left", !part(dest).exists());

        // missing entry
        File d2 = new File(tmp, "miss.ttf");
        eq("missing entry -> zip_entry", codeOf(() -> EmojiCatalog.extractEntry(zip, "fonts/nope.ttf", d2, ttf.length, ttfSha, 50L << 20, null)), "zip_entry");
        check("missing: no dest", !d2.isFile());

        // wrong expected hash for the entry
        File d3 = new File(tmp, "wrong.ttf");
        eq("wrong entry hash -> sha_mismatch", codeOf(() -> EmojiCatalog.extractEntry(zip, "fonts/twemoji.ttf", d3, ttf.length, "a".repeat(64), 50L << 20, null)), "sha_mismatch");
        check("wrong: no dest", !d3.isFile());

        // the entry's declared size is above the cap
        File d4 = new File(tmp, "cap.ttf");
        eq("entry over cap -> too_big", codeOf(() -> EmojiCatalog.extractEntry(zip, "fonts/twemoji.ttf", d4, ttf.length, ttfSha, 1000, null)), "too_big");
        check("cap: no dest", !d4.isFile());

        // not a zip at all
        File notzip = new File(tmp, "notzip.bin");
        Files.write(notzip.toPath(), bytesOf(5000, 9));
        File d5 = new File(tmp, "bad.ttf");
        eq("not a zip -> bad_zip", codeOf(() -> EmojiCatalog.extractEntry(notzip, "fonts/twemoji.ttf", d5, ttf.length, ttfSha, 50L << 20, null)), "bad_zip");
    }

    // ---------------------------------------------------------------------------------------------
    // Fuzz: hostile streams and zips never throw anything but an IOException, hang or run away
    // ---------------------------------------------------------------------------------------------

    static void fuzz(long seed, int rounds) throws Exception {
        Random rnd = new Random(seed);
        File d = new File(tmp, "fuzz.ttf");
        long t0 = System.currentTimeMillis();
        int ok = 0;
        for (int r = 0; r < rounds; r++) {
            byte[] data = new byte[rnd.nextInt(5000)];
            rnd.nextBytes(data);
            long size = rnd.nextBoolean() ? data.length : rnd.nextInt(6000);
            String sha = rnd.nextBoolean() ? sha256(data) : randomHex(rnd);
            long cap = rnd.nextBoolean() ? Long.MAX_VALUE : rnd.nextInt(5000);
            boolean cancel = rnd.nextInt(10) == 0;
            try {
                EmojiCatalog.download(new ByteArrayInputStream(data), d, size, sha, cap, new Prog(cancel));
                ok++;
            } catch (EmojiCatalog.VerifyException e) {
                check("fuzz code non-empty", e.code != null && !e.code.isEmpty());
            } catch (IOException e) {
                // acceptable: any failure stays an IOException
            } catch (Throwable t) {
                fails++; n++; System.out.println("FAIL download fuzz threw " + t); return;
            }
            check("fuzz: never leaves a .part", !part(d).exists());
        }
        // a huge declared size with a small cap must be refused up front, never allocated
        try { EmojiCatalog.download(new ByteArrayInputStream(new byte[10]), d, Long.MAX_VALUE / 2, "0".repeat(64), 1000, null); fails++; n++; System.out.println("FAIL huge size not refused"); }
        catch (EmojiCatalog.VerifyException e) { eq("huge size -> too_big", e.code, "too_big"); }

        // hostile zips
        File zf = new File(tmp, "fz.zip");
        for (int r = 0; r < 300; r++) {
            byte[] z = new byte[rnd.nextInt(2000)];
            rnd.nextBytes(z);
            if (rnd.nextBoolean() && z.length >= 4) { z[0] = 'P'; z[1] = 'K'; z[2] = 3; z[3] = 4; }   // start like a zip
            Files.write(zf.toPath(), z);
            try {
                EmojiCatalog.extractEntry(zf, "fonts/twemoji.ttf", d, 100, "0".repeat(64), 1 << 20, null);
            } catch (IOException e) {
                // fine
            } catch (Throwable t) {
                fails++; n++; System.out.println("FAIL zip fuzz threw " + t); return;
            }
        }
        n++;
        check("fuzz finished in seconds", System.currentTimeMillis() - t0 < 15000);
    }

    static String randomHex(Random rnd) {
        char[] c = new char[64]; String h = "0123456789abcdef";
        for (int i = 0; i < 64; i++) c[i] = h.charAt(rnd.nextInt(16));
        return new String(c);
    }

    interface Thrower { void run() throws Exception; }
    static String codeOf(Thrower t) {
        try { t.run(); return "(no exception)"; }
        catch (EmojiCatalog.VerifyException e) { return e.code; }
        catch (Exception e) { return "(" + e.getClass().getSimpleName() + ")"; }
    }
}
