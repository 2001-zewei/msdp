package org.javaup.controller;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.ChatOrchestrator;
import org.javaup.dto.AiChatRequest;
import org.javaup.dto.AiChatResponse;
import org.javaup.dto.Result;
import org.javaup.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 智能助手控制器（V2 升级版）。
 * 使用 ChatOrchestrator 串联 记忆→知识检索→意图→多Agent→校验→持久化 六阶段管道。
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AiChatController {

    @Resource
    private ChatOrchestrator chatOrchestrator;

    /**
     * 发送聊天消息（V2 升级版）。
     */
    @PostMapping("/chat")
    public Result<AiChatResponse> chat(@Valid @RequestBody AiChatRequest request) {
        Long userId = UserHolder.getUser().getId();
        log.info("[AI V2] 收到聊天请求 userId={} message={}", userId, request.getMessage());

        ChatOrchestrator.ChatResult result = chatOrchestrator.process(
                userId.toString(), request.getMessage());

        AiChatResponse response = new AiChatResponse();
        response.setReply(result.response());
        response.setConversationId(result.conversationId());
        response.setIntent(result.intent().name().toLowerCase());
        response.setAgentType(result.agentType().name().toLowerCase());
        response.setEscalated(result.escalated());
        response.setLatencyMs(result.latencyMs());
        response.setKnowledgeUsed(result.knowledgeUsed());
        response.setVerified(result.verified());
        response.setGrounded(result.grounded());
        response.setHistory(List.of()); // Chat history managed on frontend side

        return Result.ok(response);
    }

    /** 健康检查 + Agent 路由统计 */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "status", "ok",
                "version", "2.0",
                "features", List.of("multi-agent", "rag", "skill-injection", "answer-verification", "llm-judge")
        ));
    }
}
