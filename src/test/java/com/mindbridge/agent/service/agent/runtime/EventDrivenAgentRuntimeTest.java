package com.mindbridge.agent.service.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.EmotionLabel;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.PsychologyAssessment;
import com.mindbridge.agent.service.agent.AgentAction;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentDecision;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import com.mindbridge.agent.service.agent.blackboard.AgentArtifact;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import com.mindbridge.agent.service.agent.registry.AgentCapability;
import com.mindbridge.agent.service.agent.registry.AgentRegistry;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventDrivenAgentRuntimeTest {

    // ────────── 基本属性 ──────────

    @Test
    void shouldReportEventDrivenMode() {
        var runtime = new EventDrivenAgentRuntime(sixAgentRegistry());
        assertThat(runtime.mode()).isEqualTo(RuntimeMode.EVENT_DRIVEN);
    }

    // ────────── CHAT 路径 ──────────

    @Test
    void chatRouteShouldRunMemorySupervisorCompanion() {
        var runtime = new EventDrivenAgentRuntime(sixAgentRegistry());
        AgentRunResult result = runtime.run(user(), session(),
                "帮我解释 Java 多线程", "帮我解释 Java 多线程");

        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.COMPANION_AGENT);
        assertThat(result.intent()).isEqualTo(IntentType.CHAT);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.responseAgent()).isEqualTo(AgentName.COMPANION_AGENT);
        assertThat(result.retrievedKnowledge()).isEmpty();
        assertThat(result.assessment()).isNull();
        assertThat(result.requiresReport()).isFalse();
    }

    // ────────── CONSULT 路径 ──────────

    @Test
    void consultRouteShouldRunAllFiveAgents() {
        var runtime = new EventDrivenAgentRuntime(sixAgentRegistry());
        AgentRunResult result = runtime.run(user(), session(),
                "我最近很焦虑", "我最近很焦虑");

        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.KNOWLEDGE_AGENT,
                AgentName.RISK_GUARDIAN_AGENT,
                AgentName.COUNSELOR_AGENT);
        assertThat(result.intent()).isEqualTo(IntentType.CONSULT);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.responseAgent()).isEqualTo(AgentName.COUNSELOR_AGENT);
        assertThat(result.retrievedKnowledge()).isNotEmpty();
        assertThat(result.assessment()).isNotNull();
        assertThat(result.requiresReport()).isTrue();
    }

    // ────────── HIGH RISK 路径 ──────────

    @Test
    void highRiskRouteShouldEscalateToHigh() {
        var runtime = new EventDrivenAgentRuntime(sixAgentRegistry());
        AgentRunResult result = runtime.run(user(), session(),
                "我不想活了", "我不想活了");

        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.KNOWLEDGE_AGENT,
                AgentName.RISK_GUARDIAN_AGENT,
                AgentName.COUNSELOR_AGENT);
        assertThat(result.intent()).isEqualTo(IntentType.RISK);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.assessment().risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.responseAgent()).isEqualTo(AgentName.COUNSELOR_AGENT);
        assertThat(result.requiresReport()).isTrue();
    }

    // ────────── 候选同分稳定性 ──────────

    @Test
    void candidateTieShouldBeDeterministicByAgentNameOrdinal() {
        // 两个 Agent 声明相同置信度和相同 producesArtifact
        // COMPANION_AGENT (ordinal 4) vs COUNSELOR_AGENT (ordinal 5)
        // 应选 COMPANION_AGENT（ordinal 更小）
        var companion = actingAgent(AgentName.COMPANION_AGENT,
                ctx -> AgentDecision.finish(AgentAction.PLAN_RESPONSE, "companion"),
                bb -> List.of(new AgentCapability("plan-response", 0.9, "response")));
        var counselor = actingAgent(AgentName.COUNSELOR_AGENT,
                ctx -> AgentDecision.finish(AgentAction.PLAN_RESPONSE, "counselor"),
                bb -> List.of(new AgentCapability("plan-response", 0.9, "response")));

        var registry = new AgentRegistry(List.of(companion, counselor), 0.6);

        // 在一个已有 memory + intent=CHAT 的 blackboard 状态下查询候选
        var board = AgentBlackboard.empty()
                .setFlag(AgentFlag.MEMORY_LOADED)
                .setFlag(AgentFlag.INTENT_ROUTED)
                .addArtifact(new AgentArtifact("memory", AgentName.MEMORY_AGENT, Instant.now(), "brief"))
                .addArtifact(new AgentArtifact("intent", AgentName.SUPERVISOR_AGENT, Instant.now(), IntentType.CHAT));

        var candidates = registry.candidates(board);
        // 两个候选都声明 "response" @0.9
        assertThat(candidates).hasSize(2);
        // tie-break: COMPANION_AGENT (ordinal 4) 排在 COUNSELOR_AGENT (ordinal 5) 前
        assertThat(candidates.get(0).agentName()).isEqualTo(AgentName.COMPANION_AGENT);
        assertThat(candidates.get(1).agentName()).isEqualTo(AgentName.COUNSELOR_AGENT);
    }

    // ────────── 无候选 ──────────

    @Test
    void shouldThrowWhenNoCandidateForTask() {
        // 只有 MemoryAgent，没有其他 agent → produce-intent 任务无候选
        var memory = actingAgent(AgentName.MEMORY_AGENT,
                ctx -> { ctx.markMemoryLoaded(); return AgentDecision.continueWith(AgentAction.READ_MEMORY, "mem"); },
                bb -> List.of(new AgentCapability("load-memory", 1.0, "memory")));
        var registry = new AgentRegistry(List.of(memory), 0.6);
        var runtime = new EventDrivenAgentRuntime(registry);

        assertThatThrownBy(() -> runtime.run(user(), session(), "test", "test"))
                .isInstanceOf(AgentRuntimeExecutionException.class)
                .hasMessageContaining("No candidate")
                .hasMessageContaining("EVENT_DRIVEN");
    }

    // ────────── Agent 执行异常 ──────────

    @Test
    void shouldThrowWithDiagnosticsWhenAgentThrowsException() {
        var failing = actingAgent(AgentName.MEMORY_AGENT,
                ctx -> { throw new RuntimeException("LLM service unavailable"); },
                bb -> List.of(new AgentCapability("load-memory", 1.0, "memory")));
        var registry = new AgentRegistry(List.of(failing), 0.6);
        var runtime = new EventDrivenAgentRuntime(registry);

        assertThatThrownBy(() -> runtime.run(user(), session(), "test", "test"))
                .isInstanceOf(AgentRuntimeExecutionException.class)
                .hasMessageContaining("MEMORY_AGENT")
                .hasMessageContaining("failed")
                .hasMessageContaining("LLM service unavailable");
    }

    // ────────── revision 成功 ──────────

    @Test
    void revisionShouldSucceedWhenRiskGuardianEventuallyAssesses() {
        // RiskGuardian 前两次不评估（不设置 RISK_ASSESSED），第三次才评估
        var riskGuardian = new MindBridgeAgent() {
            int callCount = 0;
            @Override
            public AgentName name() { return AgentName.RISK_GUARDIAN_AGENT; }
            @Override
            public boolean supports(AgentContext ctx) { return false; }
            @Override
            public AgentDecision act(AgentContext ctx) {
                callCount++;
                if (callCount < 2) {
                    // 第一次不设置 assessment，模拟不完整评估
                    return AgentDecision.continueWith(AgentAction.ASSESS_RISK, "incomplete");
                }
                // 第二次才完整评估
                var assessment = new PsychologyAssessment(
                        EmotionLabel.ANXIETY, 0.5, RiskLevel.MEDIUM, 0.8, "assessed");
                ctx.setAssessment(assessment);
                ctx.setRiskLevel(RiskLevel.MEDIUM);
                ctx.markRiskAssessed();
                return AgentDecision.continueWith(AgentAction.ASSESS_RISK, "assessed");
            }
            @Override
            public List<AgentCapability> decide(AgentBlackboard bb) {
                if (!bb.hasFlag(AgentFlag.KNOWLEDGE_HANDLED) || bb.hasFlag(AgentFlag.RISK_ASSESSED))
                    return List.of();
                IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                        .filter(a -> a.payload() instanceof IntentType)
                        .map(a -> (IntentType) a.payload())
                        .orElse(null);
                if (intent == IntentType.CHAT) return List.of();
                return List.of(new AgentCapability("assess-risk", 0.95, "assessment",
                        List.of("intent", "knowledge")));
            }
        };

        var registry = new AgentRegistry(List.of(
                memoryStub(), supervisorStub(), knowledgeStub(), riskGuardian,
                companionStub(), counselorStub()), 0.6);
        var runtime = new EventDrivenAgentRuntime(registry, 8, 3);

        AgentRunResult result = runtime.run(user(), session(), "我最近很焦虑", "我最近很焦虑");

        // RiskGuardian 应出现两次（第一次不完整，revision 后第二次完整）
        long riskGuardianCount = result.steps().stream()
                .map(AgentStep::agent)
                .filter(name -> name == AgentName.RISK_GUARDIAN_AGENT)
                .count();
        assertThat(riskGuardianCount).isGreaterThanOrEqualTo(2);
        assertThat(result.assessment()).isNotNull();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    // ────────── 轮次耗尽 ──────────

    @Test
    void shouldFailWhenMaxRoundsExhausted() {
        // Memory agent 无限循环（每次都声明能力但从不完成）
        var memory = new MindBridgeAgent() {
            @Override
            public AgentName name() { return AgentName.MEMORY_AGENT; }
            @Override
            public boolean supports(AgentContext ctx) { return false; }
            @Override
            public AgentDecision act(AgentContext ctx) {
                return AgentDecision.continueWith(AgentAction.READ_MEMORY, "loop");
            }
            @Override
            public List<AgentCapability> decide(AgentBlackboard bb) {
                // 始终声明能力，但 act 从不设置 MEMORY_LOADED
                return List.of(new AgentCapability("load-memory", 1.0, "memory"));
            }
        };

        var registry = new AgentRegistry(List.of(memory), 0.6);
        var runtime = new EventDrivenAgentRuntime(registry, 3, 2);

        assertThatThrownBy(() -> runtime.run(user(), session(), "test", "test"))
                .isInstanceOf(AgentRuntimeExecutionException.class)
                .hasMessageContaining("max rounds")
                .hasMessageContaining("3")
                .hasMessageContaining("EVENT_DRIVEN");
    }

    // ────────── revision 耗尽 ──────────

    @Test
    void shouldFailWhenMaxRevisionsExhausted() {
        // Counselor 在 CONSULT 路径中声明 complete=true 但不设 RESPONSE_PLANNED
        // 安全门禁不通过，但 revision 任务也无候选 → 应失败
        var counselor = new MindBridgeAgent() {
            @Override
            public AgentName name() { return AgentName.COUNSELOR_AGENT; }
            @Override
            public boolean supports(AgentContext ctx) { return false; }
            @Override
            public AgentDecision act(AgentContext ctx) {
                ctx.setResponseAgent(AgentName.COUNSELOR_AGENT);
                ctx.setResponsePlan("plan");
                ctx.setResponseMessages(List.of(AiMessage.assistant("reply")));
                ctx.markResponsePlanned();
                return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "done");
            }
            @Override
            public List<AgentCapability> decide(AgentBlackboard bb) {
                if (bb.hasFlag(AgentFlag.RESPONSE_PLANNED) || !bb.hasFlag(AgentFlag.RISK_ASSESSED))
                    return List.of();
                IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                        .filter(a -> a.payload() instanceof IntentType)
                        .map(a -> (IntentType) a.payload())
                        .orElse(null);
                if (intent == IntentType.CHAT || intent == null) return List.of();
                return List.of(new AgentCapability("plan-counselor-response", 0.95, "response",
                        List.of("intent", "knowledge", "assessment")));
            }
        };

        // RiskGuardian 设置 RISK_ASSESSED 但不创建 assessment artifact → 安全门禁不通过
        var riskGuardian = new MindBridgeAgent() {
            @Override
            public AgentName name() { return AgentName.RISK_GUARDIAN_AGENT; }
            @Override
            public boolean supports(AgentContext ctx) { return false; }
            @Override
            public AgentDecision act(AgentContext ctx) {
                ctx.setRiskLevel(RiskLevel.LOW);
                ctx.markRiskAssessed();
                // 不调用 setAssessment → 没有 assessment artifact
                return AgentDecision.continueWith(AgentAction.ASSESS_RISK, "no artifact");
            }
            @Override
            public List<AgentCapability> decide(AgentBlackboard bb) {
                if (!bb.hasFlag(AgentFlag.KNOWLEDGE_HANDLED) || bb.hasFlag(AgentFlag.RISK_ASSESSED))
                    return List.of();
                IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                        .filter(a -> a.payload() instanceof IntentType)
                        .map(a -> (IntentType) a.payload())
                        .orElse(null);
                if (intent == IntentType.CHAT) return List.of();
                return List.of(new AgentCapability("assess-risk", 0.95, "assessment",
                        List.of("intent", "knowledge")));
            }
        };

        var registry = new AgentRegistry(List.of(
                memoryStub(), supervisorStub(), knowledgeStub(), riskGuardian,
                companionStub(), counselor), 0.6);
        var runtime = new EventDrivenAgentRuntime(registry, 8, 1);

        // RiskGuardian 设置了 RISK_ASSESSED 但没有 assessment artifact
        // 安全门禁检查 assessment artifact 不存在 → 不通过
        // revision 任务 produce-assessment 但 RiskGuardian.decide() 看到 RISK_ASSESSED 已设 → 不声明
        // 应抛异常
        assertThatThrownBy(() -> runtime.run(user(), session(), "我最近很焦虑", "我最近很焦虑"))
                .isInstanceOf(AgentRuntimeExecutionException.class);
    }

    // ────────── 异常不含敏感输入 ──────────

    @Test
    void executionExceptionShouldNotContainSensitiveInput() {
        var memory = actingAgent(AgentName.MEMORY_AGENT,
                ctx -> { throw new RuntimeException("boom"); },
                bb -> List.of(new AgentCapability("load-memory", 1.0, "memory")));
        var registry = new AgentRegistry(List.of(memory), 0.6);
        var runtime = new EventDrivenAgentRuntime(registry);

        try {
            runtime.run(user(), session(),
                    "我的身份证号是1234567890",
                    "我的身份证号是1234567890");
        } catch (AgentRuntimeExecutionException e) {
            assertThat(e.getMessage()).doesNotContain("1234567890");
        }
    }

    // ────────── 任务推导确定性 ──────────

    @Test
    void deriveTasksShouldBeDeterministicAndIdempotent() {
        var runtime = new EventDrivenAgentRuntime(sixAgentRegistry());

        // 空 Blackboard → produce-memory
        var tasks1 = runtime.deriveTasks(AgentBlackboard.empty());
        assertThat(tasks1).hasSize(1);
        assertThat(tasks1.get(0).desiredArtifact()).isEqualTo("memory");

        // 同一 Blackboard 再次推导 → 结果相同
        var tasks2 = runtime.deriveTasks(AgentBlackboard.empty());
        assertThat(tasks2).isEqualTo(tasks1);

        // Memory loaded, intent not set → produce-intent
        var board2 = AgentBlackboard.empty()
                .setFlag(AgentFlag.MEMORY_LOADED)
                .addArtifact(new AgentArtifact("memory", AgentName.MEMORY_AGENT, Instant.now(), "brief"));
        var tasks3 = runtime.deriveTasks(board2);
        assertThat(tasks3).hasSize(1);
        assertThat(tasks3.get(0).desiredArtifact()).isEqualTo("intent");
    }

    // ────────── Helpers ──────────

    private List<AgentName> agentNames(AgentRunResult result) {
        return result.steps().stream().map(AgentStep::agent).toList();
    }

    private AgentRegistry sixAgentRegistry() {
        return new AgentRegistry(List.of(
                memoryStub(), supervisorStub(), knowledgeStub(),
                riskGuardianStub(), companionStub(), counselorStub()), 0.6);
    }

    @FunctionalInterface
    private interface ActFn { AgentDecision apply(AgentContext ctx); }

    @FunctionalInterface
    private interface DecideFn { List<AgentCapability> apply(AgentBlackboard bb); }

    private static MindBridgeAgent actingAgent(AgentName name, ActFn actFn, DecideFn decideFn) {
        return new MindBridgeAgent() {
            @Override public AgentName name() { return name; }
            @Override public boolean supports(AgentContext ctx) { return false; }
            @Override public AgentDecision act(AgentContext ctx) { return actFn.apply(ctx); }
            @Override public List<AgentCapability> decide(AgentBlackboard bb) { return decideFn.apply(bb); }
        };
    }

    private static MindBridgeAgent memoryStub() {
        return actingAgent(AgentName.MEMORY_AGENT,
                ctx -> {
                    ctx.setMemoryBrief("无相关历史记忆。");
                    ctx.markMemoryLoaded();
                    return AgentDecision.continueWith(AgentAction.READ_MEMORY, "memory loaded");
                },
                bb -> {
                    if (bb.hasFlag(AgentFlag.MEMORY_LOADED)) return List.of();
                    return List.of(new AgentCapability("load-memory", 1.0, "memory"));
                });
    }

    private static MindBridgeAgent supervisorStub() {
        return actingAgent(AgentName.SUPERVISOR_AGENT,
                ctx -> {
                    IntentType intent = classifyStub(ctx.modelInput());
                    ctx.setIntent(intent);
                    ctx.setResponseAgent(intent == IntentType.CHAT
                            ? AgentName.COMPANION_AGENT : AgentName.COUNSELOR_AGENT);
                    ctx.markIntentRouted();
                    if (intent == IntentType.CHAT) {
                        ctx.markKnowledgeHandled();
                        ctx.markRiskAssessed();
                    }
                    return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "intent=" + intent);
                },
                bb -> {
                    if (!bb.hasFlag(AgentFlag.MEMORY_LOADED) || bb.hasFlag(AgentFlag.INTENT_ROUTED))
                        return List.of();
                    return List.of(new AgentCapability("route-intent", 1.0, "intent", List.of("memory")));
                });
    }

    private static MindBridgeAgent knowledgeStub() {
        return actingAgent(AgentName.KNOWLEDGE_AGENT,
                ctx -> {
                    ctx.setKnowledgeQuery("query");
                    ctx.setRetrievedKnowledge(List.of(
                            new SearchResult(1L, "doc.md", "content", 0.9)));
                    ctx.markKnowledgeHandled();
                    return AgentDecision.continueWith(AgentAction.RETRIEVE_KNOWLEDGE, "retrieved 1");
                },
                bb -> {
                    if (!bb.hasFlag(AgentFlag.INTENT_ROUTED) || bb.hasFlag(AgentFlag.KNOWLEDGE_HANDLED))
                        return List.of();
                    IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                            .filter(a -> a.payload() instanceof IntentType)
                            .map(a -> (IntentType) a.payload())
                            .orElse(null);
                    if (intent == IntentType.CHAT) return List.of();
                    return List.of(new AgentCapability("retrieve-knowledge", 0.9, "knowledge", List.of("intent")));
                });
    }

    private static MindBridgeAgent riskGuardianStub() {
        return actingAgent(AgentName.RISK_GUARDIAN_AGENT,
                ctx -> {
                    RiskLevel risk = ctx.intent() == IntentType.RISK
                            ? RiskLevel.HIGH : RiskLevel.LOW;
                    var assessment = new PsychologyAssessment(
                            EmotionLabel.NORMAL, 0.5, risk, 0.8, "assessment");
                    ctx.setAssessment(assessment);
                    ctx.setRiskLevel(risk);
                    ctx.markRiskAssessed();
                    return AgentDecision.continueWith(AgentAction.ASSESS_RISK, "risk=" + risk);
                },
                bb -> {
                    if (!bb.hasFlag(AgentFlag.KNOWLEDGE_HANDLED) || bb.hasFlag(AgentFlag.RISK_ASSESSED))
                        return List.of();
                    IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                            .filter(a -> a.payload() instanceof IntentType)
                            .map(a -> (IntentType) a.payload())
                            .orElse(null);
                    if (intent == IntentType.CHAT) return List.of();
                    return List.of(new AgentCapability("assess-risk", 0.95, "assessment",
                            List.of("intent", "knowledge")));
                });
    }

    private static MindBridgeAgent companionStub() {
        return actingAgent(AgentName.COMPANION_AGENT,
                ctx -> {
                    ctx.setResponseAgent(AgentName.COMPANION_AGENT);
                    ctx.setResponsePlan("自然回答");
                    ctx.setResponseMessages(List.of(AiMessage.assistant("ok")));
                    ctx.markResponsePlanned();
                    return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "companion done");
                },
                bb -> {
                    if (bb.hasFlag(AgentFlag.RESPONSE_PLANNED) || !bb.hasFlag(AgentFlag.INTENT_ROUTED))
                        return List.of();
                    IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                            .filter(a -> a.payload() instanceof IntentType)
                            .map(a -> (IntentType) a.payload())
                            .orElse(null);
                    if (intent != IntentType.CHAT) return List.of();
                    return List.of(new AgentCapability("plan-companion-response", 0.9, "response", List.of("intent")));
                });
    }

    private static MindBridgeAgent counselorStub() {
        return actingAgent(AgentName.COUNSELOR_AGENT,
                ctx -> {
                    ctx.setResponseAgent(AgentName.COUNSELOR_AGENT);
                    ctx.setResponsePlan("心理支持");
                    ctx.setResponseMessages(List.of(AiMessage.assistant("support")));
                    ctx.markResponsePlanned();
                    return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "counselor done");
                },
                bb -> {
                    if (bb.hasFlag(AgentFlag.RESPONSE_PLANNED) || !bb.hasFlag(AgentFlag.RISK_ASSESSED))
                        return List.of();
                    IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                            .filter(a -> a.payload() instanceof IntentType)
                            .map(a -> (IntentType) a.payload())
                            .orElse(null);
                    if (intent == IntentType.CHAT || intent == null) return List.of();
                    return List.of(new AgentCapability("plan-counselor-response", 0.95, "response",
                            List.of("intent", "knowledge", "assessment")));
                });
    }

    private static IntentType classifyStub(String input) {
        if (input.contains("不想活") || input.contains("伤害自己")) return IntentType.RISK;
        if (input.contains("焦虑") || input.contains("失眠")) return IntentType.CONSULT;
        return IntentType.CHAT;
    }

    private UserAccount user() {
        var u = new UserAccount();
        u.setUsername("testuser");
        u.setDisplayName("Test User");
        return u;
    }

    private ChatSession session() {
        var s = new ChatSession();
        s.setPublicId("test-session");
        s.setTitle("Test Session");
        return s;
    }
}