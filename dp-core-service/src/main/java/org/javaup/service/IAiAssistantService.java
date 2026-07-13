package org.javaup.service;

import org.javaup.dto.AiChatResponse;

/**
 * AI 智能助手服务接口
 * @author: DP-Plus
 */
public interface IAiAssistantService {

    /**
     * 处理用户聊天消息
     * @param userId 用户ID
     * @param message 用户消息
     * @return AI 回复及对话历史
     */
    AiChatResponse chat(Long userId, String message);
}
