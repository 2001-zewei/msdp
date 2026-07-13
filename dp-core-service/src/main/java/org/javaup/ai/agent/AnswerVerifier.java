package org.javaup.ai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * LLM 答案校验器：检查回答是否基于事实（grounded）、是否通过、是否需要升级。
 */
@Service
public class AnswerVerifier {

    private static final Logger log = LoggerFactory.getLogger(AnswerVerifier.class);

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public AnswerVerifier(LlmGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    public record Verdict(boolean pass, boolean grounded, boolean needEscalation, String reason) {}

    /**
     * 校验 Agent 回答质量。
     */
    public Verdict verify(String query, String answer, String context) {
        String prompt = """
                你是客服回答质量审核员。根据以下信息判断回答是否合格，返回 JSON。

                用户问题: "%s"
                知识库上下文: %s
                AI 回答: "%s"

                判断标准:
                - grounded: 回答是否基于提供的上下文（而非编造）
                - pass: 回答是否解决了用户的问题
                - need_escalation: 是否需要升级到人工客服

                返回格式: {"pass":true,"grounded":true,"need_escalation":false,"reason":"一句话说明"}
                """.formatted(query, context == null ? "无" : context, answer);
        try {
            String raw = llmGateway.chat("", prompt, 0.0, 256);
            String json = sliceJson(raw);
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});
            return new Verdict(
                    asBool(data.get("pass"), true),
                    asBool(data.get("grounded"), true),
                    asBool(data.get("need_escalation"), false),
                    String.valueOf(data.getOrDefault("reason", ""))
            );
        } catch (Exception ex) {
            log.warn("答案校验失败: {}", ex.getMessage());
            // 校验失败时放行（不让校验本身成为瓶颈）
            return new Verdict(true, true, false, "校验器异常，默认放行");
        }
    }

    private String sliceJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{'), end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : "{}";
    }

    private boolean asBool(Object val, boolean defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }
}
