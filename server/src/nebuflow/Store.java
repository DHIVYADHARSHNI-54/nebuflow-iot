package nebuflow;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** All live data of the system: patient, device readings, commands, session statistics and inhalation log. */
public class Store {

    public static int thresholdFor(String mode) {
        return switch (mode) {
            case "light" -> 200;
            case "heavy" -> 1000;
            default -> 500;   // moderate
        };
    }

    public static final String[] PATIENT_KEYS = {
        "id", "name", "age", "gender", "weight", "condition", "doctor",
        "medication", "dose", "duration", "notes"
    };

    private record Point(long t, double pressure, int valve) {}

    /** One inhalation = one valve-open period. */
    private static class Event {
        long start; long durMs; double peak; String mode; boolean open = true;
        Event(long start, String mode, double peak) { this.start = start; this.mode = mode; this.peak = peak; }
    }

    // ---- patient details (entered on the website) ----
    private final Map<String, String> patient = new LinkedHashMap<>();

    // ---- latest device reading ----
    private String deviceId = "-";
    private double pressure = 0, temp = 0, baseline = 0;
    private int valve = 0, rssi = 0, threshold = 500;
    private long uptime = 0, packets = 0;
    private String deviceMode = "moderate";
    private long lastSeen = 0;
    private long prevUptime = -1;
    private int restarts = 0;
    private final Deque<Long> pktTimes = new ArrayDeque<>();

    // ---- command from website to device ----
    private long cmdSeq = 0;
    private String cmdMode = "moderate";
    private boolean cmdEnabled = true;

    // ---- session statistics ----
    private long sessionStart = System.currentTimeMillis();
    private int inhalations = 0, completed = 0, weakAttempts = 0;
    private double peak = 0, sumPeaks = 0;
    private long valveOpenMs = 0, activeMs = 0, longestMs = 0, lastDurMs = 0;
    private final long[] modeMs = new long[3];   // light, moderate, heavy
    private long lastTelemetry = 0;
    private int lastValve = 0;
    private boolean inEpisode = false, episodeOpened = false;
    private Event current = null;
    private final Deque<Point> history = new ArrayDeque<>();
    private final Deque<Event> events = new ArrayDeque<>();
    private static final int MAX_POINTS = 120;   // 1 minute at 2 samples/second
    private static final int MAX_EVENTS = 15;

    private final File logFile = new File("data/log.csv");

    public Store() {
        for (String k : PATIENT_KEYS) patient.put(k, "");
        try {
            logFile.getParentFile().mkdirs();
            if (!logFile.exists()) {
                try (PrintWriter w = new PrintWriter(new FileWriter(logFile, true))) {
                    w.println("time,patient_id,device,mode,threshold_pa,pressure_drop_pa,temp_c,valve_open,rssi");
                }
            }
        } catch (IOException e) {
            System.err.println("Could not create log file: " + e.getMessage());
        }
    }

    public synchronized void telemetry(String device, double p, double t, int v, String mode,
                                       int thr, int rs, long up, double base) {
        long now = System.currentTimeMillis();
        long dt = lastTelemetry == 0 ? 0 : Math.min(now - lastTelemetry, 2000);
        lastTelemetry = now;
        lastSeen = now;
        packets++;
        if (prevUptime >= 0 && up < prevUptime) restarts++;   // ESP32 rebooted
        prevUptime = up;
        pktTimes.addLast(now);
        while (!pktTimes.isEmpty() && now - pktTimes.peekFirst() > 10000) pktTimes.removeFirst();
        deviceId = device; pressure = p; temp = t; valve = v; deviceMode = mode;
        threshold = thr; rssi = rs; uptime = up; baseline = base;

        activeMs += dt;
        if (v == 1) valveOpenMs += dt;
        int mi = mode.equals("light") ? 0 : mode.equals("heavy") ? 2 : 1;
        modeMs[mi] += dt;
        if (p > peak) peak = p;

        // inhalation events (valve open periods)
        if (v == 1 && lastValve == 0) {
            inhalations++;
            current = new Event(now, mode, p);
            events.addFirst(current);
            while (events.size() > MAX_EVENTS) events.removeLast();
        }
        if (v == 1 && current != null) {
            current.peak = Math.max(current.peak, p);
            current.durMs = now - current.start;
        }
        if (v == 0 && lastValve == 1 && current != null) {
            current.durMs = now - current.start;
            current.open = false;
            sumPeaks += current.peak;
            completed++;
            lastDurMs = current.durMs;
            longestMs = Math.max(longestMs, current.durMs);
            current = null;
        }
        lastValve = v;

        // weak attempts: a breath that clearly rose but never reached the threshold
        if (!cmdEnabled) {
            inEpisode = false;
        } else if (!inEpisode) {
            if (p > 0.4 * thr) { inEpisode = true; episodeOpened = (v == 1); }
        } else {
            if (v == 1) episodeOpened = true;
            if (p < 0.2 * thr) {
                if (!episodeOpened) weakAttempts++;
                inEpisode = false;
            }
        }

        history.addLast(new Point(now, p, v));
        while (history.size() > MAX_POINTS) history.removeFirst();

        try (PrintWriter w = new PrintWriter(new FileWriter(logFile, true))) {
            w.printf(Locale.ROOT, "%d,%s,%s,%s,%d,%.1f,%.1f,%d,%d%n",
                    now, patient.get("id").replace(',', ' '), device, mode, thr, p, t, v, rs);
        } catch (IOException ignored) { }
    }

    public synchronized void setPatient(Map<String, String> values) {
        for (String k : PATIENT_KEYS) patient.put(k, values.getOrDefault(k, ""));
    }

    public synchronized void command(String mode, boolean enabled) {
        if (mode.equals("light") || mode.equals("moderate") || mode.equals("heavy")) cmdMode = mode;
        cmdEnabled = enabled;
        cmdSeq++;
    }

    public synchronized void resetSession() {
        sessionStart = System.currentTimeMillis();
        inhalations = 0; completed = 0; weakAttempts = 0;
        peak = 0; sumPeaks = 0;
        valveOpenMs = 0; activeMs = 0; longestMs = 0; lastDurMs = 0;
        java.util.Arrays.fill(modeMs, 0);
        lastValve = 0; inEpisode = false; episodeOpened = false; current = null;
        history.clear(); events.clear();
        restarts = 0;
    }

    /** JSON sent back to the ESP32 after every telemetry post. */
    public synchronized String commandJson() {
        return "{\"cmdSeq\":" + cmdSeq + ",\"mode\":\"" + cmdMode + "\",\"enabled\":" + cmdEnabled + "}";
    }

    public synchronized long cmdSeq() { return cmdSeq; }
    public synchronized String cmdMode() { return cmdMode; }
    public synchronized boolean cmdEnabled() { return cmdEnabled; }

    /** Full state for the website. */
    public synchronized String stateJson() {
        long now = System.currentTimeMillis();
        boolean online = lastSeen != 0 && now - lastSeen < 3000;
        double openPct = activeMs == 0 ? 0 : 100.0 * valveOpenMs / activeMs;
        double savedPct = activeMs == 0 ? 0 : 100.0 - openPct;
        double avgInhaleS = inhalations == 0 ? 0 : valveOpenMs / 1000.0 / inhalations;
        double bpm = activeMs == 0 ? 0 : inhalations / (activeMs / 60000.0);
        double avgPeak = completed == 0 ? 0 : sumPeaks / completed;
        while (!pktTimes.isEmpty() && now - pktTimes.peekFirst() > 10000) pktTimes.removeFirst();
        double pktRate = 0;
        if (pktTimes.size() >= 2) {
            double span = (pktTimes.peekLast() - pktTimes.peekFirst()) / 1000.0;
            if (span > 0) pktRate = (pktTimes.size() - 1) / span;
        }

        StringBuilder sb = new StringBuilder(8192);
        sb.append(String.format(Locale.ROOT,
            "{\"online\":%b,\"device\":\"%s\",\"pressure\":%.1f,\"temp\":%.1f,\"valve\":%d,"
          + "\"mode\":\"%s\",\"threshold\":%d,\"rssi\":%d,\"uptime\":%d,"
          + "\"baselineHpa\":%.1f,\"packets\":%d,\"ageMs\":%d,\"pktRate\":%.2f,\"restarts\":%d,"
          + "\"cmdMode\":\"%s\",\"cmdEnabled\":%b,",
            online, Json.esc(deviceId), pressure, temp, valve, deviceMode, threshold, rssi, uptime,
            baseline / 100.0, packets, lastSeen == 0 ? -1 : now - lastSeen, pktRate, restarts,
            cmdMode, cmdEnabled));
        sb.append(String.format(Locale.ROOT,
            "\"session\":{\"seconds\":%d,\"inhalations\":%d,\"peak\":%.1f,\"avgPeak\":%.1f,"
          + "\"valveOpenSeconds\":%.1f,\"valveOpenPct\":%.1f,\"medicineSavedPct\":%.1f,"
          + "\"avgInhaleSeconds\":%.2f,\"lastInhaleSeconds\":%.2f,\"longestInhaleSeconds\":%.2f,"
          + "\"breathsPerMin\":%.1f,\"weakAttempts\":%d,"
          + "\"modeSeconds\":{\"light\":%.0f,\"moderate\":%.0f,\"heavy\":%.0f}},",
            (now - sessionStart) / 1000, inhalations, peak, avgPeak,
            valveOpenMs / 1000.0, openPct, savedPct,
            avgInhaleS, lastDurMs / 1000.0, longestMs / 1000.0,
            bpm, weakAttempts,
            modeMs[0] / 1000.0, modeMs[1] / 1000.0, modeMs[2] / 1000.0));
        sb.append("\"patient\":{");
        boolean firstK = true;
        for (String k : PATIENT_KEYS) {
            if (!firstK) sb.append(',');
            firstK = false;
            sb.append('"').append(k).append("\":\"").append(Json.esc(patient.get(k))).append('"');
        }
        sb.append("},\"events\":[");
        boolean fe = true;
        for (Event e : events) {
            if (!fe) sb.append(',');
            fe = false;
            sb.append(String.format(Locale.ROOT, "[%d,%.2f,%.1f,\"%s\",%b]",
                    e.start, e.durMs / 1000.0, e.peak, e.mode, e.open));
        }
        sb.append("],\"history\":[");
        boolean first = true;
        for (Point p : history) {
            if (!first) sb.append(',');
            first = false;
            sb.append(String.format(Locale.ROOT, "[%d,%.1f,%d]", p.t, p.pressure, p.valve));
        }
        sb.append("]}");
        return sb.toString();
    }

    public File logFile() { return logFile; }
}
