package com.mindbridge.agent.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis 实现的 Per-Agent 私有记忆。
 *
 * <p>Redis key 格式：{@code mindbridge:agent:mem:{agentName}:{sessionId}}
 * 严格按 Agent 名称和会话 ID 隔离，动态片段经过校验和规范化，防止 key 注入。</p>
 *
 * <p>compact 流程：当消息数超过 {@code compactThreshold} 时，用 LLM 生成历史摘要，
 * 替换旧消息为摘要 + 最近 {@code keepRecent} 条。摘要失败时保留原始近期数据，不破坏记忆。</p>
 *
 * <p>Redis 不可用时降级为无操作 + 结构化日志，不影响聊天主链路。</p>
 */
@Service
public class RedisAgentPrivateMemory implements AgentPrivateMemory {

    private static final Logger log = LoggerFactory.getLogger(RedisAgentPrivateMemory.class);
    private static final String KEY_PREFIX = "mindbridge:agent:mem:";
    private static final String SUMMARY_SUFFIX = ":summary";

    /** 合法 key 片段：仅允许字母、数字、下划线和连字符 */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AiClient aiClient;
    private final MindBridgeProperties properties;

    public RedisAgentPrivateMemory(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AiClient aiClient,
            MindBridgeProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.aiClient = aiClient;
        this.properties = properties;
    }

    @Override
    public void append(AgentName agentName, String sessionId, MemoryMessage message) {
        try {
            String key = memoryKey(agentName, sessionId);
            String value = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().rightPush(key, value);
            redisTemplate.expire(key, ttl());
        } catch (Exception e) {
            logStructured("append", agentName, "Redis private memory append skipped: {}", e.getMessage());
        }
    }

    @Override
    public List<MemoryMessage> recent(AgentName agentName, String sessionId) {
        try {
            String key = memoryKey(agentName, sessionId);
            List<String> values = redisTemplate.opsForList().range(key, 0, -1);
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream()
                    .map(this::readMessage)
                    .filter(m -> m != null)
                    .toList();
        } catch (Exception e) {
            logStructured("recent", agentName, "Redis private memory read skipped: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public String summarize(AgentName agentName, String sessionId) {
        try {
            String key = summaryKey(agentName, sessionId);
            String value = redisTemplate.opsForValue().get(key);
            return value == null ? "" : value;
        } catch (Exception e) {
            logStructured("summarize", agentName, "Redis private memory summary read skipped: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public void compact(AgentName agentName, String sessionId) {
        try {
            String key = memoryKey(agentName, sessionId);
            List<MemoryMessage> messages = recent(agentName, sessionId);
            int threshold = compactThreshold();

            if (messages.size() <= threshold) {
                return;
            }

            int keepRecent = keepRecentCount();
            List<MemoryMessage> toSummarize = messages.subList(0, messages.size() - keepRecent);
            List<MemoryMessage> recent = messages.subList(messages.size() - keepRecent, messages.size());

            String summary = generateSummary(agentName, toSummarize);
            if (summary == null || summary.isBlank()) {
                // 摘要失败：保留原始近期数据，不破坏记忆
                logStructured("compact", agentName,
                        "Summary generation failed, keeping original {} messages", messages.size());
                redisTemplate.opsForList().trim(key, -Math.min(messages.size(), threshold), -1);
                redisTemplate.expire(key, ttl());
                return;
            }

            // 合并已有摘要 + 新摘要
            String existingSummary = summarize(agentName, sessionId);
            String mergedSummary = existingSummary.isBlank()
                    ? summary
                    : existingSummary + "\n" + summary;

            // 写入摘要
            String sKey = summaryKey(agentName, sessionId);
            redisTemplate.opsForValue().set(sKey, mergedSummary, ttl());

            // 替换消息列表：只保留 recent 条
            redisTemplate.delete(key);
            for (MemoryMessage msg : recent) {
                redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(msg));
            }
            redisTemplate.expire(key, ttl());
        } catch (Exception e) {
            logStructured("compact", agentName, "Redis private memory compact skipped: {}", e.getMessage());
        }
    }

    private String generateSummary(AgentName agentName, List<MemoryMessage> messages) {
        if (messages.isEmpty()) return "";
        try {
            String historyText = messages.stream()
                    .map(m -> m.role() + ": " + m.content())
                    .reduce("", (a, b) -> a + "\n" + b);
            return aiClient.complete(List.of(
                    AiMessage.system("""
                            你是 MindBridge 的记忆压缩助手。
                            请将以下对话历史压缩为 2-3 条中文要点摘要，保留关键上下文信息。
                            不要输出风险等级或诊断结论。
                            """),
                    AiMessage.user("需要压缩的对话历史：\n" + historyText)
            )).trim();
        } catch (Exception e) {
            logStructured("generateSummary", agentName, "LLM summary failed: {}", e.getMessage());
            return "";
        }
    }

    // ────────────── Key 构建与校验 ──────────────

    String memoryKey(AgentName agentName, String sessionId) {
        return KEY_PREFIX + normalizeSegment(agentName.name()) + ":" + normalizeSegment(sessionId);
    }

    String summaryKey(AgentName agentName, String sessionId) {
        return memoryKey(agentName, sessionId) + SUMMARY_SUFFIX;
    }

    /**
     * 校验并规范化 key 片段，防止 key 注入。
     * 仅允许字母、数字、下划线和连字符，其他字符替换为下划线。
     */
    private String normalizeSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException("key segment must not be null or blank");
        }
        String normalized = segment.trim();
        if (!SAFE_SEGMENT.matcher(normalized).matches()) {
            // 替换不安全字符
            normalized = normalized.replaceAll("[^A-Za-z0-9_-]", "_");
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("key segment is empty after normalization: " + segment);
        }
        return normalized;
    }

    // ────────────── 配置 ──────────────

    private Duration ttl() {
        return Duration.ofHours(Math.max(1, properties.getChat().getPrivateMemoryTtlHours()));
    }

    private int compactThreshold() {
        return Math.max(4, properties.getChat().getPrivateMemoryCompactThreshold());
    }

    private int keepRecentCount() {
        return Math.max(2, properties.getChat().getPrivateMemoryKeepRecent());
    }

    // ────────────── 内部工具 ──────────────

    private MemoryMessage readMessage(String value) {
        try {
            return objectMapper.readValue(value, MemoryMessage.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void logStructured(String operation, AgentName agentName, String pattern, Object... args) {
        log.debug("[agent-private-memory] op={}, agent={}, {}", operation, agentName,
                org.slf4j.helpers.MessageFormatter.arrayFormat(pattern, args).getMessage());
    }
}