package com.mindbridge.agent.service.agent.runtime;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentDecision;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import com.mindbridge.agent.service.agent.blackboard.AgentArtifact;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.agent.blackboard.AgentEvent;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import com.mindbridge.agent.service.agent.registry.AgentCapability;
import com.mindbridge.agent.service.agent.registry.AgentRegistry;
import com.mindbridge.agent.service.agent.registry.AgentRegistry.Candidate;
import com.mindbridge.agent.service.agent.registry.AgentTask;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 声明式事件驱动 Agent 运行时。
 *
 * <p>基于 Blackboard、AgentTask、AgentRegistry 实现有限轮协调循环：</p>
 * <ol>
 *   <li>每轮从 Blackboard 缺失 artifact 推导待办任务（确定性、幂等）</li>
 *   <li>Registry 收集 decide() 候选，过滤低于阈值，按置信度降序 + AgentName 序排序</li>
 *   <li>选择最佳候选执行 agent.act(context)，将结果同步到新 Blackboard</li>
 *   <li>Safety/RiskGuardian 是 CONSULT/RISK 路径的强制门禁</li>
 *   <li>安全检查不通过时创建有界 revision 任务，maxRevisions 耗尽后明确失败</li>
 * </ol>
 *
 * <p>与 SEQUENTIAL/GRAPH 共用 AgentContext/Blackboard/AgentRunResult/AgentStep 模型。</p>
 */
public class EventDrivenAgentRuntime implements AgentRuntime {

    public static final int DEFAULT_MAX_ROUNDS = 8;
    public static final int DEFAULT_MAX_REVISIONS = 2;

    private final AgentRegistry registry;
    private final int maxRounds;
    private final int maxRevisions;

    public EventDrivenAgentRuntime(AgentRegistry registry) {
        this(registry, DEFAULT_MAX_ROUNDS, DEFAULT_MAX_REVISIONS);
    }

    public EventDrivenAgentRuntime(AgentRegistry registry, int maxRounds, int maxRevisions) {
        if (maxRounds < 1) throw new IllegalArgumentException("maxRounds must be >= 1");
        if (maxRevisions < 0) throw new IllegalArgumentException("maxRevisions must be >= 0");
        this.registry = registry;
        this.maxRounds = maxRounds;
        this.maxRevisions = maxRevisions;
    }

    /**
     * 从 Spring 注入的 Agent 列表和配置构建。
     */
    public static EventDrivenAgentRuntime fromAgents(
            List<MindBridgeAgent> agents, int maxRounds, int maxRevisions) {
        var reg = new AgentRegistry(agents, AgentRegistry.DEFAULT_THRESHOLD);
        return new EventDrivenAgentRuntime(reg, maxRounds, maxRevisions);
    }

    @Override
    public RuntimeMode mode() {
        return RuntimeMode.EVENT_DRIVEN;
    }

    @Override
    public AgentRunResult run(UserAccount user, ChatSession session, String originalInput, String modelInput) {
        AgentContext context = new AgentContext(user, session, originalInput, modelInput);
        int revisions = 0;

        for (int round = 1; round <= maxRounds; round++) {
            AgentBlackboard board = context.blackboard();

            // 1. 推导待办任务
            List<AgentTask> tasks = deriveTasks(board);
            if (tasks.isEmpty()) {
                // 所有必需 artifact 已存在，检查安全门禁
                if (safetyGatePassed(board)) {
                    context.finish();
                    return AgentRunResult.from(context);
                }
                // 安全门禁未通过 → 创建 revision 任务
                if (revisions >= maxRevisions) {
                    throw new AgentRuntimeExecutionException(
                            "Event-driven runtime exhausted max revisions (" + maxRevisions
                                    + ") without passing safety gate",
                            RuntimeMode.EVENT_DRIVEN, context);
                }
                revisions++;
                tasks = List.of(revisionTask(board));
            }

            // 2. 为每个任务寻找最佳候选并执行（每轮只执行第一个待办任务）
            AgentTask task = tasks.get(0);
            Candidate selected = selectCandidate(board, task);
            if (selected == null) {
                throw new AgentRuntimeExecutionException(
                        "No candidate agent for task '" + task.name()
                                + "' (desired artifact: " + task.desiredArtifact() + ")",
                        RuntimeMode.EVENT_DRIVEN, context);
            }

            // 3. 执行选中的 Agent
            MindBridgeAgent agent = registry.profile(selected.agentName()).agent();
            AgentDecision decision;
            try {
                decision = agent.act(context);
            } catch (Exception e) {
                context.blackboard().addEvent(new AgentEvent(
                        "AGENT_EXECUTION_ERROR", selected.agentName(),
                        "Agent 执行异常: " + e.getClass().getSimpleName()));
                throw new AgentRuntimeExecutionException(
                        "Agent " + selected.agentName() + " failed during task '"
                                + task.name() + "': " + e.getMessage(),
                        RuntimeMode.EVENT_DRIVEN, context);
            }

            int step = context.steps().size() + 1;
            context.addStep(AgentStep.of(step, selected.agentName(), decision));

            // 4. 应用 decision artifacts 到 blackboard
            if (decision.artifacts() != null) {
                for (AgentArtifact artifact : decision.artifacts()) {
                    context.blackboard().addArtifact(artifact);
                }
            }

            if (decision.complete()) {
                if (safetyGatePassed(context.blackboard())) {
                    context.finish();
                    return AgentRunResult.from(context);
                }
                // complete 但安全门禁未通过 → revision
                if (revisions >= maxRevisions) {
                    throw new AgentRuntimeExecutionException(
                            "Event-driven runtime exhausted max revisions after agent completion",
                            RuntimeMode.EVENT_DRIVEN, context);
                }
                revisions++;
            }
        }

        throw new AgentRuntimeExecutionException(
                "Event-driven runtime exhausted max rounds (" + maxRounds
                        + ") without completion",
                RuntimeMode.EVENT_DRIVEN, context);
    }

    /**
     * 从 Blackboard 缺失 artifact 推导待办任务（确定性、幂等）。
     *
     * <p>任务推导顺序固定：memory → intent → (intent 分支) → response。
     * 已存在的 artifact 不生成重复任务。</p>
     */
    List<AgentTask> deriveTasks(AgentBlackboard board) {
        List<AgentTask> tasks = new ArrayList<>();

        if (!board.hasFlag(AgentFlag.MEMORY_LOADED) && !board.getArtifact("memory").isPresent()) {
            tasks.add(new AgentTask("produce-memory", "memory", "加载用户记忆"));
            return tasks;
        }

        if (!board.hasFlag(AgentFlag.INTENT_ROUTED) && !board.getArtifact(AgentArtifact.NAME_INTENT).isPresent()) {
            tasks.add(new AgentTask("produce-intent", AgentArtifact.NAME_INTENT, "分类用户意图"));
            return tasks;
        }

        IntentType intent = board.getArtifact(AgentArtifact.NAME_INTENT)
                .filter(a -> a.payload() instanceof IntentType)
                .map(a -> (IntentType) a.payload())
                .orElse(null);

        if (intent != null && intent != IntentType.CHAT) {
            if (!board.hasFlag(AgentFlag.KNOWLEDGE_HANDLED) && !board.getArtifact(AgentArtifact.NAME_KNOWLEDGE).isPresent()) {
                tasks.add(new AgentTask("produce-knowledge", AgentArtifact.NAME_KNOWLEDGE, "检索知识库"));
                return tasks;
            }
            if (!board.hasFlag(AgentFlag.RISK_ASSESSED) && !board.getArtifact(AgentArtifact.NAME_ASSESSMENT).isPresent()) {
                tasks.add(new AgentTask("produce-assessment", AgentArtifact.NAME_ASSESSMENT, "评估风险"));
                return tasks;
            }
        }

        if (!board.hasFlag(AgentFlag.RESPONSE_PLANNED) && !board.getArtifact(AgentArtifact.NAME_RESPONSE).isPresent()) {
            tasks.add(new AgentTask("produce-response", AgentArtifact.NAME_RESPONSE, "规划回复"));
            return tasks;
        }

        return tasks;
    }

    /**
     * 安全门禁：CONSULT/RISK 路径必须经过 RiskGuardian 评估后才能完成。
     */
    boolean safetyGatePassed(AgentBlackboard board) {
        IntentType intent = board.getArtifact(AgentArtifact.NAME_INTENT)
                .filter(a -> a.payload() instanceof IntentType)
                .map(a -> (IntentType) a.payload())
                .orElse(null);

        if (intent == null) return false;
        if (intent == IntentType.CHAT) return true;

        // CONSULT/RISK: 必须有 assessment artifact 且 RISK_ASSESSED 标记
        return board.hasFlag(AgentFlag.RISK_ASSESSED)
                && board.getArtifact(AgentArtifact.NAME_ASSESSMENT).isPresent();
    }

    /**
     * 为指定任务从 Registry 候选中选择最佳候选。
     * 候选的 producesArtifact 必须匹配任务的 desiredArtifact。
     */
    Candidate selectCandidate(AgentBlackboard board, AgentTask task) {
        List<Candidate> candidates = registry.candidates(board);
        for (Candidate c : candidates) {
            if (c.capability().producesArtifact().equals(task.desiredArtifact())) {
                return c;
            }
        }
        return null;
    }

    /**
     * 创建 revision 任务（安全检查不通过时）。
     */
    AgentTask revisionTask(AgentBlackboard board) {
        if (!board.hasFlag(AgentFlag.RISK_ASSESSED)) {
            return new AgentTask("revise-assessment", AgentArtifact.NAME_ASSESSMENT,
                    "安全门禁未通过：需要重新评估风险");
        }
        return new AgentTask("revise-response", AgentArtifact.NAME_RESPONSE,
                "安全门禁要求修正回复");
    }
}