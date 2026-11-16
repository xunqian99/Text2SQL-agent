package com.text2sql.agent.generation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptFingerprintTest {
    @Test
    void includesSystemRoleMessageOrderAndBoundaries() {
        String expected = PromptFingerprint.messagesSha256(List.of(new SystemMessage("ab"), new UserMessage("c")));
        assertThat(expected).hasSize(64).isEqualTo(PromptFingerprint.messagesSha256(
                List.of(new SystemMessage("ab"), new UserMessage("c"))));
        assertThat(expected).isNotEqualTo(PromptFingerprint.messagesSha256(
                List.of(new SystemMessage("a"), new UserMessage("bc"))));
        assertThat(expected).isNotEqualTo(PromptFingerprint.messagesSha256(
                List.of(new UserMessage("ab"), new SystemMessage("c"))));
        assertThat(expected).isNotEqualTo(PromptFingerprint.messagesSha256(
                List.of(new UserMessage("c"), new SystemMessage("ab"))));
    }

    @Test
    void detectsEqualLengthDifferencesAndDoesNotNormalizeWhitespace() {
        String expected = PromptFingerprint.messagesSha256(List.of(new UserMessage("甲乙")));
        assertThat(expected).isNotEqualTo(PromptFingerprint.messagesSha256(List.of(new UserMessage("乙甲"))));
        assertThat(PromptFingerprint.messagesSha256(List.of(new UserMessage("SELECT  1"))))
                .isNotEqualTo(PromptFingerprint.messagesSha256(List.of(new UserMessage("SELECT 1"))));
    }

    @Test
    void includesToolArgumentsAndToolResponsesEvenWithoutAssistantText() {
        var first = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("id", "function", "inspect", "{\"table\":\"orders\"}"))).build();
        var second = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("id", "function", "inspect", "{\"table\":\"members\"}"))).build();
        assertThat(PromptFingerprint.messagesSha256(List.of(first)))
                .isNotEqualTo(PromptFingerprint.messagesSha256(List.of(second)));
        var response1 = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("id", "inspect", "orders schema"))).build();
        var response2 = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("id", "inspect", "members schema"))).build();
        assertThat(PromptFingerprint.messagesSha256(List.of(first, response1)))
                .isNotEqualTo(PromptFingerprint.messagesSha256(List.of(first, response2)));
    }
}
