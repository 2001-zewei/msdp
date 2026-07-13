package org.javaup.ai.knowledge;

import java.util.Map;

/**
 * 知识库文档。
 */
public record KnowledgeDocument(
    String id,
    String title,
    String content,
    int chunkIndex,
    Map<String, Object> metadata,
    double[] embedding
) {}
