package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The root-manager apps this app can hand off to so the user grants it su there. This holds no root or grant mechanics: it only names the manager
 * apps and picks which installed one to open. The actual grant is approved inside the manager; the app opens it with a normal launch intent
 * ({@link MainActivity.AndroidBridge#openRootManager}) and then reads the root status back through the usual working-mode check.
 *
 * No Android classes, so it is unit-tested on a computer ({@code RootManagersTest}).
 */
public final class RootManagers {

    /**
     * One root manager: its package name and the name the page may show for it. An empty label means the page names no manager and says "your root
     * manager" instead: Magisk is still recognised so phones that have it keep working, but its name never reaches the UI (Kyle, 2026-10-21: people
     * with locked bootloaders must not be led to it).
     */
    public static final class Manager {
        public final String pkg, label;
        Manager(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }

    // The well-known root managers, best first. KernelSU and APatch give the kernel-level / systemless root Kyle asked for; Magisk is the common
    // fallback. These are public package names, not internals - the app only launches whichever is installed.
    private static final Manager[] KNOWN = {
        new Manager("me.weishu.kernelsu", "KernelSU"),
        new Manager("com.rifsxd.ksunext", "KernelSU Next"),
        new Manager("me.bmax.apatch", "APatch"),
        new Manager("com.topjohnwu.magisk", ""),
    };

    private RootManagers() {}

    /** Every known root manager, best first (read-only). */
    public static List<Manager> known() {
        return Collections.unmodifiableList(Arrays.asList(KNOWN));
    }

    /**
     * The best installed root manager to open, or null when none of the known managers is installed. {@code installed} is the set of package names
     * the phone has (blanks and nulls are ignored); the order of {@link #KNOWN} decides which wins when more than one is installed.
     */
    public static Manager pick(Set<String> installed) {
        if (installed == null) return null;
        for (Manager m : KNOWN) if (installed.contains(m.pkg)) return m;
        return null;
    }

    /** The known managers that are installed, best first (for a chooser or a report). */
    public static List<Manager> installedManagers(Set<String> installed) {
        List<Manager> out = new ArrayList<Manager>();
        if (installed == null) return out;
        for (Manager m : KNOWN) if (installed.contains(m.pkg)) out.add(m);
        return out;
    }
}
