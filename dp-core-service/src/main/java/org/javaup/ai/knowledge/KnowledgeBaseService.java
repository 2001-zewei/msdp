package org.javaup.ai.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import jakarta.annotation.PostConstruct;
import org.javaup.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 知识库服务：BM25 + Hash Vector 混合检索。
 * 支持文档分块、持久化、混合排序检索。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final int VECTOR_DIM = 256;

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final List<KnowledgeDocument> documents = new CopyOnWriteArrayList<>();

    public KnowledgeBaseService(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadPersistedDocuments();
        if (documents.isEmpty()) {
            addDocuments(defaultDocuments());
        }
    }

    // ── 文档管理 ───────────────────────────────────────────────────

    public int addDocuments(List<Map<String, String>> inputDocs) {
        int added = 0;
        for (Map<String, String> input : inputDocs) {
            String title = input.getOrDefault("title", "未命名文档");
            String content = input.getOrDefault("content", "");
            List<String> chunks = split(content);
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                if (chunk.isBlank()) continue;
                String id = md5(title + i + chunk);
                KnowledgeDocument doc = new KnowledgeDocument(
                        id, title, chunk, i, Map.of("source_title", title), embed(chunk));
                documents.removeIf(existing -> existing.id().equals(id));
                documents.add(doc);
                added++;
            }
        }
        persistDocuments();
        return added;
    }

    public int addDocumentsFromRaw(List<String> rawList) {
        List<Map<String, String>> docs = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            docs.add(Map.of("title", "doc_" + (i + 1), "content", rawList.get(i)));
        }
        return addDocuments(docs);
    }

    // ── 检索 ───────────────────────────────────────────────────────

    /**
     * 混合检索：BM25 权重 × 0.45 + 向量相似度 × 0.55。
     */
    public List<SearchResult> search(String query, int topK) {
        if (documents.isEmpty()) return List.of();

        Map<String, Double> bm25 = bm25Scores(query);
        Map<String, Double> vector = vectorScores(query);
        List<KnowledgeDocument> ranked = new ArrayList<>(documents);
        ranked.sort(Comparator.<KnowledgeDocument, Double>comparing(doc -> {
            double b = bm25.getOrDefault(doc.id(), 0.0);
            double v = vector.getOrDefault(doc.id(), 0.0);
            return b * properties.getRag().getBm25Weight() + v * properties.getRag().getVectorWeight();
        }).reversed());

        List<SearchResult> results = new ArrayList<>();
        for (KnowledgeDocument doc : ranked) {
            double score = bm25.getOrDefault(doc.id(), 0.0) * properties.getRag().getBm25Weight()
                    + vector.getOrDefault(doc.id(), 0.0) * properties.getRag().getVectorWeight();
            if (score <= 0) continue;
            results.add(new SearchResult(doc.id(), doc.title(), doc.content(), score, doc.chunkIndex(), doc.metadata()));
            if (results.size() >= topK) break;
        }
        return results;
    }

    public int documentCount() { return documents.size(); }

    // ── 持久化 ─────────────────────────────────────────────────────

    private void loadPersistedDocuments() {
        Path path = getStorePath();
        if (!Files.exists(path)) return;
        try {
            List<StoredDoc> stored = objectMapper.readValue(path.toFile(), new TypeReference<>() {});
            for (StoredDoc item : stored) {
                if (item.content() == null || item.content().isBlank()) continue;
                String id = item.id() == null || item.id().isBlank()
                        ? md5(item.title() + item.chunkIndex() + item.content())
                        : item.id();
                KnowledgeDocument doc = new KnowledgeDocument(id,
                        item.title() == null ? "未命名文档" : item.title(),
                        item.content(), item.chunkIndex(),
                        item.metadata() == null ? Map.of() : item.metadata(),
                        embed(item.content()));
                documents.removeIf(e -> e.id().equals(id));
                documents.add(doc);
            }
            log.info("Loaded {} persisted knowledge chunks from {}", documents.size(), path);
        } catch (Exception ex) {
            log.warn("Failed to load knowledge store {}: {}", path, ex.getMessage());
        }
    }

    private synchronized void persistDocuments() {
        Path path = getStorePath();
        try {
            Files.createDirectories(path.getParent());
            List<StoredDoc> stored = documents.stream()
                    .map(doc -> new StoredDoc(doc.id(), doc.title(), doc.content(),
                            doc.chunkIndex(), doc.metadata()))
                    .toList();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), stored);
        } catch (Exception ex) {
            log.warn("Failed to persist knowledge store {}: {}", path, ex.getMessage());
        }
    }

    private Path getStorePath() {
        return Path.of(properties.getStorage().getDataDir(), properties.getStorage().getKnowledgePath());
    }

    // ── BM25 ───────────────────────────────────────────────────────

    private Map<String, Double> bm25Scores(String query) {
        List<String> queryTerms = tokenize(query);
        int n = documents.size();
        Map<String, Integer> dfs = new HashMap<>();
        Map<String, Integer> docLengths = new HashMap<>();
        for (KnowledgeDocument doc : documents) {
            Set<String> unique = new HashSet<>(tokenize(doc.content()));
            docLengths.put(doc.id(), tokenize(doc.content()).size());
            for (String term : unique) dfs.merge(term, 1, Integer::sum);
        }
        double avgDl = docLengths.values().stream().mapToInt(Integer::intValue).average().orElse(1.0);
        double k1 = 1.5, b = 0.75;
        Map<String, Double> scores = new HashMap<>();
        for (KnowledgeDocument doc : documents) {
            List<String> docTerms = tokenize(doc.content());
            double score = 0.0;
            for (String term : queryTerms) {
                int tf = (int) docTerms.stream().filter(term::equals).count();
                int df = dfs.getOrDefault(term, 0);
                if (df == 0) continue;
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                double numerator = tf * (k1 + 1);
                double denominator = tf + k1 * (1 - b + b * docLengths.get(doc.id()) / avgDl);
                score += idf * numerator / denominator;
            }
            scores.put(doc.id(), score);
        }
        return scores;
    }

    // ── Hash Vector ────────────────────────────────────────────────

    private Map<String, Double> vectorScores(String query) {
        double[] queryVec = embed(query);
        Map<String, Double> scores = new HashMap<>();
        for (KnowledgeDocument doc : documents)
            scores.put(doc.id(), Math.max(0.0, cosine(queryVec, doc.embedding())));
        return scores;
    }

    private double[] embed(String text) {
        double[] vector = new double[VECTOR_DIM];
        Set<String> grams = new HashSet<>(tokenize(text));
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

    // ── 分词 / 分块 ────────────────────────────────────────────────

    private List<String> split(String content) {
        try {
            Document langDoc = dev.langchain4j.data.document.Document.from(content);
            var splitter = DocumentSplitters.recursive(500, 80);
            return splitter.split(langDoc).stream().map(TextSegment::text).toList();
        } catch (Exception ex) {
            log.debug("LangChain4j splitter unavailable, fallback to simple split");
            List<String> chunks = new ArrayList<>();
            for (int i = 0; i < content.length(); i += 500)
                chunks.add(content.substring(i, Math.min(i + 500, content.length())));
            return chunks;
        }
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        String cleaned = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\u4e00-\\u9fa5a-z0-9]", " ");
        for (String word : cleaned.split("\\s+")) {
            if (word.isEmpty()) continue;
            tokens.add(word);
            for (int n = 2; n <= 3; n++)
                for (int i = 0; i + n <= word.length(); i++)
                    tokens.add(word.substring(i, i + n));
        }
        return tokens;
    }

    // ── 默认数据 ───────────────────────────────────────────────────

    private List<Map<String, String>> defaultDocuments() {
        return List.of(
                Map.of("title", "退款政策", "content",
                        "优惠券退款政策：1. 未使用的优惠券可在购买后7天内申请退款；"
                                + "2. 已使用的优惠券不予退款；3. 秒杀优惠券购买后不支持退款；"
                                + "4. 系统故障导致的重复扣款可全额退款；5. 退款金额原路返回，到账时间1-7个工作日。"),
                Map.of("title", "订单查询指南", "content",
                        "用户可通过以下方式查询订单：1. 登录后进入"我的订单"页面查看所有订单；"
                                + "2. 使用订单号精确搜索；3. 按时间范围筛选订单；4. 订单状态包括：待支付、已支付、已取消、已退款。"),
                Map.of("title", "账号安全", "content",
                        "账号安全指南：1. 建议开启双重验证；2. 不要在公共网络保存登录状态；"
                                + "3. 定期修改密码；4. 发现异常登录请立即修改密码并联系客服。"),
                Map.of("title", "技术故障排查", "content",
                        "常见技术问题：1. 登录失败：检查网络、清除缓存、确认账号状态；"
                                + "2. 支付失败：确认支付渠道、检查账户余额、重试或更换支付方式；"
                                + "3. 页面加载异常：尝试刷新或更换浏览器。"),
                Map.of("title", "会员积分规则", "content",
                        "会员积分规则：1. 每消费1元获得1积分；2. 积分可用于兑换优惠券；"
                                + "3. 积分有效期为获得后12个月；4. 会员等级分为：白银、黄金、铂金、钻石；"
                                + "5. 不同等级享受不同折扣力度和专属优惠券。"),
                Map.of("title", "配送说明", "content",
                        "配送相关说明：优惠券为电子券，购买后即时到账，在"我的优惠券"中查看和使用。")
        );
    }

    // ── 工具 ───────────────────────────────────────────────────────

    private String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, digest).toString(16);
        } catch (Exception ex) { return Integer.toHexString(input.hashCode()); }
    }

    private record StoredDoc(String id, String title, String content, int chunkIndex,
                             Map<String, Object> metadata) {}
}
