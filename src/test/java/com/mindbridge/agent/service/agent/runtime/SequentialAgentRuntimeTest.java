package com.mindbridge.agent.service.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.agent.AgentAction;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentDecision;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentRuntimeService;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import java.util.List;
import org.junit.jupiter.api.Test;

class SequentialAgentRuntimeTest {

    // ────────── 基本属性 ──────────

    @Test
    void sequentialRuntimeShouldReportSequentialMode() {
        var rt = new SequentialAgentRuntime(List.of(stubAgent(AgentName.MEMORY_AGENT)));
        assertThat(rt.mode()).isEqualTo(RuntimeMode.SEQUENTIAL);
    }

    @Test
    void maxStepsShouldBe8() {
        assertThat(SequentialAgentRuntime.MAX_STEPS).isEqualTo(8);
    }

    // ────────── 顺序执行：stub agent 路径 ──────────

    @Test
    void shouldExecuteAgentsInOrderUntilComplete() {
        var memory = stubAgent(AgentName.MEMORY_AGENT,
                ctx -> !ctx.memoryLoaded(),
                ctx -> { ctx.markMemoryLoaded(); return AgentDecision.continueWith(AgentAction.READ_MEMORY, "mem"); });
        var supervisor = stubAgent(AgentName.SUPERVISOR_AGENT,
                ctx -> ctx.memoryLoaded() && !ctx.intentRouted(),
                ctx -> { ctx.markIntentRouted(); return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "route"); });
        var companion = stubAgent(AgentName.COMPANION_AGENT,
                ctx -> ctx.intentRouted() && !ctx.responsePlanned(),
                ctx -> { ctx.markResponsePlanned(); return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "done"); });

        var rt = new SequentialAgentRuntime(List.of(memory, supervisor, companion));
        AgentRunResult result = rt.run(user(), session(), "test", "test");

        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.COMPANION_AGENT);
        assertThat(result.steps()).hasSize(3);
    }

    @Test
    void shouldStopWhenDecisionIsComplete() {
        var first = stubAgent(AgentName.MEMORY_AGENT,
                ctx -> !ctx.memoryLoaded(),
                ctx -> { ctx.markMemoryLoaded(); return AgentDecision.finish(AgentAction.READ_MEMORY, "done"); });
        var neverRuns = stubAgent(AgentName.SUPERVISOR_AGENT,
                ctx -> { throw new AssertionError("should not run"); });

        var rt = new SequentialAgentRuntime(List.of(first, neverRuns));
        AgentRunResult result = rt.run(user(), session(), "test", "test");

        assertThat(agentNames(result)).containsExactly(AgentName.MEMORY_AGENT);
        assertThat(result.steps()).hasSize(1);
    }

    // ────────── 异常：无 Agent 可处理 ──────────

    @Test
    void shouldThrowWhenNoAgentCanHandle() {
        var neverSupports = stubAgent(AgentName.MEMORY_AGENT, ctx -> false);
        var rt = new SequentialAgentRuntime(List.of(neverSupports));

        assertThatThrownBy(() -> rt.run(user(), session(), "test", "test"))
                .isInstanceOf(AgentRuntimeExecutionException.class)
                .hasMessageContaining("No agent can handle")
                .hasMessageContaining("SEQUENTIAL");
    }

    @Test
    void executionExceptionShouldNotContainSensitiveInput() {
        var neverSupports = stubAgent(AgentName.MEMORY_AGENT, ctx -> false);
        var rt = new SequentialAgentRuntime(List.of(neverSupports));

        try {
            rt.run(user(), session(),
                    "我的身份证号是1234567890",
                    "我的身份证号是1234567890");
        } catch (AgentRuntimeExecutionException e) {
            assertThat(e.getMessage()).doesNotContain("1234567890");
            assertThat(e.agentSequence()).isEmpty();
            assertThat(e.flags()).isEmpty();
        }
    }

    @Test
    void executionExceptionShouldContainModeAndState() {
        var memory = stubAgent(AgentName.MEMORY_AGENT,
                ctx -> !ctx.memoryLoaded(),
                ctx -> { ctx.markMemoryLoaded(); return AgentDecision.continueWith(AgentAction.READ_MEMORY, "mem"); });
        var neverSupports = stubAgent(AgentName.SUPERVISOR_AGENT, ctx -> false);
        var rt = new SequentialAgentRuntime(List.of(memory, neverSupports));

        try {
            rt.run(user(), session(), "test", "test");
        } catch (AgentRuntimeExecutionException e) {
            assertThat(e.mode()).isEqualTo(RuntimeMode.SEQUENTIAL);
            assertThat(e.stepsCompleted()).isEqualTo(1);
            assertThat(e.agentSequence()).containsExactly(AgentName.MEMORY_AGENT);
            assertThat(e.flags()).contains(AgentFlag.MEMORY_LOADED);
        }
    }

    // ────────── 异常：达到最大步数 ──────────

    @Test
    void shouldThrowWhenMaxStepsReachedWithoutCompletion() {
        var infiniteAgent = stubAgent(AgentName.MEMORY_AGENT,
                ctx -> true,
                ctx -> AgentDecision.continueWith(AgentAction.READ_MEMORY, "loop"));
        var rt = new SequentialAgentRuntime(List.of(infiniteAgent));

        assertThatThrownBy(() -> rt.run(user(), session(), "test", "test"))
                .isInstanceOf(AgentRuntimeExecutionException.class)
                .hasMessageContaining("max steps")
                .hasMessageContaining("SEQUENTIAL");
    }

    @Test
    void maxStepsExceptionShouldContainModeAndState() {
        var infiniteAgent = stubAgent(AgentName.MEMORY_AGENT,
                ctx -> true,
                ctx -> AgentDecision.continueWith(AgentAction.READ_MEMORY, "loop"));
        var rt = new SequentialAgentRuntime(List.of(infiniteAgent));

        try {
            rt.run(user(), session(), "test", "test");
        } catch (AgentRuntimeExecutionException e) {
            assertThat(e.mode()).isEqualTo(RuntimeMode.SEQUENTIAL);
            assertThat(e.stepsCompleted()).isEqualTo(SequentialAgentRuntime.MAX_STEPS);
            assertThat(e.agentSequence()).hasSize(SequentialAgentRuntime.MAX_STEPS);
            assertThat(e.agentSequence()).allMatch(name -> name == AgentName.MEMORY_AGENT);
        }
    }

    // ────────── AgentRuntimeService 门面兼容 ──────────

    @Test
    void agentRuntimeServiceWithDirectRuntimeShouldDelegate() {
        var memory = stubAgent(AgentName.MEMORY_AGENT,
                ctx -> !ctx.memoryLoaded(),
                ctx -> { ctx.markMemoryLoaded(); return AgentDecision.finish(AgentAction.READ_MEMORY, "done"); });
        var rt = new SequentialAgentRuntime(List.of(memory));
        var svc = new AgentRuntimeService(rt);

        AgentRunResult result = svc.run(user(), session(), "test", "test");
        assertThat(agentNames(result)).containsExactly(AgentName.MEMORY_AGENT);
    }

    // ────────── Helpers ──────────

    private List<AgentName> agentNames(AgentRunResult result) {
        return result.steps().stream().map(AgentStep::agent).toList();
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

    @FunctionalInterface
    private interface SupportsFn { boolean test(AgentContext ctx); }

    @FunctionalInterface
    private interface ActFn { AgentDecision apply(AgentContext ctx); }

    private static MindBridgeAgent stubAgent(AgentName name) {
        return stubAgent(name, ctx -> false, ctx ->
                AgentDecision.continueWith(AgentAction.READ_MEMORY, "stub"));
    }

    private static MindBridgeAgent stubAgent(AgentName name, SupportsFn supportsFn) {
        return stubAgent(name, supportsFn, ctx ->
                AgentDecision.continueWith(AgentAction.READ_MEMORY, "stub"));
    }

    private static MindBridgeAgent stubAgent(AgentName name, SupportsFn supportsFn, ActFn actFn) {
        return new MindBridgeAgent() {
            @Override
            public AgentName name() { return name; }
            @Override
            public boolean supports(AgentContext ctx) { return supportsFn.test(ctx); }
            @Override
            public AgentDecision act(AgentContext ctx) { return actFn.apply(ctx); }
        };
    }
}