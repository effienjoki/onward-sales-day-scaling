import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Onward Logistics order system.
 * One program that serves the web page and processes orders.
 * Uses only the Java standard library, so it runs with: java OrderApp.java
 */
public class OrderApp {

    private static final String OWNER = "Effie Njoki";
    private static final String LAB = "Handling Sales Day Demand for the Onward Logistics Order System";
    private static final int PORT = 8080;
    private static final int MAX_ORDERS = 100;
    private static final int MAX_FIELD_LENGTH = 100;

    // Orders are kept in memory on this server only (see Limits in the discovery report).
    private static final List<String> ORDERS = new CopyOnWriteArrayList<>();

    private static String instanceId;
    private static String availabilityZone;

    public static void main(String[] args) throws IOException {
        instanceId = readMetadata("instance-id");
        availabilityZone = readMetadata("placement/availability-zone");

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/health", OrderApp::handleHealth);
        server.createContext("/orders", OrderApp::handleOrders);
        server.createContext("/", OrderApp::handleHome);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.println("Order app started on port " + PORT + " on server " + instanceId);
    }

    // ---------- Pages ----------

    private static void handleHealth(HttpExchange exchange) throws IOException {
        send(exchange, 200, "text/plain", "OK");
    }

    private static void handleHome(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            send(exchange, 404, "text/plain", "Not found");
            return;
        }

        StringBuilder rows = new StringBuilder();
        for (String order : ORDERS) {
            rows.append("<li>").append(escape(order)).append("</li>");
        }
        if (ORDERS.isEmpty()) {
            rows.append("<li>No orders on this server yet.</li>");
        }

        String form = """
            <h2>Place an order</h2>
            <form method="post" action="/orders">
              <label>Customer name <input name="customer" maxlength="100" required></label>
              <label>Parcel description <input name="parcel" maxlength="100" required></label>
              <button type="submit">Place order</button>
            </form>
            <h2>Orders held on this server</h2>
            <ul>%s</ul>
            """.formatted(rows);

        send(exchange, 200, "text/html; charset=utf-8", page(form));
    }

    private static void handleOrders(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> fields = parseForm(body);
        String customer = limit(fields.getOrDefault("customer", "").trim());
        String parcel = limit(fields.getOrDefault("parcel", "").trim());

        String message;
        if (customer.isEmpty() || parcel.isEmpty()) {
            message = "Please enter a customer name and a parcel description.";
        } else {
            if (ORDERS.size() >= MAX_ORDERS) {
                ORDERS.remove(0);
            }
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            ORDERS.add(time + " | " + customer + " | " + parcel);
            message = "Order received for " + customer + ".";
        }

        String content = """
            <h2>%s</h2>
            <p><a href="/">Back to the order page</a></p>
            """.formatted(escape(message));

        send(exchange, 200, "text/html; charset=utf-8", page(content));
    }

    // ---------- Shared page layout ----------

    private static String page(String content) {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Onward Order System</title>
              <style>
                body { font-family: Arial, sans-serif; max-width: 720px; margin: 2rem auto; padding: 0 1rem; color: #1f2933; }
                h1 { color: #0b5394; }
                .server { background: #e6f4ea; border-left: 6px solid #2e7d32; padding: 1rem; margin: 1rem 0; }
                label { display: block; margin: 0.5rem 0; }
                input { display: block; padding: 0.4rem; width: 20rem; max-width: 90vw; }
                button { margin-top: 0.5rem; padding: 0.5rem 1rem; }
              </style>
            </head>
            <body>
              <h1>Onward Logistics Order System</h1>
              <p><strong>Name:</strong> %s<br><strong>Lab:</strong> %s</p>
              <div class="server">
                <strong>Answered by server:</strong> %s<br>
                <strong>Availability Zone:</strong> %s
              </div>
              %s
            </body>
            </html>
            """.formatted(escape(OWNER), escape(LAB), escape(instanceId), escape(availabilityZone), content);
    }

    // ---------- Helpers ----------

    /** Reads this server's details from the EC2 instance metadata service, using IMDSv2 (token first). */
    private static String readMetadata(String path) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create("http://169.254.169.254/latest/api/token"))
                    .header("X-aws-ec2-metadata-token-ttl-seconds", "300")
                    .timeout(Duration.ofSeconds(2))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            String token = client.send(tokenRequest, HttpResponse.BodyHandlers.ofString()).body();

            HttpRequest request = HttpRequest.newBuilder(URI.create("http://169.254.169.254/latest/meta-data/" + path))
                    .header("X-aws-ec2-metadata-token", token)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : "unknown";
        } catch (Exception e) {
            return "local-machine";
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                fields.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return fields;
    }

    private static String limit(String value) {
        return value.length() > MAX_FIELD_LENGTH ? value.substring(0, MAX_FIELD_LENGTH) : value;
    }

    /** Stops anything a visitor types from being run as HTML in other people's browsers. */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}