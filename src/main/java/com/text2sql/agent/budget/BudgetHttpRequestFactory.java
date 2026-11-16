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

    public BudgetHttpRequestFactory(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    @Override public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        RequestBudget budget = RequestBudget.current();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(CLIENT);
        factory.setReadTimeout(Duration.ofMillis(budget == null ? timeoutSeconds * 1000L
                : Math.min(timeoutSeconds * 1000L, budget.remainingMillis())));
        return new Request(factory.createRequest(uri, method), budget);
    }

    private static final class Request extends HttpRequestWrapper implements ClientHttpRequest {
        private final ClientHttpRequest delegate;
        private final RequestBudget budget;
        private Request(ClientHttpRequest delegate, RequestBudget budget) {
            super(delegate); this.delegate = delegate; this.budget = budget;
        }
        @Override public OutputStream getBody() throws IOException { return delegate.getBody(); }
        @Override public ClientHttpResponse execute() throws IOException {
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
