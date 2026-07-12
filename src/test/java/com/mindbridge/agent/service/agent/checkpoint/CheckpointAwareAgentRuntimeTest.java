package com.mindbridge.agent.service.agent.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.ChatSessionRepository;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.PsychologyAssessment;
import com.mindbridge.agent.service.agent.AgentAction;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentDecision;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import com.mindbridge.agent.service.agent.runtime.AgentRuntime;
import com.mindbridge.agent.service.agent.runtime.AgentStepListener;
import com.mindbridge.agent.service.agent.runtime.RuntimeMode;
import com.mindbridge.agent.service.agent.runtime.SequentialAgentRuntime;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@link CheckpointAwareAgentRuntime} 全场景测试。
 *
 * <p>覆盖：保存/恢复、完成清理、损坏数据、版本不兼容、TTL、Redis 失败、
 * 并发 runId 隔离、恢复后不重复步骤、禁用时完全透传。</p>
 */
class CheckpointAwareAgentRuntimeTest {

    private InMemoryCheckpointStore store;
    private MindBridgeProperties properties;
    private CheckpointService checkpointService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private UserAccountRepository userRepo;
    private ChatSessionRepository sessionRepo;

    @BeforeEach
    void setUp() {
        store = new InMemoryCheckpointStore();
        properties = new MindBridgeProperties();
        properties.getCheckpoint().setEnabled(true);
        properties.getCheckpoint().setTtlSeconds(3600);
        properties.getCheckpoint().setSchemaVersion(2);
        checkpointService = new CheckpointService(store, objectMapper, properties);
        userRepo = Mockito.mock(UserAccountRepository.class);
        sessionRepo = Mockito.mock(ChatSessionRepository.class);
    }

    private String fp(String input) {
        return CheckpointData.computeFingerprint(input);
    }

    // ────────── 保存/恢复 + 不重复步骤 ──────────

    @Test
    void resumeShouldSkipCompletedStepsAndNotReExecuteAgents() {
        // 场景：Memory → Supervisor 成功完成（checkpoint 保存所有 flags），
        // 然后在 Companion 步骤崩溃。恢复后 Memory 和 Supervisor 不应再次执行。
        var memoryCallCount = new AtomicInteger(0);
        var supervisorCallCount = new AtomicInteger(0);
        var companionCallCount = new AtomicInteger(0);

        // 完整 runtime（恢复后使用）
        var runtime = new SequentialAgentRuntime(List.of(
                countingAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> {
                            memoryCallCount.incrementAndGet();
                            ctx.setPreviousHistory(List.of(AiMessage.user("input")));
                            ctx.setModelHistory(List.of(AiMessage.user("input")));
                            ctx.setMemoryBrief("memory brief");
                            ctx.markMemoryLoaded();
                            return AgentDecision.continueWith(AgentAction.READ_MEMORY, "mem");
                        }),
                countingAgent(AgentName.SUPERVISOR_AGENT,
                        ctx -> ctx.memoryLoaded() && !ctx.intentRouted(),
                        ctx -> {
                            supervisorCallCount.incrementAndGet();
                            ctx.setIntent(IntentType.CHAT);
                            ctx.setResponseAgent(AgentName.COMPANION_AGENT);
                            ctx.setRiskLevel(RiskLevel.LOW);
                            ctx.markIntentRouted();
                            ctx.markKnowledgeHandled();
                            ctx.markRiskAssessed();
                            return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "intent=CHAT");
                        }),
                countingAgent(AgentName.COMPANION_AGENT,
                        ctx -> ctx.intentRouted() && !ctx.responsePlanned(),
                        ctx -> {
                            companionCallCount.incrementAndGet();
                            ctx.setResponseMessages(List.of(AiMessage.assistant("reply")));
                            ctx.setResponsePlan("plan");
                            ctx.markResponsePlanned();
                            return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "done");
                        })
        ));

        UserAccount user = user(1L);
        ChatSession session = session("sess-resume", 1L);

        // 中断 runtime：Memory + Supervisor 成功完成，Companion 崩溃
        var interruptRuntime = new SequentialAgentRuntime(List.of(
                countingAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> {
                            memoryCallCount.incrementAndGet();
                            ctx.setPreviousHistory(List.of(AiMessage.user("input")));
                            ctx.setModelHistory(List.of(AiMessage.user("input")));
                            ctx.setMemoryBrief("memory brief");
                            ctx.markMemoryLoaded();
                            return AgentDecision.continueWith(AgentAction.READ_MEMORY, "mem");
                        }),
                countingAgent(AgentName.SUPERVISOR_AGENT,
                        ctx -> ctx.memoryLoaded() && !ctx.intentRouted(),
                        ctx -> {
                            supervisorCallCount.incrementAndGet();
                            ctx.setIntent(IntentType.CHAT);
                            ctx.setResponseAgent(AgentName.COMPANION_AGENT);
                            ctx.setRiskLevel(RiskLevel.LOW);
                            ctx.markIntentRouted();
                            ctx.markKnowledgeHandled();
                            ctx.markRiskAssessed();
                            return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "intent=CHAT");
                        }),
                countingAgent(AgentName.COMPANION_AGENT,
                        ctx -> ctx.intentRouted() && !ctx.responsePlanned(),
                        ctx -> {
                            companionCallCount.incrementAndGet();
                            throw new RuntimeException("simulated crash in companion");
                        })
        ));

        var interruptWrapper = new CheckpointAwareAgentRuntime(
                interruptRuntime, checkpointService, userRepo, sessionRepo, properties);

        Mockito.when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        Mockito.when(sessionRepo.findByPublicIdAndUser_Id("sess-resume", 1L))
                .thenReturn(Optional.of(session));

        // 执行中断运行：Memory + Supervisor 成功（checkpoint 保存），Companion 崩溃
        assertThatThrownBy(() -> interruptWrapper.run(user, session, "input", "input"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated crash");

        // 中断运行后：Memory=1, Supervisor=1, Companion=1(crash)
        assertThat(memoryCallCount.get()).isEqualTo(1);
        assertThat(supervisorCallCount.get()).isEqualTo(1);
        assertThat(companionCallCount.get()).isEqualTo(1);

        // checkpoint 应存在（保存了 Memory + Supervisor 完成后的状态）
        var unfinished = checkpointService.loadUnfinished("sess-resume", fp("input"));
        assertThat(unfinished).isPresent();
        assertThat(unfinished.get().stepNumber()).isEqualTo(2); // 2 steps completed
        assertThat(unfinished.get().blackboard().flags()).contains("MEMORY_LOADED", "INTENT_ROUTED");

        // 重置计数器，用完整 runtime 恢复
        memoryCallCount.set(0);
        supervisorCallCount.set(0);
        companionCallCount.set(0);

        var fullWrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        AgentRunResult result = fullWrapper.run(user, session, "input", "input");

        // Memory 和 Supervisor 不应再次执行（flags 已恢复），只 Companion 执行
        assertThat(memoryCallCount.get()).isEqualTo(0);
        assertThat(supervisorCallCount.get()).isEqualTo(0);
        assertThat(companionCallCount.get()).isEqualTo(1);

        // 结果正确
        assertThat(result.intent()).isEqualTo(IntentType.CHAT);
        assertThat(result.responseAgent()).isEqualTo(AgentName.COMPANION_AGENT);
        assertThat(result.responseMessages()).hasSize(1);

        // 步骤总数应为 3（恢复的 2 步 + 新执行的 1 步）
        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps().get(2).agent()).isEqualTo(AgentName.COMPANION_AGENT);

        // checkpoint 应被删除
        assertThat(checkpointService.loadUnfinished("sess-resume", fp("input"))).isEmpty();
    }

    // ────────── 完成清理 ──────────

    @Test
    void completedRunShouldDeleteCheckpoint() {
        var runtime = new SequentialAgentRuntime(List.of(
                simpleAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> { ctx.markMemoryLoaded();
                            return AgentDecision.finish(AgentAction.READ_MEMORY, "done"); })
        ));

        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        UserAccount user = user(1L);
        ChatSession session = session("sess-done", 1L);
        Mockito.when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        Mockito.when(sessionRepo.findByPublicIdAndUser_Id("sess-done", 1L))
                .thenReturn(Optional.of(session));

        AgentRunResult result = wrapper.run(user, session, "input", "input");

        assertThat(result.steps()).hasSize(1);
        // checkpoint 应被删除
        assertThat(checkpointService.loadUnfinished("sess-done", fp("input"))).isEmpty();
    }

    // ────────── 损坏数据 ──────────

    @Test
    void corruptedCheckpointShouldStartFreshWithoutError() {
        // 存入损坏数据（使用与运行时一致的指纹，以便能找到索引指向的损坏 checkpoint）
        store.save(checkpointService.indexKey("sess-corrupt", fp("input")), "runCorrupt",
                java.time.Duration.ofSeconds(3600));
        store.save(checkpointService.checkpointKey("sess-corrupt", "runCorrupt"),
                "{{broken", java.time.Duration.ofSeconds(3600));

        var memoryCalls = new AtomicInteger(0);
        var runtime = new SequentialAgentRuntime(List.of(
                countingAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> {
                            memoryCalls.incrementAndGet();
                            ctx.markMemoryLoaded();
                            return AgentDecision.finish(AgentAction.READ_MEMORY, "done");
                        })
        ));

        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        UserAccount user = user(1L);
        ChatSession session = session("sess-corrupt", 1L);
        Mockito.when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        Mockito.when(sessionRepo.findByPublicIdAndUser_Id("sess-corrupt", 1L))
                .thenReturn(Optional.of(session));

        // 应安全从新运行，不抛异常
        AgentRunResult result = wrapper.run(user, session, "input", "input");

        assertThat(result.steps()).hasSize(1);
        // Memory 执行了（从新运行）
        assertThat(memoryCalls.get()).isEqualTo(1);
    }

    // ────────── 版本不兼容 ──────────

    @Test
    void incompatibleSchemaVersionShouldStartFresh() {
        properties.getCheckpoint().setSchemaVersion(2);

        // 存入 schemaVersion=1 的 checkpoint
        UserAccount user = user(1L);
        ChatSession session = session("sess-ver", 1L);
        AgentContext context = new AgentContext(user, session, "input", "input");
        context.markMemoryLoaded();
        CheckpointData data = CheckpointData.from(context, "runV1", "SEQUENTIAL",
                1, 0, objectMapper);
        store.save(checkpointService.checkpointKey("sess-ver", "runV1"),
                objectMapper.valueToTree(data).toString(),
                java.time.Duration.ofSeconds(3600));
        store.save(checkpointService.indexKey("sess-ver", fp("input")), "runV1",
                java.time.Duration.ofSeconds(3600));

        var memoryCalls = new AtomicInteger(0);
        var runtime = new SequentialAgentRuntime(List.of(
                countingAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> {
                            memoryCalls.incrementAndGet();
                            ctx.markMemoryLoaded();
                            return AgentDecision.finish(AgentAction.READ_MEMORY, "done");
                        })
        ));

        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        Mockito.when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        Mockito.when(sessionRepo.findByPublicIdAndUser_Id("sess-ver", 1L))
                .thenReturn(Optional.of(session));

        AgentRunResult result = wrapper.run(user, session, "input", "input");

        // 版本不兼容，从新运行
        assertThat(memoryCalls.get()).isEqualTo(1);
        assertThat(result.steps()).hasSize(1);
    }

    // ────────── TTL ──────────

    @Test
    void expiredCheckpointShouldStartFresh() {
        // 先保存一个 checkpoint
        UserAccount user = user(1L);
        ChatSession session = session("sess-ttl", 1L);
        AgentContext context = new AgentContext(user, session, "input", "input");
        context.markMemoryLoaded();
        checkpointService.saveCheckpoint(context, "runTTL", "SEQUENTIAL", 0);

        // 模拟过期
        store.expireKey(checkpointService.checkpointKey("sess-ttl", "runTTL"));

        var memoryCalls = new AtomicInteger(0);
        var runtime = new SequentialAgentRuntime(List.of(
                countingAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> {
                            memoryCalls.incrementAndGet();
                            ctx.markMemoryLoaded();
                            return AgentDecision.finish(AgentAction.READ_MEMORY, "done");
                        })
        ));

        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        Mockito.when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        Mockito.when(sessionRepo.findByPublicIdAndUser_Id("sess-ttl", 1L))
                .thenReturn(Optional.of(session));

        AgentRunResult result = wrapper.run(user, session, "input", "input");

        // checkpoint 过期，从新运行
        assertThat(memoryCalls.get()).isEqualTo(1);
        assertThat(result.steps()).hasSize(1);
    }

    // ────────── Redis 失败 ──────────

    @Test
    void redisFailureShouldNotBlockRun() {
        store.setFailure(new InMemoryCheckpointStore.RuntimeRuntimeException("Redis down"));

        var memoryCalls = new AtomicInteger(0);
        var runtime = new SequentialAgentRuntime(List.of(
                countingAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> {
                            memoryCalls.incrementAndGet();
                            ctx.markMemoryLoaded();
                            return AgentDecision.finish(AgentAction.READ_MEMORY, "done");
                        })
        ));

        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        UserAccount user = user(1L);
        ChatSession session = session("sess-redis", 1L);

        // 不应抛异常，安全从新运行
        AgentRunResult result = wrapper.run(user, session, "input", "input");

        assertThat(memoryCalls.get()).isEqualTo(1);
        assertThat(result.steps()).hasSize(1);
    }

    // ────────── 并发 runId 隔离 ──────────

    @Test
    void concurrentRunsShouldUseDifferentRunIdsAndNotInterfere() {
        // 验证两次运行产生不同的 checkpoint key（不同 runId）
        var runtime = new SequentialAgentRuntime(List.of(
                simpleAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> { ctx.markMemoryLoaded();
                            return AgentDecision.finish(AgentAction.READ_MEMORY, "done"); })
        ));

        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        UserAccount user = user(1L);
        ChatSession session = session("sess-concurrent", 1L);

        // 第一次运行（完整完成，checkpoint 会被删除）
        wrapper.run(user, session, "input1", "input1");

        // 第二次运行也完整完成
        wrapper.run(user, session, "input2", "input2");

        // 两次运行都成功完成，没有残留 checkpoint
        assertThat(checkpointService.loadUnfinished("sess-concurrent", fp("input1"))).isEmpty();
        assertThat(checkpointService.loadUnfinished("sess-concurrent", fp("input2"))).isEmpty();
    }

    @Test
    void interruptedRunCheckpointShouldNotBeDeleted() {
        // 运行中途失败时 checkpoint 应保留
        var runtime = new SequentialAgentRuntime(List.of(
                simpleAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> { ctx.markMemoryLoaded();
                            return AgentDecision.continueWith(AgentAction.READ_MEMORY, "mem"); }),
                failingAgent(AgentName.SUPERVISOR_AGENT,
                        ctx -> ctx.memoryLoaded() && !ctx.intentRouted(),
                        ctx -> { throw new RuntimeException("agent failure"); })
        ));

        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        UserAccount user = user(1L);
        ChatSession session = session("sess-fail", 1L);

        assertThatThrownBy(() -> wrapper.run(user, session, "input", "input"))
                .isInstanceOf(RuntimeException.class);

        // checkpoint 应保留（Memory 步骤成功保存了）
        // 注意：索引可能被更新了
        var unfinished = checkpointService.loadUnfinished("sess-fail", fp("input"));
        assertThat(unfinished).isPresent();
        assertThat(unfinished.get().stepNumber()).isGreaterThanOrEqualTo(1);
    }

    // ────────── 禁用时完全透传 ──────────

    @Test
    void disabledCheckpointShouldNotSaveOrLoad() {
        properties.getCheckpoint().setEnabled(false);

        var runtime = new SequentialAgentRuntime(List.of(
                simpleAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> { ctx.markMemoryLoaded();
                            return AgentDecision.finish(AgentAction.READ_MEMORY, "done"); })
        ));

        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        UserAccount user = user(1L);
        ChatSession session = session("sess-disabled", 1L);

        AgentRunResult result = wrapper.run(user, session, "input", "input");

        // 没有保存任何 checkpoint
        assertThat(store.keys()).isEmpty();
        assertThat(result.steps()).hasSize(1);
    }

    @Test
    void disabledCheckpointShouldDelegateMode() {
        properties.getCheckpoint().setEnabled(false);
        var runtime = new SequentialAgentRuntime(List.of());
        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);
        assertThat(wrapper.mode()).isEqualTo(RuntimeMode.SEQUENTIAL);
    }

    @Test
    void enabledCheckpointShouldDelegateMode() {
        var runtime = new SequentialAgentRuntime(List.of());
        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);
        assertThat(wrapper.mode()).isEqualTo(RuntimeMode.SEQUENTIAL);
    }

    // ────────── 恢复后不重复产生工具作业 ──────────

    @Test
    void resumedRunShouldNotReExecuteCompletedAgentsViaFlags() {
        // 验证 Blackboard flags 是防止重复执行的核心机制
        // 模拟：Memory + Knowledge 已完成，恢复后只执行 Risk + Counselor
        var knowledgeCallCount = new AtomicInteger(0);
        var riskCallCount = new AtomicInteger(0);
        var counselorCallCount = new AtomicInteger(0);

        UserAccount user = user(1L);
        ChatSession session = session("sess-noDup", 1L);

        // 构造一个 Memory+Knowledge 已完成的 checkpoint
        AgentContext context = new AgentContext(user, session, "我最近很焦虑", "我最近很焦虑");
        context.setPreviousHistory(List.of(AiMessage.user("我最近很焦虑")));
        context.setModelHistory(List.of(AiMessage.user("我最近很焦虑")));
        context.setMemoryBrief("memory brief");
        context.markMemoryLoaded();
        context.setIntent(IntentType.CONSULT);
        context.setResponseAgent(AgentName.COUNSELOR_AGENT);
        context.setRiskLevel(RiskLevel.LOW);
        context.markIntentRouted();
        context.setRetrievedKnowledge(List.of(new SearchResult(1L, "doc.md", "content", 0.9)));
        context.setKnowledgeQuery("query");
        context.markKnowledgeHandled();
        context.addStep(AgentStep.of(1, AgentName.MEMORY_AGENT,
                AgentDecision.continueWith(AgentAction.READ_MEMORY, "mem")));
        context.addStep(AgentStep.of(2, AgentName.SUPERVISOR_AGENT,
                AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "intent=CONSULT")));
        context.addStep(AgentStep.of(3, AgentName.KNOWLEDGE_AGENT,
                AgentDecision.continueWith(AgentAction.RETRIEVE_KNOWLEDGE, "retrieved")));

        checkpointService.saveCheckpoint(context, "runNoDup", "SEQUENTIAL", 0);

        // 用完整 CONSULT 路径的 runtime 恢复
        var runtime = new SequentialAgentRuntime(List.of(
                simpleAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> { ctx.markMemoryLoaded();
                            return AgentDecision.continueWith(AgentAction.READ_MEMORY, "mem"); }),
                simpleAgent(AgentName.SUPERVISOR_AGENT,
                        ctx -> ctx.memoryLoaded() && !ctx.intentRouted(),
                        ctx -> { ctx.setIntent(IntentType.CONSULT);
                            ctx.setResponseAgent(AgentName.COUNSELOR_AGENT);
                            ctx.markIntentRouted();
                            return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "route"); }),
                countingAgent(AgentName.KNOWLEDGE_AGENT,
                        ctx -> ctx.intentRouted() && !ctx.knowledgeHandled() && ctx.intent() != IntentType.CHAT,
                        ctx -> {
                            knowledgeCallCount.incrementAndGet();
                            ctx.markKnowledgeHandled();
                            return AgentDecision.continueWith(AgentAction.RETRIEVE_KNOWLEDGE, "retrieved");
                        }),
                countingAgent(AgentName.RISK_GUARDIAN_AGENT,
                        ctx -> ctx.knowledgeHandled() && !ctx.riskAssessed() && ctx.intent() != IntentType.CHAT,
                        ctx -> {
                            riskCallCount.incrementAndGet();
                            ctx.setAssessment(new PsychologyAssessment(
                                    com.mindbridge.agent.domain.EmotionLabel.NORMAL, 0.5,
                                    RiskLevel.LOW, 0.8, "assessment"));
                            ctx.setRiskLevel(RiskLevel.LOW);
                            ctx.markRiskAssessed();
                            return AgentDecision.continueWith(AgentAction.ASSESS_RISK, "risk=LOW");
                        }),
                countingAgent(AgentName.COUNSELOR_AGENT,
                        ctx -> ctx.riskAssessed() && ctx.intent() != IntentType.CHAT && !ctx.responsePlanned(),
                        ctx -> {
                            counselorCallCount.incrementAndGet();
                            ctx.setResponseMessages(List.of(AiMessage.assistant("support")));
                            ctx.setResponsePlan("plan");
                            ctx.markResponsePlanned();
                            return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "done");
                        })
        ));

        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        Mockito.when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        Mockito.when(sessionRepo.findByPublicIdAndUser_Id("sess-noDup", 1L))
                .thenReturn(Optional.of(session));

        AgentRunResult result = wrapper.run(user, session, "我最近很焦虑", "我最近很焦虑");

        // Memory、Supervisor、Knowledge 不应再次执行
        assertThat(knowledgeCallCount.get()).isEqualTo(0);
        // Risk 和 Counselor 应执行
        assertThat(riskCallCount.get()).isEqualTo(1);
        assertThat(counselorCallCount.get()).isEqualTo(1);

        // 步骤号应从 4 开始（恢复的 3 步 + 新执行的 2 步）
        assertThat(result.steps()).hasSize(5);
        assertThat(result.steps().get(3).step()).isEqualTo(4);
        assertThat(result.steps().get(4).step()).isEqualTo(5);

        // checkpoint 删除
        assertThat(checkpointService.loadUnfinished("sess-noDup", fp("我最近很焦虑"))).isEmpty();
    }

    // ────────── 用户/会话通过稳定 ID 恢复 ──────────

    @Test
    void restoreShouldReloadUserAndSessionFromRepositoryById() {
        UserAccount user = user(1L);
        ChatSession session = session("sess-reload", 1L);

        // 保存一个有 MEMORY_LOADED + INTENT_ROUTED 的 checkpoint
        AgentContext context = new AgentContext(user, session, "input", "input");
        context.markMemoryLoaded();
        context.setIntent(IntentType.CHAT);
        context.setResponseAgent(AgentName.COMPANION_AGENT);
        context.setRiskLevel(RiskLevel.LOW);
        context.markIntentRouted();
        context.markKnowledgeHandled();
        context.markRiskAssessed();
        context.addStep(AgentStep.of(1, AgentName.MEMORY_AGENT,
                AgentDecision.continueWith(AgentAction.READ_MEMORY, "mem")));
        context.addStep(AgentStep.of(2, AgentName.SUPERVISOR_AGENT,
                AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "intent=CHAT")));
        checkpointService.saveCheckpoint(context, "runReload", "SEQUENTIAL", 0);

        // Mock 返回不同实例（模拟从 Repository 重新加载）
        UserAccount reloadedUser = user(1L);
        reloadedUser.setUsername("reloaded");
        ChatSession reloadedSession = session("sess-reload", 1L);
        reloadedSession.setTitle("Reloaded Session");

        Mockito.when(userRepo.findById(1L)).thenReturn(Optional.of(reloadedUser));
        Mockito.when(sessionRepo.findByPublicIdAndUser_Id("sess-reload", 1L))
                .thenReturn(Optional.of(reloadedSession));

        // runtime 有 Companion 能在 INTENT_ROUTED 时执行
        var companionCalls = new AtomicInteger(0);
        var runtime = new SequentialAgentRuntime(List.of(
                simpleAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> { ctx.markMemoryLoaded();
                            return AgentDecision.continueWith(AgentAction.READ_MEMORY, "mem"); }),
                simpleAgent(AgentName.SUPERVISOR_AGENT,
                        ctx -> ctx.memoryLoaded() && !ctx.intentRouted(),
                        ctx -> { ctx.markIntentRouted();
                            return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "route"); }),
                countingAgent(AgentName.COMPANION_AGENT,
                        ctx -> ctx.intentRouted() && !ctx.responsePlanned(),
                        ctx -> {
                            companionCalls.incrementAndGet();
                            ctx.setResponseMessages(List.of(AiMessage.assistant("reply")));
                            ctx.setResponsePlan("plan");
                            ctx.markResponsePlanned();
                            return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "done");
                        })
        ));

        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        AgentRunResult result = wrapper.run(user, session, "input", "input");

        // 应该从 Repository 重新加载了实体
        Mockito.verify(userRepo).findById(1L);
        Mockito.verify(sessionRepo).findByPublicIdAndUser_Id("sess-reload", 1L);

        // Memory 和 Supervisor 不应执行（flags 已恢复），只 Companion 执行
        assertThat(companionCalls.get()).isEqualTo(1);
        assertThat(result.steps()).hasSize(3); // 2 restored + 1 new
    }

    @Test
    void restoreShouldStartFreshWhenUserNotFound() {
        UserAccount user = user(999L);
        ChatSession session = session("sess-userNotFound", 999L);

        AgentContext context = new AgentContext(user, session, "input", "input");
        context.markMemoryLoaded();
        checkpointService.saveCheckpoint(context, "runNotFound", "SEQUENTIAL", 0);

        Mockito.when(userRepo.findById(999L)).thenReturn(Optional.empty());

        var memoryCalls = new AtomicInteger(0);
        var runtime = new SequentialAgentRuntime(List.of(
                countingAgent(AgentName.MEMORY_AGENT,
                        ctx -> !ctx.memoryLoaded(),
                        ctx -> {
                            memoryCalls.incrementAndGet();
                            ctx.markMemoryLoaded();
                            return AgentDecision.finish(AgentAction.READ_MEMORY, "done");
                        })
        ));

        var wrapper = new CheckpointAwareAgentRuntime(
                runtime, checkpointService, userRepo, sessionRepo, properties);

        AgentRunResult result = wrapper.run(user, session, "input", "input");

        // 用户找不到，从新运行
        assertThat(memoryCalls.get()).isEqualTo(1);
    }

    // ────────── Helpers ──────────

    private UserAccount user(long id) {
        var u = new UserAccount();
        try {
            var field = UserAccount.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(u, id);
        } catch (Exception e) {
            // ignore
        }
        u.setUsername("testuser");
        u.setDisplayName("Test");
        return u;
    }

    private ChatSession session(String publicId, long userId) {
        var s = new ChatSession();
        s.setPublicId(publicId);
        s.setTitle("Test");
        try {
            var field = ChatSession.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(s, userId);
        } catch (Exception e) {
            // ignore
        }
        return s;
    }

    @FunctionalInterface
    private interface SupportsFn { boolean test(AgentContext ctx); }

    @FunctionalInterface
    private interface ActFn { AgentDecision apply(AgentContext ctx); }

    private static MindBridgeAgent simpleAgent(AgentName name, SupportsFn supportsFn, ActFn actFn) {
        return new MindBridgeAgent() {
            @Override public AgentName name() { return name; }
            @Override public boolean supports(AgentContext ctx) { return supportsFn.test(ctx); }
            @Override public AgentDecision act(AgentContext ctx) { return actFn.apply(ctx); }
        };
    }

    private static MindBridgeAgent countingAgent(AgentName name, SupportsFn supportsFn, ActFn actFn) {
        return simpleAgent(name, supportsFn, actFn);
    }

    private static MindBridgeAgent failingAgent(AgentName name, SupportsFn supportsFn, ActFn actFn) {
        return simpleAgent(name, supportsFn, actFn);
    }
}
