# Phone script for v7.18.3

About 7 minutes, one pass or fail per step. v7.18.3 adds **Restore the default label** to the File Manager's **Permissions…** (`CHANGELOG.md`, `## v7.18.3-Pro`). It needs a rooted phone: Root as the working mode with su granted, or Shizuku running as root (UID 0). Use only a test file you made yourself.

**Before you start:** install the `arm64-v8a` file of v7.18.3 over v7.18.2.

1. Without root, open the File Manager and a file's sheet. **Pass:** no **Permissions…** (as in v7.18.2).
2. With root, make a file `/data/local/tmp/l.txt`, tap it → **Permissions…**. **Pass:** under **Owner and group** there is **SELinux label** with a label such as `u:object_r:shell_data_file:s0`, and **Restore the default label**.
3. Tap **Restore the default label**. **Pass:** "Done: the default label" at once (no question), the label line shows what Android has now, and a line "Label before the last change: …".
4. Make a folder `/data/local/tmp/lt` with a file in it. **Permissions…** on `lt`, tick **Also everything inside this folder** under the label, tap **Restore the default label**. **Pass:** it asks first; after Yes, "Done: the default label".
5. Delete `/data/local/tmp/l.txt` and `/data/local/tmp/lt`.

## VirusTotal key (1 minute, no root needed)

6. If you have a VirusTotal API key saved, close and reopen the app, open Settings and scroll to **VirusTotal API key**. **Pass:** the field shows dots, not an empty box with "Paste your personal API key". (With no key saved it is empty, as before.)

## Live Logcat Row tints (1 minute, no root needed)

7. Open **Live Logcat**, tap **Play**, scroll the button row to the end and tap **Row tints**. **Pass:** a sheet comes up with the slider and **Gradient**; moving the slider to **Vivid** makes the lines behind it more strongly tinted at once, and turning **Gradient** on makes each tint fade across its line.
8. Tap outside the sheet, then open the Apps tab's search menu (⋮). **Pass:** its Row tints slider and Gradient show what you just chose. Put them back as you like them.
