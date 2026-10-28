# Phone script for v7.18.2

About 10 minutes, one pass or fail per step. v7.18.2 brings root file management (`CHANGELOG.md`, `## v7.18.2-Pro`). It needs a rooted phone: Root as the working mode with su granted, or Shizuku running as root (UID 0). Use only a test file you made yourself.

**Before you start:** install the `arm64-v8a` file of v7.18.2 over v7.18.1. In the File Manager, make a folder `Download/permtest` with a file `a.txt` inside.

## A. Without root (1 minute)

1. Without root (ADB or Shizuku over ADB), open Root Features. **Pass:** **Root File Management** is there and locked ("Needs Super User"). In the File Manager, a file's sheet has no **Permissions…**.

## B. With root (8 minutes)

2. Switch to Root (su granted). Root Features → **Root File Management** → **Open files**. **Pass:** the File Manager opens.
3. Open `/data/local/tmp` and make a file `p.txt` there. Tap it → **Permissions…**. **Pass:** a line "Now: 0644 (rw-r--r--) · …" (or what Android set) with the owner, group and SELinux label; the ticks match the digits.
4. Tick Owner **Run**. **Pass:** the field reads 0744 and the letters rwxr--r--. Tap **Apply permissions**. **Pass:** "Done: 0744 (rwxr--r--)", the Now line agrees, and a line "Before the last change: 0644 …".
5. Type `shell` as Owner, empty the Group, **Apply owner**, Yes. **Pass:** the Now line shows owner shell (2000), the group as before.
6. Put back the values the "Before" line showed (mode and owner). **Pass:** the Now line matches the first one of step 3.
7. Open the sheet of `/data/local/tmp` itself. **Pass:** **Permissions…** answers "System location: not touched" when you apply anything.
8. Make a folder `/data/local/tmp/t` with two files in it. **Permissions…** on `t`, type 700, tick **Also everything inside this folder**, **Apply permissions**. **Pass:** it asks first; after Yes, each file inside shows 0700 in its own Permissions….
9. Delete `/data/local/tmp/p.txt` and `/data/local/tmp/t`.
