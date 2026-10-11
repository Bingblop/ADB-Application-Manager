package com.bloatware.bingblop;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the manifest of a split APK says about the split, read from the decoded text of its AndroidManifest.xml (see ManifestDecoder).
 * Free of Android classes, so it is tested off the device.
 */
final class SplitInfo {

    private SplitInfo() {}

    private static final Pattern SPLIT = Pattern.compile("(?i)\\bsplit\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern CONFIG_FOR = Pattern.compile("(?i)\\bconfigForSplit\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern FEATURE = Pattern.compile("(?i)\\bisFeatureSplit\\s*=\\s*\"(true|false)\"");

    /** The start tag of the manifest element: only its own attributes say what the split is (a later element may carry a "split" of its own). */
    private static String manifestTag(String xml) {
        int a = xml.indexOf("<manifest");
        if (a < 0) return xml;
        int b = xml.indexOf('>', a);
        return b < 0 ? xml.substring(a) : xml.substring(a, b);
    }

    /**
     * {split, configForSplit, isFeatureSplit}: split is null for a base APK (it has no split attribute), configForSplit is "" for a
     * config split of the base itself and names the feature module otherwise, isFeatureSplit is "true" or "false". null when there is no text.
     */
    static String[] parse(String xml) {
        if (xml == null || xml.isEmpty()) return null;
        String tag = manifestTag(xml);
        Matcher m = SPLIT.matcher(tag);
        String split = m.find() ? m.group(1) : null;
        Matcher c = CONFIG_FOR.matcher(tag);
        String configFor = c.find() ? c.group(1) : "";
        Matcher f = FEATURE.matcher(tag);
        String feature = f.find() ? f.group(1).toLowerCase(java.util.Locale.US) : "false";
        return new String[] { split, configFor, feature };
    }
}
