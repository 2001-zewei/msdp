package org.javaup.ai.tool;

import java.util.List;

/**
 * 工具调用结果。
 */
public record ToolResult<T>(
    boolean success,
    T data,
    String toolName,
    String error,
    boolean cached,
    long latencyMs,
    boolean reranked
) {}
