package org.javaup.ai.agent;

import org.javaup.ai.intent.IntentCategory;
import org.javaup.ai.intent.IntentRecognizer;
import org.javaup.ai.intent.IntentResult;
import org.javaup.ai.intent.UrgencyLevel;
import org.javaup.ai.skill.SkillManager;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 多 Agent 编排器。
 * 路由逻辑：意图映射 → 性能加权选最优 → 专属 Agent 失败时降级 General。
 * 支持复合问题并行处理。
 */
@Service
public class AgentOrchestrator {

    private final IntentRecognizer intentRecognizer;
    private final Map<AgentType, List<BaseAgent>> pool;
    private SkillManager skillManager;

    private static final Map<IntentCategory, AgentType> ROUTING = new EnumMap<>(IntentCategory.class);
    static {
        ROUTING.put(IntentCategory.TECHNICAL, AgentType.TECHNICAL);
        ROUTING.put(IntentCategory.BILLING, AgentType.BILLING);
        ROUTING.put(IntentCategory.ACCOUNT, AgentType.BILLING);
        ROUTING.put(IntentCategory.ESCALATION, AgentType.ESCALATION);
    }

    public AgentOrchestrator(IntentRecognizer intentRecognizer,
                              Map<AgentType, List<BaseAgent>> pool,
                              SkillManager skillManager) {
        this.intentRecognizer = intentRecognizer;
        this.pool = pool;
        this.skillManager = skillManager;
    }

    /** 运行时重载 Skills 后更新所有 Agent 引用 */
    public void setSkillManager(SkillManager sm) {
        this.skillManager = sm;
        pool.values().stream().flatMap(List::stream).forEach(a -> a.setSkillManager(sm));
    }

    // ── 主入口 ─────────────────────────────────────────────────────

    public OrchestratorResult run(AgentRequest request) {
        Instant start = Instant.now();
        AgentRequest req = request;

        // 1. 意图识别
        if (req.intent() == null) {
            IntentResult intentResult = intentRecognizer.recognize(req.message(), req.history());
            req = req.withIntent(intentResult.intent(), intentResult.urgency());
        }

        // 2. 判断是否需要并行
        List<AgentType> targets = collaborationTargets(req);
        AgentResponse response = targets.size() > 1
                ? runParallel(req, targets)
                : execute(req, route(req.intent(), req.urgency()));

        // 3. 升级判断
        boolean escalated = response.escalate()
                || req.urgency() == UrgencyLevel.CRITICAL
                || req.intent() == IntentCategory.ESCALATION;

        return new OrchestratorResult(req.requestId(), response.content(),
                response.agentType(), req.intent(), escalated,
                Duration.between(start, Instant.now()).toMillis());
    }

    // ── 路由 ───────────────────────────────────────────────────────

    private AgentType route(IntentCategory intent, UrgencyLevel urgency) {
        if (urgency == UrgencyLevel.CRITICAL) return AgentType.ESCALATION;
        AgentType target = ROUTING.get(intent);
        return (target != null && pool.containsKey(target)) ? target : AgentType.GENERAL;
    }

    private List<AgentType> collaborationTargets(AgentRequest req) {
        String msg = req.message() == null ? "" : req.message().toLowerCase(Locale.ROOT);
        Set<AgentType> targets = new LinkedHashSet<>();
        if (req.intent() == IntentCategory.TECHNICAL
                || containsAny(msg, "崩溃", "报错", "error", "crash", "无法登录", "500", "401"))
            targets.add(AgentType.TECHNICAL);
        if (req.intent() == IntentCategory.BILLING || req.intent() == IntentCategory.ACCOUNT
                || containsAny(msg, "退款", "扣款", "发票", "账单", "支付", "refund", "invoice"))
            targets.add(AgentType.BILLING);
        if (targets.isEmpty()) targets.add(AgentType.GENERAL);
        return new ArrayList<>(targets);
    }

    // ── 执行 ───────────────────────────────────────────────────────

    private AgentResponse execute(AgentRequest req, AgentType agentType) {
        BaseAgent agent = bestAgent(agentType)
                .orElseGet(() -> bestAgent(AgentType.GENERAL).orElse(null));
        if (agent == null)
            return new AgentResponse(AgentType.GENERAL, "服务暂时不可用，请稍后重试。", false, 0.0, 0, false);
        AgentResponse response = agent.handle(req);
        if (!response.success() && agentType != AgentType.GENERAL)
            return bestAgent(AgentType.GENERAL).map(a -> a.handle(req)).orElse(response);
        return response;
    }

    private AgentResponse runParallel(AgentRequest req, List<AgentType> targets) {
        List<CompletableFuture<AgentResponse>> futures = targets.stream()
                .map(type -> CompletableFuture.supplyAsync(() -> execute(req, type)))
                .toList();
        List<AgentResponse> responses = futures.stream().map(CompletableFuture::join).toList();
        String content = responses.stream()
                .filter(AgentResponse::success)
                .map(r -> "[" + r.agentType().name().toLowerCase(Locale.ROOT) + "]\n" + r.content())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("抱歉，所有 Agent 均处理失败。");
        boolean escalate = responses.stream().anyMatch(AgentResponse::escalate);
        long latency = responses.stream().mapToLong(AgentResponse::latencyMs).max().orElse(0);
        return new AgentResponse(targets.getFirst(), content, true, 1.0, latency, escalate);
    }

    // ── 最优 Agent 选择 ────────────────────────────────────────────

    private Optional<BaseAgent> bestAgent(AgentType agentType) {
        return pool.getOrDefault(agentType, List.of()).stream()
                .max(Comparator.comparingDouble(a -> a.stats().routingScore()));
    }

    // ── 统计 ───────────────────────────────────────────────────────

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++) {
                BaseAgent a = agents.get(i);
                result.put(type.name().toLowerCase(Locale.ROOT) + "_" + i, Map.of(
                        "total", a.stats().total(),
                        "success_rate", round(a.stats().successRate()),
                        "avg_ms", round(a.stats().avgLatencyMs()),
                        "monitor_penalty", round(a.stats().monitorPenalty()),
                        "routing_score", round(a.stats().routingScore())
                ));
            }
        });
        return result;
    }

    public void updateRoutingPenalties(Map<String, Double> penalties) {
        pool.forEach((type, agents) -> {
            for (int i = 0; i < agents.size(); i++)
                agents.get(i).stats().setMonitorPenalty(
                        penalties.getOrDefault(type.name().toLowerCase(Locale.ROOT) + "_" + i, 0.0));
        });
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }

    private double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
