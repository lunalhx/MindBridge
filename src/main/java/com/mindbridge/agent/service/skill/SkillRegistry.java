package com.mindbridge.agent.service.skill;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * 技能注册中心。
 *
 * <p>启动时从 {@code classpath:skills/*.md} 加载所有技能文件，解析 YAML frontmatter，
 * 验证字段完整性和合法性，提供 {@link #selectBy} 按意图、风险等级和输入关键词选择技能。</p>
 *
 * <p>加载失败时记录结构化错误日志，不泄漏用户数据；选择无匹配时返回空列表，保持现有 Prompt 行为。</p>
 */
@Service
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private static final String SKILLS_PATH = "classpath:skills/*.md";
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);
    private static final int MAX_FILE_SIZE = 32 * 1024; // 32KB
    private static final int DEFAULT_MAX_SKILLS = 3;
    private static final int DEFAULT_MAX_TOTAL_CHARS = 2000;

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final int maxSkills;
    private final int maxTotalChars;

    public SkillRegistry() {
        this(DEFAULT_MAX_SKILLS, DEFAULT_MAX_TOTAL_CHARS);
    }

    public SkillRegistry(int maxSkills, int maxTotalChars) {
        this.maxSkills = maxSkills;
        this.maxTotalChars = maxTotalChars;
        loadSkills();
    }

    // ────────────── 公共 API ──────────────

    /**
     * 按意图、风险等级和输入文本选择匹配的技能列表。
     *
     * <p>确定性排序：关键词匹配数降序，同分按技能名升序。
     * 结果数量不超过 maxSkills，总字符数不超过 maxTotalChars。</p>
     *
     * @param intent    当前意图
     * @param riskLevel 当前风险等级
     * @param input     用户输入文本（已脱敏）
     * @return 排序后的技能列表（可为空）
     */
    public List<Skill> selectBy(IntentType intent, RiskLevel riskLevel, String input) {
        if (intent == null || riskLevel == null || input == null || input.isBlank()) {
            return List.of();
        }
        String normalizedInput = input.toLowerCase(Locale.ROOT);

        List<Skill> candidates = new ArrayList<>();
        for (Skill skill : skills.values()) {
            if (!skill.intents().contains(intent)) continue;
            if (!skill.riskLevels().contains(riskLevel)) continue;
            int matchCount = countKeywordMatches(skill, normalizedInput);
            if (matchCount > 0 || skill.keywords().isEmpty()) {
                candidates.add(skill);
            }
        }

        candidates.sort(Comparator
                .comparingInt((Skill s) -> countKeywordMatches(s, normalizedInput)).reversed()
                .thenComparing(Skill::name));

        return applyLimits(candidates);
    }

    /**
     * 返回所有已加载的技能。
     */
    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    /**
     * 按名称获取技能。
     */
    public Skill get(String name) {
        return skills.get(name);
    }

    /**
     * 已加载技能数量。
     */
    public int size() {
        return skills.size();
    }

    // ────────────── 加载与解析 ──────────────

    private void loadSkills() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(SKILLS_PATH);
            for (Resource resource : resources) {
                try {
                    Skill skill = parseResource(resource);
                    if (skills.containsKey(skill.name())) {
                        log.error("[skill] Duplicate skill name '{}' in resource {}, already loaded from another file",
                                skill.name(), resource.getFilename());
                        throw new IllegalStateException(
                                "Duplicate skill name: " + skill.name() + " in " + resource.getFilename());
                    }
                    skills.put(skill.name(), skill);
                    log.info("[skill] Loaded skill '{}' from {}", skill.name(), resource.getFilename());
                } catch (Exception e) {
                    log.error("[skill] Failed to load skill from {}: {}", resource.getFilename(), e.getMessage());
                    throw new IllegalStateException(
                            "Skill load error in " + resource.getFilename() + ": " + e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            log.warn("[skill] No skills directory found or I/O error, skills disabled: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Skill parseResource(Resource resource) throws IOException {
        byte[] bytes = readResource(resource);
        if (bytes.length > MAX_FILE_SIZE) {
            throw new IllegalStateException(
                    "Skill file too large (> " + MAX_FILE_SIZE + " bytes): " + resource.getFilename());
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        Matcher matcher = FRONTMATTER_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "Missing YAML frontmatter (---) in: " + resource.getFilename());
        }

        String yamlText = matcher.group(1);
        String content = matcher.group(2).trim();
        if (content.isBlank()) {
            throw new IllegalStateException("Empty skill body in: " + resource.getFilename());
        }

        Map<String, Object> yaml = new Yaml().load(yamlText);
        if (yaml == null) {
            throw new IllegalStateException("Empty frontmatter in: " + resource.getFilename());
        }

        String name = requireString(yaml, "name", resource);
        String description = requireString(yaml, "description", resource);
        String version = optionalString(yaml, "version", "1.0");

        Set<IntentType> intents = parseIntents(yaml, resource);
        Set<RiskLevel> riskLevels = parseRiskLevels(yaml, resource);
        Set<String> keywords = parseKeywords(yaml, resource);

        return new Skill(name, description, intents, riskLevels, keywords, version, content);
    }

    private byte[] readResource(Resource resource) throws IOException {
        try (InputStream is = resource.getInputStream()) {
            return is.readAllBytes();
        }
    }

    private String requireString(Map<String, Object> yaml, String key, Resource resource) {
        Object value = yaml.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException(
                    "Missing required field '" + key + "' in: " + resource.getFilename());
        }
        return value.toString();
    }

    private String optionalString(Map<String, Object> yaml, String key, String defaultValue) {
        Object value = yaml.get(key);
        return value == null ? defaultValue : value.toString();
    }

    @SuppressWarnings("unchecked")
    private Set<IntentType> parseIntents(Map<String, Object> yaml, Resource resource) {
        Object value = yaml.get("intent");
        if (value == null) {
            throw new IllegalStateException(
                    "Missing required field 'intent' in: " + resource.getFilename());
        }
        List<String> rawList;
        if (value instanceof List) {
            rawList = (List<String>) value;
        } else {
            rawList = List.of(value.toString());
        }
        Set<IntentType> intents = new java.util.LinkedHashSet<>();
        for (String raw : rawList) {
            try {
                intents.add(IntentType.valueOf(raw.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Invalid intent '" + raw + "' in: " + resource.getFilename()
                                + ". Valid: CHAT, CONSULT, RISK");
            }
        }
        if (intents.isEmpty()) {
            throw new IllegalStateException("No valid intent specified in: " + resource.getFilename());
        }
        return intents;
    }

    @SuppressWarnings("unchecked")
    private Set<RiskLevel> parseRiskLevels(Map<String, Object> yaml, Resource resource) {
        Object value = yaml.get("riskLevel");
        if (value == null) {
            throw new IllegalStateException(
                    "Missing required field 'riskLevel' in: " + resource.getFilename());
        }
        List<String> rawList;
        if (value instanceof List) {
            rawList = (List<String>) value;
        } else {
            rawList = List.of(value.toString());
        }
        Set<RiskLevel> levels = new java.util.LinkedHashSet<>();
        for (String raw : rawList) {
            try {
                levels.add(RiskLevel.valueOf(raw.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Invalid riskLevel '" + raw + "' in: " + resource.getFilename()
                                + ". Valid: LOW, MEDIUM, HIGH");
            }
        }
        if (levels.isEmpty()) {
            throw new IllegalStateException("No valid riskLevel specified in: " + resource.getFilename());
        }
        return levels;
    }

    @SuppressWarnings("unchecked")
    private Set<String> parseKeywords(Map<String, Object> yaml, Resource resource) {
        Object value = yaml.get("keywords");
        if (value == null) {
            return Set.of();
        }
        List<String> rawList;
        if (value instanceof List) {
            rawList = (List<String>) value;
        } else {
            rawList = List.of(value.toString());
        }
        Set<String> keywords = new java.util.LinkedHashSet<>();
        for (String kw : rawList) {
            if (kw != null && !kw.isBlank()) {
                keywords.add(kw.trim().toLowerCase());
            }
        }
        return keywords;
    }

    // ────────────── 选择逻辑 ──────────────

    private int countKeywordMatches(Skill skill, String normalizedInput) {
        int count = 0;
        for (String keyword : skill.keywords()) {
            if (normalizedInput.contains(keyword)) {
                count++;
            }
        }
        return count;
    }

    private List<Skill> applyLimits(List<Skill> candidates) {
        List<Skill> result = new ArrayList<>();
        int totalChars = 0;
        for (Skill skill : candidates) {
            if (result.size() >= maxSkills) break;
            int skillChars = skill.content().length();
            if (totalChars + skillChars > maxTotalChars) break;
            result.add(skill);
            totalChars += skillChars;
        }
        return result;
    }
}