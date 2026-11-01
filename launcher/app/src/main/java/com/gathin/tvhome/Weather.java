package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Today's weather for the Home panel, from Open-Meteo (free, no key; the request carries only the coordinates).
 * Fetched at most every 30 minutes and only while Home is visible; the last report outlives the activity, so a
 * recreate or a trip to an app does not fetch again.
 */
final class Weather {

    /** Where the weather is for when a theme names no place (weather_city, weather_latitude, weather_longitude). */
    static final String DEFAULT_CITY = "Curitiba";
    static final double DEFAULT_LATITUDE = -25.4284, DEFAULT_LONGITUDE = -49.2733;
    private static final long REFRESH_MS = 30 * 60_000L;

    interface Listener {
        /** The latest report, or null when none could be fetched yet. Called on the main thread. */
        void onWeather(Report report);
    }

    static final class Report {
        final int temp, high, low, code;
        final boolean day;

        Report(int temp, int high, int low, int code, boolean day) {
            this.temp = temp;
            this.high = high;
            this.low = low;
            this.code = code;
            this.day = day;
        }
    }

    private static Report last;
    private static long lastFetch;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final String url;
    /** METAR station (ICAO, e.g. SBBR) whose observation replaces the model's "now"; empty for none. */
    private final String station;
    private boolean running;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            fetch();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    Weather(double latitude, double longitude, String station, Listener listener) {
        this.listener = listener;
        this.url = url(latitude, longitude);
        this.station = station.matches("[A-Za-z0-9]{4}") ? station.toUpperCase(java.util.Locale.ROOT) : "";
    }

    /** The forecast request; String.valueOf keeps the decimal point whatever the TV's locale. */
    static String url(double latitude, double longitude) {
        // shortcut: the timezone is fixed to Brazil's; make it a theme key if a TV ever leaves UTC-3.
        return "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude
                + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                + "&timezone=America%2FSao_Paulo&forecast_days=1";
    }

    void start() {
        if (running) return;
        running = true;
        listener.onWeather(last);
        long wait = Math.max(0, lastFetch + REFRESH_MS - System.currentTimeMillis());
        handler.postDelayed(refresh, last == null ? 0 : wait);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(refresh);
    }

    private void fetch() {
        lastFetch = System.currentTimeMillis();
        new Thread(() -> {
            Report fetched = download(url);
            Report r = fetched != null && !station.isEmpty() ? observed(fetched, station) : fetched;
            handler.post(() -> {
                if (r != null) last = r;
                if (running) listener.onWeather(last);
            });
        }, "weather").start();
    }

    /** One request; null on any network or parse failure, which keeps the previous report on screen. */
    private static Report download(String url) {
        String body = get(url);
        try {
            return body == null ? null : parse(body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The model's report with its "now" (temperature and condition) replaced by the station's latest METAR, a real
     * observation: Open-Meteo is a forecast model and misses local showers (it said clear sky in Brasilia during a
     * thunderstorm). Today's high and low stay the model's. The model's report as it was when the METAR is missing,
     * older than 90 minutes or does not parse.
     */
    private static Report observed(Report model, String station) {
        String body = get("https://aviationweather.gov/api/data/metar?format=json&hours=2&ids=" + station);
        try {
            return body == null ? model : fromMetar(model, body, System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            return model;
        }
    }

    static Report fromMetar(Report model, String json, long nowSeconds) throws Exception {
        JSONArray list = new JSONArray(json);
        if (list.length() == 0) return model;
        JSONObject m = list.getJSONObject(0);   // newest first
        if (!m.has("temp")) return model;
        return withObservation(model, m.getLong("obsTime"), m.getDouble("temp"),
                m.optString("wxString", ""), m.optString("cover", ""), nowSeconds);
    }

    /** The observation as "now" when it is at most 90 minutes old; the model's report otherwise. */
    static Report withObservation(Report model, long obsSeconds, double temp, String wx, String cover, long nowSeconds) {
        if (nowSeconds - obsSeconds > 90 * 60) return model;
        return new Report((int) Math.round(temp), model.high, model.low, metarCode(wx, cover), model.day);
    }

    /** METAR present weather (wxString, e.g. "-TSRA BR") and total cover to the WMO code the card shows. */
    static int metarCode(String wx, String cover) {
        if (wx.contains("TS")) return 95;
        if (wx.contains("SN")) return 71;
        if (wx.contains("RA") || wx.contains("SH")) return wx.contains("+") ? 65 : wx.contains("-") ? 61 : 63;
        if (wx.contains("DZ")) return 53;
        if (wx.contains("FG") || wx.contains("BR")) return 45;
        switch (cover) {
            case "OVC": case "OVX": case "BKN": return 3;
            case "SCT": case "FEW": return 2;
            default: return 0;   // CAVOK, CLR, SKC, NSC
        }
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            if (c.getResponseCode() != 200) return null;
            return read(c.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static Report parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONObject now = o.getJSONObject("current");
        JSONObject daily = o.getJSONObject("daily");
        return new Report(
                (int) Math.round(now.getDouble("temperature_2m")),
                (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)),
                now.getInt("weather_code"),
                now.optInt("is_day", 1) == 1);
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** WMO weather code to the panel's line icon. */
    static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_weather_sun : R.drawable.ic_weather_moon;
        if (code <= 2) return day ? R.drawable.ic_weather_partly : R.drawable.ic_weather_cloud;
        if (code == 3) return R.drawable.ic_weather_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_weather_fog;
        if (code >= 51 && code <= 57) return R.drawable.ic_weather_drizzle;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_weather_snow;
        if (code >= 95) return R.drawable.ic_weather_storm;
        return R.drawable.ic_weather_rain;   // 61-67, 80-82
    }

    /** WMO weather code to the panel's description. */
    static int description(int code) {
        if (code == 0) return R.string.weather_clear;
        if (code <= 2) return R.string.weather_partly_cloudy;
        if (code == 3) return R.string.weather_cloudy;
        if (code == 45 || code == 48) return R.string.weather_fog;
        if (code >= 51 && code <= 57) return R.string.weather_drizzle;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.string.weather_snow;
        if (code >= 95) return R.string.weather_storm;
        return R.string.weather_rain;
    }
}
