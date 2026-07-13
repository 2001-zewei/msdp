package org.javaup.ai.monitor;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.javaup.ai.agent.AgentOrchestrator;
import org.javaup.ai.config.AiProperties;
import org.javaup.ai.tool.KnowledgeToolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 性能监控：每 10 秒采集 + 阈值告警 + 路由惩罚 + Prometheus 指标。
 */
@Service
public class PerformanceMonitor {

    private static final Logger log = LoggerFactory.getLogger(PerformanceMonitor.class);

    private final AgentOrchestrator orchestrator;
    private final KnowledgeToolManager toolManager;
    private final AiProperties properties;
    private final RestClient restClient;
    private final AtomicLong collectionCount = new AtomicLong();
    private final Map<String, Instant> lastAlerts = new HashMap<>();
    private final MeterRegistry meterRegistry;

    public PerformanceMonitor(AgentOrchestrator orchestrator, KnowledgeToolManager toolManager,
                               AiProperties properties, MeterRegistry meterRegistry) {
        this.orchestrator = orchestrator;
        this.toolManager = toolManager;
        this.properties = properties;
        this.restClient = RestClient.create();
        this.meterRegistry = meterRegistry;

        // 注册 Prometheus 指标
        Gauge.builder("echomind.collections.total", collectionCount::get)
                .description("Total monitoring collections").register(meterRegistry);
    }

    @Scheduled(fixedRate = 10_000)
    public void collect() {
        collectionCount.incrementAndGet();
        Instant now = Instant.now();
        Timer.Sample sample = Timer.start(meterRegistry);

        Map<String, Object> agentStats = orchestrator.stats();
        List<String> alerts = new ArrayList<>();
        Map<String, Double> penalties = new LinkedHashMap<>();

        agentStats.forEach((key, value) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) value;
            double successRate = ((Number) data.getOrDefault("success_rate", 1.0)).doubleValue();
            double avgMs = ((Number) data.getOrDefault("avg_ms", 0.0)).doubleValue();

            // 注册 Prometheus gauge
            Gauge.builder("echomind.agent.success_rate", () -> successRate)
                    .tag("agent", key).strongReference(true).register(meterRegistry);

            // 告警检查
            if (successRate < properties.getMonitor().getSuccessRateThreshold()) {
                alerts.add(key + " 成功率过低: " + round(successRate));
            }
            if (avgMs > properties.getMonitor().getLatencyMsThreshold()) {
                alerts.add(key + " 平均延迟过高: " + round(avgMs) + "ms");
            }

            // 路由惩罚计算
            double penalty = 0.0;
            if (successRate < 0.5) penalty = 0.9;
            else if (successRate < 0.8) penalty = 0.5;
            else if (successRate < properties.getMonitor().getSuccessRateThreshold()) penalty = 0.2;
            if (avgMs > 5000) penalty = Math.max(penalty, 0.7);
            penalties.put(key, penalty);
        });

        // 应用惩罚
        if (!penalties.isEmpty()) orchestrator.updateRoutingPenalties(penalties);

        // 去重告警（同一 key 60s 内不重复）
        if (!alerts.isEmpty()) {
            alerts.stream()
                    .filter(a -> lastAlerts.getOrDefault(alertKey(a), Instant.EPOCH)
                            .isBefore(now.minusSeconds(60)))
                    .forEach(a -> {
                        log.warn("告警: {}", a);
                        lastAlerts.put(alertKey(a), now);
                        sendWebhook(a);
                    });
        }

        sample.stop(Timer.builder("echomind.monitor.duration")
                .description("Monitor collection duration").register(meterRegistry));
    }

    public Map<String, Object> summary() {
        return Map.of(
                "agent_stats", orchestrator.stats(),
                "tool_stats", toolManager.stats(),
                "alerts", List.of(),
                "suggestions", List.of()
        );
    }

    private void sendWebhook(String message) {
        String url = properties.getMonitor().getWebhookUrl();
        if (url == null || url.isBlank()) return;
        try {
            restClient.post().uri(url).body(Map.of("text", "EchoMind 告警: " + message)).retrieve();
        } catch (Exception ex) {
            log.warn("Webhook 发送失败: {}", ex.getMessage());
        }
    }

    private String alertKey(String msg) { return msg.split("[\\s:]")[0]; }
    private double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
