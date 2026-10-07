package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

/** The System UI Tuner's page-to-command layer: the operations the page sends, the commands and parsed answers that come back, the refusals in words. */
public class SysUiOpsTest {
    static int n = 0, fails = 0;
    static void ok(String name) { n++; System.out.println("ok   " + name); }
    static void fail(String name, String msg) { n++; fails++; System.out.println("FAIL " + name + " " + msg); }
    static void is(String name, boolean c, String extra) { if (c) ok(name); else fail(name, extra); }

    static JSONObject call(String op, String args) throws Exception { return new JSONObject(SysUiOps.run(op, args)); }

    public static void main(String[] a) throws Exception {
        JSONObject r = call("demo.allowedRead", "{}");
        is("demo.allowedRead gives the settings read", r.optBoolean("ok") && r.optString("cmd").equals("settings get global sysui_demo_allowed"), r.toString());
        r = call("demo.allow", "{\"allow\":true}");
        is("demo.allow true writes the one flag", r.optString("cmd").equals("settings put global sysui_demo_allowed 1"), r.toString());
        r = call("demo.allow", "{\"allow\":false}");
        is("demo.allow false deletes it", r.optString("cmd").startsWith("settings delete global"), r.toString());

        String state = "{\"clock\":\"12:00\",\"battery\":{\"level\":80,\"plugged\":true,\"powersave\":false},\"bars\":\"opaque\",\"notifications\":\"hide\","
                + "\"icons\":{\"volume\":\"hide\",\"alarm\":\"show\",\"airplane\":\"hide\",\"zen\":\"dnd\"},\"sims\":2,"
                + "\"wifi\":{\"show\":\"show\",\"level\":3,\"fully\":true,\"activity\":\"inout\"},"
                + "\"mobile\":{\"show\":\"show\",\"level\":4,\"fully\":true,\"datatype\":\"lte\",\"roam\":\"hide\",\"carrierchange\":\"hide\",\"inflate\":false,\"activity\":\"none\"}}";
        r = call("demo.apply", state);
        String c = r.optString("cmd");
        is("demo.apply enters demo mode first", c.startsWith("am broadcast -a com.android.systemui.demo -e command enter"), c);
        is("demo.apply sends the clock as HHMM", c.contains("-e command clock -e hhmm 1200"), c);
        is("demo.apply sends the battery", c.contains("-e command battery") && c.contains("-e level 80") && c.contains("-e plugged true"), c);
        is("demo.apply sends wifi and mobile on their own lines", c.contains("-e command network") && c.contains("-e wifi show") && c.contains("-e mobile show") && c.contains("-e datatype lte"), c);
        is("demo.apply carries no allow write", !c.contains("settings put"), c);

        r = call("demo.apply", "{\"clock\":\"25:99\"}");
        is("a clock that is not a time is refused in words", !r.optBoolean("ok") && r.optString("error").length() > 8, r.toString());
        r = call("demo.apply", "{\"battery\":{\"level\":500}}");
        is("a battery level over 100 is refused", !r.optBoolean("ok"), r.toString());

        r = call("shade", "{\"which\":\"collapse\"}");
        is("shade collapse", r.optString("cmd").contains("statusbar") && r.optString("cmd").contains("collapse"), r.toString());
        r = call("shade", "{\"which\":\"rm -rf /\"}");
        is("an unknown shade action is refused", !r.optBoolean("ok"), r.toString());

        r = call("disableRisky", "{\"flags\":[\"clock\",\"home\",\"recents\"]}");
        JSONArray risky = r.getJSONObject("data").getJSONArray("risky");
        is("disableRisky names home and recents, not the clock", risky.length() == 2 && risky.toString().contains("home") && !risky.toString().contains("clock"), r.toString());
        r = call("disable", "{\"flags\":[]}");
        is("disable with no flags restores everything", r.optString("cmd").contains("none"), r.toString());
        r = call("disable", "{\"flags\":[\"clock\",\"search\"]}");
        is("disable lists the flags in a fixed order", r.optBoolean("ok") && r.optString("cmd").contains("search") && r.optString("cmd").contains("clock"), r.toString());

        r = call("notify.post", "{\"style\":\"basic\",\"title\":\"Hi\",\"text\":\"it's me\",\"tag\":\"t1\",\"lines\":[],\"messages\":[],\"conversation\":\"Hi\"}");
        is("notify.post quotes the text as one argument", r.optBoolean("ok") && r.optString("cmd").contains("cmd notification post") && r.optString("cmd").contains("it"), r.toString());
        r = call("notify.post", "{\"style\":\"basic\",\"title\":\"x\\ny\",\"text\":\"t\",\"tag\":\"t\"}");
        is("a title with a line break is refused", !r.optBoolean("ok"), r.toString());
        r = call("notify.snooze", "{\"key\":\"0|com.x|1|null|1000\",\"ms\":60000}");
        is("notify.snooze builds a command", r.optBoolean("ok") && r.optString("cmd").contains("snooze"), r.toString());
        r = call("notify.snooze", "{\"key\":\"0|com.x|1|null|1000\",\"ms\":5}");
        is("a snooze under a second is refused", !r.optBoolean("ok"), r.toString());

        r = call("action", "{\"id\":2}");
        is("system action 2 (Home)", r.optBoolean("ok") && r.optString("cmd").contains("accessibility"), r.toString());
        r = call("action", "{\"id\":99}");
        is("a system action out of range is refused", !r.optBoolean("ok"), r.toString());
        r = call("restartUi", "{}");
        is("restart System UI", r.optString("cmd").equals("am crash com.android.systemui"), r.toString());

        r = call("battery.set", "{\"key\":\"level\",\"value\":15}");
        is("battery.set level", r.optString("cmd").equals("dumpsys battery set level 15"), r.toString());
        r = call("battery.set", "{\"key\":\"level\",\"value\":101}");
        is("battery level over 100 refused", !r.optBoolean("ok"), r.toString());
        r = call("battery.set", "{\"key\":\"; reboot\",\"value\":1}");
        is("a battery key that is not on the list is refused", !r.optBoolean("ok"), r.toString());
        is("battery.reset", call("battery.reset", "{}").optString("cmd").equals("dumpsys battery reset"), "");

        r = call("nav.enable", "{\"mode\":\"gestural\"}");
        is("nav.enable gestural", r.optString("cmd").contains("gestural") && r.optString("cmd").contains("overlay"), r.toString());
        r = call("nav.enable", "{\"mode\":\"evil\"}");
        is("an unknown navigation mode is refused", !r.optBoolean("ok"), r.toString());
        r = call("parse.navModes", "{\"text\":\"android:\\n[x] com.android.internal.systemui.navbar.threebutton\\n[ ] com.android.internal.systemui.navbar.gestural\"}");
        JSONObject d = r.getJSONObject("data");
        is("parse.navModes: both modes available, three-button in use", d.getJSONArray("available").length() == 2 && d.optString("enabled").equals("threebutton"), r.toString());

        r = call("wm.read", "{}");
        is("wm.read reads size and density", r.optString("cmd").contains("wm size") && r.optString("cmd").contains("wm density"), r.toString());
        r = call("parse.wm", "{\"text\":\"Physical size: 1080x2400\\nPhysical density: 420\\nOverride density: 380\"}");
        d = r.getJSONObject("data");
        is("parse.wm gives size, density and the override", d.optString("size").equals("1080x2400") && d.optString("density").equals("420") && d.optString("overrideDensity").equals("380"), r.toString());
        is("wm.density 72..1000 only", !call("wm.density", "{\"value\":5}").optBoolean("ok") && call("wm.density", "{\"value\":420}").optBoolean("ok"), "");
        is("wm.size", call("wm.size", "{\"w\":1080,\"h\":2400}").optString("cmd").contains("1080x2400"), "");
        is("wm.scaling off or auto only", call("wm.scaling", "{\"mode\":\"auto\"}").optBoolean("ok") && !call("wm.scaling", "{\"mode\":\"x\"}").optBoolean("ok"), "");

        r = call("parse.demoAllowed", "{\"text\":\"1\"}");
        is("parse.demoAllowed 1 = allowed", r.getJSONObject("data").optBoolean("allowed"), r.toString());
        r = call("parse.demoAllowed", "{\"text\":\"null\"}");
        is("parse.demoAllowed null = not allowed", !r.getJSONObject("data").optBoolean("allowed"), r.toString());
        r = call("parse.battery", "{\"text\":\"Current Battery Service state:\\n  (UPDATES STOPPED -- use 'reset' to restart)\\n  level: 15\"}");
        is("parse.battery sees the frozen state", r.getJSONObject("data").optBoolean("frozen"), r.toString());

        r = call("info", "{\"which\":\"statusbar\"}");
        is("info statusbar", r.optString("cmd").equals("dumpsys statusbar"), r.toString());
        r = call("info", "{\"which\":\"notification\"}");
        is("the notification dump is not offered", !r.optBoolean("ok"), r.toString());
        r = call("no.such.op", "{}");
        is("an unknown operation says so", !r.optBoolean("ok") && r.optString("error").contains("Unknown"), r.toString());
        r = new JSONObject(SysUiOps.run("shade", "{not json"));
        is("a broken request is refused without an exception", !r.optBoolean("ok"), r.toString());
        r = new JSONObject(SysUiOps.run(null, null));
        is("a null operation is refused", !r.optBoolean("ok"), r.toString());

        System.out.println(n + " checks, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
