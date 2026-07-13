package org.javaup.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI 聊天响应 DTO（升级版，包含意图、Agent、知识来源等丰富字段）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {

    /** AI 回复内容 */
    private String reply;

    /** 会话 ID */
    private String conversationId;

    /** 识别的意图 */
    private String intent;

    /** 处理的 Agent 类型 */
    private String agentType;

    /** 是否已升级 */
    private boolean escalated;

    /** 总处理延迟 (ms) */
    private long latencyMs;

    /** 是否使用了知识库 */
    private boolean knowledgeUsed;

    /** 是否经过了答案校验 */
    private boolean verified;

    /** 回答是否基于事实 (grounded) */
    private boolean grounded;

    /** 本次对话历史 */
    private List<ChatMessage> history;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessage {
        private String role;
        private String content;
        private Long timestamp;
    }
}
