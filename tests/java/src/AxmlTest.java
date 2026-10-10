import com.bloatware.bingblop.*;
import com.bloatware.bingblop.ZipTool.*;
import java.io.*;
public class AxmlTest {
  public static void main(String[] a) throws Exception {
    Archive ar = ZipTool.open(new File(System.getProperty("testapk")));
    int axml = 0, ok = 0, bad = 0;
    for (Entry e : ar.entries) {
      if (e.dir || !e.name.endsWith(".xml")) continue;
      Head h = ZipTool.readHead(ar, e, 8 * 1024 * 1024);
      if (!ZipTool.classify(e.name, h.data, h.data.length).equals("axml")) { System.out.println("not axml: " + e.name); continue; }
      axml++;
      try { String xml = ManifestDecoder.decodeBytes(h.data, null); ok++; if (e.name.equals("AndroidManifest.xml")) { System.out.println(xml.substring(0, Math.min(700, xml.length()))); } else if (ok < 4) System.out.println("--- " + e.name + "\n" + xml.substring(0, Math.min(300, xml.length()))); }
      catch (Throwable t) { bad++; System.out.println("decode failed " + e.name + ": " + t); }
    }
    System.out.println("axml entries=" + axml + " decoded=" + ok + " failed=" + bad);
    if (axml == 0 || bad > 0) { System.out.println("FAIL: every compiled XML file of the APK should decode (and there should be some)"); System.exit(1); }
    System.out.println("ALL PASS");
  }
}
