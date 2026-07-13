package org.javaup.ai.memory;

import java.util.List;
import java.util.Map;

/**
 * 聚合后的记忆上下文。
 */
public record MemoryContext(
    List<ConversationMessage> recentMessages,
    List<String> relevantHistory,
    Map<String, Object> userProfile,
    String summary
) {
    /**
     * 格式化为可拼入 LLM prompt 的文本。
     */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        if (userProfile != null && !userProfile.isEmpty()) {
            sb.append("[用户画像]\n");
            userProfile.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }
        if (summary != null && !summary.isBlank()) {
            sb.append("[历史摘要]\n").append(summary).append("\n\n");
        }
        if (relevantHistory != null && !relevantHistory.isEmpty()) {
            sb.append("[相关历史]\n");
            relevantHistory.forEach(h -> sb.append("- ").append(h).append("\n"));
            sb.append("\n");
        }
        if (recentMessages != null && !recentMessages.isEmpty()) {
            sb.append("[最近对话]\n");
            recentMessages.forEach(m -> sb.append(m.role().name()).append(": ").append(m.content()).append("\n"));
        }
        return sb.toString();
    }

    public static MemoryContext empty() {
        return new MemoryContext(List.of(), List.of(), Map.of(), "");
    }
}
