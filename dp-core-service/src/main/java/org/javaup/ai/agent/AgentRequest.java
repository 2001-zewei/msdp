package org.javaup.ai.agent;

import org.javaup.ai.intent.IntentCategory;
import org.javaup.ai.intent.UrgencyLevel;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 请求上下文。
 */
public record AgentRequest(
    String message,
    String userId,
    String conversationId,
    String context,
    List<Map<String, String>> history,
    IntentCategory intent,
    UrgencyLevel urgency,
    String requestId
) {
    public static AgentRequest of(String message, String userId, String conversationId,
                                   String context, List<Map<String, String>> history) {
        return new AgentRequest(message, userId, conversationId, context, history,
                null, null, UUID.randomUUID().toString());
    }

    public AgentRequest withIntent(IntentCategory intent, UrgencyLevel urgency) {
        return new AgentRequest(message, userId, conversationId, context, history,
                intent, urgency, requestId);
    }
}
