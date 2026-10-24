package com.blissfuljuan.dieapi.ops.service.adapter;

import com.blissfuljuan.dieapi.ops.model.WorkerHealth;
import com.blissfuljuan.dieapi.ops.service.port.WorkerHealthProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads each worker's readiness endpoint ({@code /health/ready}: 200 only while the worker is consuming and its heartbeat is
 * fresh; the body always reports {@code secondsSinceHeartbeat}). The URLs are operator configuration
 * ({@code die.ops.workers.*}), never request input, so the probe cannot be pointed at arbitrary hosts. The workers sit on the
 * internal broker network; the API joins that network, nothing else needs to reach their health ports.
 */
@Component
public class HttpWorkerHealthProbe implements WorkerHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpWorkerHealthProbe.class);

    private final Map<String, String> urls = new LinkedHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper;

    public HttpWorkerHealthProbe(@Value("${die.ops.workers.orchestrator-url:}") String orchestratorUrl,
                                 @Value("${die.ops.workers.document-worker-url:}") String documentWorkerUrl,
                                 ObjectMapper mapper) {
        this.mapper = mapper;
        urls.put("orchestrator", orchestratorUrl);
        urls.put("document-worker", documentWorkerUrl);
    }

    @Override
    public List<WorkerHealth> probeAll() {
        List<WorkerHealth> result = new ArrayList<>();
        urls.forEach((name, url) -> result.add(url == null || url.isBlank()
            ? new WorkerHealth(name, WorkerHealth.State.NOT_CONFIGURED, null) : probe(name, normalize(url))));
        return result;
    }

    /**
     * Accepts a full URL or just {@code host:port} (what Render injects for a private service): the scheme defaults to
     * http and the path to {@code /health/ready}.
     */
    static String normalize(String raw) {
        String value = raw.strip();
        if (!value.contains("://")) value = "http://" + value;
        String path = URI.create(value).getPath();
        if (path == null || path.isEmpty() || path.equals("/")) value = value.replaceAll("/+$", "") + "/health/ready";
        return value;
    }

    private WorkerHealth probe(String name, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank() ? null : mapper.readTree(response.body());
            Double age = body != null && body.hasNonNull("secondsSinceHeartbeat")
                ? body.get("secondsSinceHeartbeat").asDouble() : null;
            boolean up = response.statusCode() == 200;
            return new WorkerHealth(name, up ? WorkerHealth.State.UP : WorkerHealth.State.DOWN, age);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new WorkerHealth(name, WorkerHealth.State.UNREACHABLE, null);
        } catch (Exception e) {
            log.warn("worker {} health probe failed: {}", name, e.toString());
            return new WorkerHealth(name, WorkerHealth.State.UNREACHABLE, null);
        }
    }
}
