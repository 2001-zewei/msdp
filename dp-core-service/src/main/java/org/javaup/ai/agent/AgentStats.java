package org.javaup.ai.agent;

/**
 * Agent 统计信息，含路由评分。
 */
public class AgentStats {
    private volatile long total;
    private volatile long success;
    private volatile long totalLatencyMs;
    private volatile double monitorPenalty;

    public synchronized void record(boolean ok, long latencyMs) {
        total++;
        if (ok) success++;
        totalLatencyMs += latencyMs;
    }

    public long total() { return total; }
    public long successCount() { return success; }

    public double successRate() {
        return total == 0 ? 1.0 : (double) success / total;
    }

    public double avgLatencyMs() {
        return total == 0 ? 0.0 : (double) totalLatencyMs / total;
    }

    public double monitorPenalty() { return monitorPenalty; }
    public void setMonitorPenalty(double p) { this.monitorPenalty = Math.max(0.0, Math.min(0.9, p)); }

    /**
     * 路由评分 = 成功率 × 0.7 + 延迟分 × 0.3，再扣除监控惩罚。
     */
    public double routingScore() {
        double latencyScore = avgLatencyMs() <= 0 ? 1.0 : Math.max(0.0, 1.0 - avgLatencyMs() / 10000.0);
        double base = successRate() * 0.7 + latencyScore * 0.3;
        return base * (1.0 - monitorPenalty);
    }
}
