package com.blissfuljuan.die.orchestrator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Liveness (/health/live) and readiness (/health/ready) over plain HTTP; no other route exists. */
public final class HealthServer implements AutoCloseable {

    private final HttpServer server;

    public HealthServer(HealthState state, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> respond(exchange, state));
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "health");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, HealthState state) throws IOException {
        String path = exchange.getRequestURI().getPath();
        boolean ok;
        if (path.equals("/health") || path.equals("/health/live")) {
            ok = state.isLive();
        } else if (path.equals("/health/ready")) {
            ok = state.isReady();
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] body = ("{\"status\":\"" + (ok ? "UP" : "DOWN") + "\",\"worker\":\"orchestrator\",\"secondsSinceHeartbeat\":"
            + state.millisSinceHeartbeat() / 1000 + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(ok ? 200 : 503, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
