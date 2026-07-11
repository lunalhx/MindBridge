package com.mindbridge.agent.service.agent.registry;

import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import java.util.List;
import java.util.Objects;

/**
 * Agent 注册信息（不可变）。
 *
 * <p>封装一个 Agent 的名称和能力列表，由 {@link AgentRegistry} 在启动时从
 * Spring 注入的 {@code List<MindBridgeAgent>} 构建。</p>
 *
 * @param agentName    Agent 标识
 * @param agent        Agent 实例引用
 * @param capabilities 该 Agent 声明的所有能力
 */
public record AgentProfile(
        AgentName agentName,
        MindBridgeAgent agent,
        List<AgentCapability> capabilities
) {
    public AgentProfile {
        Objects.requireNonNull(agentName, "agentName must not be null");
        Objects.requireNonNull(agent, "agent must not be null");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}