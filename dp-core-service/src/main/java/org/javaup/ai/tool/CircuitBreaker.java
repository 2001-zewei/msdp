package org.javaup.ai.tool;

import java.time.Duration;
import java.time.Instant;

/**
 * 简单熔断器：阈值计数 + 恢复期。
 */
public class CircuitBreaker {

    private final int failureThreshold;
    private final Duration recoveryDuration;
    private int failureCount;
    private Instant openedAt;
    private CircuitState state = CircuitState.CLOSED;

    public CircuitBreaker(int failureThreshold, Duration recoveryDuration) {
        this.failureThreshold = failureThreshold;
        this.recoveryDuration = recoveryDuration;
    }

    public synchronized boolean allow() {
        if (state == CircuitState.OPEN) {
            if (openedAt != null && Duration.between(openedAt, Instant.now()).compareTo(recoveryDuration) >= 0) {
                state = CircuitState.HALF_OPEN;
                failureCount = 0;
            } else {
                return false;
            }
        }
        return true;
    }

    public synchronized void recordSuccess() {
        failureCount = 0;
        if (state == CircuitState.HALF_OPEN) {
            state = CircuitState.CLOSED;
        }
    }

    public synchronized void recordFailure() {
        failureCount++;
        if (state == CircuitState.HALF_OPEN || failureCount >= failureThreshold) {
            state = CircuitState.OPEN;
            openedAt = Instant.now();
        }
    }

    public CircuitState state() { return state; }
}
