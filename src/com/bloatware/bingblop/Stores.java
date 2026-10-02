package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Catalog clients for the extra Store sub-tabs added in v5.6, alongside the existing {@link ShizuStore}:
 *
 *  - <b>Komi</b> (github): a curated set of popular open-source Android apps that ship an APK on their
 *    GitHub Releases - the kind of "app store for GitHub releases" that komi-store/komi-store is. Each
 *    entry resolves live through {@link UpdateManager#githubRelease} + {@link UpdateManager#pickApk}.
 *  - <b>Orion</b>: the public catalog published at RookieEnough/Orion-Data (apps.json). Android entries
 *    are resolved from a direct APK link, a GitHub repo + release keyword, or a Codeberg repo.
 *  - <b>F-Droid</b>: a directory of known F-Droid repositories (forum.f-droid.org/t/known-repositories).
 *    A repo is browsed by parsing its index-v2.json; apps install from {repoAddress}{file}.
 *
 * Everything here is pure HTTP/JSON (no Android UI), so the mapping/resolve logic is unit-testable
 * off-device. Nothing is rehosted - the app only reads public catalogs and installs upstream APKs
 * through this app's existing installer plumbing.
 */
public final class Stores {

    private Stores() {}

    // =============================================================================================
    // Komi - curated GitHub-releases catalog
    // =============================================================================================

    /**
     * {id, name, owner, repo, pkg, assetFilter, category, description}. The APK asset is chosen by
     * {@link UpdateManager#pickApk} for the device ABI; assetFilter narrows it when a repo ships more
     * than one app's APKs. The icon is the GitHub owner's avatar (github.com/{owner}.png).
     */
    private static final String[][] KOMI = {
        {"newpipe", "NewPipe", "TeamNewPipe", "NewPipe", "org.schabi.newpipe", "", "Media", "Lightweight YouTube frontend - no ads, background play, downloads."},
        {"libretube", "LibreTube", "libre-tube", "LibreTube", "com.github.libretube", "", "Media", "Privacy-friendly YouTube through the Piped backend."},
        {"mihon", "Mihon", "mihonapp", "mihon", "app.mihon", "", "Media", "Manga reader (the maintained Tachiyomi successor)."},
        {"seal", "Seal", "JunkFood02", "Seal", "com.junkfood.seal", "", "Utility", "Video/audio downloader powered by yt-dlp."},
        {"revanced-manager", "ReVanced Manager", "ReVanced", "revanced-manager", "app.revanced.manager.flutter", "", "Utility", "Patch your own apps with ReVanced."},
        {"obtainium", "Obtainium", "ImranR98", "Obtainium", "dev.imranr.obtainium", "", "Utility", "Get app updates straight from the source."},
        {"aegis", "Aegis Authenticator", "beemdevelopment", "Aegis", "com.beemdevelopment.aegis", "", "Security", "Secure, encrypted 2FA / TOTP authenticator."},
        {"keepassdx", "KeePassDX", "Kunzisoft", "KeePassDX", "com.kunzisoft.keepass.libre", "", "Security", "Lightweight KeePass password manager."},
        {"ankidroid", "AnkiDroid", "ankidroid", "Anki-Android", "com.ichi2.anki", "", "Education", "Flashcards with spaced repetition."},
        {"organicmaps", "Organic Maps", "organicmaps", "organicmaps", "app.organicmaps", "", "Navigation", "Offline OpenStreetMap maps & navigation."},
        {"cromite", "Cromite", "uazo", "cromite", "org.cromite.cromite", "", "Browser", "Chromium fork with built-in ad-blocking and privacy."},
        {"materialfiles", "Material Files", "zhanghai", "MaterialFiles", "me.zhanghai.android.files", "", "Utility", "Material Design open-source file manager."},
        {"fossify-gallery", "Fossify Gallery", "FossifyOrg", "Gallery", "org.fossify.gallery", "", "Media", "Offline photo gallery, no ads or tracking."},
        {"fossify-calendar", "Fossify Calendar", "FossifyOrg", "Calendar", "org.fossify.calendar", "", "Productivity", "Private offline calendar."},
        {"fossify-phone", "Fossify Phone", "FossifyOrg", "Phone", "org.fossify.phone", "", "Utility", "Dialer with no ads or tracking."},
        {"tasks", "Tasks.org", "tasks", "tasks", "org.tasks", "", "Productivity", "To-do lists and reminders with optional sync."},
        {"catima", "Catima", "CatimaLoyalty", "Android", "me.hackerchick.catima", "", "Utility", "Loyalty / membership card wallet."},
        {"breezyweather", "Breezy Weather", "breezy-weather", "breezy-weather", "org.breezyweather", "", "Weather", "Material weather app with many providers."},
        {"feeder", "Feeder", "spacecowboys", "Feeder", "com.nononsenseapps.feeder", "", "News", "Lightweight, offline-friendly RSS reader."},
        {"jerboa", "Jerboa", "dessalines", "jerboa", "com.jerboa", "", "Social", "A Lemmy client for Android."},
        {"infinity", "Infinity for Reddit", "Docile-Alligator", "Infinity-For-Reddit", "ml.docilealligator.infinityforreddit", "", "Social", "Feature-rich, ad-free Reddit client."},
        {"streetcomplete", "StreetComplete", "streetcomplete", "StreetComplete", "de.westnordost.streetcomplete", "", "Navigation", "Improve OpenStreetMap by answering quests."},
    };

    public static JSONObject komiCatalog() throws Exception {
        JSONArray items = new JSONArray();
        for (String[] e : KOMI) {
            JSONObject o = new JSONObject();
            o.put("id", e[0]);
            o.put("name", e[1]);
            o.put("owner", e[2]);
            o.put("repo", e[3]);
            o.put("pkg", e[4]);
            o.put("assetFilter", e[5]);
            o.put("category", e[6]);
            o.put("desc", e[7]);
            o.put("icon", "https://github.com/" + e[2] + ".png?size=96");
            o.put("page", "https://github.com/" + e[2] + "/" + e[3]);
            o.put("source", "komi");
            o.put("resolveKind", "github");
            items.put(o);
        }
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("items", items);
        out.put("total", items.length());
        return out;
    }

    // =============================================================================================
    // Orion - RookieEnough/Orion-Data catalog
    // =============================================================================================

    public static final String ORION_APPS_URL = "https://raw.githubusercontent.com/RookieEnough/Orion-Data/main/apps.json";

    /** True for a catalog entry that runs on Android (skips the PC / Windows / desktop-only entries). */
    private static boolean isAndroid(String platform) {
        if (platform == null || platform.isEmpty()) return true; // unknown -> keep
        String p = platform.toLowerCase(Locale.US);
        if (p.contains("android")) return true;
        return !(p.contains("pc") || p.contains("windows") || p.contains("mac") || p.contains("linux") || p.contains("web"));
    }

    private static boolean isDirectApk(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.US);
        if (!u.startsWith("https://")) return false;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        return u.endsWith(".apk") || u.endsWith(".apks") || u.endsWith(".apkm") || u.endsWith(".xapk");
    }

    /**
     * Fetches Orion's apps.json and maps it to installable items. Keeps Android entries that resolve to
     * an APK via a direct link, a GitHub repo (+ release keyword), or a Codeberg repo; others are dropped
     * so everything shown can actually be installed. Screenshots are stripped and descriptions trimmed to
     * keep the payload small.
     */
    public static JSONObject orionCatalog(int maxItems) throws Exception {
        byte[] raw = UpdateManager.httpGet(ORION_APPS_URL, "application/json", 8 * 1024 * 1024);
        JSONArray arr = new JSONArray(new String(raw, "UTF-8"));
        JSONArray items = new JSONArray();
        for (int i = 0; i < arr.length() && items.length() < maxItems; i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            if (!isAndroid(e.optString("platform", ""))) continue;

            String downloadUrl = e.optString("downloadUrl", "");
            String githubRepo = e.optString("githubRepo", "");
            String repoUrl = e.optString("repoUrl", "");
            String keyword = e.optString("releaseKeyword", "");

            String resolveKind, owner = "", repo = "", apkUrl = "";
            if (isDirectApk(downloadUrl)) {
                resolveKind = "direct";
                apkUrl = downloadUrl.trim();
            } else if (githubRepo != null && githubRepo.contains("/")) {
                resolveKind = "github";
                String[] or = githubRepo.trim().split("/");
                owner = or[0];
                repo = or[1];
            } else {
                JSONObject parsed = UpdateManager.parseRepoUrl(repoUrl);
                if (parsed != null && "codeberg.org".equals(parsed.optString("host"))) {
                    resolveKind = "codeberg";
                    owner = parsed.optString("owner");
                    repo = parsed.optString("repo");
                } else if (parsed != null && "github.com".equals(parsed.optString("host"))) {
                    resolveKind = "github";
                    owner = parsed.optString("owner");
                    repo = parsed.optString("repo");
                } else {
                    continue; // gitlab / sourceforge / plain website - not resolvable in-app
                }
            }

            JSONObject o = new JSONObject();
            o.put("id", e.optString("id", e.optString("packageName", "orion-" + i)));
            o.put("name", e.optString("name", "App"));
            o.put("desc", trim(e.optString("description", ""), 200));
            o.put("icon", e.optString("icon", ""));
            o.put("version", e.optString("latestVersion", e.optString("version", "")));
            o.put("pkg", e.optString("packageName", ""));
            o.put("category", e.optString("category", ""));
            o.put("author", e.optString("author", ""));
            o.put("page", repoUrl == null ? "" : repoUrl);
            o.put("source", "orion");
            o.put("resolveKind", resolveKind);
            o.put("owner", owner);
            o.put("repo", repo);
            o.put("assetFilter", keyword == null ? "" : keyword);
            o.put("apkUrl", apkUrl);
            items.put(o);
        }
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("items", items);
        out.put("total", items.length());
        return out;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // =============================================================================================
    // F-Droid - known-repository directory + per-repo index-v2 parsing
    // =============================================================================================

    /** {id, name, address, fingerprint, description}. Addresses end in /repo; fingerprints are SHA-256. */
    private static final String[][] FDROID_REPOS = FDROID_REPO_TABLE();

    public static JSONObject fdroidRepos() throws Exception {
        JSONArray items = new JSONArray();
        for (String[] r : FDROID_REPOS) {
            JSONObject o = new JSONObject();
            o.put("id", r[0]);
            o.put("name", r[1]);
            o.put("address", r[2]);
            o.put("fingerprint", r[3]);
            o.put("desc", r[4]);
            o.put("source", "fdroid");
            items.put(o);
        }
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("items", items);
        out.put("total", items.length());
        return out;
    }

    private static String localized(JSONObject map, String fallback) {
        if (map == null) return fallback;
        String v = map.optString("en-US", "");
        if (!v.isEmpty()) return v;
        v = map.optString("en", "");
        if (!v.isEmpty()) return v;
        Iterator<String> it = map.keys();
        if (it.hasNext()) return map.optString(it.next(), fallback);
        return fallback;
    }

    /**
     * Fetches and parses a repo's index-v2.json into installable items. Each item's apkUrl is
     * {address}{file.name}. Capped by maxBytes: a repo whose index is larger than the cap (the big
     * aggregators) returns status "too_large" instead, so the UI can tell the user honestly.
     */
    public static JSONObject fdroidRepoIndex(String address, int maxItems, int maxBytes) throws Exception {
        JSONObject out = new JSONObject();
        if (address == null || !address.startsWith("https://")) {
            out.put("status", "error");
            out.put("error", "Invalid repository address.");
            return out;
        }
        String base = address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
        String indexUrl = base + "/index-v2.json";
        byte[] raw;
        try {
            raw = UpdateManager.httpGet(indexUrl, "application/json", maxBytes);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("too large")) {
                out.put("status", "too_large");
                out.put("error", "This repository's catalog is too large to browse in-app. Add it to an F-Droid client with its address and fingerprint.");
                return out;
            }
            throw e;
        }
        JSONObject index = new JSONObject(new String(raw, "UTF-8"));
        JSONObject packages = index.optJSONObject("packages");
        JSONArray items = new JSONArray();
        if (packages != null) {
            Iterator<String> pkgs = packages.keys();
            while (pkgs.hasNext() && items.length() < maxItems) {
                String pkg = pkgs.next();
                JSONObject p = packages.optJSONObject(pkg);
                if (p == null) continue;
                JSONObject meta = p.optJSONObject("metadata");
                JSONObject versions = p.optJSONObject("versions");
                if (versions == null || versions.length() == 0) continue;

                // Latest version = highest versionCode across the version map.
                JSONObject best = null;
                long bestCode = Long.MIN_VALUE;
                Iterator<String> vit = versions.keys();
                while (vit.hasNext()) {
                    JSONObject v = versions.optJSONObject(vit.next());
                    if (v == null) continue;
                    JSONObject mani = v.optJSONObject("manifest");
                    long code = mani != null ? mani.optLong("versionCode", 0) : 0;
                    if (code >= bestCode) { bestCode = code; best = v; }
                }
                if (best == null) continue;
                JSONObject file = best.optJSONObject("file");
                if (file == null) continue;
                String fname = file.optString("name", "");
                if (fname.isEmpty()) continue;
                JSONObject mani = best.optJSONObject("manifest");

                String name = meta != null ? localized(meta.optJSONObject("name"), pkg) : pkg;
                String summary = meta != null ? localized(meta.optJSONObject("summary"), "") : "";
                String icon = "";
                if (meta != null) {
                    JSONObject ic = meta.optJSONObject("icon");
                    String ipath = ic != null ? localized(ic, "") : "";
                    if (ipath.isEmpty() && ic != null) {
                        JSONObject enus = ic.optJSONObject("en-US");
                        if (enus != null) ipath = enus.optString("name", "");
                    }
                    if (!ipath.isEmpty()) icon = base + (ipath.startsWith("/") ? ipath : "/" + ipath);
                }

                JSONObject o = new JSONObject();
                o.put("id", pkg);
                o.put("name", name);
                o.put("desc", trim(summary, 200));
                o.put("icon", icon);
                o.put("pkg", pkg);
                o.put("version", mani != null ? mani.optString("versionName", "") : "");
                o.put("size", file.optLong("size", 0));
                o.put("apkUrl", base + (fname.startsWith("/") ? fname : "/" + fname));
                o.put("source", "fdroid");
                o.put("resolveKind", "direct");
                items.put(o);
            }
        }
        out.put("status", "ok");
        out.put("items", items);
        out.put("total", items.length());
        return out;
    }

    // =============================================================================================
    // Resolve a Komi / Orion item to a concrete APK URL (for the on-device installer)
    // =============================================================================================

    /**
     * Resolves one catalog item to {apkUrl, pkg, name, version, size}. Direct/F-Droid items already carry
     * an apkUrl; GitHub/Codeberg items hit the release API and pick the APK for this device's ABI (honoring
     * the item's assetFilter keyword). Throws with a human message when no installable APK is found.
     */
    public static JSONObject resolve(JSONObject item, String[] abis) throws Exception {
        String kind = item.optString("resolveKind", "");
        String name = item.optString("name", item.optString("pkg", "app"));
        String pkg = item.optString("pkg", "");
        String apkUrl = item.optString("apkUrl", "");

        JSONObject out = new JSONObject();
        out.put("pkg", pkg);
        out.put("name", name);

        if ("direct".equals(kind) || !apkUrl.isEmpty()) {
            if (apkUrl.isEmpty() || !apkUrl.startsWith("https://"))
                throw new IllegalStateException("This app has no direct APK to install.");
            out.put("apkUrl", apkUrl);
            out.put("version", item.optString("version", ""));
            return out;
        }

        String owner = item.optString("owner", "");
        String repo = item.optString("repo", "");
        if (owner.isEmpty() || repo.isEmpty())
            throw new IllegalStateException("No GitHub/Codeberg repository is known for " + name + ".");

        UpdateManager.Release rel;
        if ("codeberg".equals(kind)) {
            rel = UpdateManager.codebergRelease(owner, repo);
        } else {
            rel = UpdateManager.githubRelease(owner, repo, "", false);
        }
        if (rel == null || rel.assets.isEmpty())
            throw new IllegalStateException("The latest release of " + owner + "/" + repo + " has no APK asset.");

        String filter = item.optString("assetFilter", "");
        // "apk" keyword is a no-op (every asset is already an .apk); treat it as "no filter".
        if (filter.equalsIgnoreCase("apk")) filter = "";
        String[] picked = UpdateManager.pickApk(rel.assets, regexEscape(filter), false, abis);
        if (picked == null && !filter.isEmpty())
            picked = UpdateManager.pickApk(rel.assets, null, false, abis); // keyword missed -> best ABI match
        if (picked == null)
            throw new IllegalStateException("No matching APK in the latest release of " + owner + "/" + repo + ".");

        out.put("apkUrl", picked[1]);
        out.put("assetName", picked[0]);
        out.put("version", rel.version == null ? "" : rel.version);
        return out;
    }

    /** Escapes a plain keyword so it is matched literally by pickApk's regex engine ("" stays ""). */
    private static String regexEscape(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    /**
     * Known third-party F-Droid repositories, from the community thread
     * forum.f-droid.org/t/known-repositories/721. Addresses and SHA-256 fingerprints are transcribed
     * verbatim from that list; the fingerprint is shown so the user can add the repo to a full F-Droid
     * client (which verifies it), while in-app browse just reads the repo's public index-v2.json.
     */
    private static String[][] FDROID_REPO_TABLE() {
        return new String[][] {
            {"izzyondroid", "IzzyOnDroid", "https://apt.izzysoft.de/fdroid/repo", "3BF0D6ABFEAE2F401707B6D966BE743BF0EEE49C2561B9BA39073711F628937A", "Developer binaries, 900+ apps - many from GitHub releases."},
            {"guardian", "Guardian Project", "https://guardianproject.info/fdroid/repo", "B7C2EEFD8DAC7806AF67DFCD92EB18126BC08312A7F2D6F3862E46013C7A6135", "Privacy & security apps (Orbot, Tor, Haven)."},
            {"microg", "microG", "https://microg.org/fdroid/repo", "9BD06727E62796C0130EB6DAB39B73157451582CBD138E86C468ACC395D14165", "Free re-implementation of Google Play Services."},
            {"fdroid", "F-Droid", "https://f-droid.org/repo", "43238D512C1E5EB2D6569F4A3AFBF5523418B82E0A3ED1552770ABB9A9C9CCAB", "F-Droid's mainline repository, 3600+ apps."},
            {"bitwarden", "Bitwarden", "https://mobileapp.bitwarden.com/fdroid/repo", "BC54EA6FD1CD5175BCCCC47C561C5726E1C3ED7E686B6DB4B18BAC843A3EFE6C", "Secure, free password manager."},
            {"brave", "Brave Browser", "https://brave-browser-apk-release.s3.brave.com/fdroid/repo", "3C60DE135AA19EC949E998469C908F7171885C1E2805F39EB403DDB0F37B4BD2", "Chromium-based privacy browser."},
            {"cromite", "Cromite", "https://www.cromite.org/fdroid/repo", "49F37E74DEE483DCA2B991334FB5A0200787430D0B5F9A783DD5F13695E9517B", "Chromium fork with ad-blocking and privacy."},
            {"ironfox", "IronFox", "https://fdroid.ironfoxoss.org/fdroid/repo", "C5E291B5A571F9C8CD9A9799C2C94E02EC9703948893F2CA756D67B94204F904", "Privacy-hardened Firefox (Mull successor)."},
            {"newpipe", "NewPipe", "https://archive.newpipe.net/fdroid/repo", "E2402C78F9B97C6C89E97DB914A2751FDA1D02FE2039CC0897A462BDB57E7501", "Lightweight YouTube client; faster updates."},
            {"molly", "Molly", "https://molly.im/fdroid/repo/", "3B7E93B1FE32C6E35A93D6DDFC5AFBEB1239A7C6EA6AF20FF33ED53CDC38B04A", "Signal fork with passphrase lock & hardening."},
            {"molly-foss", "Molly-FOSS", "https://molly.im/fdroid/foss/fdroid/repo/", "5198DAEF37FC23C14D5EE32305B2AF45787BD7DF2034DE33AD302BDB3446DF74", "FOSS build of Molly, microG compatible."},
            {"session", "Session", "https://fdroid.getsession.org/fdroid/repo", "DB0E5297EB65CC22D6BD93C869943BDCFCB6A07DC69A48A0DD8C7BA698EC04E6", "Private messenger without identifiers."},
            {"simplex", "SimpleX Chat", "https://app.simplex.chat/fdroid/repo", "9F358FF284D1F71656A2BFAF0E005DEAE6AA14143720E089F11FF2DDCFEB01BA", "Messaging with no user IDs."},
            {"briar", "Briar", "https://briarproject.org/fdroid/repo", "1FB874BEE7276D28ECB2C9B06E8A122EC4BCB4008161436CE474C257CBF49BD6", "Serverless messenger that hides metadata."},
            {"threema", "Threema (Libre)", "https://releases.threema.ch/fdroid/repo", "5734E753899B25775D90FE85362A49866E05AC4F83C05BEF5A92880D2910639E", "De-Googled build of the Threema messenger."},
            {"signal-foss", "Signal-FOSS", "https://fdroid.twinhelix.com/fdroid/repo", "7B03B0232209B21B10A30A63897D3C6BCA4F58FE29BC3477E8E3D8CF8E304028", "De-Googled open-source fork of Signal (TwinHelix)."},
            {"collabora", "Collabora Office", "https://www.collaboraoffice.com/downloads/fdroid/repo/", "573258C84E149B5F4D9299E7434B2B69A8410372921D4AE586BA91EC767892CC", "LibreOffice-based office suite."},
            {"cryptomator", "Cryptomator", "https://static.cryptomator.org/android/fdroid/repo", "F7C3EC3B0D588D3CB52983E9EB1A7421C93D4339A286398E71D7B651E8D8ECDD", "Encryption for cloud storage."},
            {"thunderbird", "Thunderbird (K-9 Mail)", "https://thunderbird.github.io/fdroid-thunderbird/repo", "8B86E5D48983F0875F7EB7A1B2F91B225EE5B997E463E3D63D0E2556E53666BE", "Thunderbird for Android (formerly K-9 Mail)."},
            {"futo", "FUTO", "https://app.futo.org/fdroid/repo", "39D47869D29CBFCE4691D9F7E6946A7B6D7E6FF4883497E6E675744ECDFA6D6D", "FUTO Keyboard, Voice Input, Grayjay, Circles."},
            {"fedilab", "Fedilab", "https://fdroid.fedilab.app/repo", "11F0A69910A4280E2CD3CCC3146337D006BE539B18E1A9FEACE15FF757A94FEB", "Fediverse clients (Fedilab, Fedilab Lite)."},
            {"gultsch", "Conversations & Ltt.rs", "https://gultsch.dev/fdroid/repo/", "9C2E57C85C279E5E1A427F6E87927FC1E2278F62D61D7FCEFDE9346E568CCF86", "Conversations XMPP messenger & Ltt.rs email."},
            {"breezy", "Breezy Weather", "https://breezy-weather.github.io/fdroid-repo/fdroid/repo/", "3480A7BB2A296D8F98CB90D2309199B5B9519C1B31978DBCD877ADB102AF35EE", "Material Design weather app."},
            {"cgeo", "c:geo", "https://fdroid.cgeo.org/fdroid/repo/", "370BB4D550C391D5DCCB6C81FD82FDA4892964764E085A09B7E075E9BAD5ED98", "Unofficial geocaching client."},
            {"gadgetbridge", "Gadgetbridge Nightly", "https://freeyourgadget.codeberg.page/fdroid/repo", "CD381ECCC465AB324E21BCC335895615E07E70EE11E9FD1DF3C020C5194F00B2", "Nightly Gadgetbridge builds."},
            {"shelter", "Shelter", "https://fdroid.typeblog.net/repo", "1A7E446C491C80BC2F83844A26387887990F97F2F379AE7B109679FEAE3DBC8C", "Isolate/clone apps using Work Profiles."},
            {"nethunter", "Kali NetHunter", "https://store.nethunter.com/repo", "7E418D34C3AD4F3C37D7E6B0FACE13332364459C862134EB099A3BDA2CCF4494", "Penetration-testing & forensics apps."},
            {"kde", "KDE Release builds", "https://cdn.kde.org/android/stable-releases/fdroid/repo/", "13784BA6C80FF4E2181E55C56F961EED5844CEA16870D3B38D58780B85E1158F", "Stable builds of KDE applications."},
            {"libretro", "LibRetro", "https://fdroid.libretro.com/repo", "3F05B24D497515F31FEAB421297C79B19552C5C81186B3750B7C131EF41D733D", "RetroArch (emulator frontend) builds."},
            {"calyx", "The Calyx Institute", "https://fdroid-repo.calyxinstitute.org/fdroid/repo", "C44D58B4547DE5096138CB0B34A1CC99DAB3B4274412ED753FCCBFC11DC1B7B6", "Apps for use with CalyxOS."},
            {"monerujo", "Monerujo", "https://f-droid.monerujo.io/fdroid/repo", "A82C68E14AF0AA6A2EC20E6B272EFF25E5A038F3F65884316E0F5E0D91E7B713", "Monero wallet for Android."},
            {"purplei2p", "PurpleI2P", "https://fdroid.i2pd.xyz/fdroid/repo", "2B9564B0895EEAC039E854C6B065291B01E6A9CA02939CEDD0D35CF44BEE78E0", "i2pd for Android (I2P network)."},
            {"nebulo", "Nebulo", "https://fdroid.frostnerd.com/fdroid/repo", "74BB580F263EC89E15C207298DEC861B5069517550FE0F1D852F16FA611D2D26", "DNS changer (DNS-over-HTTPS / DNS-over-TLS)."},
            {"dns66", "DNS66", "https://jak-linux.org/fdroid/repo/", "C00A81E44BFF606530C4C7A2137BAC5F1C03D2FDEF6DB3B84C71386EA9BFD225", "DNS-based host / ad blocker."},
            {"ffupdater", "FFUpdater", "https://raw.githubusercontent.com/Tobi823/ffupdaterrepo/master/fdroid/repo", "6E4E6A597D289CB2D4D4F0E4B792E14CCE070BDA6C47AF4918B342FA51F2DC89", "Firefox-family browser updater."},
            {"wgtunnel", "WG Tunnel", "https://raw.githubusercontent.com/zaneschepke/fdroid/main/fdroid/repo", "0890C5D44C0109E366801C39840325E810E21B270B9D2AEC53CE0D6C5FC849DB", "WireGuard / AmneziaWG client with auto-tunneling."},
            {"spiritcroc", "SpiritCroc (SchildiChat)", "https://s2.spiritcroc.de/fdroid/repo", "6612ADE7E93174A589CF5BA26ED3AB28231A789640546C8F30375EF045BC9242", "SchildiChat variants (FOSS & prerelease)."},
            {"mm20", "Kvaesitso (MM20)", "https://fdroid.mm20.de/repo", "156FBAB952F6996415F198F3F29628D24B30E725B0F07A2B49C3A9B5161EEE1A", "Search-focused Android launcher."},
            {"iitc", "IITC Mobile", "https://iitc.app/fdroid/repo/", "B7899FB661896B374D1003A31BA1C54139A9EFDC6B9D53A70F5180768EF197B9", "Ingress Intel Total Conversion."},
            {"privacyguides", "Privacy Guides", "https://fdroid.privacyguides.org/fdroid/repo", "14E81B3EB0D5682C6E822D2840E89685B2419E9188FC643C072433DCFA5C69B7", "A curated set of privacy apps."},
            {"gitjournal", "GitJournal", "https://gitjournal.io/fdroid/repo", "E2EE4AA4380F0D3B3CF81EB17F5E48F827C3AA77122D9AD330CC441650894574", "Markdown notes backed by Git."},
            {"netsyms", "Netsyms Technologies", "https://repo.netsyms.com/fdroid/repo", "2581BA7B32D3AB443180C4087CAB6A7E8FB258D3A6E98870ECB3C675E4D64489", "Netsyms Android applications."},
            {"maintainteam", "MaintainTeam", "https://maintainteam.github.io/fdroid-pages/fdroid/repo", "1099CCEFC59ECBF75733F52137A39D67330E721A8D1E82CB93BB2DF9DF07C2D4", "Maintained forks of abandoned apps."},
        };
    }
}
