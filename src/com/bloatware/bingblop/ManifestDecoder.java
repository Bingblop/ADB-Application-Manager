package com.bloatware.bingblop;

import android.content.res.Resources;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Decodes the compiled (binary AXML) AndroidManifest.xml inside an APK back into readable XML.
 * Resource references are resolved to @type/name through the app's own Resources when possible.
 */
public final class ManifestDecoder {

    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_XML_START_NAMESPACE_TYPE = 0x0100;
    private static final int RES_XML_END_NAMESPACE_TYPE = 0x0101;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;
    private static final int RES_XML_CDATA_TYPE = 0x0104;
    private static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;

    private static final int UTF8_FLAG = 1 << 8;

    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_ATTRIBUTE = 0x02;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_FLOAT = 0x04;
    private static final int TYPE_DIMENSION = 0x05;
    private static final int TYPE_FRACTION = 0x06;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
    private static final int TYPE_INT_BOOLEAN = 0x12;
    private static final int TYPE_FIRST_COLOR = 0x1c;
    private static final int TYPE_LAST_COLOR = 0x1f;

    private static final String[] DIMENSION_UNITS = {"px", "dp", "sp", "pt", "in", "mm"};
    private static final String[] FRACTION_UNITS = {"%", "%p"};
    private static final float[] RADIX_MULTS = {1.0f / (1 << 8), 1.0f / (1 << 15), 1.0f / (1 << 23), 1.0f / 2147483648f};

    private final Resources appResources;
    private String[] strings = new String[0];
    private int[] resourceMap = new int[0];
    private final Map<String, String> uriToPrefix = new HashMap<String, String>();
    private final Map<String, String> pendingNamespaces = new LinkedHashMap<String, String>();

    private ManifestDecoder(Resources appResources) {
        this.appResources = appResources;
    }

    // A real AndroidManifest.xml is KBs, occasionally low MBs; this is only ever called on an installed
    // app's own APK (including a sideloaded one), so bound the read instead of trusting the zip entry's
    // own (spoofable) declared size.
    private static final int MAX_MANIFEST_BYTES = 32 * 1024 * 1024;

    /** Reads AndroidManifest.xml from the APK at apkPath and returns it as indented XML text. */
    public static String decodeApk(String apkPath, Resources appResources) throws Exception {
        ZipFile zip = new ZipFile(apkPath);
        try {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) throw new IllegalStateException("AndroidManifest.xml not found in " + apkPath);
            InputStream in = zip.getInputStream(entry);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int len;
            int total = 0;
            while ((len = in.read(buf)) > 0) {
                total += len;
                if (total > MAX_MANIFEST_BYTES) throw new IllegalStateException("AndroidManifest.xml is implausibly large; refusing to decode it");
                out.write(buf, 0, len);
            }
            in.close();
            return new ManifestDecoder(appResources).decode(out.toByteArray());
        } finally {
            zip.close();
        }
    }

    private String decode(byte[] data) {
        if (data.length < 8) throw new IllegalStateException("Not a binary XML file (too short)");
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int fileType = bb.getShort(0) & 0xFFFF;
        if (fileType != RES_XML_TYPE) throw new IllegalStateException("Not a binary XML file");
        int headerSize = bb.getShort(2) & 0xFFFF;

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        int depth = 0;
        boolean openTagPending = false;
        boolean childOnSameElement = false;
        // Tracks which element is actually open at each depth, so a crafted END chunk referencing the
        // wrong pool string can't make the output claim to close a tag that was never opened.
        java.util.ArrayDeque<String> openTags = new java.util.ArrayDeque<String>();

        int pos = headerSize;
        while (pos + 8 <= data.length) {
          try {
            int type = bb.getShort(pos) & 0xFFFF;
            int chunkHeaderSize = bb.getShort(pos + 2) & 0xFFFF;
            int chunkSize = bb.getInt(pos + 4);
            // pos/chunkSize come straight from the file; add as longs first so a huge chunkSize can't
            // overflow the int sum back into something that looks in-range.
            if (chunkSize <= 0 || chunkHeaderSize < 8 || (long) pos + chunkSize > data.length) break;

            switch (type) {
                case RES_STRING_POOL_TYPE:
                    readStringPool(bb, pos);
                    break;
                case RES_XML_RESOURCE_MAP_TYPE: {
                    int count = Math.max(0, (chunkSize - chunkHeaderSize) / 4);
                    resourceMap = new int[count];
                    for (int i = 0; i < count; i++) {
                        int off = pos + chunkHeaderSize + i * 4;
                        if (off < 0 || off + 4 > data.length) break; // a crafted/short chunk: keep what we read so far
                        resourceMap[i] = bb.getInt(off);
                    }
                    break;
                }
                case RES_XML_START_NAMESPACE_TYPE: {
                    String prefix = str(bb.getInt(pos + 16));
                    String uri = str(bb.getInt(pos + 20));
                    uriToPrefix.put(uri, prefix);
                    pendingNamespaces.put(prefix, uri);
                    break;
                }
                case RES_XML_END_NAMESPACE_TYPE:
                    break;
                case RES_XML_START_ELEMENT_TYPE: {
                    if (openTagPending) xml.append(">\n");
                    int ext = pos + chunkHeaderSize;
                    String name = str(bb.getInt(ext + 4));
                    int attrStart = bb.getShort(ext + 8) & 0xFFFF;
                    int attrSize = bb.getShort(ext + 10) & 0xFFFF;
                    int attrCount = bb.getShort(ext + 12) & 0xFFFF;

                    indent(xml, depth);
                    xml.append('<').append(name);
                    for (Map.Entry<String, String> ns : pendingNamespaces.entrySet()) {
                        xml.append("\n");
                        indent(xml, depth + 2);
                        xml.append("xmlns:").append(ns.getKey()).append("=\"").append(escape(ns.getValue())).append('"');
                    }
                    pendingNamespaces.clear();

                    for (int i = 0; i < attrCount; i++) {
                        int a = ext + attrStart + i * attrSize;
                        if (attrSize < 20 || a < 0 || (long) a + 20 > data.length) break; // crafted/short chunk: keep the attributes already read
                        String nsUri = str(bb.getInt(a));
                        int nameIdx = bb.getInt(a + 4);
                        int rawIdx = bb.getInt(a + 8);
                        int dataType = bb.get(a + 15) & 0xFF;
                        int dataValue = bb.getInt(a + 16);

                        String attrName = attributeName(nameIdx);
                        String prefix = nsUri.isEmpty() ? null : uriToPrefix.get(nsUri);
                        String value = rawIdx >= 0 ? str(rawIdx) : formatValue(dataType, dataValue);

                        xml.append("\n");
                        indent(xml, depth + 2);
                        if (prefix != null && !prefix.isEmpty()) xml.append(prefix).append(':');
                        xml.append(attrName).append("=\"").append(escape(value)).append('"');
                    }
                    openTagPending = true;
                    childOnSameElement = false;
                    openTags.push(name);
                    depth++;
                    break;
                }
                case RES_XML_END_ELEMENT_TYPE: {
                    depth--;
                    // The element actually on top of the open-tags stack, not the END chunk's own name
                    // reference: a crafted file can point that reference at any pool string, which would
                    // otherwise let a manifest claim to close a different tag than the one it opened.
                    String name = !openTags.isEmpty() ? openTags.pop() : str(bb.getInt(pos + chunkHeaderSize + 4));
                    if (openTagPending) {
                        xml.append(" />\n");
                    } else {
                        if (!childOnSameElement) indent(xml, depth);
                        xml.append("</").append(name).append(">\n");
                    }
                    openTagPending = false;
                    childOnSameElement = false;
                    break;
                }
                case RES_XML_CDATA_TYPE: {
                    if (openTagPending) {
                        xml.append('>');
                        openTagPending = false;
                    }
                    xml.append(escape(str(bb.getInt(pos + chunkHeaderSize))));
                    childOnSameElement = true;
                    break;
                }
                default:
                    break;
            }
            pos += chunkSize;
          } catch (RuntimeException crafted) {
              // A chunk pointed somewhere nonsensical (crafted or just corrupted file): stop decoding and
              // return everything read so far rather than let one bad chunk surface a raw exception message.
              break;
          }
        }
        return xml.toString();
    }

    private void readStringPool(ByteBuffer bb, int pos) {
        int headerSize = bb.getShort(pos + 2) & 0xFFFF;
        int count = bb.getInt(pos + 8);
        int flags = bb.getInt(pos + 16);
        int stringsStart = bb.getInt(pos + 20);
        boolean utf8 = (flags & UTF8_FLAG) != 0;
        // A crafted count (e.g. 0xFFFFFFFF) would throw NegativeArraySizeException and, since this chunk
        // is usually the very first one, abort decoding the entire manifest instead of just this string
        // pool; clamp it to the buffer's own capacity, which no real pool could exceed anyway.
        strings = new String[count >= 0 && count <= bb.capacity() ? count : 0];
        Charset charset = Charset.forName(utf8 ? "UTF-8" : "UTF-16LE");
        for (int i = 0; i < strings.length; i++) {
            try {
                // The offset table entry itself, not just the bytes it points to, can be crafted out of
                // bounds; read it inside the same try as the rest so one bad entry only blanks that string,
                // the way every other malformed-string case here already degrades.
                int offset = pos + stringsStart + bb.getInt(pos + headerSize + i * 4);
                if (utf8) {
                    // UTF-16 length then UTF-8 byte length, each 1 or 2 bytes
                    int p = offset;
                    p += ((bb.get(p) & 0x80) != 0) ? 2 : 1;
                    int byteLen = bb.get(p) & 0xFF;
                    if ((byteLen & 0x80) != 0) {
                        byteLen = ((byteLen & 0x7F) << 8) | (bb.get(p + 1) & 0xFF);
                        p += 2;
                    } else {
                        p += 1;
                    }
                    strings[i] = new String(bb.array(), p, byteLen, charset);
                } else {
                    int charLen = bb.getShort(offset) & 0xFFFF;
                    int p = offset + 2;
                    if ((charLen & 0x8000) != 0) {
                        charLen = ((charLen & 0x7FFF) << 16) | (bb.getShort(offset + 2) & 0xFFFF);
                        p += 2;
                    }
                    strings[i] = new String(bb.array(), p, charLen * 2, charset);
                }
            } catch (Exception e) {
                strings[i] = "";
            }
        }
    }

    private String str(int index) {
        return index >= 0 && index < strings.length && strings[index] != null ? strings[index] : "";
    }

    /** Attribute names can be stripped from the string pool; fall back to the framework attr id. */
    private String attributeName(int index) {
        String name = str(index);
        if (!name.isEmpty()) return name;
        if (index >= 0 && index < resourceMap.length) {
            try {
                return Resources.getSystem().getResourceEntryName(resourceMap[index]);
            } catch (Exception ignored) {}
            return String.format("attr_0x%08x", resourceMap[index]);
        }
        return "unknown";
    }

    private String formatValue(int type, int data) {
        switch (type) {
            case TYPE_REFERENCE:
                return data == 0 ? "@null" : "@" + resourceName(data);
            case TYPE_ATTRIBUTE:
                return "?" + resourceName(data);
            case TYPE_STRING:
                return str(data);
            case TYPE_FLOAT:
                return String.valueOf(Float.intBitsToFloat(data));
            case TYPE_DIMENSION:
                return complexToFloat(data) + unit(DIMENSION_UNITS, data & 0xF);
            case TYPE_FRACTION:
                return (complexToFloat(data) * 100) + unit(FRACTION_UNITS, data & 0xF);
            case TYPE_INT_DEC:
                return String.valueOf(data);
            case TYPE_INT_HEX:
                return String.format("0x%08x", data);
            case TYPE_INT_BOOLEAN:
                return data != 0 ? "true" : "false";
            default:
                if (type >= TYPE_FIRST_COLOR && type <= TYPE_LAST_COLOR) {
                    return String.format("#%08X", data);
                }
                return String.format("0x%08x", data);
        }
    }

    private String resourceName(int id) {
        Resources[] candidates = {appResources, Resources.getSystem()};
        for (Resources res : candidates) {
            if (res == null) continue;
            try {
                String pkg = res.getResourcePackageName(id);
                String type = res.getResourceTypeName(id);
                String entry = res.getResourceEntryName(id);
                return ("android".equals(pkg) ? "android:" : "") + type + "/" + entry;
            } catch (Exception ignored) {}
        }
        return String.format("0x%08x", id);
    }

    private static float complexToFloat(int complex) {
        return (complex & 0xFFFFFF00) * RADIX_MULTS[(complex >> 4) & 3];
    }

    private static String unit(String[] units, int index) {
        return index < units.length ? units[index] : "";
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("    ");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
