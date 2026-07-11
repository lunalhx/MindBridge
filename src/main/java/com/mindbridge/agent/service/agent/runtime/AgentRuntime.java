package com.mindbridge.agent.service.agent.runtime;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.agent.AgentRunResult;

/**
 * Agent 运行时接口。
 *
 * <p>每种运行时模式实现不同的 Agent 调度策略，但对外提供统一的执行入口。
 * 由 {@link AgentRuntimeFactory} 按配置选择具体实现。</p>
 */
public interface AgentRuntime {

    /**
     * 执行一轮 Agent loop。
     *
     * @param user          当前用户
     * @param session       当前会话
     * @param originalInput 学生原始输入（已脱敏前）
     * @param modelInput    脱敏后的模型输入
     * @return Agent loop 结构化结果
     */
    AgentRunResult run(UserAccount user, ChatSession session, String originalInput, String modelInput);

    /**
     * 返回当前运行时模式。
     */
    RuntimeMode mode();
}