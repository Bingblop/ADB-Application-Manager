package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What the app menu's Features, Configurations, Signatures and Libraries tabs show, in the parts that need no Android classes:
 * the words for the numbers a package gives (OpenGL ES version, touch screen, keyboard, navigation, input flags), the libraries a
 * manifest asks for, what is in a signing certificate, which APK signature schemes an APK carries, and the native libraries inside it.
 */
public final class AppExtras {
    private AppExtras() {}

    // ---- numbers to words ----

    /** 0x00030002 -> "3.2", 0x00020000 -> "2.0"; "" for 0 (the app does not say). */
    public static String glEs(int req) {
        if (req == 0) return "";
        return ((req >> 16) & 0xFFFF) + "." + (req & 0xFFFF);
    }

    /** ConfigurationInfo.reqTouchScreen */
    public static String touchScreen(int v) {
        switch (v) {
            case 1: return "No touch screen";
            case 2: return "Stylus";
            case 3: return "Finger";
            default: return "Any";
        }
    }

    /** ConfigurationInfo.reqKeyboardType */
    public static String keyboardType(int v) {
        switch (v) {
            case 1: return "No keyboard";
            case 2: return "QWERTY";
            case 3: return "12-key";
            default: return "Any";
        }
    }

    /** ConfigurationInfo.reqNavigation */
    public static String navigation(int v) {
        switch (v) {
            case 1: return "No navigation";
            case 2: return "D-pad";
            case 3: return "Trackball";
            case 4: return "Wheel";
            default: return "Any";
        }
    }

    /** ConfigurationInfo.reqInputFeatures: bit 1 five-way navigation, bit 2 hardware keyboard. */
    public static String inputFeatures(int flags) {
        List<String> l = new ArrayList<String>();
        if ((flags & 1) != 0) l.add("Five-way navigation");
        if ((flags & 2) != 0) l.add("Hardware keyboard");
        if (l.isEmpty()) return "None";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) sb.append(i == 0 ? "" : ", ").append(l.get(i));
        return sb.toString();
    }

    // ---- libraries named in a (decoded) manifest ----

    private static final Pattern LIB_TAG = Pattern.compile("<(uses-library|uses-static-library|uses-native-library)\\b([^>]*)>");
    private static final Pattern ATTR_NAME = Pattern.compile("\\bandroid:name=\"([^\"]*)\"");
    private static final Pattern ATTR_REQUIRED = Pattern.compile("\\bandroid:required=\"([^\"]*)\"");
    private static final Pattern ATTR_VERSION = Pattern.compile("\\bandroid:version=\"([^\"]*)\"");

    /** The libraries a decoded manifest asks for: [{name, kind (library | static | native), required, version}] */
    public static JSONArray manifestLibraries(String xml) {
        JSONArray out = new JSONArray();
        if (xml == null) return out;
        Matcher m = LIB_TAG.matcher(xml);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        while (m.find() && out.length() < 500) {
            String attrs = m.group(2);
            Matcher n = ATTR_NAME.matcher(attrs);
            if (!n.find()) continue;
            String name = n.group(1);
            String kind = "uses-library".equals(m.group(1)) ? "library" : "uses-static-library".equals(m.group(1)) ? "static" : "native";
            if (!seen.add(kind + ":" + name)) continue;
            Matcher r = ATTR_REQUIRED.matcher(attrs);
            Matcher v = ATTR_VERSION.matcher(attrs);
            try {
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("kind", kind);
                o.put("required", !r.find() || !"false".equalsIgnoreCase(r.group(1)));
                o.put("version", v.find() ? v.group(1) : "");
                out.put(o);
            } catch (Exception ignored) {}
        }
        return out;
    }

    // ---- a signing certificate ----

    private static String hex(byte[] d, boolean colons) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (colons && i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", d[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * What a DER-encoded X.509 certificate says: {subject, issuer, serial, notBefore, notAfter (ms), sigAlg, version, keyAlg, keyBits,
     * md5, sha1, sha256}; or {error} when it cannot be read.
     */
    public static JSONObject certInfo(byte[] der) {
        JSONObject o = new JSONObject();
        try {
            X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
            o.put("subject", c.getSubjectDN().getName());
            o.put("issuer", c.getIssuerDN().getName());
            o.put("selfSigned", c.getSubjectDN().getName().equals(c.getIssuerDN().getName()));
            o.put("serial", c.getSerialNumber().toString(16).toUpperCase(Locale.US));
            o.put("notBefore", c.getNotBefore().getTime());
            o.put("notAfter", c.getNotAfter().getTime());
            o.put("sigAlg", c.getSigAlgName());
            o.put("version", c.getVersion());
            PublicKey pk = c.getPublicKey();
            o.put("keyAlg", pk.getAlgorithm());
            int bits = 0;
            if (pk instanceof RSAPublicKey) bits = ((RSAPublicKey) pk).getModulus().bitLength();
            else if (pk instanceof ECPublicKey) bits = ((ECPublicKey) pk).getParams().getOrder().bitLength();
            else if (pk instanceof DSAPublicKey) bits = ((DSAPublicKey) pk).getParams().getP().bitLength();
            o.put("keyBits", bits);
            o.put("md5", hex(MessageDigest.getInstance("MD5").digest(der), true));
            o.put("sha1", hex(MessageDigest.getInstance("SHA-1").digest(der), true));
            o.put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(der), true));
        } catch (Exception e) {
            try { o.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) {}
        }
        return o;
    }

    // ---- APK signature schemes ----

    private static final long ID_V2 = 0x7109871aL, ID_V3 = 0xf05368c0L, ID_V31 = 0x1b93ad61L;

    private static long le32(byte[] b, int o) { return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24; }
    private static long le64(byte[] b, int o) { return le32(b, o) | le32(b, o + 4) << 32; }

    /** The signature schemes an APK carries: "v1" (JAR signature files), "v2", "v3", "v3.1" (the APK Signing Block). v4 is a separate file and is not looked for. */
    public static List<String> sigSchemes(File apk) {
        List<String> out = new ArrayList<String>();
        try {
            ZipFile z = new ZipFile(apk);
            try {
                boolean sf = false, block = false;
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName().toUpperCase(Locale.US);
                    if (n.startsWith("META-INF/") && n.endsWith(".SF")) sf = true;
                    if (n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) block = true;
                }
                if (sf && block) out.add("v1");
            } finally {
                z.close();
            }
        } catch (Exception ignored) {}
        try {
            RandomAccessFile f = new RandomAccessFile(apk, "r");
            try {
                long len = f.length();
                int scan = (int) Math.min(len, 65535 + 22);
                byte[] tail = new byte[scan];
                f.seek(len - scan);
                f.readFully(tail);
                int eocd = -1;
                for (int i = scan - 22; i >= 0; i--) {
                    if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) { eocd = i; break; }
                }
                if (eocd >= 0) {
                    long cd = le32(tail, eocd + 16);
                    if (cd >= 32 && cd <= len) {
                        byte[] foot = new byte[24];
                        f.seek(cd - 24);
                        f.readFully(foot);
                        if ("APK Sig Block 42".equals(new String(foot, 8, 16, "ISO-8859-1"))) {
                            long size = le64(foot, 0);                 // the block without its first size field
                            long start = cd - size - 8;
                            if (size >= 24 && size <= 64L * 1024 * 1024 && start >= 0) {
                                byte[] pairs = new byte[(int) (size - 24)];
                                f.seek(start + 8);
                                f.readFully(pairs);
                                int p = 0;
                                while (p + 12 <= pairs.length) {
                                    long l = le64(pairs, p);
                                    long id = le32(pairs, p + 8);
                                    if (l < 4 || l > pairs.length - p - 8) break;
                                    if (id == ID_V2 && !out.contains("v2")) out.add("v2");
                                    if (id == ID_V3 && !out.contains("v3")) out.add("v3");
                                    if (id == ID_V31 && !out.contains("v3.1")) out.add("v3.1");
                                    p += 8 + (int) l;
                                }
                            }
                        }
                    }
                }
            } finally {
                f.close();
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---- native libraries inside an APK ----

    /** [{name, abi, size}] of lib/<abi>/*.so in an APK, at most 600. */
    public static JSONArray nativeLibs(File apk) {
        JSONArray out = new JSONArray();
        try {
            ZipFile z = new ZipFile(apk);
            try {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements() && out.length() < 600) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (e.isDirectory() || !n.startsWith("lib/") || !n.endsWith(".so")) continue;
                    String[] parts = n.split("/");
                    if (parts.length != 3) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", parts[2]);
                    o.put("abi", parts[1]);
                    o.put("size", e.getSize());
                    out.put(o);
                }
            } finally {
                z.close();
            }
        } catch (Exception ignored) {}
        return out;
    }
}
