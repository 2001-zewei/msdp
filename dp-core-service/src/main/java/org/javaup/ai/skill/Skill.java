package org.javaup.ai.skill;

import java.util.List;

/**
 * 单个 Skill 的标准化表示，对应 Python Skill dataclass。
 * 支持 Markdown/JSON 等不同文件格式差异。
 */
public record Skill(
    String name,
    String description,
    String content,
    String path,
    List<String> keywords,
    List<String> agents,
    boolean enabled
) {

    /**
     * 判断当前请求是否应注入此 Skill。
     * - agents 不为空时，只匹配指定 Agent。
     * - keywords 为空 → 全局注入；否则命中才注入。
     */
    public boolean matches(String message, String agentType) {
        if (!enabled) return false;
        if (agents != null && !agents.isEmpty()
                && agentType != null
                && !agents.contains(agentType.toLowerCase())) {
            return false;
        }
        if (keywords == null || keywords.isEmpty()) return true;
        String lowered = message == null ? "" : message.toLowerCase();
        return keywords.stream().anyMatch(k -> lowered.contains(k.toLowerCase()));
    }

    /**
     * 格式化为可拼入 system prompt 的文本块。
     */
    public String toPromptBlock(int maxChars) {
        String body = content.strip();
        if (body.length() > maxChars) {
            body = body.substring(0, maxChars).stripTrailing() + "\n...";
        }
        StringBuilder sb = new StringBuilder("### ").append(name);
        if (description != null && !description.isBlank()) {
            sb.append("\n说明: ").append(description);
        }
        sb.append("\n").append(body);
        return sb.toString();
    }

    public String toPromptBlock() {
        return toPromptBlock(3200);
    }
}
