package nebuflow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;

/** NebuFlow-IoT dashboard server. Run:  java ... nebuflow.Main [port] [--demo] */
public class Main {

    private static final Map<String, String> TYPES = Map.of(
        "html", "text/html; charset=utf-8",
        "css", "text/css; charset=utf-8",
        "js", "application/javascript; charset=utf-8",
        "png", "image/png",
        "svg", "image/svg+xml",
        "ico", "image/x-icon");

    private static final Store store = new Store();
    private static final Path WEB = Path.of("web").toAbsolutePath().normalize();

    public static void main(String[] args) throws Exception {
        int port = 8080;
        boolean demo = false;
        for (String a : args) {
            if (a.equals("--demo")) demo = true;
            else try { port = Integer.parseInt(a); } catch (NumberFormatException ignored) { }
        }
        if (!Files.isDirectory(WEB)) {
            System.err.println("Cannot find the 'web' folder at " + WEB);
            System.err.println("Run the server from the 'server' folder (the one that contains 'web').");
            System.exit(1);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", Main::handleStatic);
        server.createContext("/api/state", ex -> json(ex, 200, store.stateJson()));
        server.createContext("/api/telemetry", Main::handleTelemetry);
        server.createContext("/api/patient", Main::handlePatient);
        server.createContext("/api/command", Main::handleCommand);
        server.createContext("/api/session/reset", ex -> {
            if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, "{\"error\":\"POST only\"}"); return; }
            store.resetSession();
            json(ex, 200, "{\"ok\":true}");
        });
        server.createContext("/api/export.csv", Main::handleExport);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("=== NebuFlow-IoT server running ===");
        System.out.println("Dashboard : http://localhost:" + port);
        System.out.println("ESP32 should POST to one of these:");
        for (String ip : lanAddresses()) System.out.println("            http://" + ip + ":" + port + "/api/telemetry");
        if (demo) {
            System.out.println("DEMO MODE: simulated device is running (no hardware needed).");
            Thread t = new Thread(new Simulator(store), "simulator");
            t.setDaemon(true);
            t.start();
        }
    }

    // ---------- handlers ----------

    private static void handleTelemetry(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, "{\"error\":\"POST only\"}"); return; }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            String mode = Json.str(body, "mode", "moderate");
            store.telemetry(
                Json.str(body, "device", "ESP32"),
                Json.num(body, "pressure", 0),
                Json.num(body, "temp", 0),
                (int) Json.num(body, "valve", 0),
                mode,
                (int) Json.num(body, "threshold", Store.thresholdFor(mode)),
                (int) Json.num(body, "rssi", 0),
                (long) Json.num(body, "uptime", 0),
                Json.num(body, "baseline", 0));
            json(ex, 200, store.commandJson());
        } catch (Exception e) {
            json(ex, 400, "{\"error\":\"bad telemetry\"}");
        }
    }

    private static void handlePatient(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, "{\"error\":\"POST only\"}"); return; }
        String b = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String k : Store.PATIENT_KEYS) m.put(k, Json.str(b, k, ""));
        store.setPatient(m);
        json(ex, 200, "{\"ok\":true}");
    }

    private static void handleCommand(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { json(ex, 405, "{\"error\":\"POST only\"}"); return; }
        String b = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        store.command(Json.str(b, "mode", store.cmdMode()), Json.bool(b, "enabled", store.cmdEnabled()));
        json(ex, 200, store.commandJson());
    }

    private static void handleExport(HttpExchange ex) throws IOException {
        byte[] data = Files.readAllBytes(store.logFile().toPath());
        ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=nebuflow_log.csv");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private static void handleStatic(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        Path file = WEB.resolve(p.substring(1)).normalize();
        if (!file.startsWith(WEB) || !Files.isRegularFile(file)) {
            byte[] nf = "404 Not found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, nf.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(nf); }
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private static java.util.List<String> lanAddresses() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
            }
        } catch (Exception ignored) { }
        return out;
    }
}
