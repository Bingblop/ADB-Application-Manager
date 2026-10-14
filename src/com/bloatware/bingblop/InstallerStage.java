package com.bloatware.bingblop;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The files the APK Installer works from. Every package read into the Installer gets its own folder under the installer cache and a new
 * generation; an install takes a snapshot of that package's data files (OBB), their archive and the picked file's name when it starts, and
 * works from that snapshot to its end. So reading another package while one installs neither deletes the files that install still opens nor
 * hands it the other package's data files, and the end of an install removes only what is still its own.
 *
 * @param <E> one data file of the package (XapkInfo.Extra in the app)
 */
final class InstallerStage<E> {

    /** What one install works from, taken when it starts. */
    static final class Snapshot<E> {
        final long gen;
        final File dir;
        final List<E> extras;
        final File zip;
        final String sourceName;

        Snapshot(long gen, File dir, List<E> extras, File zip, String sourceName) {
            this.gen = gen;
            this.dir = dir;
            this.extras = extras;
            this.zip = zip;
            this.sourceName = sourceName;
        }
    }

    private final File root;
    private long gen = 0;
    private File dir = null;                    // the folder of the package loaded now; null when there is none
    private List<E> extras = Collections.emptyList();
    private File zip = null;
    private String sourceName = "";
    private final List<File> busy = new ArrayList<File>();     // folders of installs that are running (one entry per install)

    /** @param root the installer cache folder, in canonical form (the folders handed out are compared with the canonical parents of files) */
    InstallerStage(File root) {
        this.root = root;
    }

    /**
     * A new package is being read: returns the folder to read it into, and adds to {@code toDelete} everything in the installer cache that no
     * running install uses (older packages, and leftovers from an earlier run). The caller deletes those and then creates the folder.
     */
    synchronized File beginRead(String name, List<File> toDelete) {
        gen++;
        dir = new File(root, "p" + gen);
        extras = Collections.emptyList();
        zip = null;
        sourceName = name == null ? "" : name;
        File[] kids = root.listFiles();
        if (kids != null) for (File k : kids) if (!busy.contains(k)) toDelete.add(k);
        return dir;
    }

    /** The generation {@link #beginRead} started; the answer of {@link #loaded} is matched against it. */
    synchronized long generation() {
        return gen;
    }

    /** The package of generation {@code g} is read: its data files and the archive that holds them. Ignored when a newer read has started. */
    synchronized void loaded(long g, List<E> extrasOfIt, File zipOfIt) {
        if (g != gen) return;
        extras = extrasOfIt == null ? Collections.<E>emptyList() : Collections.unmodifiableList(new ArrayList<E>(extrasOfIt));
        zip = zipOfIt;
    }

    /**
     * An install of the files in {@code filesDir} starts. Returns what it works from, or null when those files are not the package loaded now
     * (another package was read since, or an earlier install used them up): the caller asks for the package to be picked again.
     */
    synchronized Snapshot<E> beginInstall(File filesDir) {
        if (dir == null || filesDir == null || !dir.equals(filesDir)) return null;
        busy.add(dir);
        return new Snapshot<E>(gen, dir, extras, zip, sourceName);
    }

    /**
     * The install of {@code s} ends. {@code finished}: it gave its result and its files are used up; false: they stay for another try (after an
     * error, or while the person is asked whether to uninstall first). Returns the folder the caller deletes, or null to delete nothing.
     */
    synchronized File endInstall(Snapshot<E> s, boolean finished) {
        if (s == null || !busy.remove(s.dir)) return null;      // no install, or its end was already counted
        if (busy.contains(s.dir)) return null;             // another install still opens these files
        if (s.gen != gen) return s.dir;                    // a newer package was read since: this folder is nobody's now
        if (!finished) return null;
        dir = null;                                        // the package shown has been installed: pick it again to install it again
        extras = Collections.emptyList();
        zip = null;
        sourceName = "";
        return s.dir;
    }

    /** True while an install runs from {@code d}. */
    synchronized boolean inUse(File d) {
        return busy.contains(d);
    }
}
