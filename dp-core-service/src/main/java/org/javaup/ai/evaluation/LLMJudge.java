package org.javaup.ai.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * LLM-as-Judge: 用 LLM 对回答进行四维质量评分。
 */
@Service
public class LLMJudge {

    private static final Logger log = LoggerFactory.getLogger(LLMJudge.class);

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public LLMJudge(LlmGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * 对单条回答评分：relevance, accuracy, completeness, helpfulness (0.0-1.0)。
     */
    public QualityScores judge(String query, String answer, String context) {
        String prompt = """
                你是客服回答质量评审专家。对以下回答评分，返回 JSON。
                用户问题: "%s"
                上下文: %s
                AI 回答: "%s"

                评分维度 (0.0-1.0):
                - relevance: 回答与问题的相关程度
                - accuracy: 回答的事实准确性
                - completeness: 是否完整覆盖了用户的问题
                - helpfulness: 对用户是否有实际帮助

                返回格式: {"relevance":0.9,"accuracy":0.8,"completeness":0.7,"helpfulness":0.85}
                """.formatted(query, context == null ? "无" : context, answer);
        try {
            String raw = llmGateway.chat("", prompt, 0.0, 256);
            String json = sliceJson(raw);
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});
            return new QualityScores(
                    asDouble(data.get("relevance"), 0.5),
                    asDouble(data.get("accuracy"), 0.5),
                    asDouble(data.get("completeness"), 0.5),
                    asDouble(data.get("helpfulness"), 0.5),
                    false, null
            );
        } catch (Exception ex) {
            log.warn("LLM Judge 评分失败: {}", ex.getMessage());
            return new QualityScores(0.5, 0.5, 0.5, 0.5, true, ex.getMessage());
        }
    }

    private String sliceJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{'), end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : "{}";
    }

    private double asDouble(Object val, double defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
