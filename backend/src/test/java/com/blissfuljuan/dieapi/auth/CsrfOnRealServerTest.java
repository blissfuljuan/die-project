package com.blissfuljuan.dieapi.auth;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against a real Tomcat (MockMvc behaves differently for CSRF failures): a missing or wrong CSRF token
 * must be answered 403 with the token cookie set, never 401 — 401 means "sign in" to API clients and made a
 * Swagger UI call look like a bad password.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CsrfOnRealServerTest extends PostgresIntegrationTest {

    @LocalServerPort int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> post(String accept, String xsrfHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
            .header("Content-Type", "application/json");
        if (accept != null) b.header("Accept", accept);
        if (xsrfHeader != null) b.header("X-XSRF-TOKEN", xsrfHeader).header("Cookie", "XSRF-TOKEN=something-else");
        return client.send(b.POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@example.invalid\",\"password\":\"x\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aMissingCsrfTokenIsForbiddenNotUnauthorized() throws Exception {
        for (String accept : new String[] {null, "*/*", "application/json, text/plain, */*"}) {
            HttpResponse<String> r = post(accept, null);
            assertThat(r.statusCode()).as("Accept: " + accept).isEqualTo(403);
        }
    }

    @Test
    void aWrongCsrfTokenIsForbiddenNotUnauthorized() throws Exception {
        assertThat(post("*/*", "wrong").statusCode()).isEqualTo(403);
    }

    @Test
    void theTokenEndpointHandsOutTheCookieAndAValidTokenReachesTheLoginController() throws Exception {
        HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        List<String> cookies = csrf.headers().allValues("Set-Cookie");
        String xsrfCookie = cookies.stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        String token = xsrfCookie.substring("XSRF-TOKEN=".length(), xsrfCookie.indexOf(';'));

        HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
            .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token).header("Cookie", "XSRF-TOKEN=" + token)
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@example.invalid\",\"password\":\"x\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(401);
        assertThat(login.body()).contains("Invalid email or password");   // the controller answered, not the filter chain
    }
}
