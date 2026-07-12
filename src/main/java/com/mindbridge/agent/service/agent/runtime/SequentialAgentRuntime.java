package com.mindbridge.agent.service.agent.runtime;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.agent.AgentAction;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentDecision;
import com.mindbridge.agent.service.agent.AgentExecutionLifecycle;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import java.util.List;

/**
 * 顺序模式 Agent 运行时。
 *
 * <p>完整迁移原 AgentRuntimeService 的有限步循环逻辑：
 * 按固定优先序遍历 Agent 列表，通过 {@code supports()} 选择下一个执行的 Agent，
 * 最多执行 {@value #MAX_STEPS} 步。适合心理安全场景的可控 agent loop。</p>
 */
public class SequentialAgentRuntime implements AgentRuntime {

    public static final int MAX_STEPS = 8;

    private final List<MindBridgeAgent> agents;
    private final AgentExecutionLifecycle lifecycle;

    public SequentialAgentRuntime(List<MindBridgeAgent> agents) {
        this(agents, null);
    }

    public SequentialAgentRuntime(List<MindBridgeAgent> agents, AgentExecutionLifecycle lifecycle) {
        this.agents = List.copyOf(agents);
        this.lifecycle = lifecycle;
    }

    @Override
    public RuntimeMode mode() {
        return RuntimeMode.SEQUENTIAL;
    }

    @Override
    public AgentRunResult run(UserAccount user, ChatSession session, String originalInput, String modelInput,
                               AgentStepListener listener) {
        return run(new AgentContext(user, session, originalInput, modelInput), listener);
    }

    @Override
    public AgentRunResult run(AgentContext context, AgentStepListener listener) {
        int startStep = context.steps().size() + 1;
        for (int step = startStep; step <= MAX_STEPS && !context.finished(); step++) {
            MindBridgeAgent agent = nextAgent(context);
            AgentAction expectedAction = agent.getExpectedAction();
            String action = expectedAction != null ? expectedAction.name() : "";
            listener.onStarted(step, agent.name(), action);
            String sessionId = context.session() != null ? context.session().getPublicId() : null;
            if (lifecycle != null && sessionId != null) {
                lifecycle.beforeStep(context, agent.name(), sessionId);
            }
            try {
                AgentDecision decision = agent.act(context);
                action = decision.action() != null ? decision.action().name() : "";
                context.addStep(AgentStep.of(step, agent.name(), decision));
                if (lifecycle != null && sessionId != null) {
                    lifecycle.afterStep(context, agent.name(), sessionId, decision.observation());
                }
                listener.onCompleted(step, agent.name(), action,
                        sanitizeObservation(decision.observation()));
                if (decision.complete()) {
                    context.finish();
                }
            } catch (Exception e) {
                listener.onFailed(step, agent.name(), action, sanitizeObservation(e.getMessage()));
                throw e;
            }
        }

        if (!context.finished()) {
            throw new AgentRuntimeExecutionException(
                    "Agent loop reached max steps without completion",
                    RuntimeMode.SEQUENTIAL,
                    context);
        }

        return AgentRunResult.from(context);
    }

    private MindBridgeAgent nextAgent(AgentContext context) {
        return agents.stream()
                .filter(agent -> agent.supports(context))
                .findFirst()
                .orElseThrow(() -> new AgentRuntimeExecutionException(
                        "No agent can handle current context state",
                        RuntimeMode.SEQUENTIAL,
                        context));
    }

    static String sanitizeObservation(String observation) {
        if (observation == null) return "";
        return observation.length() > 200 ? observation.substring(0, 200) : observation;
    }
}