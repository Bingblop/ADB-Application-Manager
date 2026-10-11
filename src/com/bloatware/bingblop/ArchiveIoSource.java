package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapts {@link ArchiveIo} (7z and the tar family: tar, tar.gz, tar.bz2, tar.xz, tar.zst, tar.lz4; and a single compressed file: gz, bz2, xz,
 * zst, lz4) to {@link ZipTool.Source}, so a {@link ZipTool.Archive} of one of these formats browses, is previewed and is extracted the same
 * way as a zip. Creating and editing these formats goes through {@link ArchiveIo#create} / {@link ArchiveIo#rewrite} directly (see
 * MainActivity), not through this adapter.
 */
final class ArchiveIoSource implements ZipTool.Source {
    private final File file;
    private final String format;

    private ArchiveIoSource(File file, String format) {
        this.file = file;
        this.format = format;
    }

    /** Opens {@code f} as an {@code ArchiveIo} archive of {@code format}. Throws {@link ZipTool.NeedPassword} when a password is needed or wrong. */
    static ZipTool.Archive open(File f, String format, char[] password) throws IOException {
        try {
            ArchiveIo.Info info = ArchiveIo.list(f, format, password);
            List<ZipTool.Entry> entries = new ArrayList<ZipTool.Entry>(info.items.size());
            for (ArchiveIo.Item it : info.items) entries.add(toEntry(it));
            ZipTool.Archive a = new ZipTool.Archive(f, f.length(), f.lastModified(), entries, info.format, new ArchiveIoSource(f, info.format), password);
            a.solid = info.solid;
            a.headerEncrypted = info.headerEncrypted;
            return a;
        } catch (ArchiveIo.PasswordException pe) {
            throw needPassword(pe);
        }
    }

    private static ZipTool.NeedPassword needPassword(ArchiveIo.PasswordException pe) {
        return new ZipTool.NeedPassword(pe.wrong, pe.wrong ? "That password did not work" : "This archive needs a password to open");
    }

    private static ZipTool.Entry toEntry(ArchiveIo.Item it) {
        ZipTool.Entry e = new ZipTool.Entry(it.name, it.dir, it.size, it.csize, it.mtime, it.mode, it.encrypted, it.linkTarget);
        e.hardLink = it.hardLink;
        return e;
    }

    @Override
    public InputStream open(ZipTool.Archive a, ZipTool.Entry e, char[] password) throws IOException {
        try {
            return ArchiveIo.open(file, format, e.name, password);
        } catch (ArchiveIo.PasswordException pe) {
            throw needPassword(pe);
        }
    }

    @Override
    public void walk(ZipTool.Archive a, char[] password, final ZipTool.Walker w) throws IOException {
        try {
            ArchiveIo.walk(file, format, password, new ArchiveIo.Visitor() {
                @Override
                public boolean entry(ArchiveIo.Item it, InputStream data) throws IOException {
                    return w.entry(toEntry(it), data);
                }
            });
        } catch (ArchiveIo.PasswordException pe) {
            throw needPassword(pe);
        }
    }

    @Override
    public boolean sequential() {
        return true;      // 7z (often solid) and the tar family (always a single stream): one pass beats reopening per entry
    }
}
