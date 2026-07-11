package com.mindbridge.agent.service.agent.runtime;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Agent 运行时工厂。
 *
 * <p>启动时根据 {@code mindbridge.agent.runtime-mode} 选择对应的 {@link AgentRuntime} 实现。
 * 未知模式或未实现的模式会明确失败，不静默回退。</p>
 */
@Service
public class AgentRuntimeFactory {

    private final List<MindBridgeAgent> agents;
    private final MindBridgeProperties properties;

    public AgentRuntimeFactory(List<MindBridgeAgent> agents, MindBridgeProperties properties) {
        this.agents = agents;
        this.properties = properties;
    }

    /**
     * 按配置创建对应的 AgentRuntime 实例。
     *
     * @return 运行时实例
     * @throws IllegalArgumentException          未知 runtime-mode
     * @throws UnsupportedOperationException    已知但尚未实现的 runtime-mode
     */
    public AgentRuntime runtime() {
        RuntimeMode mode = resolveMode();
        return switch (mode) {
            case SEQUENTIAL -> new SequentialAgentRuntime(agents);
            case GRAPH -> GraphAgentRuntime.fromAgents(agents);
            case EVENT_DRIVEN -> throw new UnsupportedOperationException(
                    "Runtime mode EVENT_DRIVEN is not yet implemented. "
                            + "Please use SEQUENTIAL or GRAPH for now.");
        };
    }

    /**
     * 按指定模式创建 AgentRuntime（用于测试或显式选择）。
     */
    public AgentRuntime runtime(RuntimeMode mode) {
        return switch (mode) {
            case SEQUENTIAL -> new SequentialAgentRuntime(agents);
            case GRAPH -> GraphAgentRuntime.fromAgents(agents);
            case EVENT_DRIVEN -> throw new UnsupportedOperationException(
                    "Runtime mode EVENT_DRIVEN is not yet implemented.");
        };
    }

    private RuntimeMode resolveMode() {
        String raw = properties.getAgent().getRuntimeMode();
        if (raw == null || raw.isBlank()) {
            return RuntimeMode.SEQUENTIAL;
        }
        try {
            return RuntimeMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown runtime-mode: '" + raw + "'. "
                            + "Valid values: SEQUENTIAL, GRAPH, EVENT_DRIVEN.");
        }
    }
}