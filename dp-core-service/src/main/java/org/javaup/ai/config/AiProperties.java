package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 模块统一配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "dp-plus.ai")
public class AiProperties {

    private Llm llm = new Llm();
    private Memory memory = new Memory();
    private Rag rag = new Rag();
    private Storage storage = new Storage();
    private Monitor monitor = new Monitor();
    private Eval eval = new Eval();
    private SkillConfig skill = new SkillConfig();

    @Data
    public static class Llm {
        private boolean fallbackEnabled = true;
    }

    @Data
    public static class Memory {
        private int ttlSeconds = 86400;
        private int workingMax = 20;
        private int compressAt = 15;
    }

    @Data
    public static class Rag {
        private int topK = 4;
        private double bm25Weight = 0.45;
        private double vectorWeight = 0.55;
    }

    @Data
    public static class Storage {
        private String dataDir = "data";
        private String knowledgePath = "knowledge-store.json";
        private String memoryPath = "memory-store.json";
    }

    @Data
    public static class Monitor {
        private double successRateThreshold = 0.90;
        private double latencyMsThreshold = 3000;
        private String webhookUrl = "";
    }

    @Data
    public static class Eval {
        private String baselinePath = "eval/baseline.json";
    }

    @Data
    public static class SkillConfig {
        private String dir = "skills";
        private int maxPromptChars = 5000;
    }
}
