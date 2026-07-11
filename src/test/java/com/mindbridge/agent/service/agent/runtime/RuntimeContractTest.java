package com.mindbridge.agent.service.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 三种 Runtime 的统一契约测试。
 *
 * <p>验证 SEQUENTIAL、GRAPH、EVENT_DRIVEN 在相同输入下产生兼容的最终业务结果：
 * intent、riskLevel、responseAgent、requiresReport、步骤顺序一致。</p>
 */
class RuntimeContractTest {

    @ParameterizedTest
    @EnumSource(RuntimeMode.class)
    void chatRouteShouldProduceCompatibleResults(RuntimeMode mode) {
        var runtime = buildRuntime(mode);
        AgentRunResult result = runtime.run(user(), session(),
                "帮我解释 Java 多线程", "帮我解释 Java 多线程");

        assertThat(result.intent()).isEqualTo(IntentType.CHAT);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.responseAgent()).isEqualTo(AgentName.COMPANION_AGENT);
        assertThat(result.requiresReport()).isFalse();
        assertThat(result.retrievedKnowledge()).isEmpty();
        assertThat(result.assessment()).isNull();
        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.COMPANION_AGENT);
    }

    @ParameterizedTest
    @EnumSource(RuntimeMode.class)
    void consultRouteShouldProduceCompatibleResults(RuntimeMode mode) {
        var runtime = buildRuntime(mode);
        AgentRunResult result = runtime.run(user(), session(),
                "我最近很焦虑", "我最近很焦虑");

        assertThat(result.intent()).isEqualTo(IntentType.CONSULT);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.responseAgent()).isEqualTo(AgentName.COUNSELOR_AGENT);
        assertThat(result.requiresReport()).isTrue();
        assertThat(result.retrievedKnowledge()).isNotEmpty();
        assertThat(result.assessment()).isNotNull();
        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.KNOWLEDGE_AGENT,
                AgentName.RISK_GUARDIAN_AGENT,
                AgentName.COUNSELOR_AGENT);
    }

    @ParameterizedTest
    @EnumSource(RuntimeMode.class)
    void highRiskRouteShouldProduceCompatibleResults(RuntimeMode mode) {
        var runtime = buildRuntime(mode);
        AgentRunResult result = runtime.run(user(), session(),
                "我不想活了", "我不想活了");

        assertThat(result.intent()).isEqualTo(IntentType.RISK);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.assessment().risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.responseAgent()).isEqualTo(AgentName.COUNSELOR_AGENT);
        assertThat(result.requiresReport()).isTrue();
        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.KNOWLEDGE_AGENT,
                AgentName.RISK_GUARDIAN_AGENT,
                AgentName.COUNSELOR_AGENT);
    }

    @Test
    void allThreeModesShouldReportCorrectMode() {
        assertThat(buildRuntime(RuntimeMode.SEQUENTIAL).mode()).isEqualTo(RuntimeMode.SEQUENTIAL);
        assertThat(buildRuntime(RuntimeMode.GRAPH).mode()).isEqualTo(RuntimeMode.GRAPH);
        assertThat(buildRuntime(RuntimeMode.EVENT_DRIVEN).mode()).isEqualTo(RuntimeMode.EVENT_DRIVEN);
    }

    // ────────── Helpers ──────────

    private AgentRuntime buildRuntime(RuntimeMode mode) {
        var agents = sixStubAgents();
        return switch (mode) {
            case SEQUENTIAL -> new SequentialAgentRuntime(agents);
            case GRAPH -> GraphAgentRuntime.fromAgents(agents);
            case EVENT_DRIVEN -> new EventDrivenAgentRuntime(
                    new AgentRegistry(agents, AgentRegistry.DEFAULT_THRESHOLD));
        };
    }

    private List<AgentName> agentNames(AgentRunResult result) {
        return result.steps().stream().map(AgentStep::agent).toList();
    }

    private List<MindBridgeAgent> sixStubAgents() {
        return List.of(
                memoryStub(), supervisorStub(), knowledgeStub(),
                riskGuardianStub(), companionStub(), counselorStub());
    }

    @FunctionalInterface
    private interface ActFn { AgentDecision apply(AgentContext ctx); }

    @FunctionalInterface
    private interface DecideFn { List<AgentCapability> apply(AgentBlackboard bb); }

    @FunctionalInterface
    private interface SupportsFn { boolean test(AgentContext ctx); }

    private static MindBridgeAgent actingAgent(AgentName name, SupportsFn supportsFn, ActFn actFn, DecideFn decideFn) {
        return new MindBridgeAgent() {
            @Override public AgentName name() { return name; }
            @Override public boolean supports(AgentContext ctx) { return supportsFn.test(ctx); }
            @Override public AgentDecision act(AgentContext ctx) { return actFn.apply(ctx); }
            @Override public List<AgentCapability> decide(AgentBlackboard bb) { return decideFn.apply(bb); }
        };
    }

    private static MindBridgeAgent memoryStub() {
        return actingAgent(AgentName.MEMORY_AGENT,
                ctx -> !ctx.memoryLoaded(),
                ctx -> {
                    ctx.setMemoryBrief("无相关历史记忆。");
                    ctx.markMemoryLoaded();
                    return AgentDecision.continueWith(AgentAction.READ_MEMORY, "memory loaded");
                },
                bb -> bb.hasFlag(AgentFlag.MEMORY_LOADED) ? List.of()
                        : List.of(new AgentCapability("load-memory", 1.0, "memory")));
    }

    private static MindBridgeAgent supervisorStub() {
        return actingAgent(AgentName.SUPERVISOR_AGENT,
                ctx -> ctx.memoryLoaded() && !ctx.intentRouted(),
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
                bb -> (!bb.hasFlag(AgentFlag.MEMORY_LOADED) || bb.hasFlag(AgentFlag.INTENT_ROUTED))
                        ? List.of()
                        : List.of(new AgentCapability("route-intent", 1.0, "intent", List.of("memory"))));
    }

    private static MindBridgeAgent knowledgeStub() {
        return actingAgent(AgentName.KNOWLEDGE_AGENT,
                ctx -> ctx.intentRouted() && !ctx.knowledgeHandled() && ctx.intent() != IntentType.CHAT,
                ctx -> {
                    ctx.setKnowledgeQuery("query");
                    ctx.setRetrievedKnowledge(List.of(new SearchResult(1L, "doc.md", "content", 0.9)));
                    ctx.markKnowledgeHandled();
                    return AgentDecision.continueWith(AgentAction.RETRIEVE_KNOWLEDGE, "retrieved 1");
                },
                bb -> {
                    if (!bb.hasFlag(AgentFlag.INTENT_ROUTED) || bb.hasFlag(AgentFlag.KNOWLEDGE_HANDLED))
                        return List.of();
                    IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                            .filter(a -> a.payload() instanceof IntentType)
                            .map(a -> (IntentType) a.payload()).orElse(null);
                    if (intent == IntentType.CHAT) return List.of();
                    return List.of(new AgentCapability("retrieve-knowledge", 0.9, "knowledge", List.of("intent")));
                });
    }

    private static MindBridgeAgent riskGuardianStub() {
        return actingAgent(AgentName.RISK_GUARDIAN_AGENT,
                ctx -> ctx.knowledgeHandled() && !ctx.riskAssessed() && ctx.intent() != IntentType.CHAT,
                ctx -> {
                    RiskLevel risk = ctx.intent() == IntentType.RISK
                            ? RiskLevel.HIGH : RiskLevel.LOW;
                    ctx.setAssessment(new PsychologyAssessment(
                            EmotionLabel.NORMAL, 0.5, risk, 0.8, "assessment"));
                    ctx.setRiskLevel(risk);
                    ctx.markRiskAssessed();
                    return AgentDecision.continueWith(AgentAction.ASSESS_RISK, "risk=" + risk);
                },
                bb -> {
                    if (!bb.hasFlag(AgentFlag.KNOWLEDGE_HANDLED) || bb.hasFlag(AgentFlag.RISK_ASSESSED))
                        return List.of();
                    IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                            .filter(a -> a.payload() instanceof IntentType)
                            .map(a -> (IntentType) a.payload()).orElse(null);
                    if (intent == IntentType.CHAT) return List.of();
                    return List.of(new AgentCapability("assess-risk", 0.95, "assessment",
                            List.of("intent", "knowledge")));
                });
    }

    private static MindBridgeAgent companionStub() {
        return actingAgent(AgentName.COMPANION_AGENT,
                ctx -> ctx.intentRouted() && ctx.intent() == IntentType.CHAT && !ctx.responsePlanned(),
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
                            .map(a -> (IntentType) a.payload()).orElse(null);
                    if (intent != IntentType.CHAT) return List.of();
                    return List.of(new AgentCapability("plan-companion-response", 0.9, "response", List.of("intent")));
                });
    }

    private static MindBridgeAgent counselorStub() {
        return actingAgent(AgentName.COUNSELOR_AGENT,
                ctx -> ctx.riskAssessed() && ctx.intent() != IntentType.CHAT && !ctx.responsePlanned(),
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
                            .map(a -> (IntentType) a.payload()).orElse(null);
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