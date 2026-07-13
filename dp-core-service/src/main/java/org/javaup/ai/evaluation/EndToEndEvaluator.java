package org.javaup.ai.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.agent.AgentOrchestrator;
import org.javaup.ai.agent.AgentRequest;
import org.javaup.ai.config.AiProperties;
import org.javaup.ai.intent.IntentRecognizer;
import org.javaup.ai.intent.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 端到端评测器：意图准确率 + 对话质量 + 回归检测 + 优化建议。
 */
@Service
public class EndToEndEvaluator {

    private static final Logger log = LoggerFactory.getLogger(EndToEndEvaluator.class);

    private final IntentRecognizer intentRecognizer;
    private final AgentOrchestrator orchestrator;
    private final LLMJudge judge;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public EndToEndEvaluator(IntentRecognizer intentRecognizer, AgentOrchestrator orchestrator,
                              LLMJudge judge, ObjectMapper objectMapper, AiProperties properties) {
        this.intentRecognizer = intentRecognizer;
        this.orchestrator = orchestrator;
        this.judge = judge;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Map<String, Object> run(List<IntentCase> intentCases, List<DialogCase> dialogCases) {
        if (intentCases == null || intentCases.isEmpty()) intentCases = defaultIntentCases();
        if (dialogCases == null || dialogCases.isEmpty()) dialogCases = defaultDialogCases();

        List<Map<String, Object>> results = new ArrayList<>();
        List<String> predictions = new ArrayList<>();
        List<String> groundTruth = new ArrayList<>();
        long intentCorrect = 0;

        // 意图评测
        for (IntentCase c : intentCases) {
            IntentResult result = intentRecognizer.recognize(c.message(), null);
            String predicted = result.intent().name().toLowerCase(Locale.ROOT);
            predictions.add(predicted);
            groundTruth.add(c.expectedIntent());
            boolean passed = predicted.equals(c.expectedIntent());
            if (passed) intentCorrect++;
            results.add(Map.of("test_id", "intent_" + results.size(), "passed", passed,
                    "scores", Map.of("accuracy", passed ? 1.0 : 0.0),
                    "metadata", Map.of("message", c.message(), "expected", c.expectedIntent(), "predicted", predicted)));
        }

        // 对话评测
        for (DialogCase c : dialogCases) {
            List<String> turns = c.turns() != null ? c.turns() : List.of(c.question());
            List<Map<String, String>> history = new ArrayList<>();
            String convId = c.conversationId() == null ? "eval_" + UUID.randomUUID() : c.conversationId();
            String userId = c.userId() == null ? "eval_user" : c.userId();
            for (String turn : turns) {
                var response = orchestrator.run(AgentRequest.of(turn, userId, convId, history.toString(), history));
                QualityScores scores = judge.judge(turn, response.response(), history.toString());
                results.add(Map.of("test_id", "dialog_" + results.size(),
                        "passed", scores.overall() >= 0.75,
                        "scores", Map.of("overall", round(scores.overall()), "relevance", scores.relevance(),
                                "accuracy", scores.accuracy(), "completeness", scores.completeness(),
                                "helpfulness", scores.helpfulness()),
                        "metadata", Map.of("question", turn, "response", response.response(),
                                "agent_type", response.agentType().name().toLowerCase(Locale.ROOT),
                                "judge_failed", scores.judgeFailed(), "judge_error", scores.error() == null ? "" : scores.error())));
                history.add(Map.of("role", "user", "content", turn));
                history.add(Map.of("role", "assistant", "content", response.response()));
            }
        }

        long passed = results.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
        double intentAccuracy = intentCases.isEmpty() ? 0.0 : (double) intentCorrect / intentCases.size();
        Map<String, Object> avgScores = new LinkedHashMap<>();
        avgScores.put("intent_accuracy", round(intentAccuracy));
        avgScores.put("macro_f1", round(macroF1(predictions, groundTruth)));
        avgScores.put("dialog_overall", round(avgDialogOverall(results)));

        List<String> regressions = detectRegressions(avgScores);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pass_rate", results.isEmpty() ? 0.0 : round((double) passed / results.size()));
        report.put("total", results.size());
        report.put("passed", passed);
        report.put("avg_scores", avgScores);
        report.put("per_class", perClassMetrics(predictions, groundTruth));
        report.put("regressions", regressions);
        report.put("recommendations", recommendations(intentAccuracy, regressions));
        report.put("results", results);
        saveBaseline(report);
        return report;
    }

    // ── 默认用例 ───────────────────────────────────────────────────

    private List<IntentCase> defaultIntentCases() {
        return List.of(
                new IntentCase("我的订单什么时候到？", "query"),
                new IntentCase("帮我取消订单", "request"),
                new IntentCase("你们服务太差了！", "complaint"),
                new IntentCase("应用一直报500错误", "technical"),
                new IntentCase("为什么扣了两次款？", "billing"),
                new IntentCase("我要投诉，转人工！", "escalation"),
                new IntentCase("你好", "greeting"),
                new IntentCase("修改我的邮箱地址", "account")
        );
    }

    private List<DialogCase> defaultDialogCases() {
        return List.of(
                new DialogCase("我的订单 #12345 还没到，已经超时了", null, null, null),
                new DialogCase("应用登录一直报错 401", null, null, null),
                new DialogCase("为什么这个月多扣了 50 块钱？", null, null, null),
                new DialogCase(null, List.of("你好，我想退款", "订单号是 #12345", "退款多久能到账？"), null, null)
        );
    }

    // ── 计算 ───────────────────────────────────────────────────────

    private double macroF1(List<String> predictions, List<String> groundTruth) {
        Map<String, Map<String, Double>> perClass = perClassMetrics(predictions, groundTruth);
        return perClass.values().stream().mapToDouble(m -> m.getOrDefault("f1", 0.0)).average().orElse(0.0);
    }

    private Map<String, Map<String, Double>> perClassMetrics(List<String> predictions, List<String> groundTruth) {
        Set<String> labels = new HashSet<>();
        labels.addAll(predictions);
        labels.addAll(groundTruth);
        Map<String, Map<String, Double>> metrics = new LinkedHashMap<>();
        for (String label : labels) {
            int tp = 0, fp = 0, fn = 0;
            for (int i = 0; i < predictions.size(); i++) {
                boolean p = label.equals(predictions.get(i));
                boolean g = label.equals(groundTruth.get(i));
                if (p && g) tp++;
                if (p && !g) fp++;
                if (!p && g) fn++;
            }
            double precision = tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
            double recall = tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
            double f1 = precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
            metrics.put(label, Map.of("precision", round(precision), "recall", round(recall), "f1", round(f1)));
        }
        return metrics;
    }

    @SuppressWarnings("unchecked")
    private double avgDialogOverall(List<Map<String, Object>> results) {
        return results.stream()
                .filter(r -> String.valueOf(r.get("test_id")).startsWith("dialog_"))
                .map(r -> (Map<String, Object>) r.get("scores"))
                .mapToDouble(s -> ((Number) s.getOrDefault("overall", 0.0)).doubleValue()).average().orElse(0.0);
    }

    @SuppressWarnings("unchecked")
    private List<String> detectRegressions(Map<String, Object> currentScores) {
        Path path = Path.of(properties.getStorage().getDataDir(), properties.getEval().getBaselinePath());
        if (!Files.exists(path)) return List.of();
        try {
            Map<String, Object> previous = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            Map<String, Object> prevScores = (Map<String, Object>) previous.getOrDefault("avg_scores", Map.of());
            List<String> regressions = new ArrayList<>();
            for (var entry : currentScores.entrySet()) {
                Object prev = prevScores.get(entry.getKey());
                if (prev instanceof Number p && entry.getValue() instanceof Number c && p.doubleValue() > 0) {
                    double delta = (c.doubleValue() - p.doubleValue()) / p.doubleValue();
                    if (delta < -0.05) regressions.add(entry.getKey() + ": " + round(p.doubleValue()) + " -> " + round(c.doubleValue()));
                }
            }
            return regressions;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private void saveBaseline(Map<String, Object> report) {
        try {
            Path path = Path.of(properties.getStorage().getDataDir(), properties.getEval().getBaselinePath());
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        } catch (Exception ignored) { log.warn("Failed to save baseline: {}", ignored.getMessage()); }
    }

    private List<String> recommendations(double intentAccuracy, List<String> regressions) {
        List<String> recs = new ArrayList<>();
        if (intentAccuracy < 0.9) recs.add("补充低准确率意图类别的样本和 Few-shot 示例");
        if (!regressions.isEmpty()) recs.add("发现评测回归，请对比 baseline 中退化指标并检查最近 prompt 或检索逻辑变更");
        if (recs.isEmpty()) recs.add("所有指标均达标");
        return recs;
    }

    private double round(double v) { return Math.round(v * 10000.0) / 10000.0; }

    public record IntentCase(String message, String expectedIntent) {}
    public record DialogCase(String question, List<String> turns, String userId, String conversationId) {}
}
