package org.javaup.ai.agent;

/**
 * Agent 响应。
 */
public record AgentResponse(
    AgentType agentType,
    String content,
    boolean success,
    double confidence,
    long latencyMs,
    boolean escalate
) {}
