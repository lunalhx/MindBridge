package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.agent.runtime.AgentRuntime;
import com.mindbridge.agent.service.agent.runtime.AgentRuntimeFactory;
import com.mindbridge.agent.service.agent.runtime.AgentStepListener;
import com.mindbridge.agent.service.agent.runtime.SequentialAgentRuntime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * MindBridge Agent Loop 运行时服务（兼容门面）。
 *
 * <p>每轮学生输入都会进入有限步循环：读取记忆、主控路由、知识检索、风险守护和回复规划。
 * 这里不是无限自主循环，而是受步数限制的安全 agent loop，适合心理安全场景。</p>
 *
 * <p>批次 3 后，实际调度逻辑委托给 {@link AgentRuntime}（由 {@link AgentRuntimeFactory} 按配置选择）。
 * 默认使用 {@link SequentialAgentRuntime}，保持与升级前完全一致的行为。</p>
 *
 * <p>保留 6-agent 直接构造入口，便于测试在不依赖 Spring 容器的情况下直接实例化。</p>
 */
@Service
public class AgentRuntimeService {

    private final AgentRuntime runtime;

    /**
     * Spring 主构造：通过工厂按配置选择运行时。
     */
    @Autowired
    public AgentRuntimeService(AgentRuntimeFactory factory) {
        this.runtime = factory.runtime();
    }

    /**
     * 测试兼容构造：直接注入 6 个 Agent，使用 SEQUENTIAL 模式。
     *
     * <p>等价于 {@code new SequentialAgentRuntime(List.of(agents...))}，
     * 保留此构造是为了兼容现有测试中 {@code new AgentRuntimeService(6 agents)} 的用法。</p>
     */
    public AgentRuntimeService(
            MemoryAgent memoryAgent,
            SupervisorAgent supervisorAgent,
            KnowledgeAgent knowledgeAgent,
            RiskGuardianAgent riskGuardianAgent,
            CompanionAgent companionAgent,
            CounselorAgent counselorAgent
    ) {
        this.runtime = new SequentialAgentRuntime(List.of(
                memoryAgent,
                supervisorAgent,
                knowledgeAgent,
                riskGuardianAgent,
                companionAgent,
                counselorAgent));
    }

    /**
     * 直接指定运行时的构造（用于测试或其他需要显式控制运行时的场景）。
     */
    public AgentRuntimeService(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    public AgentRunResult run(UserAccount user, ChatSession session, String originalInput, String modelInput) {
        return runtime.run(user, session, originalInput, modelInput, AgentStepListener.NOOP);
    }

    public AgentRunResult run(UserAccount user, ChatSession session, String originalInput, String modelInput,
                               AgentStepListener listener) {
        return runtime.run(user, session, originalInput, modelInput, listener);
    }
}