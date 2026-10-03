package android.content.res;
public class Resources {
    public static Resources getSystem() { return new Resources(); }
    // Mimic a few framework attribute names for stripped string pools
    public String getResourceEntryName(int id) { if (id == 0x01010003) return "name"; throw new RuntimeException("nf"); }
    public String getResourcePackageName(int id) { if ((id >>> 24) == 1) return "android"; throw new RuntimeException("nf"); }
    public String getResourceTypeName(int id) { if ((id >>> 24) == 1) return "style"; throw new RuntimeException("nf"); }
}
