package com.text2sql.agent.budget;

import com.sun.net.httpserver.HttpServer;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.LlmSqlGenerator;
import com.text2sql.agent.generation.PromptTemplate;
import com.text2sql.agent.observability.EvaluationTrace;
import com.text2sql.agent.observability.LlmCallLog;
import com.text2sql.agent.orchestrator.AgentResponse;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetTransportTest {
    @Test void slowDripBodyIsCancelledAndCannotSilentlyStartAnSdkRetry() throws Exception {
        var disconnected = new CountDownLatch(1);
        var bodyStarted = new CountDownLatch(1);
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(handlers);
            server.createContext("/v1/chat/completions", exchange -> {
                requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                bodyStarted.countDown();
                try (var output = exchange.getResponseBody()) {
                    // Continuous progress defeats an ordinary idle/read timeout.
                    byte[] chunk = (" ".repeat(4096)).getBytes(StandardCharsets.UTF_8);
                    for (int i = 0; i < 200; i++) {
                        output.write(chunk); output.flush();
                        try { Thread.sleep(20); } catch (InterruptedException ignored) { break; }
                    }
                } catch (Exception closed) { disconnected.countDown(); }
            });
            server.start();
            var props = new AgentProperties();
            props.getLlm().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            props.getLlm().setApiKey("local-fixture-only");
            props.getObservability().setEnabled(false);
            try (var logs = new LlmCallLog(props); var trace = EvaluationTrace.open()) {
                var generator = new LlmSqlGenerator(props, new PromptTemplate(), logs, com.text2sql.agent.retrieval.CatalogFixture.healthy());
                long started = System.nanoTime();
                AgentResponse result = RequestBudgetRunner.run("count orders", null, RequestBudgetTest.config(1200), () -> {
                    generator.generate("count orders", new SchemaContext(List.of(), List.of(), "", ""));
                    return RequestBudgetTest.answer();
                });
                assertThat(result.status()).isEqualTo(AgentResponse.Status.REQUEST_TIMEOUT);
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2200);
                assertThat(bodyStarted.getCount()).isZero();
                assertThat(disconnected.await(2, TimeUnit.SECONDS)).as("cancel must close the actual response body").isTrue();
                assertThat(requests).hasValue(1);
                assertThat(result.budget().unknownUsageCalls()).isEqualTo(1);
                assertThat(result.timings().generationMs()).isGreaterThanOrEqualTo(800);
                assertThat(result.timings().generationMs()).isEqualTo(result.budget().stageMs().get("MODEL"));
                assertThat(trace.calls()).hasSize(1);
            } finally { server.stop(0); }
        }
    }
}
