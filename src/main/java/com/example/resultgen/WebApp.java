package com.example.resultgen;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Small embedded HTTP server exposing a front page to submit CSV and save results to JSON files.
 */
public class WebApp {
    private static final ExecutorService processExecutor = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors())
    );
    private static final ExecutorService webExecutor = Executors.newCachedThreadPool();
    private static int activeThreads = 0;

    public static void main(String[] args) throws Exception {
        // Read port from environment variable (Render sets PORT) or use default 8080
        String portEnv = System.getenv("PORT");
        int port = 8080;
        if (portEnv != null && !portEnv.isEmpty()) {
            try {
                port = Integer.parseInt(portEnv);
            } catch (NumberFormatException e) {
                System.err.println("Invalid PORT environment variable: " + portEnv + ", using default 8080");
            }
        }
        // Bind to all interfaces (0.0.0.0) for Render compatibility
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
    server.createContext("/", new StaticHandler());
    server.createContext("/submit", new SubmitHandler());
    server.createContext("/api/preview", new ApiPreviewHandler());
    server.createContext("/api/save", new ApiSaveHandler());
    server.createContext("/api/export", new ApiExportHandler());
    server.createContext("/system-info", new SystemInfoHandler());
    server.createContext("/thread-count", new ThreadCountHandler());
        server.setExecutor(webExecutor);
        server.start();
        System.out.println("Multithreading Student Result Generator started at http://0.0.0.0:" + port + "/");
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) path = "/index.html";
            InputStream in = WebApp.class.getResourceAsStream("/static" + path);
            if (in == null) {
                byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(notFound); }
                return;
            }
            Headers h = exchange.getResponseHeaders();
            if (path.endsWith(".html")) h.set("Content-Type", "text/html; charset=utf-8");
            else if (path.endsWith(".css")) h.set("Content-Type", "text/css; charset=utf-8");
            else if (path.endsWith(".js")) h.set("Content-Type", "application/javascript; charset=utf-8");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
            byte[] body = baos.toByteArray();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        }
    }

    static class SystemInfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"processors\":" + Runtime.getRuntime().availableProcessors() + "}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    static class ThreadCountHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"threads\":" + Thread.activeCount() + "}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    static class SubmitHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));

            Map<String, String> params = parseForm(body);
            String csv = params.getOrDefault("csvData", "");

            StringBuilder out = new StringBuilder();
            out.append("<html><head><meta charset=\"utf-8\"><title>Results</title></head><body>");
            if (csv.trim().isEmpty()) {
                out.append("<h3>No CSV provided</h3>");
                out.append("<p><a href=\"/\">Back</a></p>");
                out.append("</body></html>");
                sendHtml(exchange, out.toString());
                return;
            }

            try {
                DataSet ds = DataLoader.fromCsvString(csv);
                int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
                List<StudentResult> results = computeParallel(ds, threads, 40);
                rankResults(results);
                out.append(WebApp.buildResultsHtml(results, ds.subjects));

            } catch (Exception e) {
                out.append("<h3>Error processing CSV:</h3>");
                out.append("<pre>" + escapeHtml(e.getMessage()) + "</pre>");
            }

            out.append("<p><a href=\"/\">Back</a></p>");
            out.append("</body></html>");
            sendHtml(exchange, out.toString());
        }

        private Map<String, String> parseForm(String body) {
            Map<String, String> map = new HashMap<>();
            String[] parts = body.split("&");
            for (String p : parts) {
                int idx = p.indexOf('=');
                if (idx >= 0) {
                    String k = urlDecode(p.substring(0, idx));
                    String v = urlDecode(p.substring(idx + 1));
                    map.put(k, v);
                }
            }
            return map;
        }

        private String urlDecode(String s) {
            try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
        }

        private void sendHtml(HttpExchange exchange, String html) throws IOException {
            byte[] b = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(b); }
        }

        // Reuses WebApp.buildResultsHtml and WebApp.escapeHtml
    }

    // Build HTML table for results (used by both submit and API preview)
    private static String buildResultsHtml(List<StudentResult> results, List<String> subjects) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Results</h2>");
        sb.append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\">");
        sb.append("<tr><th>ID</th><th>Name</th>");
        for (String s : subjects) sb.append("<th>").append(escapeHtml(s)).append("</th>");
        sb.append("<th>Total</th><th>Average</th><th>Grade</th><th>Status</th><th>Rank</th></tr>");
        for (StudentResult r : results) {
            sb.append("<tr>");
            sb.append("<td>").append(escapeHtml(r.student.id)).append("</td>");
            sb.append("<td>").append(escapeHtml(r.student.name)).append("</td>");
            for (String subj : subjects) sb.append("<td>").append(r.student.marks.getOrDefault(subj, 0)).append("</td>");
            sb.append("<td>").append(r.total).append("</td>");
            sb.append("<td>").append(String.format(Locale.US, "%.2f", r.average)).append("</td>");
            sb.append("<td>").append(escapeHtml(r.grade)).append("</td>");
            sb.append("<td>").append(r.pass ? "PASS" : "FAIL").append("</td>");
            sb.append("<td>").append(r.rank).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // API handlers
    static class ApiPreviewHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
            Map<String,String> params = parseFormStatic(body);
            String csv = params.getOrDefault("csvData", "");
            if (csv.trim().isEmpty()) { String msg = "<p>No CSV provided</p>"; sendHtml(exchange, msg); return; }
            try {
                DataSet ds = DataLoader.fromCsvString(csv);
                List<StudentResult> results = computeParallel(ds, Math.max(2, Runtime.getRuntime().availableProcessors()), 40);
                rankResults(results);
                String html = buildResultsHtml(results, ds.subjects);
                sendHtml(exchange, html);
            } catch (Exception e) {
                sendHtml(exchange, "<pre>"+escapeHtml(e.getMessage())+"</pre>");
            }
        }
    }

    static class ApiExportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
            Map<String,String> params = parseFormStatic(body);
            String csv = params.getOrDefault("csvData", "");
            if (csv.trim().isEmpty()) { sendJson(exchange, "{\"ok\":false,\"error\":\"No CSV provided\"}"); return; }
            try {
                DataSet ds = DataLoader.fromCsvString(csv);
                List<StudentResult> results = computeParallel(ds, Math.max(2, Runtime.getRuntime().availableProcessors()), 40);
                rankResults(results);

                String savedPath = DB.saveExcel(results, ds.subjects, "result.xlsx");
                sendJson(exchange, "{\"ok\":true,\"path\":\""+escapeJson(savedPath)+"\"}");
            } catch (Exception e) {
                sendJson(exchange, "{\"ok\":false,\"error\":\""+escapeJson(e.getMessage())+"\"}");
            }
        }
    }

    static class ApiSaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
            Map<String,String> params = parseFormStatic(body);
            String csv = params.getOrDefault("csvData", "");
            if (csv.trim().isEmpty()) { sendJson(exchange, "{\"ok\":false,\"error\":\"No CSV provided\"}"); return; }
            try {
                DataSet ds = DataLoader.fromCsvString(csv);
                List<StudentResult> results = computeParallel(ds, Math.max(2, Runtime.getRuntime().availableProcessors()), 40);
                rankResults(results);
                
                // Save results to JSON file
                String savedPath = DB.saveResults(results, ds.subjects);
                sendJson(exchange, "{\"ok\":true,\"message\":\"Results saved successfully\",\"path\":\""+escapeJson(savedPath)+"\"}");
            } catch (Exception e) {
                sendJson(exchange, "{\"ok\":false,\"error\":\""+escapeJson(e.getMessage())+"\"}");
            }
        }
    }

    private static Map<String,String> parseFormStatic(String body) {
        Map<String,String> map = new HashMap<>();
        String[] parts = body.split("&");
        for (String p : parts) {
            int idx = p.indexOf('=');
            if (idx>=0) {
                try { map.put(java.net.URLDecoder.decode(p.substring(0,idx), "UTF-8"), java.net.URLDecoder.decode(p.substring(idx+1), "UTF-8")); } catch (Exception ex) { /* ignore */ }
            }
        }
        return map;
    }

    private static void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, b.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(b); }
    }

    private static void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, b.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(b); }
    }

    private static String escapeJson(String s) { if (s==null) return ""; return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"); }

    // Helper compute methods (adapted from original logic)
    private static List<StudentResult> computeParallel(DataSet ds, int threads, int passMark) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Callable<StudentResult>> tasks = new ArrayList<>();
            for (Student s : ds.students) tasks.add(new ResultCalculator(s, ds.subjects, passMark));
            List<java.util.concurrent.Future<StudentResult>> futures = pool.invokeAll(tasks);
            List<StudentResult> res = new ArrayList<>(futures.size());
            for (java.util.concurrent.Future<StudentResult> f : futures) {
                try { res.add(f.get()); } catch (Exception e) { throw new RuntimeException(e); }
            }
            return res;
        } finally {
            pool.shutdown();
        }
    }

    private static void rankResults(List<StudentResult> results) {
        results.sort(Comparator.comparingInt(StudentResult::getTotal).reversed());
        int rank = 0; int prevTotal = Integer.MIN_VALUE; int pos = 0;
        for (StudentResult r : results) {
            pos++;
            if (r.total != prevTotal) { rank = pos; prevTotal = r.total; }
            r.rank = rank;
        }
    }
}
