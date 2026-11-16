package com.text2sql.agent.observability;

import com.text2sql.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationTraceTest {
    @Test
    void capturesHashesAndUsageEvenWithFileLoggingDisabled() {
        var props = new AgentProperties();
        props.getObservability().setEnabled(false);
        try (var trace = EvaluationTrace.open(); var log = new LlmCallLog(props)) {
            var usage = LlmCallRecord.of("test", 10, 20, 40, 1, .001, .004);
            log.record(usage, "OK", "private-question", "private-response", "hash");
            assertThat(trace.calls()).containsExactly(new EvaluationTrace.Call(1, "OK", "hash", usage));
            assertThat(log.written()).isZero();
            EvaluationTrace.call(null, "CALL_FAILED_CORRECTION", "retry-hash");
            assertThat(trace.calls().get(1).usage()).isNull();
        }
    }

    @Test
    void refusesNestedScopesAndRemovesContextAfterExceptionalExit() {
        assertThatThrownBy(() -> {
            try (var trace = EvaluationTrace.open()) {
                assertThatThrownBy(EvaluationTrace::open).isInstanceOf(IllegalStateException.class);
                throw new IllegalArgumentException("failure");
            }
        }).isInstanceOf(IllegalArgumentException.class);
        EvaluationTrace.call(null, "OUTSIDE", "outside");
        try (var next = EvaluationTrace.open()) {
            assertThat(next.calls()).isEmpty();
            assertThat(next.attempts()).isEmpty();
            assertThat(next.trigger()).isEqualTo("NONE");
        }
    }

    @Test
    void doesNotInheritOrMixConcurrentRequestEvidence() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor(); var parent = EvaluationTrace.open()) {
            EvaluationTrace.call(null, "PARENT", "parent");
            var child = pool.submit(() -> {
                EvaluationTrace.call(null, "UNSCOPED", "not-inherited");
                try (var trace = EvaluationTrace.open()) {
                    EvaluationTrace.call(null, "CHILD", "child");
                    return trace.calls();
                }
            }).get();
            assertThat(child).extracting(EvaluationTrace.Call::promptSha256).containsExactly("child");
            assertThat(parent.calls()).extracting(EvaluationTrace.Call::promptSha256).containsExactly("parent");
        }
    }
}
