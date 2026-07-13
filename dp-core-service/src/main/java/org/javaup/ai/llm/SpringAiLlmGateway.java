package org.javaup.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Spring AI 实现的 LLM 网关，适配 DashScope / Anthropic / DeepSeek 等多种 Provider。
 */
@Service
public class SpringAiLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmGateway.class);
    private static final String PLACEHOLDER = "echomind-local-placeholder";

    private final ChatClient chatClient;
    private final boolean fallbackEnabled;

    public SpringAiLlmGateway(ObjectProvider<ChatModel> chatModelProvider) {
        ChatModel model = chatModelProvider.getIfAvailable();
        this.chatClient = model != null ? ChatClient.builder(model).build() : null;
        // 如果没有配置真实 API Key，启用本地关键词降级
        this.fallbackEnabled = model == null;
        if (chatClient == null) {
            log.warn("未检测到 ChatModel Bean，将使用本地关键词降级模式。"
                    + "请在 application.yml 中配置 spring.ai.openai.api-key");
        } else {
            log.info("LLM 网关已就绪: ChatModel={}", model.getClass().getSimpleName());
        }
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        if (chatClient == null) {
            return fallbackReply(userPrompt);
        }
        try {
            String fullPrompt = systemPrompt != null && !systemPrompt.isBlank()
                    ? systemPrompt + "\n\n用户消息: " + userPrompt
                    : userPrompt;
            return chatClient.prompt()
                    .user(fullPrompt)
                    .temperature(temperature)
                    .call()
                    .content();
        } catch (Exception ex) {
            log.error("LLM 调用失败: {}", ex.getMessage());
            if (fallbackEnabled) {
                return fallbackReply(userPrompt);
            }
            return "抱歉，AI 服务暂时不可用，请稍后重试。";
        }
    }

    /**
     * 无 LLM 时的关键词降级回复。
     */
    private String fallbackReply(String message) {
        if (message == null) return "请问有什么可以帮您？";
        String msg = message.toLowerCase();
        if (msg.contains("退款") || msg.contains("refund")) {
            return "关于退款问题：请提供您的订单号，我们会尽快核实处理。通常退款审核需要 1-3 个工作日。";
        }
        if (msg.contains("报错") || msg.contains("error") || msg.contains("500") || msg.contains("401")) {
            return "关于技术故障问题：请提供具体的错误码或错误提示截图，以及发生时间，我们帮您排查。";
        }
        if (msg.contains("扣款") || msg.contains("账单") || msg.contains("发票")) {
            return "关于账单查询问题：请在"我的订单"中查看消费明细。如有疑问可提供支付流水号进一步核实。";
        }
        if (msg.contains("你好") || msg.contains("hello") || msg.contains("hi")) {
            return "您好！我是 DP-Plus 智能客服，请问有什么可以帮助您的？";
        }
        return "我已收到您的消息，但当前 AI 服务繁忙。如需帮助，请转人工客服或稍后再试。";
    }
}
