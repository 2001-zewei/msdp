package org.javaup.ai.memory;

import java.time.Instant;
import java.util.Map;

/**
 * 会话消息。
 */
public record ConversationMessage(
    MessageRole role,
    String content,
    Instant timestamp,
    Map<String, String> metadata
) {
    public static ConversationMessage user(String content) {
        return new ConversationMessage(MessageRole.USER, content, Instant.now(), Map.of());
    }

    public static ConversationMessage assistant(String content) {
        return new ConversationMessage(MessageRole.ASSISTANT, content, Instant.now(), Map.of());
    }

    public static ConversationMessage system(String content) {
        return new ConversationMessage(MessageRole.SYSTEM, content, Instant.now(), Map.of());
    }
}
