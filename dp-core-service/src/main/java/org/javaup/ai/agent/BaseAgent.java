package org.javaup.ai.agent;

import org.javaup.ai.llm.LlmGateway;
import org.javaup.ai.skill.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 基类。封装 LLM 调用、Skill 注入和统计。
 */
public abstract class BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(BaseAgent.class);

    protected final LlmGateway llmGateway;
    protected final AgentStats stats;
    private SkillManager skillManager;

    protected BaseAgent(LlmGateway llmGateway, SkillManager skillManager) {
        this.llmGateway = llmGateway;
        this.skillManager = skillManager;
        this.stats = new AgentStats();
    }

    public abstract AgentType agentType();
    protected abstract String systemPrompt();

    /**
     * 处理请求。组装 system prompt（含动态 Skill）后调用 LLM。
     */
    public AgentResponse handle(AgentRequest request) {
        long t0 = System.currentTimeMillis();
        stats.record(true, 0);
        try {
            String prompt = buildSystemPrompt(request);
            String userPrompt = buildUserPrompt(request);
            String answer = llmGateway.chat(prompt, userPrompt, 0.7, 1024);
            long latency = System.currentTimeMillis() - t0;
            stats.record(true, latency);
            boolean escalate = needsEscalation(answer);
            return new AgentResponse(agentType(), answer, true, 1.0, latency, escalate);
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - t0;
            stats.record(false, latency);
            log.error("{} 处理失败: {}", agentType().name(), ex.getMessage());
            return new AgentResponse(agentType(), "抱歉，处理您的请求时出现问题，请稍后重试。",
                    false, 0.0, latency, false);
        }
    }

    private String buildSystemPrompt(AgentRequest req) {
        StringBuilder sb = new StringBuilder(systemPrompt());
        if (req.context() != null && !req.context().isBlank()) {
            sb.append("\n\n[背景信息]\n").append(req.context());
        }
        // 动态 Skills 注入
        if (skillManager != null) {
            String skillPrompt = skillManager.promptFor(req.message(), agentType().name().toLowerCase());
            if (!skillPrompt.isEmpty()) {
                sb.append("\n\n[动态 Skills]\n").append(skillPrompt);
            }
        }
        return sb.toString();
    }

    private String buildUserPrompt(AgentRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户消息: ").append(req.message());
        if (req.history() != null && !req.history().isEmpty()) {
            sb.append("\n\n最近对话:\n");
            req.history().forEach(m -> sb.append(m.getOrDefault("role", "unknown"))
                    .append(": ").append(m.getOrDefault("content", "")).append("\n"));
        }
        return sb.toString();
    }

    public void setSkillManager(SkillManager sm) { this.skillManager = sm; }
    public AgentStats stats() { return stats; }

    private boolean needsEscalation(String content) {
        if (content == null) return false;
        String[] keywords = {"转人工", "人工客服", "escalate", "无法处理"};
        for (String kw : keywords) if (content.contains(kw)) return true;
        return false;
    }
}
