package com.bloatware.bingblop;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.storage.StorageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link Sdm.Packages} on a real phone, kept thin: the installed apps from PackageManager with MATCH_UNINSTALLED_PACKAGES (apps that were removed with
 * their data kept are in the list with {@code uninstalled = true}), their labels, cache sizes from StorageStatsManager (needs "Usage access"; -1 without),
 * and the running set from {@code ps} through the working mode's shell ({@link SdmShell#runningPackages}). The list is kept for 30 seconds.
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se; the port's own android.* side (spec 8.1).
 */
public final class SdmPackagesAndroid implements Sdm.Packages {
    private static final long TTL_MS = 30000;
    private final Context ctx;
    private final Sdm.Shell shell;
    private List<Sdm.Pkg> cache;
    private long cacheAt;

    public SdmPackagesAndroid(Context ctx, Sdm.Shell shell) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.shell = shell;
    }

    private Sdm.Pkg make(PackageManager pm, ApplicationInfo ai) {
        Sdm.Pkg p = new Sdm.Pkg();
        p.pkg = ai.packageName;
        try {
            CharSequence l = pm.getApplicationLabel(ai);
            p.label = l == null ? ai.packageName : l.toString();
        } catch (RuntimeException e) {
            p.label = ai.packageName;
        }
        p.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        p.enabled = ai.enabled;
        p.uninstalled = (ai.flags & ApplicationInfo.FLAG_INSTALLED) == 0;
        p.uid = ai.uid;
        return p;
    }

    @Override
    public synchronized List<Sdm.Pkg> installed() {
        long now = System.currentTimeMillis();
        if (cache != null && now - cacheAt < TTL_MS) return cache;
        PackageManager pm = ctx.getPackageManager();
        List<Sdm.Pkg> out = new ArrayList<Sdm.Pkg>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)) out.add(make(pm, ai));
        } catch (RuntimeException e) {
            // no list: the tools see no apps
        }
        cache = out;
        cacheAt = now;
        return out;
    }

    @Override
    public Sdm.Pkg get(String pkg) {
        for (Sdm.Pkg p : installed()) if (p.pkg.equals(pkg)) return withVersion(p);
        return null;
    }

    private Sdm.Pkg withVersion(Sdm.Pkg p) {
        if (!p.versionName.isEmpty()) return p;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(p.pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            if (pi.versionName != null) p.versionName = pi.versionName;
            p.versionCode = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            // no version
        } catch (RuntimeException e) {
            // no version
        }
        return p;
    }

    @Override
    public Set<String> running() {
        return SdmShell.runningPackages(shell);
    }

    @Override
    public long cacheBytes(String pkg) {
        try {
            StorageStatsManager ssm = (StorageStatsManager) ctx.getSystemService(Context.STORAGE_STATS_SERVICE);
            StorageStats st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle());
            return st.getCacheBytes();
        } catch (Exception e) {
            return -1;                         // no usage access, or the package is not installed
        }
    }
}
