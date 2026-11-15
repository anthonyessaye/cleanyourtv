package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local addition (not upstream): the current temperature and conditions above the date, from Open-Meteo (no key),
 * for config.xml latitude/longitude. Refreshed every 30 minutes while Home is visible; the last reading is kept so
 * Home shows it at once after a restart, and hidden once it is over three hours old.
 */
final class TopWeather {
    private static final String TAG = "TvHome.Weather";
    private static final long REFRESH_MS = 30L * 60 * 1000;
    private static final long RETRY_MS = 5L * 60 * 1000;
    private static final long STALE_MS = 3L * 60 * 60 * 1000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView view;
    private final SharedPreferences prefs;
    private final String latitude, longitude;
    private final boolean fahrenheit;
    private boolean active, closed, loading;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active || closed) return;
            long age = System.currentTimeMillis() - prefs.getLong("time", 0);
            if (age >= REFRESH_MS || age < 0) fetch();
            show();
            handler.postDelayed(this, 60_000);
        }
    };

    TopWeather(Context context, TextView view, String latitude, String longitude, boolean fahrenheit) {
        this.view = view;
        this.prefs = context.getSharedPreferences("top_weather", Context.MODE_PRIVATE);
        this.latitude = latitude;
        this.longitude = longitude;
        this.fahrenheit = fahrenheit;
        String place = latitude + "," + longitude + "," + fahrenheit;
        if (!place.equals(prefs.getString("place", ""))) prefs.edit().clear().putString("place", place).apply();
        show();
    }

    void start() {
        if (closed) return;
        active = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void stop() {
        active = false;
        handler.removeCallbacks(tick);
    }

    void close() {
        stop();
        closed = true;
        worker.shutdownNow();
    }

    private void show() {
        String text = prefs.getString("text", "");
        long age = System.currentTimeMillis() - prefs.getLong("time", 0);
        boolean fresh = !text.isEmpty() && age >= 0 && age < STALE_MS;
        view.setText(fresh ? text : "");
        view.setVisibility(fresh ? View.VISIBLE : View.GONE);
    }

    private void fetch() {
        if (loading) return;
        long lastTry = prefs.getLong("last_try", 0);
        long now = System.currentTimeMillis();
        if (now - lastTry >= 0 && now - lastTry < RETRY_MS) return;
        prefs.edit().putLong("last_try", now).apply();
        loading = true;
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude
                + "&current=temperature_2m,weather_code,is_day&timezone=auto"
                + (fahrenheit ? "&temperature_unit=fahrenheit" : "");
        worker.execute(() -> {
            String text = null;
            try {
                JSONObject current = new JSONObject(get(url)).getJSONObject("current");
                long temp = Math.round(current.getDouble("temperature_2m"));
                text = temp + "°  " + describe(current.getInt("weather_code"), current.optInt("is_day", 1) == 1);
            } catch (Exception e) {
                Log.w(TAG, "weather fetch failed: " + e);
            }
            String result = text;
            handler.post(() -> {
                loading = false;
                if (closed) return;
                if (result != null) {
                    prefs.edit().putString("text", result).putLong("time", System.currentTimeMillis()).apply();
                }
                show();
            });
        });
    }

    /** WMO weather interpretation codes, as Open-Meteo documents them. */
    static String describe(int code, boolean day) {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly sunny" : "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: case 56: case 57: return "Drizzle";
            case 61: case 63: case 80: case 81: return "Rain";
            case 65: case 82: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: case 85: case 86: return "Snow";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, hail";
            default: return String.format(Locale.ROOT, "Code %d", code);
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > 256 * 1024) throw new IOException("response too large");
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
