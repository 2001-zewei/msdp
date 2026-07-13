package org.javaup.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.agent.*;
import org.javaup.ai.llm.LlmGateway;
import org.javaup.ai.skill.SkillManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * AI 模块 Bean 配置。
 */
@Configuration
public class AiConfig {

    @Bean
    public SkillManager skillManager(AiProperties properties, ObjectMapper objectMapper) {
        SkillManager sm = new SkillManager(
                properties.getSkill().getDir(),
                properties.getSkill().getMaxPromptChars(),
                objectMapper);
        sm.load();
        return sm;
    }

    @Bean
    public Map<AgentType, List<BaseAgent>> agentPool(LlmGateway llmGateway, SkillManager skillManager) {
        return Map.of(
                AgentType.GENERAL,   List.of(new GeneralAgent(llmGateway, skillManager)),
                AgentType.TECHNICAL, List.of(new TechnicalAgent(llmGateway, skillManager)),
                AgentType.BILLING,   List.of(new BillingAgent(llmGateway, skillManager))
        );
    }
}
