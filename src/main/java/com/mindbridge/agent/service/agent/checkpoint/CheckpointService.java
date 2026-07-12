package com.mindbridge.agent.service.agent.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.agent.AgentContext;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Checkpoint 保存/恢复服务。
 *
 * <p>负责将可恢复的 {@link AgentContext}/{@link com.mindbridge.agent.service.agent.blackboard.AgentBlackboard}
 * 序列化为 JSON 并存入 Redis（通过 {@link CheckpointStore}）。</p>
 *
 * <p><b>Key 设计：</b>
 * <ul>
 *   <li>Checkpoint key: {@code mindbridge:checkpoint:{sessionPublicId}:{runId}}
 *       —— 包含 sessionId 和本轮唯一 runId，避免同一会话并发请求互相覆盖</li>
 *   <li>Index key: {@code mindbridge:checkpoint:index:{sessionPublicId}:{inputFingerprint}}
 *       —— 指向当前未完成 run 的 runId，用于快速查找。
 *       索引按 sessionId + 输入指纹隔离，避免同一会话不同输入的并发请求互相覆盖。</li>
 * </ul></p>
 *
 * <p><b>安全降级：</b>Redis 不可用、数据损坏、版本不兼容或 checkpoint 过期时，
 * 安全地从新运行开始，并记录结构化日志。</p>
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);
    private static final String KEY_PREFIX = "mindbridge:checkpoint:";
    private static final String INDEX_PREFIX = KEY_PREFIX + "index:";
    /** 合法 key 片段：仅允许字母、数字、下划线和连字符 */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final CheckpointStore store;
    private final ObjectMapper objectMapper;
    private final MindBridgeProperties properties;

    public CheckpointService(CheckpointStore store, ObjectMapper objectMapper,
                             MindBridgeProperties properties) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    // ────────────── 保存 ──────────────

    /**
     * 保存 checkpoint 到 Redis 并更新索引。
     *
     * <p>每个成功 Agent 步骤后调用。失败时静默跳过并记录日志，不影响主链路。</p>
     */
    public void saveCheckpoint(AgentContext context, String runId, String runtimeMode, int round) {
        try {
            CheckpointData data = CheckpointData.from(context, runId, runtimeMode,
                    properties.getCheckpoint().getSchemaVersion(), round, objectMapper);
            String sessionPublicId = data.sessionPublicId();
            if (sessionPublicId == null || sessionPublicId.isBlank()) {
                log.debug("[checkpoint] save skipped: sessionPublicId is null");
                return;
            }
            String inputFingerprint = data.inputFingerprint();
            String json = objectMapper.writeValueAsString(data);
            String key = checkpointKey(sessionPublicId, runId);
            store.save(key, json, ttl());
            store.save(indexKey(sessionPublicId, inputFingerprint), runId, ttl());
            log.debug("[checkpoint] saved: session={}, runId={}, step={}",
                    sessionPublicId, runId, data.stepNumber());
        } catch (Exception e) {
            log.debug("[checkpoint] save failed: runId={}, error={}", runId, e.getMessage());
        }
    }

    // ────────────── 恢复 ──────────────

    /**
     * 查找并加载当前未完成的 checkpoint。
     *
     * <p>通过 index key（按 sessionId + 输入指纹隔离）找到当前未完成 run 的 runId，
     * 再加载对应的 checkpoint 数据。仅返回兼容版本且状态为 RUNNING 的 checkpoint。</p>
     */
    public Optional<CheckpointData> loadUnfinished(String sessionPublicId, String inputFingerprint) {
        try {
            if (inputFingerprint == null || inputFingerprint.isBlank()) {
                return Optional.empty();
            }
            String runId = store.load(indexKey(sessionPublicId, inputFingerprint));
            if (runId == null || runId.isBlank()) {
                return Optional.empty();
            }
            String json = store.load(checkpointKey(sessionPublicId, runId));
            if (json == null || json.isBlank()) {
                // checkpoint 已过期或被删除，清理残留索引
                store.delete(indexKey(sessionPublicId, inputFingerprint));
                return Optional.empty();
            }
            CheckpointData data = objectMapper.readValue(json, CheckpointData.class);
            // 版本不兼容时不恢复
            if (data.schemaVersion() != properties.getCheckpoint().getSchemaVersion()) {
                log.info("[checkpoint] schemaVersion mismatch: stored={}, expected={}, starting fresh",
                        data.schemaVersion(), properties.getCheckpoint().getSchemaVersion());
                return Optional.empty();
            }
            // 仅恢复 RUNNING 状态的 checkpoint
            if (!CheckpointData.STATUS_RUNNING.equals(data.status())) {
                log.debug("[checkpoint] status not RUNNING: {}, starting fresh", data.status());
                return Optional.empty();
            }
            return Optional.of(data);
        } catch (Exception e) {
            log.info("[checkpoint] load failed, starting fresh: session={}, error={}",
                    sessionPublicId, e.getMessage());
            return Optional.empty();
        }
    }

    // ────────────── 删除 ──────────────

    /**
     * 完成后删除 checkpoint 和索引。
     *
     * <p>仅当索引指向当前 runId 时才删除索引，避免删除并发运行的其他 run 的索引。</p>
     */
    public void deleteCheckpoint(String sessionPublicId, String runId, String inputFingerprint) {
        try {
            store.delete(checkpointKey(sessionPublicId, runId));
            if (inputFingerprint != null && !inputFingerprint.isBlank()) {
                String currentIndexed = store.load(indexKey(sessionPublicId, inputFingerprint));
                if (runId.equals(currentIndexed)) {
                    store.delete(indexKey(sessionPublicId, inputFingerprint));
                }
            }
        } catch (Exception e) {
            log.debug("[checkpoint] delete failed: session={}, runId={}, error={}",
                    sessionPublicId, runId, e.getMessage());
        }
    }

    // ────────────── Key 构建 ──────────────

    String checkpointKey(String sessionPublicId, String runId) {
        return KEY_PREFIX + normalizeSegment(sessionPublicId) + ":" + normalizeSegment(runId);
    }

    String indexKey(String sessionPublicId, String inputFingerprint) {
        return INDEX_PREFIX + normalizeSegment(sessionPublicId) + ":" + normalizeSegment(inputFingerprint);
    }

    /**
     * 校验并规范化 key 片段，防止 key 注入。
     */
    private String normalizeSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException("key segment must not be null or blank");
        }
        String normalized = segment.trim();
        if (!SAFE_SEGMENT.matcher(normalized).matches()) {
            normalized = normalized.replaceAll("[^A-Za-z0-9_-]", "_");
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("key segment is empty after normalization: " + segment);
        }
        return normalized;
    }

    private Duration ttl() {
        return Duration.ofSeconds(Math.max(60, properties.getCheckpoint().getTtlSeconds()));
    }

    /** 暴露 ObjectMapper 供反序列化 Blackboard 时使用。 */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
