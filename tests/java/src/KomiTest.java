import com.bloatware.bingblop.KomiApi;
import org.json.*;
import java.nio.file.*;
import java.util.*;

public class KomiTest {
  static final String FIX = System.getProperty("fixtures", "fixtures") + "/";
  static int fails = 0;
  static void check(String n, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + n); if (!ok) fails++; }
  public static void main(String[] a) throws Exception {
    String[] files = {"feed-android","trending-android","most-popular-android","new-releases-android",
      "topics-privacy-android","topics-media-android","topics-productivity-android","topics-networking-android","topics-dev-tools-android"};
    Set<String> seen = new HashSet<>();
    JSONArray all = new JSONArray();
    int raw = 0;
    for (String f : files) {
      JSONObject body = new JSONObject(new String(Files.readAllBytes(Paths.get(FIX + "komi-" + f + ".json")), "UTF-8"));
      raw += body.getJSONArray("repositories").length();
      JSONArray part = KomiApi.mapItems(body, seen);
      for (int i = 0; i < part.length(); i++) all.put(part.get(i));
    }
    System.out.println("raw repos across files: " + raw + " -> unique catalog items: " + all.length());
    check("items mapped and de-duplicated across overlapping lists", all.length() > 150 && all.length() < raw);

    // No item may carry the literal string "null" (Android org.json optString() trap) or lack owner/repo/page.
    boolean nullStr = false, missing = false, badIcon = false, noCats = false;
    for (int i = 0; i < all.length(); i++) {
      JSONObject o = all.getJSONObject(i);
      for (String k : new String[]{"name","desc","owner","repo","ver","lang","page","icon"}) if ("null".equals(o.optString(k))) { nullStr = true; System.out.println("  null-string in " + k + " of " + o.optString("key")); }
      if (o.optString("owner").isEmpty() || o.optString("repo").isEmpty() || o.optString("page").isEmpty() || !o.optString("key").contains("/")) missing = true;
      if (!o.optString("icon").startsWith("https://")) badIcon = true;
      JSONArray c = o.optJSONArray("cats"); if (c == null || c.length() == 0 || c.length() > 3) noCats = true;
    }
    check("no literal \"null\" strings from JSON nulls", !nullStr);
    check("every item has owner/repo/key/page", !missing);
    check("every item has an https icon", !badIcon);
    check("every item has 1..3 categories", !noCats);

    // Feed item 0 is a Godot app: game topics -> Games
    JSONObject feed = new JSONObject(new String(Files.readAllBytes(Paths.get(FIX + "komi-feed-android.json")), "UTF-8"));
    JSONObject first = KomiApi.item(feed.getJSONArray("repositories").getJSONObject(0));
    System.out.println("  first feed item: " + first.optString("key") + " cats=" + first.optJSONArray("cats") + " stars=" + first.optInt("stars") + " ver=" + first.optString("ver") + " updated=" + first.optLong("updated"));
    check("godot/game topics -> Games category", first.optJSONArray("cats").toString().contains("Games"));
    check("stars/updated parsed", first.optInt("stars") > 0 && first.optLong("updated") > 1.6e12);

    // Category distribution over real data
    Map<String,Integer> dist = new TreeMap<>();
    for (int i = 0; i < all.length(); i++) { JSONArray c = all.getJSONObject(i).getJSONArray("cats"); for (int j = 0; j < c.length(); j++) dist.merge(c.getString(j), 1, Integer::sum); }
    List<Map.Entry<String,Integer>> es = new ArrayList<>(dist.entrySet()); es.sort((x,y) -> y.getValue() - x.getValue());
    StringBuilder sb = new StringBuilder(); for (Map.Entry<String,Integer> e : es) sb.append(e.getKey()).append('=').append(e.getValue()).append(", ");
    System.out.println("  category distribution: " + sb);
    int other = dist.getOrDefault("Other", 0);
    check("'Other' bucket is a minority of real items (" + other + "/" + all.length() + ")", other * 100 / all.length() < 40);
    check("a healthy spread of categories (>= 10 distinct)", dist.size() >= 10);

    // Unit checks
    check("topicCodes map to labels", KomiApi.categories(new JSONArray("[\"privacy\",\"video\"]"), new JSONArray("[]"), 3).equals(Arrays.asList("Privacy & Security", "Video")));
    check("security+privacy codes de-duplicate to one label", KomiApi.categories(new JSONArray("[\"privacy\",\"security\"]"), null, 3).equals(Arrays.asList("Privacy & Security")));
    check("topic keyword rules (hyphen words)", KomiApi.categories(new JSONArray("[]"), new JSONArray("[\"video-player\",\"android\"]"), 3).contains("Video"));
    check("unknown -> Other", KomiApi.categories(null, new JSONArray("[\"android\",\"kotlin\"]"), 3).equals(Arrays.asList("Other")));
    check("max categories honored", KomiApi.categories(new JSONArray("[\"ai\",\"privacy\",\"video\",\"audio\"]"), null, 2).size() == 2);
    check("parseDate PG text", KomiApi.parseDate("2026-08-15 22:05:38+00") == 1786831538000L);
    check("parseDate ISO Z with fraction", KomiApi.parseDate("2026-08-15T22:05:38.123456Z") == 1786831538000L);
    check("parseDate junk -> 0", KomiApi.parseDate("soon") == 0L && KomiApi.parseDate(null) == 0L);
    check("null description -> empty string", KomiApi.item(new JSONObject("{\"fullName\":\"a/b\",\"name\":\"b\",\"owner\":{\"login\":\"a\",\"avatarUrl\":null},\"description\":null,\"topics\":null,\"topicCodes\":null}")).optString("desc").equals(""));
    check("avatar fallback when avatarUrl null", KomiApi.item(new JSONObject("{\"fullName\":\"a/b\",\"name\":\"b\",\"owner\":{\"login\":\"a\",\"avatarUrl\":null}}")).optString("icon").equals("https://github.com/a.png"));
    check("avatar gets a size hint", KomiApi.item(new JSONObject("{\"fullName\":\"a/b\",\"name\":\"b\",\"owner\":{\"login\":\"a\",\"avatarUrl\":\"https://avatars.githubusercontent.com/u/1?v=4\"}}")).optString("icon").endsWith("?v=4&s=96"));
    check("unusable entry -> null", KomiApi.item(new JSONObject("{}")) == null && KomiApi.item(null) == null);
    check("search response shape ({items:[]}) accepted", KomiApi.mapItems(new JSONObject("{\"items\":[{\"fullName\":\"x/y\",\"name\":\"y\",\"owner\":{\"login\":\"x\"}}],\"totalHits\":1}"), null).length() == 1);
    check("bare-array shape (categories/topics endpoints) accepted", KomiApi.mapItems(new JSONArray("[{\"fullName\":\"x/y\",\"name\":\"y\",\"owner\":{\"login\":\"x\"}}]"), null).length() == 1);
    check("pretty names", "Libre Tube".equals(KomiApi.categories(null,null,1).isEmpty() ? "" : KomiApi.item(new JSONObject("{\"fullName\":\"a/libre-tube\",\"name\":\"libre-tube\",\"owner\":{\"login\":\"a\"}}")).optString("name"))
      && "NewPipe".equals(KomiApi.item(new JSONObject("{\"fullName\":\"a/NewPipe\",\"name\":\"NewPipe\",\"owner\":{\"login\":\"a\"}}")).optString("name")));
    System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
    System.exit(fails == 0 ? 0 : 1);
  }
}
