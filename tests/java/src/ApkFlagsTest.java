package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Which found package files are the same file twice, and which are an older version of a package that is there in a newer one. */
public class ApkFlagsTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static ApkFlags.Item item(String path, long size, long mtime, String pkg, long vc, String hash) {
    ApkFlags.Item i = new ApkFlags.Item(); i.path = path; i.size = size; i.mtime = mtime; i.pkg = pkg; i.versionCode = vc; i.hash = hash; return i;
  }
  public static void main(String[] args) throws Exception {
    // identical content
    ApkFlags.Item a = item("/sd/Download/app.apk", 100, 2000, "com.a", 5, "h1"), b = item("/sd/Download/app (1).apk", 100, 1000, "com.a", 5, "h1"), c = item("/sd/Other/app.apk", 100, 2000, "com.a", 5, "h1");
    List<ApkFlags.Item> l = new ArrayList<ApkFlags.Item>(); l.add(a); l.add(b); l.add(c);
    ApkFlags.compute(l);
    check("identical files: the newest is kept, on a tie the shorter path; the others point at it", c.duplicateOf == null && a.duplicateOf.equals(c.path) && b.duplicateOf.equals(c.path));
    check("exactly one copy is not a duplicate", (a.duplicateOf == null ? 1 : 0) + (b.duplicateOf == null ? 1 : 0) + (c.duplicateOf == null ? 1 : 0) == 1);
    check("the same size with another hash is not a duplicate", dup(item("/x/1", 50, 1, "p", 1, "h1"), item("/x/2", 50, 1, "p", 1, "h2")) == 0);
    check("no hash computed: nothing is called a duplicate", dup(item("/x/1", 50, 1, "p", 1, ""), item("/x/2", 50, 1, "p", 1, "")) == 0);
    check("the same hash with another size is not a duplicate", dup(item("/x/1", 50, 1, "p", 1, "h"), item("/x/2", 51, 1, "p", 1, "h")) == 0);
    // versions
    ApkFlags.Item v1 = item("/d/a-1.apk", 10, 100, "com.a", 1, ""), v2 = item("/d/a-2.apk", 11, 200, "com.a", 2, ""), v3 = item("/d/a-3.apk", 12, 300, "com.a", 3, ""), o = item("/d/b.apk", 13, 100, "com.b", 9, "");
    l = new ArrayList<ApkFlags.Item>(); l.add(v1); l.add(v2); l.add(v3); l.add(o);
    ApkFlags.compute(l);
    check("older versions of a package are marked, with the newest code and file", v1.older && v2.older && !v3.older && v1.newestVersion == 3 && v2.newestPath.equals(v3.path));
    check("a package with one file is never older; another package is left alone", !o.older && o.newestVersion == -1);
    ApkFlags.Item u = item("/d/unknown.apk", 5, 1, "", -1, "");
    l = new ArrayList<ApkFlags.Item>(); l.add(u); l.add(v1); l.add(v3);
    ApkFlags.compute(l);
    check("a file whose package could not be read is not marked", !u.older && u.duplicateOf == null);
    // a copy of the newest version counts once: the duplicate is a duplicate, not "older"
    ApkFlags.Item n1 = item("/d/n.apk", 20, 500, "com.n", 7, "hh"), n2 = item("/e/n.apk", 20, 400, "com.n", 7, "hh"), n0 = item("/d/n-old.apk", 19, 100, "com.n", 6, "");
    l = new ArrayList<ApkFlags.Item>(); l.add(n1); l.add(n2); l.add(n0);
    ApkFlags.compute(l);
    check("two copies of the newest version: one is a duplicate, neither is older, the old one is older than the kept copy", n2.duplicateOf.equals(n1.path) && !n1.older && !n2.older && n0.older && n0.newestPath.equals(n1.path));
    // other builds of a package (another signer, another set of native libraries) are not "newer"
    ApkFlags.Item m1 = item("/d/m1.apk", 1, 1, "com.m", 10, ""), m2 = item("/d/m2.apk", 2, 2, "com.m", 99, ""), m3 = item("/d/m3.apk", 3, 3, "com.m", 99, "");
    m1.signer = "AAAA"; m2.signer = "BBBB"; m3.signer = "AAAA"; m3.abis = "x86";
    l = new ArrayList<ApkFlags.Item>(); l.add(m1); l.add(m2); l.add(m3);
    ApkFlags.compute(l);
    check("a build with another signer, or for another ABI, does not make this one older", !m1.older && !m2.older && !m3.older);
    ApkFlags.Item m4 = item("/d/m4.apk", 4, 4, "com.m", 11, ""); m4.signer = "AAAA";
    l.add(m4); ApkFlags.compute(l);
    check("the same signer and ABIs still compare", m1.older && m1.newestPath.equals(m4.path));
    // same version code twice, different files
    ApkFlags.Item s1 = item("/d/s1.apk", 1, 100, "com.s", 4, ""), s2 = item("/d/s2.apk", 2, 200, "com.s", 4, "");
    l = new ArrayList<ApkFlags.Item>(); l.add(s1); l.add(s2);
    ApkFlags.compute(l);
    check("the same version code in two different files is not 'older'", !s1.older && !s2.older);
    // hashing real files
    File dir = Files.createTempDirectory("af").toFile();
    File f1 = new File(dir, "one.apk"), f2 = new File(dir, "two.apk"), f3 = new File(dir, "three.apk"), f4 = new File(dir, "lonely.apk");
    Files.write(f1.toPath(), "same content".getBytes(StandardCharsets.UTF_8)); Files.write(f2.toPath(), "same content".getBytes(StandardCharsets.UTF_8));
    Files.write(f3.toPath(), "diff content".getBytes(StandardCharsets.UTF_8)); Files.write(f4.toPath(), "a file of its own size!".getBytes(StandardCharsets.UTF_8));
    List<ApkFlags.Item> real = new ArrayList<ApkFlags.Item>();
    for (File f : new File[]{f1, f2, f3, f4}) { ApkFlags.Item i = new ApkFlags.Item(); i.path = f.getPath(); i.size = f.length(); i.mtime = f.lastModified(); real.add(i); }
    ApkFlags.hashSameSizes(real, 1L << 20, Long.MAX_VALUE);
    check("only files that share a size are hashed", !real.get(0).hash.isEmpty() && !real.get(1).hash.isEmpty() && !real.get(2).hash.isEmpty() && real.get(3).hash.isEmpty());
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
    StringBuilder want = new StringBuilder(); for (byte x : md.digest("same content".getBytes(StandardCharsets.UTF_8))) want.append(String.format("%02x", x & 0xFF));
    check("the hash is the SHA-256 of the content", real.get(0).hash.equals(want.toString()));
    ApkFlags.compute(real);
    int dups = 0; for (ApkFlags.Item i : real) if (i.duplicateOf != null) dups++;
    check("real files: the two with the same content, one of them a duplicate", real.get(0).hash.equals(real.get(1).hash) && !real.get(0).hash.equals(real.get(2).hash) && dups == 1);
    check("a file over the size limit is not hashed", ApkFlags.sha256(f1, 3).isEmpty() && !ApkFlags.sha256(f1, 100).isEmpty());
    check("a deadline that has passed gives up in the middle of a file", ApkFlags.sha256(f1, 100, 0).isEmpty());
    check("a missing file gives no hash", ApkFlags.sha256(new File(dir, "nope"), 100).isEmpty());
    check("hashing reports that the time ran out", !ApkFlags.hashSameSizes(real, 1L << 20, 0) && ApkFlags.hashSameSizes(real, 1L << 20, Long.MAX_VALUE));
    for (File f : dir.listFiles()) f.delete(); dir.delete();
    System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
  static int dup(ApkFlags.Item x, ApkFlags.Item y) {
    List<ApkFlags.Item> l = new ArrayList<ApkFlags.Item>(); l.add(x); l.add(y); ApkFlags.compute(l);
    return (x.duplicateOf != null ? 1 : 0) + (y.duplicateOf != null ? 1 : 0);
  }
}
