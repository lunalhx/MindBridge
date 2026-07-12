package com.mindbridge.agent.service.agent.runtime;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentRunResult;

/**
 * Agent 运行时接口。
 *
 * <p>每种运行时模式实现不同的 Agent 调度策略，但对外提供统一的执行入口。
 * 由 {@link AgentRuntimeFactory} 按配置选择具体实现。</p>
 */
public interface AgentRuntime {

    /**
     * 执行一轮 Agent loop（无监听器）。
     */
    default AgentRunResult run(UserAccount user, ChatSession session, String originalInput, String modelInput) {
        return run(user, session, originalInput, modelInput, AgentStepListener.NOOP);
    }

    /**
     * 执行一轮 Agent loop，带步骤监听器。
     *
     * @param stepListener 每个 Agent act() 前后调用
     */
    AgentRunResult run(UserAccount user, ChatSession session, String originalInput, String modelInput,
                        AgentStepListener stepListener);

    /**
     * 用预构建的 AgentContext 执行 Agent loop。
     *
     * <p>默认实现从 context 提取参数委托给 {@link #run(UserAccount, ChatSession, String, String, AgentStepListener)}。
     * 具体运行时可以重写此方法以直接使用提供的 context（包括已恢复的 Blackboard 状态），
     * 这对 Checkpoint/中断恢复至关重要：恢复的 context 带有已完成的 flags/artifacts，
     * 运行时应从 context 当前状态继续，而非从头开始。</p>
     */
    default AgentRunResult run(AgentContext context, AgentStepListener stepListener) {
        return run(context.user(), context.session(), context.originalInput(), context.modelInput(),
                stepListener);
    }

    /**
     * 返回当前运行时模式。
     */
    RuntimeMode mode();
}