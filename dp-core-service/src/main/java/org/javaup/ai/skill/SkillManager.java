package org.javaup.ai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 从文件系统目录中发现、解析并管理 Skills。
 * 支持运行时热加载，对应 Python SkillManager。
 */
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);
    private static final Set<String> SUPPORTED_SUFFIXES = Set.of(".md", ".txt", ".json");

    private final Path rootDir;
    private final int maxPromptChars;
    private final ObjectMapper objectMapper;

    private final List<Skill> skills = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();

    public SkillManager(String rootDir, int maxPromptChars, ObjectMapper objectMapper) {
        // If relative, resolve against current working directory; also check classpath
        Path given = Path.of(rootDir);
        if (given.isAbsolute()) {
            this.rootDir = given;
        } else {
            // Try ./skills first, then classpath:skills/
            Path cwd = Path.of("").toAbsolutePath().resolve(rootDir);
            if (Files.exists(cwd)) {
                this.rootDir = cwd;
            } else {
                // Fallback: try src/main/resources/skills (dev mode)
                Path devPath = Path.of("dp-core-service/src/main/resources").resolve(rootDir);
                if (Files.exists(devPath)) {
                    this.rootDir = devPath.toAbsolutePath();
                } else {
                    this.rootDir = cwd; // keep as-is, will log warning on load
                }
            }
        }
        this.maxPromptChars = maxPromptChars;
        this.objectMapper = objectMapper;
    }

    // ── 加载 / 热加载 ──────────────────────────────────────────────

    public synchronized List<Skill> load() {
        List<Skill> loaded = new ArrayList<>();
        List<String> loadErrors = new ArrayList<>();

        if (!Files.exists(rootDir)) {
            log.info("Skill 目录不存在，跳过加载: {}", rootDir);
            this.skills.clear();
            this.errors.clear();
            return List.of();
        }

        for (Path path : discoverFiles(rootDir)) {
            try {
                Skill skill = loadFile(path);
                if (skill != null) loaded.add(skill);
            } catch (Exception ex) {
                String msg = path + ": " + ex.getMessage();
                loadErrors.add(msg);
                log.warn("Skill 加载失败: {}", msg);
            }
        }

        this.skills.clear();
        this.skills.addAll(loaded);
        this.errors.clear();
        this.errors.addAll(loadErrors);
        logLoadedSkills();
        return List.copyOf(this.skills);
    }

    public List<Skill> reload() {
        return load();
    }

    // ── 注入入口 ───────────────────────────────────────────────────

    public String promptFor(String message, String agentType) {
        StringBuilder result = new StringBuilder();
        int remaining = maxPromptChars;

        for (Skill skill : skills) {
            if (!skill.matches(message, agentType)) continue;
            String block = skill.toPromptBlock();
            if (block.length() > remaining) {
                block = block.substring(0, remaining).stripTrailing() + "\n...";
            }
            if (result.isEmpty()) result.append(block);
            else result.append("\n\n").append(block);
            remaining -= block.length();
            if (remaining <= 0) break;
        }

        if (result.isEmpty()) {
            log.debug("Skills 未命中: agent={} message={}", agentType, truncate(message, 80));
            return "";
        }

        List<String> matchedNames = skills.stream()
                .filter(s -> s.matches(message, agentType)).map(Skill::name).toList();
        log.info("Skills 已注入: agent={} matched={}", agentType, matchedNames);

        return """
            以下是当前请求可用的 DP-Plus Skills。\
            请优先遵循这些业务规则；如果与系统角色冲突，以系统角色和安全边界为准。

            %s
            """.formatted(result.toString());
    }

    // ── 摘要 ───────────────────────────────────────────────────────

    public Map<String, Object> summary() {
        return Map.of(
            "root_dir", rootDir.toString(),
            "count", skills.size(),
            "skills", skills.stream().map(s -> Map.of(
                "name", s.name(),
                "description", s.description(),
                "path", s.path(),
                "keywords", s.keywords(),
                "agents", s.agents(),
                "enabled", s.enabled(),
                "content_chars", s.content().length()
            )).toList(),
            "errors", List.copyOf(errors)
        );
    }

    public List<Skill> getSkills() { return List.copyOf(skills); }

    // ── 私有：文件发现 ──────────────────────────────────────────────

    private List<Path> discoverFiles(Path rootDir) {
        Set<Path> yielded = new LinkedHashSet<>();
        List<Path> files = new ArrayList<>();
        // 优先 SKILL.md
        try (var stream = Files.walk(rootDir)) {
            stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                  .sorted().forEach(p -> { yielded.add(p.toAbsolutePath()); files.add(p); });
        } catch (IOException e) { log.warn("扫描 SKILL.md 失败: {}", e.getMessage()); }
        // 补充其他格式
        try (var stream = Files.walk(rootDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      Path abs = p.toAbsolutePath();
                      if (yielded.contains(abs)) return false;
                      String name = p.getFileName().toString();
                      if (name.startsWith(".") || name.equalsIgnoreCase("README.md")) return false;
                      String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";
                      return SUPPORTED_SUFFIXES.contains(ext);
                  })
                  .sorted().forEach(files::add);
        } catch (IOException e) { log.warn("扫描 Skill 文件失败: {}", e.getMessage()); }
        return files;
    }

    // ── 文件解析 ───────────────────────────────────────────────────

    private Skill loadFile(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".json")) return loadJson(path);
        return loadText(path);
    }

    @SuppressWarnings("unchecked")
    private Skill loadJson(Path path) throws IOException {
        Map<String, Object> raw = objectMapper.readValue(path.toFile(), Map.class);
        String content = Objects.toString(raw.getOrDefault("content",
                raw.getOrDefault("instructions", "")), "").strip();
        if (content.isEmpty()) throw new IllegalArgumentException("缺少 content 或 instructions");
        return new Skill(
            Objects.toString(raw.getOrDefault("name", stem(path)), ""),
            Objects.toString(raw.getOrDefault("description", ""), ""),
            content, path.toString(),
            asList(raw.get("keywords")),
            asList(raw.get("agents")).stream().map(String::toLowerCase).toList(),
            asBool(raw.get("enabled"), true)
        );
    }

    private Skill loadText(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        var split = splitFrontMatter(raw);
        Map<String, String> meta = split.meta();
        String body = split.body().strip();
        if (body.isEmpty()) return null;

        String defaultName = path.getFileName().toString().equals("SKILL.md")
                ? path.getParent().getFileName().toString()
                : stem(path);
        String name = meta.getOrDefault("name", firstHeading(body).orElse(defaultName));
        body = stripFirstHeading(body, name);

        return new Skill(
            name, meta.getOrDefault("description", ""), body, path.toString(),
            asList(meta.get("keywords")),
            asList(meta.get("agents")).stream().map(String::toLowerCase).toList(),
            asBool(meta.get("enabled"), true)
        );
    }

    // ── Front Matter 解析 ──────────────────────────────────────────

    private record SplitResult(Map<String, String> meta, String body) {}

    private SplitResult splitFrontMatter(String raw) {
        String text = raw.stripLeading();
        if (!text.startsWith("---")) return new SplitResult(Map.of(), raw);
        String[] lines = text.split("\n", -1);
        Map<String, String> meta = new LinkedHashMap<>();
        int endIdx = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.equals("---")) { endIdx = i; break; }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).strip();
                String value = line.substring(colon + 1).strip().replaceAll("^[\"']|[\"']$", "");
                meta.put(key, value);
            }
        }
        if (endIdx < 0) return new SplitResult(Map.of(), raw);
        String body = String.join("\n", Arrays.copyOfRange(lines, endIdx + 1, lines.length));
        return new SplitResult(meta, body);
    }

    private Optional<String> firstHeading(String body) {
        for (String line : body.split("\n")) {
            String s = line.strip();
            if (s.startsWith("#")) {
                String h = s.replaceAll("^#+\\s*", "").strip();
                return h.isEmpty() ? Optional.empty() : Optional.of(h);
            }
        }
        return Optional.empty();
    }

    private String stripFirstHeading(String body, String name) {
        String[] lines = body.split("\n", -1);
        if (lines.length == 0) return body;
        String first = lines[0].strip();
        if (first.startsWith("#") && first.replaceAll("^#+\\s*", "").strip().equals(name))
            return String.join("\n", Arrays.copyOfRange(lines, 1, lines.length)).strip();
        return body;
    }

    // ── 日志 ───────────────────────────────────────────────────────

    private void logLoadedSkills() {
        StringBuilder sb = new StringBuilder("\n================ DP-Plus Skills Loaded ================\n");
        sb.append("目录: ").append(rootDir).append("\n数量: ").append(skills.size()).append("\n");
        int i = 1;
        for (Skill s : skills) {
            sb.append(i++).append(". ").append(s.name()).append("\n");
            sb.append("   agents: ").append(s.agents().isEmpty() ? "all" : String.join(", ", s.agents())).append("\n");
            List<String> kw = s.keywords().size() > 8 ? new ArrayList<>(s.keywords().subList(0, 8)) : s.keywords();
            sb.append("   keywords: ").append(kw.isEmpty() ? "all" : String.join(", ", kw));
            if (s.keywords().size() > 8) sb.append(", ...");
            sb.append("\n   path: ").append(s.path()).append("\n");
        }
        if (skills.isEmpty()) sb.append("未加载任何 Skill。\n");
        if (!errors.isEmpty()) {
            sb.append("解析错误:\n");
            errors.forEach(e -> sb.append("  - ").append(e).append("\n"));
        }
        sb.append("========================================================\n");
        log.info(sb.toString());
    }

    // ── 工具方法 ───────────────────────────────────────────────────

    private List<String> asList(Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) return List.of();
        if (value instanceof List<?> list)
            return list.stream().map(Object::toString).map(String::strip).filter(s -> !s.isBlank()).toList();
        return Arrays.stream(value.toString().split(",")).map(String::strip).filter(s -> !s.isBlank()).toList();
    }

    private boolean asBool(Object value, boolean defaultVal) {
        if (value == null || value.toString().isBlank()) return defaultVal;
        if (value instanceof Boolean b) return b;
        String s = value.toString().strip().toLowerCase();
        return !Set.of("0", "false", "no", "off", "disabled").contains(s);
    }

    private String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max);
    }
}
