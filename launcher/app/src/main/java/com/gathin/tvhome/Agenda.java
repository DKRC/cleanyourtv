package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The next episodes and races for the Home "Up next" card, from a theme's agenda_calendars: iCalendar feeds such
 * as an Episodate watchlist or an F1 calendar (webcal:// is read as https://). Fetched at most every hour and only
 * while Home is visible; the last list outlives the activity, like Weather's report.
 */
final class Agenda {

    private static final long REFRESH_MS = 60 * 60_000L;
    /** An event stays listed this long after it starts, while it is likely still on. */
    static final long STAYS_MS = 2 * 60 * 60_000L;

    interface Listener {
        /** Every event fetched so far, in start order; empty until the first fetch. Called on the main thread. */
        void onAgenda(List<Event> events);
    }

    static final class Event {
        final Instant start;
        final String title;

        Event(Instant start, String title) {
            this.start = start;
            this.title = title;
        }
    }

    private static List<Event> last = Collections.emptyList();
    private static long lastFetch;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final List<String> urls;
    private boolean running;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            fetch();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    Agenda(List<String> urls, Listener listener) {
        this.listener = listener;
        this.urls = urls;
    }

    void start() {
        if (running) return;
        running = true;
        listener.onAgenda(last);
        long wait = Math.max(0, lastFetch + REFRESH_MS - System.currentTimeMillis());
        handler.postDelayed(refresh, last.isEmpty() ? 0 : wait);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(refresh);
    }

    private void fetch() {
        lastFetch = System.currentTimeMillis();
        new Thread(() -> {
            List<Event> fetched = new ArrayList<>();
            for (String url : urls) {
                String body = get(url.replaceFirst("^webcal://", "https://"));
                if (body != null) fetched.addAll(parse(body));
            }
            fetched.sort((a, b) -> a.start.compareTo(b.start));
            handler.post(() -> {
                if (!fetched.isEmpty()) last = fetched;   // offline keeps the last list
                if (running) listener.onAgenda(last);
            });
        }, "agenda").start();
    }

    /** A series' next episode shows when it is at most this many days after today. */
    static final int SERIES_DAYS = 30;

    /**
     * What the card lists, in start order and at most {@code max}: every session of the next F1 weekend from the
     * Monday of its week, and the next episode of each series within {@link #SERIES_DAYS}, one per series.
     * Events that started less than {@link #STAYS_MS} ago still count as next.
     */
    static List<Event> select(List<Event> events, Instant now, ZoneId zone, int max) {
        Instant since = now.minusMillis(STAYS_MS);
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant seriesUntil = today.plusDays(SERIES_DAYS + 1).atStartOfDay(zone).toInstant();
        String weekend = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Event> out = new ArrayList<>();
        for (Event e : events) {   // in start order
            if (!e.start.isAfter(since)) continue;
            Matcher f1 = F1.matcher(e.title);
            if (f1.matches()) {
                // The first session ahead picks the weekend; it shows from the Monday of that session's week.
                if (weekend == null) {
                    LocalDate monday = e.start.atZone(zone).toLocalDate()
                            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                    weekend = today.isBefore(monday) ? "" : f1.group(2);
                }
                if (f1.group(2).equals(weekend)) out.add(e);
            } else if (e.start.isBefore(seriesUntil)) {
                Matcher ep = EPISODE.matcher(e.title);
                if (seen.add(ep.matches() ? ep.group(1) : e.title)) out.add(e);
            }
        }
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Minimal iCalendar reader: DTSTART and SUMMARY of each VEVENT; an event missing either is skipped. */
    static List<Event> parse(String ics) {
        List<Event> events = new ArrayList<>();
        Instant start = null;
        String title = null;
        // Unfold continuation lines (a line break followed by one space or tab) before splitting.
        for (String line : ics.replace("\r\n", "\n").replaceAll("\n[ \t]", "").split("\n")) {
            if (line.equals("BEGIN:VEVENT")) {
                start = null;
                title = null;
            } else if (line.startsWith("DTSTART")) {
                start = date(line.substring(line.lastIndexOf(':') + 1).trim());
            } else if (line.startsWith("SUMMARY:")) {
                title = line.substring(8).replace("\\,", ",").replace("\\;", ";").trim();
            } else if (line.equals("END:VEVENT") && start != null && title != null) {
                events.add(new Event(start, title));
            }
        }
        return events;
    }

    /** "20261101T130000Z" is UTC; floating times and all-day dates are the TV's local time. Null if unreadable. */
    static Instant date(String v) {
        try {
            if (v.length() == 8) return LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneId.systemDefault()).toInstant();
            LocalDateTime t = LocalDateTime.parse(v.replace("Z", ""), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            return v.endsWith("Z") ? t.toInstant(ZoneOffset.UTC) : t.atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern F1 = Pattern.compile("^F1: (.+) \\((.+)\\)$");
    private static final Pattern EPISODE = Pattern.compile("^(.+) - (S\\d+E\\d+)( \\(.+\\))?$");

    /**
     * Shorter titles for the card: "F1: Qualifying (Brazilian Grand Prix)" becomes "Brazilian GP · Quali"
     * (the race session as {@code race}), and Episodate's "Dexter - S02E02 (Paramount+)" becomes "Dexter S02E02".
     */
    static String shortTitle(String title, String race) {
        Matcher m = F1.matcher(title);
        if (m.matches()) {
            String session = m.group(1).equals("Grand Prix") ? race : m.group(1).replaceAll("Qualif\\w+", "Quali");
            return m.group(2).replace("Grand Prix", "GP") + " · " + session;   // the row's red bar says F1
        }
        m = EPISODE.matcher(title);
        return m.matches() ? m.group(1) + " " + m.group(2) : title;
    }

    /** F1 sessions show their time on the card; episodes, whose hour is only the feed's guess, do not. */
    static boolean isF1(String title) {
        return F1.matcher(title).matches();
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream s = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
