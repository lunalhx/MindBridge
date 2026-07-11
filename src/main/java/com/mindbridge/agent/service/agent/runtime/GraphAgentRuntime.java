package com.mindbridge.agent.service.agent.runtime;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentDecision;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.domain.IntentType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 图编排模式 Agent 运行时。
 *
 * <p>使用 {@link AgentGraph} 定义节点和条件边，从入口节点出发，
 * 执行每个节点的 Agent 后按条件谓词选择下一条边。
 * 最大步数 {@value #MAX_STEPS} 防止错误配置导致无限循环。</p>
 *
 * <p>图结构（MindBridge 业务路径）：</p>
 * <pre>
 *   Memory → Supervisor → [intent 分支]
 *     ├─ CHAT     → Companion → END
 *     ├─ CONSULT  → Knowledge → RiskGuardian → Counselor → END
 *     └─ RISK     → Knowledge → RiskGuardian → Counselor → END
 * </pre>
 */
public class GraphAgentRuntime implements AgentRuntime {

    public static final int MAX_STEPS = 8;

    private final AgentGraph graph;

    public GraphAgentRuntime(AgentGraph graph) {
        this.graph = graph;
    }

    /**
     * 从 Spring 注入的 Agent 列表构建 MindBridge 标准业务图。
     *
     * <p>使用 {@link #mindBridgeGraph(List)} 工厂方法创建标准图结构。</p>
     *
     * @param agents Spring 注入的 Agent 列表（需包含全部 6 个 Agent）
     * @return GraphAgentRuntime 实例
     */
    public static GraphAgentRuntime fromAgents(List<com.mindbridge.agent.service.agent.MindBridgeAgent> agents) {
        return new GraphAgentRuntime(mindBridgeGraph(agents));
    }

    /**
     * 构建 MindBridge 标准业务图。
     *
     * <p>节点：Memory → Supervisor → (CHAT: Companion | CONSULT/RISK: Knowledge → RiskGuardian → Counselor)</p>
     * <p>条件边在 Supervisor 节点按 intent 分流：CHAT 走 Companion，CONSULT/RISK 走 Knowledge 链。</p>
     */
    public static AgentGraph mindBridgeGraph(List<com.mindbridge.agent.service.agent.MindBridgeAgent> agents) {
        Map<AgentName, com.mindbridge.agent.service.agent.MindBridgeAgent> byName = new HashMap<>();
        for (var agent : agents) {
            byName.put(agent.name(), agent);
        }

        AgentGraph.Builder b = AgentGraph.builder(AgentName.MEMORY_AGENT);

        // 节点
        b.node(AgentName.MEMORY_AGENT, requireAgent(byName, AgentName.MEMORY_AGENT));
        b.node(AgentName.SUPERVISOR_AGENT, requireAgent(byName, AgentName.SUPERVISOR_AGENT));
        b.node(AgentName.KNOWLEDGE_AGENT, requireAgent(byName, AgentName.KNOWLEDGE_AGENT));
        b.node(AgentName.RISK_GUARDIAN_AGENT, requireAgent(byName, AgentName.RISK_GUARDIAN_AGENT));
        b.node(AgentName.COMPANION_AGENT, requireAgent(byName, AgentName.COMPANION_AGENT));
        b.node(AgentName.COUNSELOR_AGENT, requireAgent(byName, AgentName.COUNSELOR_AGENT));

        // 边：Memory → Supervisor（无条件）
        b.edge(AgentName.MEMORY_AGENT, AgentName.SUPERVISOR_AGENT, ctx -> true);

        // 边：Supervisor → Companion（CHAT）或 Knowledge（CONSULT/RISK）
        b.edge(AgentName.SUPERVISOR_AGENT, AgentName.COMPANION_AGENT,
                ctx -> ctx.intent() == IntentType.CHAT);
        b.edge(AgentName.SUPERVISOR_AGENT, AgentName.KNOWLEDGE_AGENT,
                ctx -> ctx.intent() == IntentType.CONSULT || ctx.intent() == IntentType.RISK);

        // 边：Knowledge → RiskGuardian
        b.edge(AgentName.KNOWLEDGE_AGENT, AgentName.RISK_GUARDIAN_AGENT, ctx -> true);

        // 边：RiskGuardian → Counselor
        b.edge(AgentName.RISK_GUARDIAN_AGENT, AgentName.COUNSELOR_AGENT, ctx -> true);

        // Companion 和 Counselor 是终点节点（无出边）

        return b.build();
    }

    private static com.mindbridge.agent.service.agent.MindBridgeAgent requireAgent(
            Map<AgentName, com.mindbridge.agent.service.agent.MindBridgeAgent> byName, AgentName name) {
        var agent = byName.get(name);
        if (agent == null) {
            throw new IllegalStateException("Required agent not found: " + name);
        }
        return agent;
    }

    @Override
    public RuntimeMode mode() {
        return RuntimeMode.GRAPH;
    }

    @Override
    public AgentRunResult run(UserAccount user, ChatSession session, String originalInput, String modelInput) {
        AgentContext context = new AgentContext(user, session, originalInput, modelInput);

        AgentName current = graph.entry();
        for (int step = 1; step <= MAX_STEPS; step++) {
            var agent = graph.agentOf(current);
            if (agent == null) {
                throw new AgentRuntimeExecutionException(
                        "Graph node has no agent: " + current,
                        RuntimeMode.GRAPH, context);
            }

            AgentDecision decision = agent.act(context);
            context.addStep(AgentStep.of(step, current, decision));

            if (decision.complete()) {
                context.finish();
                return AgentRunResult.from(context);
            }

            // 评估出边，选择第一个满足条件的
            AgentName next = selectNext(context, current);
            if (next == null) {
                // 无匹配边 → 终点节点
                context.finish();
                return AgentRunResult.from(context);
            }
            current = next;
        }

        throw new AgentRuntimeExecutionException(
                "Graph runtime reached max steps without reaching terminal node",
                RuntimeMode.GRAPH, context);
    }

    private AgentName selectNext(AgentContext context, AgentName current) {
        for (AgentGraph.Edge edge : graph.edgesFrom(current)) {
            if (edge.condition().test(context)) {
                return edge.to();
            }
        }
        return null;
    }
}