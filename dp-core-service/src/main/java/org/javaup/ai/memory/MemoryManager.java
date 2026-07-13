package org.javaup.ai.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.javaup.ai.config.AiProperties;
import org.javaup.ai.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 三层记忆管理器：
 * 1. 工作记忆 (Redis List) - 当前会话，自动压缩
 * 2. 情景记忆 (JSON 持久化 + 向量检索) - 跨会话历史
 * 3. 用户画像 (内存 Map + JSON 持久化) - LLM 异步提取
 */
@Service
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private static final int VECTOR_DIM = 256;

    private final StringRedisTemplate redis;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    private final List<EpisodicEntry> episodicStore = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Object>> profileStore = new ConcurrentHashMap<>();

    public MemoryManager(StringRedisTemplate redis, LlmGateway llmGateway,
                         ObjectMapper objectMapper, AiProperties properties) {
        this.redis = redis;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        loadPersistentMemory();
    }

    // ── 获取记忆上下文 ─────────────────────────────────────────────

    public MemoryContext getContext(String userId, String conversationId, String query) {
        List<ConversationMessage> recent = getWorkingMemory(userId, conversationId);
        List<String> relevantHistory = searchEpisodic(userId, query);
        Map<String, Object> profile = profileStore.getOrDefault(userId, Map.of());
        String summary = getSummary(userId, conversationId);
        return new MemoryContext(recent, relevantHistory, profile, summary);
    }

    // ── 工作记忆 (Redis) ──────────────────────────────────────────

    public void saveMessage(String userId, String conversationId, ConversationMessage msg) {
        String key = "wm:" + userId + ":" + conversationId;
        try {
            String json = objectMapper.writeValueAsString(msg);
            redis.opsForList().leftPush(key, json);
            redis.expire(key, Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
            Long size = redis.opsForList().size(key);
            if (size != null && size >= properties.getMemory().getCompressAt()) {
                compressWorkingMemory(userId, conversationId);
            }
        } catch (Exception ex) {
            log.warn("保存工作记忆失败: {}", ex.getMessage());
        }
    }

    public List<ConversationMessage> getWorkingMemory(String userId, String conversationId) {
        String key = "wm:" + userId + ":" + conversationId;
        try {
            List<String> raw = redis.opsForList().range(key, 0, properties.getMemory().getWorkingMax() - 1);
            if (raw == null || raw.isEmpty()) return List.of();
            List<ConversationMessage> msgs = new ArrayList<>();
            for (String s : raw.reversed()) {
                try {
                    msgs.add(objectMapper.readValue(s, ConversationMessage.class));
                } catch (Exception ignored) {}
            }
            return msgs;
        } catch (Exception ex) {
            log.warn("读取工作记忆失败: {}", ex.getMessage());
            return List.of();
        }
    }

    private void compressWorkingMemory(String userId, String conversationId) {
        String key = "wm:" + userId + ":" + conversationId;
        List<ConversationMessage> all = new ArrayList<>();
        try {
            List<String> raw = redis.opsForList().range(key, 0, -1);
            if (raw == null || raw.size() < properties.getMemory().getCompressAt()) return;
            for (String s : raw.reversed())
                try { all.add(objectMapper.readValue(s, ConversationMessage.class)); } catch (Exception ignored) {}

            // 保留最近5条
            List<ConversationMessage> recent = all.subList(Math.max(0, all.size() - 5), all.size());
            List<ConversationMessage> older = all.subList(0, Math.max(0, all.size() - 5));

            if (!older.isEmpty()) {
                StringBuilder text = new StringBuilder();
                older.forEach(m -> text.append(m.role().name()).append(": ").append(m.content()).append("\n"));
                String summary = llmGateway.chat("",
                        "请将以下对话历史总结为2-3句话的关键信息摘要:\n" + text, 0.2, 256);
                if (summary != null && !summary.isBlank()) {
                    redis.opsForValue().set("summary:" + userId + ":" + conversationId, summary,
                            Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
                    episodicStore.add(new EpisodicEntry(userId, conversationId, summary,
                            text.toString(), Instant.now(), embed(summary)));
                    persistMemory();
                }
            }

            // 重建列表
            redis.delete(key);
            for (int i = recent.size() - 1; i >= 0; i--)
                redis.opsForList().leftPush(key, objectMapper.writeValueAsString(recent.get(i)));
            redis.expire(key, Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
        } catch (Exception ex) {
            log.warn("记忆压缩失败: {}", ex.getMessage());
        }
    }

    private String getSummary(String userId, String conversationId) {
        try {
            return redis.opsForValue().get("summary:" + userId + ":" + conversationId);
        } catch (Exception ex) {
            return null;
        }
    }

    // ── 情景记忆 ───────────────────────────────────────────────────

    private List<String> searchEpisodic(String userId, String query) {
        double[] queryVec = embed(query);
        return episodicStore.stream()
                .filter(e -> e.userId().equals(userId))
                .sorted(Comparator.comparingDouble((EpisodicEntry e) -> cosine(queryVec, e.embedding())).reversed())
                .limit(3)
                .map(EpisodicEntry::summary)
                .toList();
    }

    // ── 用户画像 ───────────────────────────────────────────────────

    @Async
    public CompletableFuture<Void> updateProfile(String userId, List<ConversationMessage> recentMsgs) {
        try {
            StringBuilder dialog = new StringBuilder();
            for (ConversationMessage m : recentMsgs)
                dialog.append(m.role().name()).append(": ").append(m.content()).append("\n");
            String prompt = """
                    从以下客服对话中提炼用户画像，返回 JSON。只提取明确信息，不要推测。
                    对话:
                    %s
                    返回格式: {"preferences": "偏好简述", "level": "会员等级", "entities": {}}
                    """.formatted(sliceJson(dialog.toString()));
            String raw = llmGateway.chat("", prompt, 0.1, 256);
            String json = raw != null ? sliceJson(raw) : "{}";
            Map<String, Object> profile = objectMapper.readValue(json, new TypeReference<>() {});
            profileStore.put(userId, profile);
            persistMemory();
            log.debug("用户画像已更新: userId={}", userId);
        } catch (Exception ex) {
            log.warn("更新用户画像失败: userId={}, {}", userId, ex.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    // ── Hash Vector (与 KnowledgeBaseService 一致) ─────────────────

    private double[] embed(String text) {
        double[] vector = new double[VECTOR_DIM];
        Set<String> grams = new HashSet<>();
        String cleaned = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\u4e00-\\u9fa5a-z0-9]", "");
        for (int n = 1; n <= 3; n++)
            for (int i = 0; i + n <= cleaned.length(); i++)
                grams.add(cleaned.substring(i, i + n));
        for (String gram : grams) {
            int hash = gram.hashCode();
            int idx = Math.floorMod(hash, VECTOR_DIM);
            vector[idx] += (hash & 1) == 0 ? 1.0 : -1.0;
        }
        return vector;
    }

    private double cosine(double[] a, double[] b) {
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0.0 : dot / denom;
    }

    // ── 持久化 ─────────────────────────────────────────────────────

    private void loadPersistentMemory() {
        Path path = getMemoryPath();
        if (!Files.exists(path)) return;
        try {
            StoredMemory stored = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            if (stored.episodic() != null) {
                for (StoredEpisodicEntry entry : stored.episodic())
                    episodicStore.add(new EpisodicEntry(entry.userId(), entry.conversationId(),
                            entry.summary(), entry.fullText(), entry.timestamp(), embed(entry.summary())));
            }
            if (stored.profiles() != null) profileStore.putAll(stored.profiles());
            log.info("Loaded persisted memory: episodic={}, profiles={}", episodicStore.size(), profileStore.size());
        } catch (Exception ex) {
            log.warn("Failed to load persisted memory: {}", ex.getMessage());
        }
    }

    private synchronized void persistMemory() {
        Path path = getMemoryPath();
        try {
            Files.createDirectories(path.getParent());
            List<StoredEpisodicEntry> episodic = episodicStore.stream()
                    .map(e -> new StoredEpisodicEntry(e.userId(), e.conversationId(),
                            e.summary(), e.fullText(), e.timestamp()))
                    .toList();
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(path.toFile(), new StoredMemory(episodic, Map.copyOf(profileStore)));
        } catch (Exception ex) {
            log.warn("Failed to persist memory: {}", ex.getMessage());
        }
    }

    private Path getMemoryPath() {
        return Path.of(properties.getStorage().getDataDir(), properties.getStorage().getMemoryPath());
    }

    private String sliceJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : "{}";
    }

    // ── 内部类型 ───────────────────────────────────────────────────

    private record EpisodicEntry(String userId, String conversationId, String summary,
                                  String fullText, Instant timestamp, double[] embedding) {}
    private record StoredMemory(List<StoredEpisodicEntry> episodic,
                                Map<String, Map<String, Object>> profiles) {}
    private record StoredEpisodicEntry(String userId, String conversationId,
                                        String summary, String fullText, Instant timestamp) {}
}
