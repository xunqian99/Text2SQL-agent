package com.text2sql.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.generation.PromptFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCallLogTest {
    @TempDir Path dir;

    @Test
    void retainsFullMessageHashWhenContentLoggingIsDisabled() throws Exception {
        var props = new AgentProperties();
        props.getObservability().setDir(dir.toString());
        props.getObservability().setIncludeContent(false);
        var messages = List.of(new SystemMessage("private-system"), new UserMessage("private-question"));
        String hash = PromptFingerprint.messagesSha256(messages);
        try (var log = new LlmCallLog(props)) {
            log.record(LlmCallRecord.of("test", 10, 20, 40, 1, .001, .004), "UNPARSEABLE",
                    "private-question", "private-response", hash);
            assertThat(log.written()).isEqualTo(1);
        }
        String content;
        try (var files = Files.list(dir)) {
            content = Files.readString(files.findFirst().orElseThrow());
        }
        var entry = new ObjectMapper().readTree(content);
        assertThat(entry.get("promptSha256").asText()).isEqualTo(hash);
        assertThat(entry.get("promptHashVersion").asText()).isEqualTo(PromptFingerprint.VERSION);
        assertThat(entry.get("prompt").isNull()).isTrue();
        assertThat(entry.get("response").isNull()).isTrue();
        assertThat(content).doesNotContain("private-system", "private-question", "private-response");
    }
}
