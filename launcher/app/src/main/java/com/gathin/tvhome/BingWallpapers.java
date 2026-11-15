package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local addition (not upstream): keeps the theme's wallpapers/ folder filled with Bing's recent daily images
 * (bing_YYYYMMDD.jpg, up to 16), so the existing rotation shows them. Checks at most every CHECK_MS, on a
 * background thread, and only touches files it named itself.
 */
final class BingWallpapers {
    private static final String TAG = "TvHome.Bing";
    private static final String PREFIX = "bing_";
    private static final long CHECK_MS = 6L * 60 * 60 * 1000;
    private static final long RETRY_MS = 20L * 60 * 1000;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile boolean running;

    private BingWallpapers() {}

    /** Starts a check when one is due; onChanged runs on the main thread when files were added or removed. */
    static void refreshIfDue(Context context, String market, Runnable onChanged) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("bing_wallpapers", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long next = prefs.getLong("next_check", 0);
        if (running || (now >= next - CHECK_MS && now < next)) return;
        File dir = Config.dir(app);
        if (dir == null) return;
        running = true;
        worker.execute(() -> {
            boolean changed = false, ok = false;
            try {
                changed = sync(new File(dir, ThemeFiles.WALLPAPERS), market);
                ok = true;
            } catch (Exception e) {
                Log.w(TAG, "Bing refresh failed: " + e);
            } finally {
                prefs.edit().putLong("next_check", System.currentTimeMillis() + (ok ? CHECK_MS : RETRY_MS)).apply();
                running = false;
            }
            if (changed) new Handler(Looper.getMainLooper()).post(onChanged);
        });
    }

    private static boolean sync(File folder, String market) throws Exception {
        // idx 0 gives the newest eight; idx 7 the eight before them (Bing serves about 16 days).
        Map<String, String> wanted = new LinkedHashMap<>();
        for (int idx : new int[]{0, 7}) {
            String json = new String(get("https://www.bing.com/HPImageArchive.aspx?format=js&n=8&idx=" + idx
                    + "&mkt=" + market, 512 * 1024), StandardCharsets.UTF_8);
            JSONArray images = new JSONObject(json).getJSONArray("images");
            for (int i = 0; i < images.length(); i++) {
                JSONObject img = images.getJSONObject(i);
                String date = img.optString("startdate", "");
                String base = img.optString("urlbase", "");
                if (!date.matches("\\d{8}") || !base.startsWith("/")) continue;
                wanted.put(PREFIX + date + ".jpg", "https://www.bing.com" + base + "_1920x1080.jpg");
            }
        }
        if (wanted.isEmpty()) return false;
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
        boolean changed = false;
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            File target = new File(folder, e.getKey());
            if (target.isFile() && target.length() > 0) continue;
            File tmp = new File(folder, e.getKey() + ".part");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(get(e.getValue(), MAX_IMAGE_BYTES));
            }
            if (tmp.renameTo(target)) changed = true; else tmp.delete();
        }
        File[] existing = folder.listFiles();
        if (existing != null) {
            for (File f : existing) {
                String name = f.getName();
                if (name.startsWith(PREFIX) && !wanted.containsKey(name) && f.delete()) changed = true;
            }
        }
        return changed;
    }

    private static byte[] get(String url, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > limit) throw new IOException("response too large: " + url);
                }
                return buf.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
