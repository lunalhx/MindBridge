package com.mindbridge.agent.service.agent.runtime;

import com.mindbridge.agent.service.agent.AgentName;

/**
 * Agent 步骤监听器接口。
 *
 * <p>由运行时在每个 Agent act() 前后调用，用于向 SSE 流推送步骤进度事件。
 * 此接口不耦合 SSE/Sinks 类型，Runtime 只依赖此中立接口。</p>
 */
public interface AgentStepListener {

    /**
     * Agent 开始执行时调用。
     *
     * @param stepNumber 步骤序号
     * @param agentName  执行 Agent 名称
     * @param action     动作类型名称（AgentAction 枚举名）
     */
    void onStarted(int stepNumber, AgentName agentName, String action);

    /**
     * Agent 成功完成时调用。
     *
     * @param stepNumber 步骤序号
     * @param agentName  执行 Agent 名称
     * @param action     动作类型名称
     * @param observation 观察摘要（已截断脱敏）
     */
    void onCompleted(int stepNumber, AgentName agentName, String action, String observation);

    /**
     * Agent 执行失败时调用。
     *
     * @param stepNumber 步骤序号
     * @param agentName  执行 Agent 名称
     * @param action     动作类型名称
     * @param errorSummary 错误摘要（已截断脱敏）
     */
    void onFailed(int stepNumber, AgentName agentName, String action, String errorSummary);

    /** 空实现（默认无操作），用于不需要监听的场景。 */
    AgentStepListener NOOP = new AgentStepListener() {
        @Override public void onStarted(int s, AgentName a, String act) {}
        @Override public void onCompleted(int s, AgentName a, String act, String obs) {}
        @Override public void onFailed(int s, AgentName a, String act, String err) {}
    };
}