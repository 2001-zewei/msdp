package org.javaup.ai;

import org.javaup.ai.agent.*;
import org.javaup.ai.intent.IntentCategory;
import org.javaup.ai.intent.IntentResult;
import org.javaup.ai.knowledge.SearchResult;
import org.javaup.ai.memory.ConversationMessage;
import org.javaup.ai.memory.MemoryContext;
import org.javaup.ai.memory.MemoryManager;
import org.javaup.ai.config.AiProperties;
import org.javaup.ai.tool.KnowledgeToolManager;
import org.javaup.ai.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 智能客服总编排器：串联记忆→知识检索→意图→Agent→校验→持久化的完整管道。
 * 替代旧的 AiAssistantServiceImpl。
 */
@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final MemoryManager memoryManager;
    private final AgentOrchestrator agentOrchestrator;
    private final KnowledgeToolManager knowledgeToolManager;
    private final AnswerVerifier answerVerifier;
    private final AiProperties properties;

    public ChatOrchestrator(MemoryManager memoryManager, AgentOrchestrator agentOrchestrator,
                             KnowledgeToolManager knowledgeToolManager, AnswerVerifier answerVerifier,
                             AiProperties properties) {
        this.memoryManager = memoryManager;
        this.agentOrchestrator = agentOrchestrator;
        this.knowledgeToolManager = knowledgeToolManager;
        this.answerVerifier = answerVerifier;
        this.properties = properties;
    }

    /**
     * 处理一次完整的 AI 对话请求。
     */
    public ChatResult process(String userId, String message) {
        String conversationId = UUID.randomUUID().toString();
        return process(userId, conversationId, message, null);
    }

    /**
     * 带 conversationId 的全链路处理。
     */
    public ChatResult process(String userId, String conversationId, String message,
                               List<Map<String, String>> history) {
        long t0 = System.currentTimeMillis();
        log.info("ChatOrchestrator: userId={} convId={} message={}", userId, conversationId, truncate(message, 100));

        // 1. 获取记忆上下文
        MemoryContext memCtx = memoryManager.getContext(userId, conversationId, message);

        // 2. 判断是否需要知识库检索（跳过问候等短消息）
        String knowledgeContext = null;
        boolean knowledgeUsed = false;
        if (shouldUseKnowledge(message)) {
            ToolResult<List<SearchResult>> result = knowledgeToolManager.searchWithRewrite(
                    message, properties.getRag().getTopK());
            if (result.success() && result.data() != null && !result.data().isEmpty()) {
                knowledgeUsed = true;
                StringBuilder kc = new StringBuilder("[知识库检索结果]\n");
                for (SearchResult sr : result.data()) {
                    kc.append("--- ").append(sr.title()).append(" ---\n").append(sr.content()).append("\n");
                }
                knowledgeContext = kc.toString();
            }
        }

        // 3. 合并上下文
        String fullContext = buildFullContext(memCtx, knowledgeContext);

        // 4. Agent 编排
        AgentRequest agentReq = AgentRequest.of(message, userId, conversationId,
                fullContext, history);
        OrchestratorResult result = agentOrchestrator.run(agentReq);

        // 5. 答案校验
        boolean verified = false;
        boolean grounded = true;
        if (knowledgeUsed) {
            AnswerVerifier.Verdict verdict = answerVerifier.verify(message, result.response(), knowledgeContext);
            verified = true;
            grounded = verdict.grounded();
            if (!verdict.pass()) {
                log.warn("答案校验未通过: {}", verdict.reason());
            }
        }

        // 6. 持久化记忆
        memoryManager.saveMessage(userId, conversationId, ConversationMessage.user(message));
        memoryManager.saveMessage(userId, conversationId, ConversationMessage.assistant(result.response()));
        memoryManager.updateProfile(userId, memCtx.recentMessages());

        long totalLatency = System.currentTimeMillis() - t0;

        return new ChatResult(
                conversationId,
                result.response(),
                result.intent(),
                result.agentType(),
                result.escalated(),
                totalLatency,
                knowledgeUsed,
                verified,
                grounded
        );
    }

    private boolean shouldUseKnowledge(String message) {
        if (message == null) return false;
        if (message.length() < 4) return false;
        String m = message.toLowerCase();
        String[] skipPatterns = {"你好", "hello", "hi", "在吗", "谢谢", "再见", "byebye", "ok"};
        for (String p : skipPatterns) if (m.equals(p) || m.startsWith(p)) return false;
        return true;
    }

    private String buildFullContext(MemoryContext memCtx, String knowledgeContext) {
        StringBuilder sb = new StringBuilder();
        sb.append(memCtx.toPromptText());
        if (knowledgeContext != null && !knowledgeContext.isBlank()) {
            sb.append("\n").append(knowledgeContext);
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max);
    }

    /**
     * 聊天结果。
     */
    public record ChatResult(
            String conversationId,
            String response,
            IntentCategory intent,
            AgentType agentType,
            boolean escalated,
            long latencyMs,
            boolean knowledgeUsed,
            boolean verified,
            boolean grounded
    ) {}
}
