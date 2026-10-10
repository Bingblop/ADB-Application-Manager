package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapts {@link RarReader} (RAR4 and RAR5, read only) to {@link ZipTool.Source}, so an open RAR archive browses, is previewed and is
 * extracted the same way as a zip. There is no create or edit for RAR.
 */
final class RarSource implements ZipTool.Source {
    private final File file;

    private RarSource(File file) {
        this.file = file;
    }

    /** Opens {@code f} as a RAR archive. Throws {@link ZipTool.NeedPassword} when a password is needed or wrong (an -hp archive). */
    static ZipTool.Archive open(File f, char[] password) throws IOException {
        try {
            RarReader.Info info = RarReader.list(f, password);
            List<ZipTool.Entry> entries = new ArrayList<ZipTool.Entry>(info.items.size());
            for (RarReader.Item it : info.items) entries.add(toEntry(it));
            ZipTool.Archive a = new ZipTool.Archive(f, f.length(), f.lastModified(), entries, info.format, new RarSource(f), password);
            a.solid = info.solid;
            a.headerEncrypted = info.headerEncrypted;
            return a;
        } catch (RarReader.PasswordException pe) {
            throw needPassword(pe);
        }
    }

    private static ZipTool.NeedPassword needPassword(RarReader.PasswordException pe) {
        return new ZipTool.NeedPassword(pe.wrong, pe.wrong ? "That password did not work" : "This archive needs a password to open");
    }

    private static ZipTool.Entry toEntry(RarReader.Item it) {
        return new ZipTool.Entry(it.name, it.dir, it.size, it.csize, it.mtime, it.mode, it.encrypted, it.linkTarget);
    }

    @Override
    public InputStream open(ZipTool.Archive a, ZipTool.Entry e, char[] password) throws IOException {
        try {
            return RarReader.open(file, e.name, password);
        } catch (RarReader.PasswordException pe) {
            throw needPassword(pe);
        }
    }

    @Override
    public void walk(ZipTool.Archive a, char[] password, final ZipTool.Walker w) throws IOException {
        try {
            RarReader.walk(file, password, new RarReader.Visitor() {
                @Override
                public boolean entry(RarReader.Item it, InputStream data) throws IOException {
                    return w.entry(toEntry(it), data);
                }
            });
        } catch (RarReader.PasswordException pe) {
            throw needPassword(pe);
        }
    }

    @Override
    public boolean sequential() {
        return true;      // a solid archive needs the files before the one asked for; a non-solid one is still a single forward stream here
    }
}
