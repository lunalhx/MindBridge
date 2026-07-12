package com.mindbridge.agent.service.agent.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.EmotionLabel;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.PsychologyAssessment;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.agent.AgentAction;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentDecision;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.service.agent.blackboard.AgentArtifact;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CheckpointService} 单元测试。
 *
 * <p>覆盖：保存/恢复、完成清理、损坏数据、版本不兼容、TTL、Redis 失败、并发 runId 隔离。</p>
 */
class CheckpointServiceTest {

    private InMemoryCheckpointStore store;
    private MindBridgeProperties properties;
    private CheckpointService service;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        store = new InMemoryCheckpointStore();
        properties = new MindBridgeProperties();
        properties.getCheckpoint().setEnabled(true);
        properties.getCheckpoint().setTtlSeconds(3600);
        properties.getCheckpoint().setSchemaVersion(1);
        service = new CheckpointService(store, objectMapper, properties);
    }

    // ────────── 保存/恢复 ──────────

    @Test
    void saveAndLoadRoundTripShouldPreserveBlackboardAndFields() {
        AgentContext context = buildContextAfterMemoryAndSupervisor();
        String runId = "run123";
        service.saveCheckpoint(context, runId, "SEQUENTIAL", 0);

        Optional<CheckpointData> loaded = service.loadUnfinished("sess-1");

        assertThat(loaded).isPresent();
        CheckpointData data = loaded.get();
        assertThat(data.runId()).isEqualTo(runId);
        assertThat(data.sessionPublicId()).isEqualTo("sess-1");
        assertThat(data.runtimeMode()).isEqualTo("SEQUENTIAL");
        assertThat(data.schemaVersion()).isEqualTo(1);
        assertThat(data.status()).isEqualTo("RUNNING");
        assertThat(data.stepNumber()).isEqualTo(2);

        // Blackboard flags 恢复
        assertThat(data.blackboard().flags()).contains("MEMORY_LOADED", "INTENT_ROUTED");

        // intent artifact 恢复
        assertThat(data.blackboard().artifacts()).hasSize(1);
        var intentArtifact = data.blackboard().artifacts().get(0);
        assertThat(intentArtifact.name()).isEqualTo("intent");
        assertThat(intentArtifact.payloadType()).isEqualTo(CheckpointBlackboard.PAYLOAD_INTENT);

        // 非 Blackboard 字段
        assertThat(data.memoryBrief()).isEqualTo("用户画像：焦虑");
        assertThat(data.modelHistory()).hasSize(2);
        assertThat(data.riskLevel()).isEqualTo("LOW");

        // 步骤恢复
        assertThat(data.steps()).hasSize(2);
        assertThat(data.steps().get(0).agent()).isEqualTo("MEMORY_AGENT");
        assertThat(data.steps().get(1).agent()).isEqualTo("SUPERVISOR_AGENT");
    }

    @Test
    void loadShouldReturnEmptyWhenNoCheckpoint() {
        assertThat(service.loadUnfinished("sess-unknown")).isEmpty();
    }

    // ────────── 完成清理 ──────────

    @Test
    void deleteCheckpointShouldRemoveDataAndIndex() {
        AgentContext context = buildContextAfterMemoryAndSupervisor();
        service.saveCheckpoint(context, "run456", "SEQUENTIAL", 0);

        // checkpoint key 和 index key 都存在
        assertThat(store.keys()).contains(
                service.checkpointKey("sess-1", "run456"),
                service.indexKey("sess-1"));

        service.deleteCheckpoint("sess-1", "run456");

        // 两者都应被删除
        assertThat(store.keys()).doesNotContain(
                service.checkpointKey("sess-1", "run456"),
                service.indexKey("sess-1"));
    }

    @Test
    void deleteShouldOnlyRemoveIndexWhenItMatchesRunId() {
        // 保存 run-A
        service.saveCheckpoint(buildContextAfterMemoryAndSupervisor(), "runA", "SEQUENTIAL", 0);
        // 保存 run-B（覆盖索引指向 runB）
        service.saveCheckpoint(buildContextAfterMemoryAndSupervisor(), "runB", "SEQUENTIAL", 0);

        // 索引现在指向 runB
        assertThat(store.load(service.indexKey("sess-1"))).isEqualTo("runB");

        // 删除 runA：只应删除 runA 的数据，不应删除索引（因为索引指向 runB）
        service.deleteCheckpoint("sess-1", "runA");

        assertThat(store.keys()).doesNotContain(service.checkpointKey("sess-1", "runA"));
        // 索引仍然指向 runB
        assertThat(store.load(service.indexKey("sess-1"))).isEqualTo("runB");
        // runB 的数据仍然存在
        assertThat(store.keys()).contains(service.checkpointKey("sess-1", "runB"));
    }

    // ────────── 版本不兼容 ──────────

    @Test
    void loadShouldReturnEmptyWhenSchemaVersionMismatch() {
        properties.getCheckpoint().setSchemaVersion(2);

        AgentContext context = buildContextAfterMemoryAndSupervisor();
        // 手动构造 schemaVersion=1 的 checkpoint 存入
        CheckpointData data = CheckpointData.from(context, "run789", "SEQUENTIAL",
                1, 0, objectMapper);
        store.save(service.checkpointKey("sess-1", "run789"),
                objectMapper.valueToTree(data).toString(),
                Duration.ofSeconds(3600));
        store.save(service.indexKey("sess-1"), "run789", Duration.ofSeconds(3600));

        // 当前期望 schemaVersion=2，不兼容
        Optional<CheckpointData> loaded = service.loadUnfinished("sess-1");
        assertThat(loaded).isEmpty();
    }

    // ────────── 损坏数据 ──────────

    @Test
    void loadShouldReturnEmptyWhenDataIsCorrupted() {
        // 存入非法 JSON
        store.save(service.checkpointKey("sess-1", "runBroken"), "{{corrupted json", Duration.ofSeconds(3600));
        store.save(service.indexKey("sess-1"), "runBroken", Duration.ofSeconds(3600));

        Optional<CheckpointData> loaded = service.loadUnfinished("sess-1");
        assertThat(loaded).isEmpty();
    }

    // ────────── TTL ──────────

    @Test
    void loadShouldReturnEmptyWhenCheckpointExpired() {
        AgentContext context = buildContextAfterMemoryAndSupervisor();
        service.saveCheckpoint(context, "runExpired", "SEQUENTIAL", 0);

        // 手动让 key 过期
        store.expireKey(service.checkpointKey("sess-1", "runExpired"));

        Optional<CheckpointData> loaded = service.loadUnfinished("sess-1");
        // checkpoint 已过期，但索引可能还在；应安全返回 empty
        assertThat(loaded).isEmpty();
    }

    // ────────── Redis 失败 ──────────

    @Test
    void saveShouldNotThrowWhenRedisFails() {
        store.setFailure(new InMemoryCheckpointStore.RuntimeRuntimeException("Redis down"));

        // 不应抛异常
        service.saveCheckpoint(buildContextAfterMemoryAndSupervisor(), "runFail", "SEQUENTIAL", 0);
    }

    @Test
    void loadShouldReturnEmptyWhenRedisFails() {
        store.setFailure(new InMemoryCheckpointStore.RuntimeRuntimeException("Redis down"));

        assertThat(service.loadUnfinished("sess-1")).isEmpty();
    }

    @Test
    void deleteShouldNotThrowWhenRedisFails() {
        store.setFailure(new InMemoryCheckpointStore.RuntimeRuntimeException("Redis down"));

        // 不应抛异常
        service.deleteCheckpoint("sess-1", "runFail");
    }

    // ────────── 并发 runId 隔离 ──────────

    @Test
    void concurrentRunIdsShouldNotOverwriteEachOthersCheckpointData() {
        String runA = "runA_concurrent";
        String runB = "runB_concurrent";

        // runA 先保存
        AgentContext contextA = buildContextAfterMemoryAndSupervisor();
        contextA.setMemoryBrief("runA memory brief");
        service.saveCheckpoint(contextA, runA, "SEQUENTIAL", 0);

        // runB 后保存（会覆盖索引，但不覆盖 runA 的 checkpoint 数据）
        AgentContext contextB = buildContextAfterMemoryAndSupervisor();
        contextB.setMemoryBrief("runB memory brief");
        service.saveCheckpoint(contextB, runB, "SEQUENTIAL", 0);

        // 索引指向最新的 runB
        assertThat(store.load(service.indexKey("sess-1"))).isEqualTo(runB);

        // runA 的 checkpoint 数据仍然完整存在
        String jsonA = store.load(service.checkpointKey("sess-1", runA));
        assertThat(jsonA).isNotNull();
        assertThat(jsonA).contains("runA memory brief");

        // runB 的 checkpoint 数据也存在
        String jsonB = store.load(service.checkpointKey("sess-1", runB));
        assertThat(jsonB).isNotNull();
        assertThat(jsonB).contains("runB memory brief");

        // 两个 key 不同
        assertThat(service.checkpointKey("sess-1", runA))
                .isNotEqualTo(service.checkpointKey("sess-1", runB));
    }

    @Test
    void checkpointKeyShouldContainSessionAndRunId() {
        String key = service.checkpointKey("sess-1", "runA");
        assertThat(key).startsWith("mindbridge:checkpoint:");
        assertThat(key).contains("sess-1");
        assertThat(key).contains("runA");
    }

    @Test
    void indexKeyShouldBeScopedToSession() {
        assertThat(service.indexKey("sess-1"))
                .isEqualTo("mindbridge:checkpoint:index:sess-1");
        assertThat(service.indexKey("sess-2"))
                .isEqualTo("mindbridge:checkpoint:index:sess-2");
    }

    @Test
    void keySegmentsShouldBeSanitized() {
        String key = service.checkpointKey("sess with space", "run:colon");
        assertThat(key).doesNotContain(" ");
        assertThat(key).doesNotContain("run:colon");
    }

    // ────────── 状态过滤 ──────────

    @Test
    void loadShouldReturnEmptyWhenStatusIsNotRunning() {
        AgentContext context = buildContextAfterMemoryAndSupervisor();
        // 构造一个 COMPLETED 状态的 checkpoint
        CheckpointData data = CheckpointData.from(context, "runDone", "SEQUENTIAL", 1, 0, objectMapper);
        CheckpointData completed = new CheckpointData(
                data.schemaVersion(), data.runtimeMode(), data.runId(),
                data.userId(), data.sessionId(), data.sessionPublicId(),
                data.originalInput(), data.modelInput(), data.stepNumber(),
                data.round(), data.createdAt(), CheckpointData.STATUS_COMPLETED,
                data.blackboard(), data.steps(), data.previousHistory(),
                data.modelHistory(), data.memoryBrief(), data.knowledgeQuery(), data.riskLevel()
        );
        store.save(service.checkpointKey("sess-1", "runDone"),
                objectMapper.valueToTree(completed).toString(), Duration.ofSeconds(3600));
        store.save(service.indexKey("sess-1"), "runDone", Duration.ofSeconds(3600));

        assertThat(service.loadUnfinished("sess-1")).isEmpty();
    }

    // ────────── 默认配置 ──────────

    @Test
    void defaultCheckpointShouldBeDisabled() {
        assertThat(new MindBridgeProperties().getCheckpoint().isEnabled()).isFalse();
    }

    @Test
    void defaultTtlShouldBe3600Seconds() {
        assertThat(new MindBridgeProperties().getCheckpoint().getTtlSeconds()).isEqualTo(3600);
    }

    @Test
    void defaultSchemaVersionShouldBe1() {
        assertThat(new MindBridgeProperties().getCheckpoint().getSchemaVersion()).isEqualTo(1);
    }

    // ────────── Helpers ──────────

    private AgentContext buildContextAfterMemoryAndSupervisor() {
        UserAccount user = new UserAccount();
        user.setUsername("testuser");
        user.setDisplayName("Test");

        ChatSession session = new ChatSession();
        session.setPublicId("sess-1");
        session.setTitle("Test Session");

        AgentContext context = new AgentContext(user, session, "我最近很焦虑", "我最近很焦虑");

        // 模拟 MemoryAgent 执行
        context.setPreviousHistory(List.of(AiMessage.user("我最近很焦虑")));
        context.setModelHistory(List.of(
                AiMessage.assistant("你好"),
                AiMessage.user("我最近很焦虑")));
        context.setMemoryBrief("用户画像：焦虑");
        context.markMemoryLoaded();
        context.addStep(AgentStep.of(1, AgentName.MEMORY_AGENT,
                AgentDecision.continueWith(AgentAction.READ_MEMORY, "Redis loaded 2 messages")));

        // 模拟 SupervisorAgent 执行
        context.setIntent(IntentType.CONSULT);
        context.setResponseAgent(AgentName.COUNSELOR_AGENT);
        context.setRiskLevel(RiskLevel.LOW);
        context.markIntentRouted();
        context.addStep(AgentStep.of(2, AgentName.SUPERVISOR_AGENT,
                AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "intent=CONSULT")));

        return context;
    }
}
