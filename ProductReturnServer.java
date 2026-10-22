import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProductReturnServer {
    private static final int PORT = 8080;
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path FRONTEND = ROOT.resolve("frontend").normalize();
    private static final Path DATA_DIR = ROOT.resolve("data").normalize();
    private static final Path DATA_FILE = DATA_DIR.resolve("returns.json");
    private static final Pattern FIELD = Pattern.compile("\\\"([a-zA-Z]+)\\\"\\s*:\\s*\\\"((?:\\\\\\\\.|[^\\\"\\\\\\\\])*)\\\"");
    private static final List<ReturnRequest> REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<String> REASONS = List.of("Damaged item", "Wrong item received", "Size or fit issue", "Item not as described", "Changed my mind", "Other");
    private static final List<String> STATUSES = List.of("Requested", "Approved", "Rejected", "Received", "Refunded");

    public static void main(String[] args) throws IOException {
        if (!Files.isDirectory(FRONTEND)) {
            System.err.println("Frontend folder not found. Run this program from the project root.");
            System.exit(1);
        }
        loadData();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/api/returns", ProductReturnServer::handleApi);
        server.createContext("/", ProductReturnServer::handleStatic);
        server.start();
        System.out.println("Product Return Management Platform running at http://localhost:" + PORT);
        System.out.println("Press Ctrl+C to stop.");
    }

    private static synchronized void handleApi(HttpExchange ex) throws IOException {
        commonHeaders(ex.getResponseHeaders());
        try {
            switch (ex.getRequestMethod().toUpperCase()) {
                case "GET" -> {
                    StringBuilder out = new StringBuilder("[");
                    for (int i = 0; i < REQUESTS.size(); i++) {
                        if (i > 0) out.append(',');
                        out.append(REQUESTS.get(i).toJson());
                    }
                    out.append(']');
                    send(ex, 200, out.toString(), "application/json; charset=utf-8");
                }
                case "POST" -> create(ex);
                case "PATCH" -> update(ex);
                case "DELETE" -> delete(ex);
                default -> {
                    ex.getResponseHeaders().set("Allow", "GET, POST, PATCH, DELETE");
                    send(ex, 405, "{\"error\":\"Method not allowed.\"}", "application/json; charset=utf-8");
                }
            }
        } catch (IllegalArgumentException e) {
            send(ex, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json; charset=utf-8");
        } catch (Exception e) {
            send(ex, 500, "{\"error\":\"Server could not complete the request.\"}", "application/json; charset=utf-8");
        }
    }

    private static void create(HttpExchange ex) throws IOException {
        Map<String, String> f = fields(readBody(ex));
        String order = required(f, "orderNumber", 40);
        String product = required(f, "product", 120);
        String reason = required(f, "reason", 60);
        String customer = required(f, "customer", 80);
        String details = optional(f, "details", 800);
        if (!REASONS.contains(reason)) throw new IllegalArgumentException("Choose a valid return reason.");
        ReturnRequest r = new ReturnRequest("RET-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                order, product, reason, customer, details, "Requested", Instant.now().toString());
        REQUESTS.add(0, r);
        saveData();
        send(ex, 201, r.toJson(), "application/json; charset=utf-8");
    }

    private static void update(HttpExchange ex) throws IOException {
        String id = queryParam(ex.getRequestURI().getRawQuery(), "id");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Return request id is required.");
        String status = required(fields(readBody(ex)), "status", 30);
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("Choose a valid status.");
        for (ReturnRequest r : REQUESTS) {
            if (r.id.equals(id)) {
                r.status = status;
                saveData();
                send(ex, 200, r.toJson(), "application/json; charset=utf-8");
                return;
            }
        }
        send(ex, 404, "{\"error\":\"Return request not found.\"}", "application/json; charset=utf-8");
    }

    private static void delete(HttpExchange ex) throws IOException {
        String id = queryParam(ex.getRequestURI().getRawQuery(), "id");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Return request id is required.");
        if (!REQUESTS.removeIf(r -> r.id.equals(id))) {
            send(ex, 404, "{\"error\":\"Return request not found.\"}", "application/json; charset=utf-8");
            return;
        }
        saveData();
        send(ex, 200, "{\"success\":true}", "application/json; charset=utf-8");
    }

    private static String readBody(HttpExchange ex) throws IOException {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase().startsWith("application/json"))
            throw new IllegalArgumentException("Content-Type must be application/json.");
        byte[] bytes = ex.getRequestBody().readNBytes(16385);
        if (bytes.length > 16384) throw new IllegalArgumentException("Request body is too large.");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<String, String> fields(String json) {
        Map<String, String> result = new java.util.HashMap<>();
        Matcher m = FIELD.matcher(json);
        while (m.find()) result.put(m.group(1), unescape(m.group(2)));
        return result;
    }

    private static String required(Map<String, String> f, String key, int max) {
        String v = f.get(key);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException(key + " is required.");
        v = v.trim();
        if (v.length() > max) throw new IllegalArgumentException(key + " must be " + max + " characters or fewer.");
        return v;
    }

    private static String optional(Map<String, String> f, String key, int max) {
        String v = f.getOrDefault(key, "").trim();
        if (v.length() > max) throw new IllegalArgumentException(key + " must be " + max + " characters or fewer.");
        return v;
    }

    private static String queryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] p = pair.split("=", 2);
            if (p.length == 2 && URLDecoder.decode(p[0], StandardCharsets.UTF_8).equals(key))
                return URLDecoder.decode(p[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    private static synchronized void loadData() throws IOException {
        Files.createDirectories(DATA_DIR);
        if (!Files.exists(DATA_FILE)) {
            Files.writeString(DATA_FILE, "[]", StandardCharsets.UTF_8);
            return;
        }
        String content = Files.readString(DATA_FILE, StandardCharsets.UTF_8).trim();
        if (content.isEmpty() || content.equals("[]")) return;
        Matcher objects = Pattern.compile("\\{[^{}]*}").matcher(content);
        while (objects.find()) {
            Map<String, String> f = fields(objects.group());
            if (f.keySet().containsAll(List.of("id", "orderNumber", "product", "reason", "customer", "details", "status", "createdAt"))) {
                REQUESTS.add(new ReturnRequest(f.get("id"), f.get("orderNumber"), f.get("product"), f.get("reason"),
                        f.get("customer"), f.get("details"), f.get("status"), f.get("createdAt")));
            }
        }
    }

    private static synchronized void saveData() throws IOException {
        Files.createDirectories(DATA_DIR);
        StringBuilder out = new StringBuilder("[\n");
        for (int i = 0; i < REQUESTS.size(); i++) {
            if (i > 0) out.append(",\n");
            out.append("  ").append(REQUESTS.get(i).toJson());
        }
        out.append("\n]\n");
        Files.writeString(DATA_FILE, out.toString(), StandardCharsets.UTF_8);
    }

    private static void handleStatic(HttpExchange ex) throws IOException {
        commonHeaders(ex.getResponseHeaders());
        String method = ex.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            send(ex, 405, "Method not allowed", "text/plain; charset=utf-8");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = FRONTEND.resolve(path.substring(1)).normalize();
        if (!file.startsWith(FRONTEND) || !Files.isRegularFile(file)) {
            send(ex, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        String name = file.getFileName().toString().toLowerCase();
        String mime = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", mime);
        if ("HEAD".equalsIgnoreCase(method)) {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        } else {
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }
    }

    private static void commonHeaders(Headers h) {
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
        h.set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'");
    }

    private static void send(HttpExchange ex, int status, String body, String mime) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.toString();
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) { out.append(c); continue; }
            char n = value.charAt(++i);
            switch (n) {
                case '"', '\\', '/' -> out.append(n);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        try { out.append((char)Integer.parseInt(value.substring(i + 1, i + 5), 16)); i += 4; }
                        catch (NumberFormatException e) { out.append('u'); }
                    } else out.append('u');
                }
                default -> out.append(n);
            }
        }
        return out.toString();
    }

    private static final class ReturnRequest {
        final String id, orderNumber, product, reason, customer, details, createdAt;
        volatile String status;
        ReturnRequest(String id, String orderNumber, String product, String reason, String customer,
                      String details, String status, String createdAt) {
            this.id=id; this.orderNumber=orderNumber; this.product=product; this.reason=reason;
            this.customer=customer; this.details=details; this.status=status; this.createdAt=createdAt;
        }
        String toJson() {
            return "{\"id\":\"" + escape(id) + "\",\"orderNumber\":\"" + escape(orderNumber)
                    + "\",\"product\":\"" + escape(product) + "\",\"reason\":\"" + escape(reason)
                    + "\",\"customer\":\"" + escape(customer) + "\",\"details\":\"" + escape(details)
                    + "\",\"status\":\"" + escape(status) + "\",\"createdAt\":\"" + escape(createdAt) + "\"}";
        }
    }
}
