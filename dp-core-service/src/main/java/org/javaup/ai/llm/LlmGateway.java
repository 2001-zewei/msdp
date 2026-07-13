package org.javaup.ai.llm;

/**
 * LLM 网关统一接口。
 */
public interface LlmGateway {
    /**
     * 调用 LLM 对话。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param temperature  温度
     * @param maxTokens    最大 Token 数
     * @return LLM 回复文本
     */
    String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens);
}
