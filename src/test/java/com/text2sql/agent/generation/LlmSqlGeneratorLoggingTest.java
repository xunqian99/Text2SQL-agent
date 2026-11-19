package com.text2sql.agent.generation;

import com.text2sql.agent.config.AgentProperties;
import com.text2sql.agent.observability.LlmCallLog;
import com.text2sql.agent.observability.LlmCallRecord;
import com.text2sql.agent.retrieval.SchemaContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmSqlGeneratorLoggingTest {
    @Test
    void allGenerationEntrypointsLogTheMessagesActuallySentIncludingTruncatedCorrection() {
        var props = new AgentProperties();
        var logs = mock(LlmCallLog.class);
        var model = mock(ChatModel.class);
        var generator = new LlmSqlGenerator(props, new PromptTemplate(), logs, com.text2sql.agent.retrieval.CatalogFixture.healthy());
        ReflectionTestUtils.setField(generator, "chatModel", model);
        var complete = response("stop");
        var truncated = response("length");
        when(model.call(any(Prompt.class))).thenReturn(complete, truncated, complete, complete);
        var schema = new SchemaContext(List.of(), List.of(), "", "test schema");
        generator.generate("订单总数", schema);
        assertThatThrownBy(() -> generator.generateCorrection("订单总数", schema, "SELECT 0", "retry"))
                .isInstanceOf(GenerationException.class);
        List<Message> history = List.of(new SystemMessage("other system"), new UserMessage("订单总数"));
        generator.callMessages(history, "HISTORY", null);
        generator.callWithTools(history, List.of(), "TOOLS", null, null);

        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(4)).call(prompts.capture());
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(logs, times(4)).record(any(LlmCallRecord.class), anyString(), anyString(), anyString(), hashes.capture());
        for (int i = 0; i < 4; i++) {
            assertThat(hashes.getAllValues().get(i)).isEqualTo(
                    PromptFingerprint.messagesSha256(prompts.getAllValues().get(i).getInstructions()));
        }
        assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(1));
    }

    private static ChatResponse response(String finishReason) {
        var metadata = mock(ChatGenerationMetadata.class);
        when(metadata.getFinishReason()).thenReturn(finishReason);
        return new ChatResponse(List.of(new Generation(new AssistantMessage("SELECT 1"), metadata)));
    }
}
