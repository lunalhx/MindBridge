package com.mindbridge.agent.service.agent.blackboard;

/**
 * Agent 运行过程中的状态标记，替代 AgentContext 中的 6 个 boolean。
 *
 * <p>每个标记只追加不删除，一旦设置就不可撤销，与不可变 Blackboard 的语义一致。</p>
 */
public enum AgentFlag {
    MEMORY_LOADED,
    INTENT_ROUTED,
    KNOWLEDGE_HANDLED,
    RISK_ASSESSED,
    RESPONSE_PLANNED,
    FINISHED
}
