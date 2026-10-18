package com.bloatware.bingblop;

// The page's alert / confirm / prompt in the app's look (PageDialogs): the title it shows and the colours the page sends.
public class PageDialogTextTest {
    static int fails = 0, n = 0;
    static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
    static void eq(String what, Object got, Object want) { n++; if (want == null ? got != null : !want.equals(got)) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    public static void main(String[] a) {
        PageDialogText.Parts p;
        // a title named by the page wins; the message stays whole
        p = PageDialogText.split(PageDialogText.PROMPT, "Save theme", "Name for this theme:", null);
        eq("named title", p.title, "Save theme"); eq("named title: the body is the message", p.body, "Name for this theme:");
        p = PageDialogText.split(PageDialogText.CONFIRM, "Undo\nchange", "Undo it?\n\nThree packages.", null);
        eq("a named title is one line", p.title, "Undo change"); eq("  and the body is not split then", p.body, "Undo it?\n\nThree packages.");
        // a short first line before a blank line is the title
        p = PageDialogText.split(PageDialogText.CONFIRM, null, "Create a new ADB key?\n\nAll ADB connections are dropped.", null);
        eq("first line title", p.title, "Create a new ADB key?"); eq("  the rest is the body", p.body, "All ADB connections are dropped.");
        p = PageDialogText.split(PageDialogText.CONFIRM, "  ", "Delete it?\r\n\r\n\nThis cannot be undone.", null);
        eq("CRLF and extra blank lines", p.title, "Delete it?"); eq("  body without the leading blank line", p.body, "This cannot be undone.");
        // otherwise the word for the kind
        p = PageDialogText.split(PageDialogText.CONFIRM, null, "Uninstall Foo?", null);
        eq("one-line confirm", p.title, "Confirm"); eq("  body", p.body, "Uninstall Foo?");
        eq("alert default", PageDialogText.split(PageDialogText.ALERT, null, "Done.", null).title, "ADB Full System Manager");
        eq("prompt default", PageDialogText.split(PageDialogText.PROMPT, null, "Name of this tab", null).title, "Enter a value");
        eq("the page's translated fallback", PageDialogText.split(PageDialogText.CONFIRM, null, "Sicher?", "Bestätigen").title, "Bestätigen");
        StringBuilder longLine = new StringBuilder(); for (int i = 0; i < 90; i++) longLine.append('x');
        eq("a first line too long to be a title", PageDialogText.split(PageDialogText.CONFIRM, null, longLine + "\n\nmore", null).title, "Confirm");
        eq("two lines before the gap are not a title", PageDialogText.split(PageDialogText.CONFIRM, null, "a\nb\n\nc", null).title, "Confirm");
        eq("nothing after the gap: no split", PageDialogText.split(PageDialogText.CONFIRM, null, "Only this?\n\n  ", null).title, "Confirm");
        eq("a null message", PageDialogText.split(PageDialogText.ALERT, null, null, null).body, "");
        StringBuilder huge = new StringBuilder(); for (int i = 0; i < 300; i++) huge.append('y');
        check("a named title is cut to 120 characters", PageDialogText.clean(huge.toString()).length() == 120);
        // colours
        eq("#RRGGBB", PageDialogText.parseColor("#141A28", 0), 0xFF141A28);
        eq("#RGB", PageDialogText.parseColor("#0ef", 0), 0xFF00EEFF);
        eq("#RRGGBBAA (alpha last)", PageDialogText.parseColor("#00E5FF80", 0), 0x8000E5FF);
        eq("rgba()", PageDialogText.parseColor("rgba(255, 255, 255, 0.09)", 0), 0x17FFFFFF);
        eq("rgb()", PageDialogText.parseColor(" rgb(0,0,0) ", 7), 0xFF000000);
        eq("bad hex", PageDialogText.parseColor("#12345", 7), 7);
        eq("bad name", PageDialogText.parseColor("cyan", 7), 7);
        eq("channel out of range", PageDialogText.parseColor("rgb(300,0,0)", 7), 7);
        eq("alpha out of range", PageDialogText.parseColor("rgba(1,2,3,2)", 7), 7);
        eq("wrong count", PageDialogText.parseColor("rgba(1,2,3)", 7), 7);
        eq("null", PageDialogText.parseColor(null, 7), 7);
        eq("mix", PageDialogText.mix(0xFFFFFFFF, 0xFF000000, 0.5), 0xFF808080);
        eq("withAlpha", PageDialogText.withAlpha(0xFF123456, 0x33), 0x33123456);
        System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
