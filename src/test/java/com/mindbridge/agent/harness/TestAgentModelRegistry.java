package com.mindbridge.agent.harness;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AgentModelRegistry;

/**
 * 测试辅助：构建一个所有 Agent 都返回同一个固定 {@link AiClient} 的 {@link AgentModelRegistry}。
 *
 * <p>用于在测试中把 ScriptedAiClient / mock AiClient 接入六位 Agent，
 * 避免每个测试都手写完整 Spring 装配。override 检测仍走父类逻辑（基于 properties），
 * 但 {@link #clientFor(AgentName)} 始终返回固定客户端，保证测试不依赖真实底层连接。</p>
 */
public final class TestAgentModelRegistry extends AgentModelRegistry {

    private final AiClient fixedClient;

    public TestAgentModelRegistry(MindBridgeProperties properties, AiClient fixedClient) {
        super(properties, fixedClient, null);
        this.fixedClient = fixedClient;
    }

    @Override
    public AiClient clientFor(AgentName agentName) {
        return fixedClient;
    }

    /**
     * 快捷构造：使用默认 MindBridgeProperties。
     */
    public static TestAgentModelRegistry withFixedClient(AiClient client) {
        return new TestAgentModelRegistry(new MindBridgeProperties(), client);
    }
}