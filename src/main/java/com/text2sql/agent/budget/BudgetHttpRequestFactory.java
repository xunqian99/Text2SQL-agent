package com.text2sql.agent.budget;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** Interruptible JDK transport, plus response-body close on cancellation (including slow-drip bodies). */
public final class BudgetHttpRequestFactory implements ClientHttpRequestFactory {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final int timeoutSeconds;
    private final DeepSeekOffPeakPolicy offPeak;
    private final boolean forceOffPeak;
    private final ClientHttpRequestFactory testDelegate;

    public BudgetHttpRequestFactory(int timeoutSeconds) { this(timeoutSeconds, false); }

    public BudgetHttpRequestFactory(int timeoutSeconds, boolean forceOffPeak) {
        this(timeoutSeconds, forceOffPeak, java.time.Clock.systemUTC(), null);
    }

    BudgetHttpRequestFactory(int timeoutSeconds, boolean forceOffPeak, java.time.Clock clock,
                             ClientHttpRequestFactory testDelegate) {
        this.timeoutSeconds = timeoutSeconds;
        this.forceOffPeak = forceOffPeak;
        this.offPeak = new DeepSeekOffPeakPolicy(clock);
        this.testDelegate = testDelegate;
    }

    @Override public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        boolean guarded = forceOffPeak || DeepSeekOffPeakPolicy.appliesTo(uri, null);
        if (guarded) offPeak.requireAllowed(DeepSeekOffPeakPolicy.horizon(timeoutSeconds));
        RequestBudget budget = RequestBudget.current();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(CLIENT);
        factory.setReadTimeout(Duration.ofMillis(budget == null ? timeoutSeconds * 1000L
                : Math.min(timeoutSeconds * 1000L, budget.remainingMillis())));
        return new Request((testDelegate == null ? factory : testDelegate).createRequest(uri, method), budget,
                guarded ? offPeak : null, DeepSeekOffPeakPolicy.horizon(timeoutSeconds));
    }

    private static final class Request extends HttpRequestWrapper implements ClientHttpRequest {
        private final ClientHttpRequest delegate;
        private final RequestBudget budget;
        private final DeepSeekOffPeakPolicy offPeak;
        private final Duration horizon;
        private Request(ClientHttpRequest delegate, RequestBudget budget, DeepSeekOffPeakPolicy offPeak, Duration horizon) {
            super(delegate); this.delegate = delegate; this.budget = budget;
            this.offPeak = offPeak; this.horizon = horizon;
        }
        @Override public OutputStream getBody() throws IOException { return delegate.getBody(); }
        @Override public ClientHttpResponse execute() throws IOException {
            if (offPeak != null) offPeak.requireAllowed(horizon);
            if (budget != null) budget.check();
            ClientHttpResponse response = delegate.execute();
            if (budget == null) return response;
            AutoCloseable hook;
            try { hook = budget.onCancel(response::close); }
            catch (RequestBudget.Exceeded expired) { response.close(); throw expired; }
            return new ClientHttpResponse() {
                @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                @Override public String getStatusText() throws IOException { return response.getStatusText(); }
                @Override public HttpHeaders getHeaders() { return response.getHeaders(); }
                @Override public InputStream getBody() throws IOException { return response.getBody(); }
                @Override public void close() {
                    try { response.close(); } finally {
                        try { hook.close(); } catch (Exception ignored) { }
                    }
                }
            };
        }
    }
}
