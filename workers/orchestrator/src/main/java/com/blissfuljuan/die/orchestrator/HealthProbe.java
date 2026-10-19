package com.blissfuljuan.die.orchestrator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Docker HEALTHCHECK helper: exits 0 when /health/ready answers 200 (the JRE image has no curl). */
public final class HealthProbe {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("HEALTH_PORT", "8081"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/ready"))
            .timeout(Duration.ofSeconds(3)).GET().build();
        try {
            int status = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            System.exit(status == 200 ? 0 : 1);
        } catch (Exception e) {
            System.exit(1);
        }
    }
}
