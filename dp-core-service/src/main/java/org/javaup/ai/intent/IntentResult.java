package org.javaup.ai.intent;

import java.util.List;
import java.util.Map;

/**
 * 意图识别结果。
 */
public record IntentResult(
    IntentCategory intent,
    double confidence,
    UrgencyLevel urgency,
    Map<String, List<String>> entities,
    String reasoning,
    long latencyMs
) {}
