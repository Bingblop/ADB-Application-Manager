package com.bloatware.bingblop;

/** stat records are believed only when they carry the scan's own tag: a file name cannot forge another file's record. */
public class SdmStatFrameTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static final String T = "__SDM_test__";

  static String run(String... lines) {
    SdmStatFrame f = new SdmStatFrame(T);
    StringBuilder sb = new StringBuilder();
    for (String l : lines) { Sdm.Entry e = f.feed(l); if (e != null) sb.append(e.path).append(':').append(e.size).append(' '); }
    Sdm.Entry e = f.finish(); if (e != null) sb.append(e.path).append(':').append(e.size).append(' ');
    return sb.toString().trim();
  }

  public static void main(String[] args) {
    check("the format carries the tag and is single-quoted", new SdmStatFrame(T).format().equals("'" + T + " %f %s %Y %u %n'"));
    check("two tags made by the default constructor differ", !new SdmStatFrame().format().equals(new SdmStatFrame().format()));
    check("a record is returned when the next one starts, and the last at finish",
        run(T + " 81a4 5 100 1 /a", T + " 81a4 7 200 1 /b").equals("/a:5 /b:7"));
    check("names with spaces are kept", run(T + " 81a4 5 100 1 /a b/c d.txt").equals("/a b/c d.txt:5"));
    check("a name with a newline and a forged record inside it: the forged line is not believed, and the file itself is dropped",
        run(T + " 81a4 5 100 1 /sd/Download/x", "81a4 999 1 10000 /sd/DCIM/IMG_0001.jpg", T + " 81a4 5 100 1 /sd/DCIM/IMG_0001.jpg").equals("/sd/DCIM/IMG_0001.jpg:5"));
    check("a name that runs on over a line (no forgery) is dropped too, the next record is kept",
        run(T + " 81a4 5 100 1 /sd/a", "b.txt", T + " 81a4 6 100 1 /sd/c").equals("/sd/c:6"));
    check("the last record dropped when its name runs on", run(T + " 81a4 5 100 1 /sd/a", "b.txt").equals(""));
    check("a forged line carrying a wrong tag is just a line of text", run(T + " 81a4 5 100 1 /sd/a", "__SDM_other__ 81a4 999 1 1 /sd/zz").equals(""));
    check("lines before the first record are ignored", run("junk", T + " 81a4 5 100 1 /a").equals("/a:5"));
    check("a tagged line that is not a stat record yields nothing", run(T + " zz 1 1 1 /x", T + " 81a4 5 100 1 /a").equals("/a:5"));
    check("empty output gives nothing", run().isEmpty());
    check("null and the bare tag do not crash", run(null, T).isEmpty());
    SdmStatFrame e1 = new SdmStatFrame(), e2 = new SdmStatFrame();
    check("the end marker is made up per scan, not the shared one", !e1.endMarker().equals(e2.endMarker()) && !e1.endMarker().equals("__SDM_END__") && e1.endMarker().startsWith("__SDM_END_"));
    check("the end marker is not the record tag", !e1.endMarker().equals(e1.format()));
    System.out.println(fails == 0 ? "PASS SdmStatFrameTest: " + n + " checks" : "FAILED SdmStatFrameTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
