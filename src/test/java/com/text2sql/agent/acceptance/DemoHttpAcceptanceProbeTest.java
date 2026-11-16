package com.text2sql.agent.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.execution.QueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoHttpAcceptanceProbeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DemoHttpAcceptanceProbe.Scenario orders = new DemoHttpAcceptanceProbe.Scenario(
            "FOLLOWUP-T1-002", "T1-002", "orders", "那2017年的呢？", "SELECT COUNT(*) FROM orders;", true,
            new QueryResult(List.of("count"), List.of(List.of("45101")), false, 0));

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com:8080", "https://127.0.0.1:8080", "http://127.0.0.1",
            "http://key@127.0.0.1:8080", "http://127.0.0.1:8080/proxy", "http://127.0.0.1:8080?target=other"})
    void acceptsOnlyExplicitLocalHttpEndpoint(String uri) {
        assertThatThrownBy(() -> new DemoHttpAcceptanceProbe(URI.create(uri))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameFollowUpTextCannotMakeAnotherTopicsResultCountAsCorrect() throws Exception {
        var otherTopic = json.readTree("""
                {"status":"SUCCESS","columns":["gmv","sales"],"rows":[["200.00","100.00"]],
                 "sql":"SELECT 200 AS gmv, 100 AS sales"}
                """);
        assertThat(DemoHttpAcceptanceProbe.correct(orders, otherTopic)).isFalse();
        var correct = json.readTree("""
                {"status":"SUCCESS","columns":["order_cnt"],"rows":[["45101.0000"]],"sql":"SELECT COUNT(*) FROM orders"}
                """);
        assertThat(DemoHttpAcceptanceProbe.correct(orders, correct)).isTrue();
    }

    @Test
    void successfulHttpOrPartialResultDoesNotProveBusinessCorrectness() throws Exception {
        var response = json.readTree("""
                {"status":"SUCCESS","truncated":true,"columns":["count"],"rows":[["45101"]]}
                """);
        assertThat(DemoHttpAcceptanceProbe.correct(orders, response)).isFalse();
        assertThat(DemoHttpAcceptanceProbe.correct(orders, json.readTree("{\"status\":\"NEEDS_CLARIFICATION\"}"))).isFalse();
    }
}
