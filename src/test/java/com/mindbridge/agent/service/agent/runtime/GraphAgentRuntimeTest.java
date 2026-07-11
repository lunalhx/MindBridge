package com.mindbridge.agent.service.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.agent.AgentAction;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentDecision;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphAgentRuntimeTest {

    // ────────── 图结构校验 ──────────

    @Test
    void graphShouldRejectDuplicateNode() {
        var stub = stubAgent(AgentName.MEMORY_AGENT);
        assertThatThrownBy(() ->
                AgentGraph.builder(AgentName.MEMORY_AGENT)
                        .node(AgentName.MEMORY_AGENT, stub)
                        .node(AgentName.MEMORY_AGENT, stub)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate node");
    }

    @Test
    void graphShouldRejectEdgeToUnknownNode() {
        assertThatThrownBy(() ->
                AgentGraph.builder(AgentName.MEMORY_AGENT)
                        .node(AgentName.MEMORY_AGENT, stubAgent(AgentName.MEMORY_AGENT))
                        .edge(AgentName.MEMORY_AGENT, AgentName.SUPERVISOR_AGENT, ctx -> true)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Edge target node not defined");
    }

    @Test
    void graphShouldRejectNonTerminalNodeWithoutOutgoingEdges() {
        // 图中只有一个节点 Memory（入口），它既是入口又无出边 = 终点
        // 但如果添加第二个节点也无出边，则 Supervisor 不可达
        assertThatThrownBy(() ->
                AgentGraph.builder(AgentName.MEMORY_AGENT)
                        .node(AgentName.MEMORY_AGENT, stubAgent(AgentName.MEMORY_AGENT))
                        .node(AgentName.SUPERVISOR_AGENT, stubAgent(AgentName.SUPERVISOR_AGENT))
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void graphShouldDetectUnreachableNode() {
        var stub1 = stubAgent(AgentName.MEMORY_AGENT);
        var stub2 = stubAgent(AgentName.SUPERVISOR_AGENT);
        var stub3 = stubAgent(AgentName.COMPANION_AGENT);
        var stub4 = stubAgent(AgentName.COUNSELOR_AGENT);

        // Counselor 不可达：没有边指向它
        assertThatThrownBy(() ->
                AgentGraph.builder(AgentName.MEMORY_AGENT)
                        .node(AgentName.MEMORY_AGENT, stub1)
                        .node(AgentName.SUPERVISOR_AGENT, stub2)
                        .node(AgentName.COMPANION_AGENT, stub3)
                        .node(AgentName.COUNSELOR_AGENT, stub4)
                        .edge(AgentName.MEMORY_AGENT, AgentName.SUPERVISOR_AGENT, ctx -> true)
                        .edge(AgentName.SUPERVISOR_AGENT, AgentName.COMPANION_AGENT, ctx -> true)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void graphShouldRejectAllCycleNoTerminal() {
        var stub1 = stubAgent(AgentName.MEMORY_AGENT);
        var stub2 = stubAgent(AgentName.SUPERVISOR_AGENT);

        assertThatThrownBy(() ->
                AgentGraph.builder(AgentName.MEMORY_AGENT)
                        .node(AgentName.MEMORY_AGENT, stub1)
                        .node(AgentName.SUPERVISOR_AGENT, stub2)
                        .edge(AgentName.MEMORY_AGENT, AgentName.SUPERVISOR_AGENT, ctx -> true)
                        .edge(AgentName.SUPERVISOR_AGENT, AgentName.MEMORY_AGENT, ctx -> true)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no terminal nodes");
    }

    @Test
    void graphShouldExposeEntryAndTerminalNodes() {
        var graph = buildValidMindBridgeGraph();

        assertThat(graph.entry()).isEqualTo(AgentName.MEMORY_AGENT);
        assertThat(graph.terminalNodes()).containsExactlyInAnyOrder(
                AgentName.COMPANION_AGENT, AgentName.COUNSELOR_AGENT);
    }

    // ────────── 基本属性 ──────────

    @Test
    void graphRuntimeShouldReportGraphMode() {
        var runtime = new GraphAgentRuntime(buildValidMindBridgeGraph());
        assertThat(runtime.mode()).isEqualTo(RuntimeMode.GRAPH);
    }

    @Test
    void maxStepsShouldBe8() {
        assertThat(GraphAgentRuntime.MAX_STEPS).isEqualTo(8);
    }

    // ────────── CHAT 路径 ──────────

    @Test
    void chatRouteShouldRunMemorySupervisorCompanion() {
        var runtime = new GraphAgentRuntime(buildValidMindBridgeGraph());
        AgentRunResult result = runtime.run(user(), session(), "帮我解释 Java 多线程", "帮我解释 Java 多线程");

        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.COMPANION_AGENT);
        assertThat(result.intent()).isEqualTo(IntentType.CHAT);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.responseAgent()).isEqualTo(AgentName.COMPANION_AGENT);
        // CHAT 路径不应触发 RAG 和风险评估
        assertThat(result.retrievedKnowledge()).isEmpty();
        assertThat(result.assessment()).isNull();
        assertThat(result.requiresReport()).isFalse();
    }

    // ────────── CONSULT 路径 ──────────

    @Test
    void consultRouteShouldRunKnowledgeRiskGuardianCounselor() {
        var runtime = new GraphAgentRuntime(buildValidMindBridgeGraph());
        AgentRunResult result = runtime.run(user(), session(), "我最近很焦虑", "我最近很焦虑");

        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.KNOWLEDGE_AGENT,
                AgentName.RISK_GUARDIAN_AGENT,
                AgentName.COUNSELOR_AGENT);
        assertThat(result.intent()).isEqualTo(IntentType.CONSULT);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.responseAgent()).isEqualTo(AgentName.COUNSELOR_AGENT);
        // CONSULT 路径应触发 RAG 和风险评估
        assertThat(result.retrievedKnowledge()).isNotEmpty();
        assertThat(result.assessment()).isNotNull();
        assertThat(result.requiresReport()).isTrue();
    }

    // ────────── RISK 路径 ──────────

    @Test
    void riskRouteShouldEscalateToHighAndKeepCounselor() {
        var runtime = new GraphAgentRuntime(buildValidMindBridgeGraph());
        AgentRunResult result = runtime.run(user(), session(), "我不想活了", "我不想活了");

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

    // ────────── 循环保护 ──────────

    @Test
    void shouldThrowWhenGraphCycleReachesMaxSteps() {
        // 构建一个有循环的图：A → B → A → B → ... （但有一个条件出口在 step > 10 时）
        // 由于 MAX_STEPS=8，不会满足出口条件，应抛异常
        var stubA = actingAgent(AgentName.MEMORY_AGENT, ctx -> {
            ctx.markMemoryLoaded();
            return AgentDecision.continueWith(AgentAction.READ_MEMORY, "loop");
        });
        var stubB = actingAgent(AgentName.SUPERVISOR_AGENT, ctx -> {
            ctx.markIntentRouted();
            return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "loop");
        });
        // C 作为终点节点
        var stubC = actingAgent(AgentName.COMPANION_AGENT, ctx ->
                AgentDecision.finish(AgentAction.PLAN_RESPONSE, "done"));

        // A → B (always), B → A (always), B → C (only when steps > 10, never within MAX_STEPS)
        var graph = AgentGraph.builder(AgentName.MEMORY_AGENT)
                .node(AgentName.MEMORY_AGENT, stubA)
                .node(AgentName.SUPERVISOR_AGENT, stubB)
                .node(AgentName.COMPANION_AGENT, stubC)
                .edge(AgentName.MEMORY_AGENT, AgentName.SUPERVISOR_AGENT, ctx -> true)
                .edge(AgentName.SUPERVISOR_AGENT, AgentName.COMPANION_AGENT,
                        ctx -> ctx.steps().size() > 10)
                .edge(AgentName.SUPERVISOR_AGENT, AgentName.MEMORY_AGENT, ctx -> true)
                .build();

        var runtime = new GraphAgentRuntime(graph);
        assertThatThrownBy(() -> runtime.run(user(), session(), "test", "test"))
                .isInstanceOf(AgentRuntimeExecutionException.class)
                .hasMessageContaining("max steps")
                .hasMessageContaining("GRAPH");
    }

    // ────────── 图中无匹配边到达终点 ──────────

    @Test
    void shouldFinishAtTerminalNodeWhenNoEdgeMatches() {
        // Memory → Supervisor → (no matching edge) = Supervisor is terminal if no edges match
        // But Supervisor has edges, so this tests a graph where the terminal is explicit
        var stub1 = actingAgent(AgentName.MEMORY_AGENT, ctx -> {
            ctx.markMemoryLoaded();
            return AgentDecision.continueWith(AgentAction.READ_MEMORY, "mem");
        });
        var stub2 = actingAgent(AgentName.SUPERVISOR_AGENT, ctx -> {
            ctx.markIntentRouted();
            ctx.setIntent(IntentType.CHAT);
            return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "routed");
        });
        var stub3 = actingAgent(AgentName.COMPANION_AGENT, ctx -> {
            ctx.markResponsePlanned();
            return AgentDecision.continueWith(AgentAction.PLAN_RESPONSE, "planned");
        });

        // Companion 是终点（无出边），但其 decision.complete()=false
        // 应回退到无匹配边 = 终点逻辑
        var graph = AgentGraph.builder(AgentName.MEMORY_AGENT)
                .node(AgentName.MEMORY_AGENT, stub1)
                .node(AgentName.SUPERVISOR_AGENT, stub2)
                .node(AgentName.COMPANION_AGENT, stub3)
                .edge(AgentName.MEMORY_AGENT, AgentName.SUPERVISOR_AGENT, ctx -> true)
                .edge(AgentName.SUPERVISOR_AGENT, AgentName.COMPANION_AGENT,
                        ctx -> ctx.intent() == IntentType.CHAT)
                .build();

        var runtime = new GraphAgentRuntime(graph);
        AgentRunResult result = runtime.run(user(), session(), "test", "test");

        // Companion 执行后无出边 → 终点 → finish
        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.COMPANION_AGENT);
    }

    // ────────── fromAgents 工厂 ──────────

    @Test
    void fromAgentsShouldBuildValidGraph() {
        var runtime = GraphAgentRuntime.fromAgents(sixStubAgents());
        assertThat(runtime).isNotNull();
        assertThat(runtime.mode()).isEqualTo(RuntimeMode.GRAPH);
    }

    @Test
    void fromAgentsShouldFailWithMissingAgent() {
        // 只有 5 个 agent，缺少 COUNSELOR_AGENT
        var agents = List.of(
                stubAgent(AgentName.MEMORY_AGENT),
                stubAgent(AgentName.SUPERVISOR_AGENT),
                stubAgent(AgentName.KNOWLEDGE_AGENT),
                stubAgent(AgentName.RISK_GUARDIAN_AGENT),
                stubAgent(AgentName.COMPANION_AGENT));

        assertThatThrownBy(() -> GraphAgentRuntime.fromAgents(agents))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required agent not found")
                .hasMessageContaining("COUNSELOR_AGENT");
    }

    // ────────── Helpers ──────────

    private List<AgentName> agentNames(AgentRunResult result) {
        return result.steps().stream().map(AgentStep::agent).toList();
    }

    /**
     * 构建 MindBridge 标准业务图，使用行为模拟 stub agent：
     * - Memory: markMemoryLoaded, continueWith
     * - Supervisor: setIntent(CHAT or CONSULT or RISK), markIntentRouted, continueWith
     *   意图根据 modelInput 关键词判断（模拟 IntentClassifier）
     * - Knowledge: setRetrievedKnowledge, markKnowledgeHandled, continueWith
     * - RiskGuardian: setAssessment, setRiskLevel, markRiskAssessed, continueWith
     * - Companion: setResponseAgent, markResponsePlanned, finish
     * - Counselor: setResponseAgent, markResponsePlanned, finish
     */
    private AgentGraph buildValidMindBridgeGraph() {
        return GraphAgentRuntime.mindBridgeGraph(List.of(
                memoryStub(),
                supervisorStub(),
                knowledgeStub(),
                riskGuardianStub(),
                companionStub(),
                counselorStub()));
    }

    private static MindBridgeAgent memoryStub() {
        return actingAgent(AgentName.MEMORY_AGENT, ctx -> {
            ctx.setMemoryBrief("无相关历史记忆。");
            ctx.markMemoryLoaded();
            return AgentDecision.continueWith(AgentAction.READ_MEMORY, "memory loaded");
        });
    }

    private static MindBridgeAgent supervisorStub() {
        return actingAgent(AgentName.SUPERVISOR_AGENT, ctx -> {
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
        });
    }

    private static MindBridgeAgent knowledgeStub() {
        return actingAgent(AgentName.KNOWLEDGE_AGENT, ctx -> {
            ctx.setKnowledgeQuery("query");
            ctx.setRetrievedKnowledge(List.of(
                    new com.mindbridge.agent.service.knowledge.SearchResult(
                            1L, "doc.md", "content", 0.9)));
            ctx.markKnowledgeHandled();
            return AgentDecision.continueWith(AgentAction.RETRIEVE_KNOWLEDGE, "retrieved 1");
        });
    }

    private static MindBridgeAgent riskGuardianStub() {
        return actingAgent(AgentName.RISK_GUARDIAN_AGENT, ctx -> {
            RiskLevel risk = ctx.intent() == IntentType.RISK
                    ? RiskLevel.HIGH : RiskLevel.LOW;
            var assessment = new com.mindbridge.agent.service.PsychologyAssessment(
                    com.mindbridge.agent.domain.EmotionLabel.NORMAL,
                    0.5, risk, 0.8, "assessment");
            ctx.setAssessment(assessment);
            ctx.setRiskLevel(risk);
            ctx.markRiskAssessed();
            return AgentDecision.continueWith(AgentAction.ASSESS_RISK, "risk=" + risk);
        });
    }

    private static MindBridgeAgent companionStub() {
        return actingAgent(AgentName.COMPANION_AGENT, ctx -> {
            ctx.setResponseAgent(AgentName.COMPANION_AGENT);
            ctx.setResponsePlan("自然回答");
            ctx.setResponseMessages(List.of(
                    com.mindbridge.agent.service.ai.AiMessage.assistant("ok")));
            ctx.markResponsePlanned();
            return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "companion done");
        });
    }

    private static MindBridgeAgent counselorStub() {
        return actingAgent(AgentName.COUNSELOR_AGENT, ctx -> {
            ctx.setResponseAgent(AgentName.COUNSELOR_AGENT);
            ctx.setResponsePlan("心理支持");
            ctx.setResponseMessages(List.of(
                    com.mindbridge.agent.service.ai.AiMessage.assistant("support")));
            ctx.markResponsePlanned();
            return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "counselor done");
        });
    }

    private static IntentType classifyStub(String input) {
        if (input.contains("不想活") || input.contains("伤害自己")) {
            return IntentType.RISK;
        }
        if (input.contains("焦虑") || input.contains("失眠")) {
            return IntentType.CONSULT;
        }
        return IntentType.CHAT;
    }

    private static List<MindBridgeAgent> sixStubAgents() {
        return List.of(
                memoryStub(), supervisorStub(), knowledgeStub(),
                riskGuardianStub(), companionStub(), counselorStub());
    }

    @FunctionalInterface
    private interface ActFn { AgentDecision apply(AgentContext ctx); }

    private static MindBridgeAgent stubAgent(AgentName name) {
        return actingAgent(name, ctx ->
                AgentDecision.continueWith(AgentAction.READ_MEMORY, "stub"));
    }

    private static MindBridgeAgent actingAgent(AgentName name, ActFn actFn) {
        return new MindBridgeAgent() {
            @Override
            public AgentName name() { return name; }
            @Override
            public boolean supports(AgentContext ctx) { return false; }
            @Override
            public AgentDecision act(AgentContext ctx) { return actFn.apply(ctx); }
        };
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