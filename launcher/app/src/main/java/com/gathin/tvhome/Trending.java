package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Trending titles for the top shelf: for each streaming app in the dock, the recent films and series that are
 * popular on that service in one country, with a backdrop each. The lists come from TMDB's discover endpoint
 * (watch_region + with_watch_providers, newest 18 months, sorted by TMDB popularity), so they are TMDB's measure
 * of current interest and not the services' own charts.
 *
 * Everything is cached in the app's files directory: Home shows the cached list at once, and the network is
 * asked again only when the cache is older than {@link #STALE_MS}. Without a key (launcher/tmdb.properties) or
 * with trending_shelf false, {@link #create} returns null and the launcher never opens a connection.
 *
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
final class Trending {
    private static final String TAG = "Trending";
    private static final String API = "https://api.themoviedb.org/3/discover/";
    private static final String IMAGES = "https://image.tmdb.org/t/p/w1280";
    private static final long STALE_MS = 12L * 60 * 60 * 1000;
    /** After a refresh that got nothing, wait this long before asking again. */
    private static final long RETRY_MS = 20L * 60 * 1000;
    private static final int PER_KIND = 4;
    private static final int ATTEMPTS = 4;
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    /** Decoded backdrops kept in memory: the one on screen and its neighbours. */
    private static final int MEMORY = 3;

    /** One title on the shelf. */
    static final class Item {
        final String id;
        final String title;
        final String file;

        Item(String id, String title, String file) {
            this.id = id;
            this.title = title;
            this.file = file;
        }
    }

    interface Listener {
        void onTrendingChanged();
    }

    private final File dir;
    private final String key;
    private final String region;
    /** Package name to TMDB watch provider id, in config order. */
    private final Map<String, Integer> providers;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService decode = Executors.newSingleThreadExecutor();

    // Main thread only.
    private Map<String, List<Item>> items = Collections.emptyMap();
    private final Map<String, Integer> index = new HashMap<>();
    private final LinkedHashMap<String, Bitmap> memory = new LinkedHashMap<>(8, 0.75f, true);
    private Listener listener;
    private boolean refreshing;
    private long fetchedAt;
    private long lastAttempt;

    private Trending(Context context, String key, String region, Map<String, Integer> providers) {
        this.dir = new File(context.getFilesDir(), "trending");
        this.key = key;
        this.region = region;
        this.providers = providers;
    }

    /** The shelf's data source, or null when the feature is off, has no key, or lists no provider. */
    static Trending create(Context context) {
        if (!context.getResources().getBoolean(R.bool.trending_shelf)) return null;
        String key = BuildConfig.TMDB_KEY.trim();
        if (key.isEmpty()) return null;
        Map<String, Integer> providers = new LinkedHashMap<>();
        for (String line : context.getResources().getStringArray(R.array.trending_providers)) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try {
                providers.put(line.substring(0, eq).trim(), Integer.parseInt(line.substring(eq + 1).trim()));
            } catch (NumberFormatException e) {
                Log.w(TAG, "trending_providers: not a provider id in " + line);
            }
        }
        if (providers.isEmpty()) return null;
        String region = context.getString(R.string.trending_region).trim();
        return new Trending(context.getApplicationContext(), key, region.isEmpty() ? "IN" : region, providers);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Loads the cached lists, then asks the network if they are old. */
    void start() {
        net.execute(() -> {
            Map<String, List<Item>> cached = new HashMap<>();
            long when = readIndex(cached);
            main.post(() -> {
                fetchedAt = when;
                if (!cached.isEmpty()) publish(cached);
                refreshIfStale();
            });
        });
    }

    /** Asks the network again when the cache is older than half a day; cheap to call on every resume. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (refreshing) return;
        if (now - fetchedAt < STALE_MS && !items.isEmpty()) return;
        if (now - lastAttempt < RETRY_MS) return;
        refreshing = true;
        lastAttempt = now;
        final Map<String, List<Item>> before = items;
        net.execute(() -> {
            Map<String, List<Item>> fresh = new HashMap<>();
            boolean any = false;
            for (Map.Entry<String, Integer> p : providers.entrySet()) {
                List<Item> list = fetchProvider(p.getValue());
                if (!list.isEmpty()) {
                    fresh.put(p.getKey(), list);
                    any = true;
                } else if (before.containsKey(p.getKey())) {
                    fresh.put(p.getKey(), before.get(p.getKey()));   // keep yesterday's list for this service
                }
            }
            final boolean got = any;
            if (got) {
                writeIndex(fresh);
                prune(fresh);
            }
            main.post(() -> {
                refreshing = false;
                if (!got) return;
                fetchedAt = System.currentTimeMillis();
                publish(fresh);
            });
        });
    }

    private void publish(Map<String, List<Item>> next) {
        items = next;
        index.clear();
        if (listener != null) listener.onTrendingChanged();
    }

    /** The title this app's shelf shows now, or null when the app has none. */
    Item current(String pkg) {
        if (pkg == null) return null;
        List<Item> list = items.get(pkg);
        if (list == null || list.isEmpty()) return null;
        Integer at = index.get(pkg);
        return list.get(at == null ? 0 : at % list.size());
    }

    /** Moves this app's shelf to its next title; false when there is nothing to move to. */
    boolean advance(String pkg) {
        List<Item> list = pkg == null ? null : items.get(pkg);
        if (list == null || list.size() < 2) return false;
        Integer at = index.get(pkg);
        index.put(pkg, ((at == null ? 0 : at) + 1) % list.size());
        return true;
    }

    /** The decoded backdrop if it is already in memory. */
    Bitmap cached(Item item) {
        return memory.get(item.id);
    }

    /** Decodes the backdrop off the main thread and hands it back on the main thread; null when it cannot be read. */
    void load(Item item, Consumer<Bitmap> done) {
        Bitmap have = memory.get(item.id);
        if (have != null) {
            done.accept(have);
            return;
        }
        decode.execute(() -> {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;   // 1280x720 in 1.8 MB instead of 3.7 MB
            Bitmap bmp = BitmapFactory.decodeFile(new File(dir, item.file).getPath(), opts);
            main.post(() -> {
                if (bmp != null) remember(item.id, bmp);
                done.accept(bmp);
            });
        });
    }

    private void remember(String id, Bitmap bmp) {
        memory.put(id, bmp);
        while (memory.size() > MEMORY) {
            String eldest = memory.keySet().iterator().next();
            memory.remove(eldest);   // not recycled: the shelf's ImageView may still be drawing it
        }
    }

    /** Home is stopped: drop the decoded backdrops. The files stay. */
    void trimMemory() {
        memory.clear();
    }

    void shutdown() {
        listener = null;
        net.shutdownNow();
        decode.shutdownNow();
    }

    // ---- network (net thread) -------------------------------------------------------------------

    /** Series and films for one service, interleaved, each with its backdrop already on disk. */
    private List<Item> fetchProvider(int providerId) {
        String since = LocalDate.now().minusMonths(18).toString();
        List<Item> tv = fetchKind("tv", "first_air_date.gte=" + since, providerId);
        List<Item> films = fetchKind("movie", "primary_release_date.gte=" + since, providerId);
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < PER_KIND; i++) {
            if (i < tv.size()) out.add(tv.get(i));
            if (i < films.size()) out.add(films.get(i));
        }
        return out;
    }

    private List<Item> fetchKind(String kind, String dateFilter, int providerId) {
        List<Item> out = new ArrayList<>();
        String url = API + kind + "?watch_region=" + region + "&with_watch_providers=" + providerId
                + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false"
                + "&vote_count.gte=15&" + dateFilter + "&api_key=" + key;
        byte[] body = get(url, 512 * 1024);
        if (body == null) return out;
        try {
            JSONArray results = new JSONObject(new String(body, StandardCharsets.UTF_8)).getJSONArray("results");
            for (int i = 0; i < results.length() && out.size() < PER_KIND; i++) {
                JSONObject r = results.getJSONObject(i);
                String backdrop = r.optString("backdrop_path", "");
                String title = r.optString("movie".equals(kind) ? "title" : "name", "").trim();
                if (backdrop.length() < 2 || "null".equals(backdrop) || title.isEmpty()) continue;
                String id = kind + "-" + r.getLong("id");
                String file = id + ".jpg";
                if (!ensureImage(backdrop, file)) continue;
                out.add(new Item(id, title, file));
            }
        } catch (Exception e) {
            Log.w(TAG, "discover/" + kind + " for provider " + providerId + ": " + e);
        }
        return out;
    }

    private boolean ensureImage(String backdropPath, String file) {
        File target = new File(dir, file);
        if (target.length() > 0) return true;
        byte[] jpg = get(IMAGES + backdropPath, MAX_IMAGE_BYTES);
        if (jpg == null || jpg.length < 1024) return false;
        return write(target, jpg);
    }

    /** One GET with a few retries: some networks reset the first connections to this host. */
    private static byte[] get(String url, int maxBytes) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(20_000);
                c.setRequestProperty("Accept", "application/json, image/jpeg");
                int code = c.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "HTTP " + code + " from " + c.getURL().getHost());
                    if (code == 401 || code == 404) return null;   // a bad key or a missing image never recovers
                } else {
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
                        byte[] chunk = new byte[16 * 1024];
                        int n;
                        while ((n = in.read(chunk)) > 0) {
                            buf.write(chunk, 0, n);
                            if (buf.size() > maxBytes) return null;
                        }
                        return buf.toByteArray();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "attempt " + attempt + ": " + e.getClass().getSimpleName());
            } finally {
                if (c != null) c.disconnect();
            }
            try {
                Thread.sleep(1500L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // ---- cache (net thread) ---------------------------------------------------------------------

    private boolean write(File target, byte[] bytes) {
        File tmp = new File(dir, target.getName() + ".tmp");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            return tmp.renameTo(target);
        } catch (Exception e) {
            Log.w(TAG, "write " + target.getName() + ": " + e);
            return false;
        }
    }

    private void writeIndex(Map<String, List<Item>> lists) {
        try {
            JSONObject root = new JSONObject();
            root.put("fetchedAt", System.currentTimeMillis());
            root.put("region", region);
            JSONObject apps = new JSONObject();
            for (Map.Entry<String, List<Item>> e : lists.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Item it : e.getValue()) {
                    arr.put(new JSONObject().put("id", it.id).put("title", it.title).put("file", it.file));
                }
                apps.put(e.getKey(), arr);
            }
            root.put("apps", apps);
            write(new File(dir, "index.json"), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "index: " + e);
        }
    }

    /** Fills {@code into} from the cache and returns when it was fetched, 0 when there is none for this region. */
    private long readIndex(Map<String, List<Item>> into) {
        File f = new File(dir, "index.json");
        if (!f.isFile()) return 0;
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (!region.equals(root.optString("region"))) return 0;
            JSONObject apps = root.getJSONObject("apps");
            for (String pkg : providers.keySet()) {
                JSONArray arr = apps.optJSONArray(pkg);
                if (arr == null) continue;
                List<Item> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Item it = new Item(o.getString("id"), o.getString("title"), o.getString("file"));
                    if (new File(dir, it.file).length() > 0) list.add(it);
                }
                if (!list.isEmpty()) into.put(pkg, list);
            }
            return root.optLong("fetchedAt", 0);
        } catch (Exception e) {
            Log.w(TAG, "cache: " + e);
            return 0;
        }
    }

    /** Deletes backdrops no list refers to any more. */
    private void prune(Map<String, List<Item>> lists) {
        Set<String> keep = new HashSet<>();
        keep.add("index.json");
        for (List<Item> list : lists.values()) for (Item it : list) keep.add(it.file);
        File[] all = dir.listFiles();
        if (all == null) return;
        for (File f : all) {
            if (!keep.contains(f.getName()) && !f.delete()) Log.w(TAG, "could not delete " + f.getName());
        }
    }
}
