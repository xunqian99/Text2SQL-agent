package com.text2sql.agent.generation;

import com.text2sql.agent.observability.LlmCallRecord;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmCompletionTest {
    private final LlmCallRecord call = LlmCallRecord.of("model", 100, 1024, 10, 50, 0.001, 0.004);

    @Test
    void refusesTruncatedAnswerEvenWhenPartialTextLooksLikeValidSql() {
        GenerationException error = catchThrowableOfType(GenerationException.class,
                () -> LlmSqlGenerator.requireComplete(response("length", "SELECT 1"), call));
        assertThat(error.getReason()).isEqualTo(GenerationException.Reason.OUTPUT_TRUNCATED);
        assertThat(error.getCallRecord()).isSameAs(call);
    }

    @Test
    void completedAnswerAtTokenBudgetIsNotMisclassifiedAsTruncated() {
        assertThatCode(() -> LlmSqlGenerator.requireComplete(response("stop", "SELECT 1"), call))
                .doesNotThrowAnyException();
    }

    @Test
    void toolCallsAndMissingFinishReasonDoNotCountAsTruncation() {
        assertThatCode(() -> LlmSqlGenerator.requireComplete(response("tool_calls", ""), call))
                .doesNotThrowAnyException();
        assertThatCode(() -> LlmSqlGenerator.requireComplete(response(null, "SELECT 1"), call))
                .doesNotThrowAnyException();
    }

    private ChatResponse response(String finishReason, String text) {
        ChatGenerationMetadata metadata = mock(ChatGenerationMetadata.class);
        when(metadata.getFinishReason()).thenReturn(finishReason);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
    }
}
