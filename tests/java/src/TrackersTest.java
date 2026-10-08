package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Tracker detection: the bundled Exodus list, a hand-built dex (classes defined and merely referenced), apks with splits, stored and deflated entries, junk. */
public class TrackersTest {
    static int n = 0, fails = 0;
    static void check(String what, boolean ok, String extra) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + (extra == null ? "" : " :: " + extra)); } }
    static void check(String what, boolean ok) { check(what, ok, null); }

    static void u4(ByteArrayOutputStream o, long v) { o.write((int) v); o.write((int) (v >> 8)); o.write((int) (v >> 16)); o.write((int) (v >> 24)); }

    /**
     * A small valid-enough dex: header, string_ids, type_ids, class_defs, then the strings (uleb length, MUTF-8, NUL). Types in {@code defined} get a class_def;
     * the ones in {@code referenced} are only type_ids (a class the code mentions but does not contain).
     */
    static byte[] dex(String[] defined, String[] referenced) throws Exception {
        List<String> types = new ArrayList<String>();
        for (String d : defined) types.add("L" + d.replace('.', '/') + ";");
        for (String r : referenced) types.add("L" + r.replace('.', '/') + ";");
        types.add("I");                                                    // a primitive: never a class
        int nt = types.size(), nd = defined.length;
        int strIdsOff = 0x70, typeIdsOff = strIdsOff + 4 * nt, classDefsOff = typeIdsOff + 4 * nt, dataOff = classDefsOff + 32 * nd;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] strOff = new int[nt];
        for (int i = 0; i < nt; i++) {
            strOff[i] = dataOff + data.size();
            byte[] b = types.get(i).getBytes(Charset.forName("UTF-8"));
            data.write(b.length);                                          // uleb128 (every name here is short)
            data.write(b);
            data.write(0);
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[] {'d', 'e', 'x', '\n', '0', '3', '5', 0});
        u4(o, 0); o.write(new byte[20]);                                   // checksum, signature (not read)
        u4(o, dataOff + data.size());                                      // file_size
        u4(o, 0x70); u4(o, 0x12345678);                                    // header_size, endian_tag
        u4(o, 0); u4(o, 0); u4(o, 0);                                      // link_size, link_off, map_off
        u4(o, nt); u4(o, strIdsOff);                                       // string_ids
        u4(o, nt); u4(o, typeIdsOff);                                      // type_ids
        u4(o, 0); u4(o, 0); u4(o, 0); u4(o, 0); u4(o, 0); u4(o, 0);        // proto, field, method ids
        u4(o, nd); u4(o, classDefsOff);                                    // class_defs
        u4(o, data.size()); u4(o, dataOff);                                // data
        for (int i = 0; i < nt; i++) u4(o, strOff[i]);
        for (int i = 0; i < nt; i++) u4(o, i);                             // type i -> string i
        for (int i = 0; i < nd; i++) { u4(o, i); for (int k = 0; k < 7; k++) u4(o, 0); }
        o.write(data.toByteArray());
        return o.toByteArray();
    }

    static File apk(File dir, String name, boolean stored, Object... entries) throws Exception {
        File f = new File(dir, name);
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
        for (int i = 0; i < entries.length; i += 2) {
            String en = (String) entries[i];
            byte[] b = (byte[]) entries[i + 1];
            ZipEntry e = new ZipEntry(en);
            if (stored && en.endsWith(".dex")) {
                CRC32 c = new CRC32(); c.update(b);
                e.setMethod(ZipEntry.STORED); e.setSize(b.length); e.setCompressedSize(b.length); e.setCrc(c.getValue());
            }
            z.putNextEntry(e);
            z.write(b);
            z.closeEntry();
        }
        z.close();
        return f;
    }

    static String ids(Trackers.Scan s) { return Arrays.toString(s.ids); }

    public static void main(String[] a) throws Exception {
        String fix = System.getProperty("fixtures", "fixtures");
        File asset = new File(fix, "../../../assets/trackers.json");
        Trackers t = Trackers.load(new String(Files.readAllBytes(asset.toPath()), Charset.forName("UTF-8")));
        check("the bundled list has the Exodus trackers (about 430)", t.db.n >= 400 && t.db.n <= 600, "n=" + t.db.n);
        check("Firebase Analytics is id 49, AdMob 312", t.db.indexOf(49) >= 0 && t.db.name[t.db.indexOf(49)].contains("Firebase Analytics") && t.db.indexOf(312) >= 0 && t.db.name[t.db.indexOf(312)].contains("AdMob"));
        check("every tracker has a name and an unambiguous id", t.db.name[0] != null && java.util.Arrays.stream(t.db.id).distinct().count() == t.db.n);
        check("the list says when it was copied and has a version for the cache", t.db.retrieved.matches("\\d{4}-\\d{2}-\\d{2}") && t.dbVersion().startsWith(t.db.retrieved + ":"), t.dbVersion());

        File dir = Files.createTempDirectory("trk").toFile();

        // a defined tracker class is found; the same apk stored uncompressed gives the same answer
        byte[] d1 = dex(new String[] {"com.example.app.Main", "com.google.firebase.analytics.FirebaseAnalytics", "com.google.android.gms.ads.AdRequest"}, new String[] {"java.lang.Object"});
        Trackers.Scan s = t.scan(Arrays.asList(apk(dir, "a.apk", false, "classes.dex", d1, "AndroidManifest.xml", new byte[] {1})));
        check("Firebase Analytics and AdMob are found in a class that the app defines", s.scannable && ids(s).equals("[49, 312]"), ids(s));
        Trackers.Scan s2 = t.scan(Arrays.asList(apk(dir, "a-stored.apk", true, "classes.dex", d1)));
        check("a stored (uncompressed) dex gives the same answer", s2.scannable && ids(s2).equals(ids(s)), ids(s2));

        // only mentioned, not defined: not a tracker inside the app
        byte[] d2 = dex(new String[] {"com.example.app.Main"}, new String[] {"com.google.firebase.analytics.FirebaseAnalytics"});
        Trackers.Scan r = t.scan(Arrays.asList(apk(dir, "ref.apk", false, "classes.dex", d2)));
        check("a class that is only referenced does not count", r.scannable && r.ids.length == 0, ids(r));

        // names that merely look alike
        byte[] d3 = dex(new String[] {"org.acme.ads.Banner", "com.example.firebase.Analytics", "com.googleish.Thing"}, new String[0]);
        Trackers.Scan near = t.scan(Arrays.asList(apk(dir, "near.apk", false, "classes.dex", d3)));
        check("near misses find nothing", near.scannable && near.ids.length == 0, ids(near));

        // dex files after the first, and splits
        byte[] d4 = dex(new String[] {"com.flurry.android.FlurryAgent"}, new String[0]);
        File base = apk(dir, "base.apk", false, "classes.dex", d2, "classes2.dex", d4);
        File resOnly = apk(dir, "split_config.apk", false, "resources.arsc", new byte[] {1, 2, 3});
        File feature = apk(dir, "split_feature.apk", false, "classes.dex", d1);
        Trackers.Scan multi = t.scan(Arrays.asList(base, resOnly, feature));
        check("classes2.dex is read, a split with only resources is skipped, a split with code counts", multi.scannable && multi.ids.length == 3 && ids(multi).contains("49") && ids(multi).contains("312"), ids(multi));
        check("the ids come ascending", multi.ids[0] < multi.ids[1] && multi.ids[1] < multi.ids[2], ids(multi));

        // nothing to read
        check("an apk without dex is not scannable (not 'no trackers')", !t.scan(Arrays.asList(resOnly)).scannable);
        check("a file that is not a zip is not scannable", !t.scan(Arrays.asList(new File(dir, "missing.apk"))).scannable);
        File junk = new File(dir, "junk.apk"); Files.write(junk.toPath(), new byte[] {1, 2, 3, 4});
        check("junk bytes are not scannable and do not throw", !t.scan(Arrays.asList(junk)).scannable);

        // a damaged dex (bad magic / absurd header) is survived, the good file next to it still counts
        byte[] bad = d1.clone(); bad[0] = 'x';
        File badApk = apk(dir, "bad.apk", false, "classes.dex", bad);
        byte[] huge = d1.clone(); huge[0x38] = (byte) 0xFF; huge[0x39] = (byte) 0xFF; huge[0x3A] = (byte) 0xFF; huge[0x3B] = (byte) 0x7F;
        File hugeApk = apk(dir, "huge.apk", false, "classes.dex", huge);
        Trackers.Scan dmg = t.scan(Arrays.asList(badApk, hugeApk, feature));
        check("damaged dex files are skipped and the good split still counts", dmg.scannable && ids(dmg).equals("[49, 312]"), ids(dmg));
        check("only damaged dex files: not scannable", !t.scan(Arrays.asList(badApk, hugeApk)).scannable);

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
