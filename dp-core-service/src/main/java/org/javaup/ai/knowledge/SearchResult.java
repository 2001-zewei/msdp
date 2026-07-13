package org.javaup.ai.knowledge;

import java.util.Map;

/**
 * 检索结果。
 */
public record SearchResult(
    String id,
    String title,
    String content,
    double score,
    int chunk,
    Map<String, Object> metadata
) {}
