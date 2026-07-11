package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.agent.registry.AgentCapability;
import com.mindbridge.agent.service.agent.registry.AgentTask;
import java.util.List;

/**
 * MindBridge 专业 Agent 接口。
 *
 * <p>每个 Agent 只负责一个清晰职责，由 AgentRuntimeService 按上下文状态循环选择下一步。</p>
 *
 * <p>批次 2 新增声明式调度契约：</p>
 * <ul>
 *   <li>{@link #decide(AgentBlackboard)} —— Agent 声明对当前 Blackboard 状态的能力和置信度</li>
 *   <li>{@link #act(AgentBlackboard, AgentTask)} —— 声明式执行入口，默认桥接到旧 {@code act(AgentContext)}</li>
 * </ul>
 * <p>未迁移的 Agent 默认不参与声明式竞争（decide 返回空列表）。</p>
 */
public interface MindBridgeAgent {

    AgentName name();

    boolean supports(AgentContext context);

    AgentDecision act(AgentContext context);

    /**
     * 声明式调度：Agent 根据当前 Blackboard 状态返回自己的能力列表。
     *
     * <p>默认返回空列表，表示该 Agent 暂不参与声明式调度。
     * 已迁移的 Agent 应重写此方法，返回与当前职责一致的 {@link AgentCapability}。</p>
     *
     * @param blackboard 当前不可变 Blackboard 状态
     * @return 能力列表（空列表表示不参与本轮竞争）
     */
    default List<AgentCapability> decide(AgentBlackboard blackboard) {
        return List.of();
    }

    /**
     * 声明式执行入口：在声明式运行时中被选中后执行具体任务。
     *
     * <p>默认桥接到旧的 {@code act(AgentContext)} 路径。
     * 子类可以重写此方法以直接操作 Blackboard 而非 AgentContext。</p>
     *
     * @param blackboard 当前 Blackboard 状态
     * @param task       被选中执行的任务
     * @return 执行决策
     */
    default AgentDecision act(AgentBlackboard blackboard, AgentTask task) {
        return act(AgentContext.from(blackboard));
    }
}