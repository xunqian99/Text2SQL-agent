package com.text2sql.agent.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Fingerprint of ordered role/text messages and tool-call/response payloads.
 * No prompt content is retained. Model options and tool definitions are separate
 * configuration, not part of this hash; this Text2SQL pipeline sends no media.
 */
public final class PromptFingerprint {
    public static final String VERSION = "text-tool-messages-v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private PromptFingerprint() {}

    public static String messagesSha256(List<? extends Message> messages) {
        List<List<Object>> payload = new ArrayList<>();
        for (Message message : messages) {
            List<Object> fields = new ArrayList<>();
            fields.add(message.getMessageType().name());
            fields.add(message.getText());
            if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
                fields.add(assistant.getToolCalls());
            }
            if (message instanceof ToolResponseMessage tool) {
                fields.add(tool.getResponses());
            }
            payload.add(fields);
        }
        try {
            // JSON escaping and array boundaries prevent concatenation collisions:
            // ["ab", "c"] must differ from ["a", "bc"]. Preserve whitespace verbatim.
            return textSha256(JSON.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot fingerprint supported chat messages", e);
        }
    }

    public static String textSha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
