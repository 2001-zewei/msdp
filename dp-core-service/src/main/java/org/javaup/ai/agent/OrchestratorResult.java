package org.javaup.ai.agent;

import org.javaup.ai.intent.IntentCategory;

/**
 * 编排器结果。
 */
public record OrchestratorResult(
    String requestId,
    String response,
    AgentType agentType,
    IntentCategory intent,
    boolean escalated,
    long latencyMs
) {}
