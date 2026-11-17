package com.text2sql.agent.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleUserAccessFilterTest {
    static final String TOKEN = "fixture-access-token-at-least-32-characters";
    static final String AUTH = "Basic " + Base64.getEncoder().encodeToString(("demo:" + TOKEN).getBytes(StandardCharsets.UTF_8));

    static AccessProperties config() {
        var config = new AccessProperties();
        config.setEnabled(true);
        config.setToken(TOKEN);
        return config;
    }

    @ParameterizedTest
    @CsvSource({"GET,/", "GET,/index.html", "GET,/api/health", "POST,/api/ask", "GET,/api/sessions",
            "POST,/api/session/one/close", "DELETE,/api/session/one", "GET,/api/system/logs",
            "GET,/api/metrics", "PUT,/api/products/one", "PUT,/api/orders/one/status",
            "GET,/api/access/status", "GET,/unknown", "POST,/api;variant/ask"})
    void everySurfaceRequiresApplicationCredentialsBeforeItCanReachAHandler(String method, String path) throws Exception {
        var config = config();
        var filter = new SingleUserAccessFilter(config, new ApiAdmissionGate(config));
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("X-Api-Key", TOKEN); // Model credential is never an application login.
        var response = new MockHttpServletResponse();
        var handlers = new AtomicInteger();
        filter.doFilter(request, response, (req, res) -> handlers.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Basic ");
        assertThat(response.getContentAsString()).doesNotContain(TOKEN);
        assertThat(handlers).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer ignored", "Basic %%%%", "Basic ZGVtbzp3cm9uZw==", "Basic ", "Basic ZGVtbw=="})
    void malformedAndWrongCredentialsAreRejectedWithoutEcho(String authorization) throws Exception {
        var config = config();
        var request = new MockHttpServletRequest("POST", "/api/ask");
        request.addHeader("Authorization", authorization);
        var response = new MockHttpServletResponse();
        new SingleUserAccessFilter(config, new ApiAdmissionGate(config)).doFilter(request, response,
                (req, res) -> { throw new AssertionError("Unauthorized handler invocation"); });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain(authorization);
    }

    @Test void duplicateAuthorizationAndOversizedHeadersAreRejected() throws Exception {
        var config = config();
        var filter = new SingleUserAccessFilter(config, new ApiAdmissionGate(config));
        for (boolean duplicate : new boolean[]{true, false}) {
            var request = new MockHttpServletRequest("GET", "/api/sessions");
            request.addHeader("Authorization", duplicate ? AUTH : "Basic " + "A".repeat(1025));
            if (duplicate) request.addHeader("Authorization", AUTH);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { throw new AssertionError(); });
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Test void unauthenticatedRequestsDoNotDrainAuthenticatedBucketAndHealthRemainsAccessibleWhenLimited() throws Exception {
        var config = config(); config.setBurst(1); config.setRequestsPerMinute(1);
        var gate = new ApiAdmissionGate(config, () -> 0);
        var filter = new SingleUserAccessFilter(config, gate);
        for (int i = 0; i < 20; i++) filter.doFilter(new MockHttpServletRequest("GET", "/api/sessions"),
                new MockHttpServletResponse(), (req, res) -> { throw new AssertionError(); });
        assertThat(invoke(filter, "GET", "/api/sessions", null).getStatus()).isEqualTo(200);
        var limited = invoke(filter, "DELETE", "/api/session/one", null);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(invoke(filter, "GET", "/api/health", null).getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "GET", "/api/health", "http://localhost").getContentAsString()).doesNotContain(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.test", "null", "http://localhost:81", "https://localhost", "http://localhost/path", "http://localhost?x=1"})
    void basicBrowserCredentialsCannotAuthorizeACrossOriginMutation(String origin) throws Exception {
        var config = config();
        var filter = new SingleUserAccessFilter(config, new ApiAdmissionGate(config));
        var response = invoke(filter, "POST", "/api/session/one/close", origin);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test void sameOriginBrowserMutationAndAuthenticatedCliAreAcceptedAndStaticHealthDoNotConsumeApiTokens() throws Exception {
        var config = config(); config.setBurst(1);
        var gate = new ApiAdmissionGate(config, () -> 0);
        var filter = new SingleUserAccessFilter(config, gate);
        assertThat(invoke(filter, "GET", "/index.html", null).getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "GET", "/api/health", null).getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "POST", "/api/ask", "http://localhost").getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "GET", "/api/health", null).getStatus()).isEqualTo(200);
        assertThat(invoke(filter, "GET", "/api/sessions", null).getStatus()).isEqualTo(429);
    }

    @Test void crossSiteFetchAndDuplicateOriginsAreRejected() throws Exception {
        var config = config();
        var filter = new SingleUserAccessFilter(config, new ApiAdmissionGate(config));
        for (boolean duplicate : new boolean[]{true, false}) {
            var request = new MockHttpServletRequest("POST", "/api/ask");
            request.addHeader("Authorization", AUTH);
            if (duplicate) request.addHeader("Origin", new String[]{"http://localhost", "http://localhost"});
            else request.addHeader("Sec-Fetch-Site", "cross-site");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { throw new AssertionError(); });
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @Test void downstreamFailureCannotLeakConcurrencyPermit() throws Exception {
        var config = config(); config.setMaxConcurrent(1);
        var gate = new ApiAdmissionGate(config, () -> 0);
        var filter = new SingleUserAccessFilter(config, gate);
        var request = new MockHttpServletRequest("POST", "/api/ask");
        request.addHeader("Authorization", AUTH);
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> { throw new IllegalStateException("controlled failure"); })).isInstanceOf(IllegalStateException.class);
        assertThat(gate.activeRequests()).isZero();
        assertThat(invoke(filter, "GET", "/api/sessions", null).getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short-secret", "contains spaces even though it is more than thirty two characters"})
    void invalidSecretFailsApplicationContextAndDoesNotPrintItsValue(String secret) {
        new ApplicationContextRunner().withUserConfiguration(AccessConfiguration.class)
                .withPropertyValues("agent.access.enabled=true", "agent.access.token=" + secret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("agent.access.token must contain")
                            .hasStackTraceContaining("32..256");
                    if (!secret.isEmpty()) assertThat(context.getStartupFailure().toString()).doesNotContain(secret);
                });
    }

    @Test void protectedProfileLoadsAuthenticationAndDemoWithoutEmbeddingCredentials() {
        new ApplicationContextRunner()
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withUserConfiguration(AccessConfiguration.class)
                .withPropertyValues("spring.profiles.active=demo,protected", "agent.access.token=" + TOKEN)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var access = context.getBean(AccessProperties.class);
                    assertThat(access.isEnabled()).isTrue();
                    assertThat(access.getBurst()).isEqualTo(10);
                    assertThat(access.getRequestsPerMinute()).isEqualTo(30);
                    assertThat(access.getMaxConcurrent()).isEqualTo(2);
                    assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
                    assertThat(context.getEnvironment().getProperty("agent.request-budget.timeout-ms")).isEqualTo("15000");
                    var status = new AccessStatusController(access, context.getBean(ApiAdmissionGate.class)).status();
                    assertThat(status.toString()).doesNotContain(TOKEN);
                    assertThat(status.get("costQuotaEnforced")).isEqualTo(false);
                });
    }

    @Test void disabledModeKeepsExistingLocalBehavior() throws Exception {
        var config = new AccessProperties();
        var filter = new SingleUserAccessFilter(config, new ApiAdmissionGate(config));
        var called = new AtomicInteger();
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/ask"), response, (req, res) -> called.incrementAndGet());
        assertThat(called).hasValue(1);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
    }

    private static MockHttpServletResponse invoke(SingleUserAccessFilter filter, String method, String path, String origin) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", AUTH);
        if (origin != null) request.addHeader("Origin", origin);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        return response;
    }
}
