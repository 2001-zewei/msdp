package org.javaup.ai.tool;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具统计（线程安全）。
 */
public class ToolStats {
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final AtomicLong consecutiveFails = new AtomicLong();

    public void record(boolean ok, long latencyMs) {
        total.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        if (ok) {
            success.incrementAndGet();
            consecutiveFails.set(0);
        } else {
            failed.incrementAndGet();
            consecutiveFails.incrementAndGet();
        }
    }

    public long total() { return total.get(); }
    public long failed() { return failed.get(); }
    public long consecutiveFails() { return consecutiveFails.get(); }

    public double successRate() {
        long t = total.get();
        return t == 0 ? 1.0 : (double) success.get() / t;
    }

    public double avgLatencyMs() {
        long t = total.get();
        return t == 0 ? 0.0 : (double) totalLatencyMs.get() / t;
    }
}
