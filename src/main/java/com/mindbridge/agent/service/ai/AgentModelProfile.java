package com.mindbridge.agent.service.ai;

import com.mindbridge.agent.service.agent.AgentName;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 模型配置档案（不可变）。
 *
 * <p>声明某个 Agent 使用的 LLM provider、model、temperature 和 maxTokens。
 * 未配置 overrides 的 Agent 使用 default profile。</p>
 *
 * @param provider    模型提供方，仅允许 "ollama" 或 "openai"
 * @param model       模型名称
 * @param temperature 生成温度，范围 [0.0, 2.0]
 * @param maxTokens   单次最大生成 token 数，必须 > 0
 */
public record AgentModelProfile(
        String provider,
        String model,
        double temperature,
        int maxTokens
) {
    private static final Set<String> ALLOWED_PROVIDERS = Set.of("ollama", "openai");

    public AgentModelProfile {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        String normalized = provider.trim().toLowerCase();
        if (!ALLOWED_PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Unsupported provider: '" + provider + "'. Allowed: " + ALLOWED_PROVIDERS);
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException(
                    "temperature must be in [0.0, 2.0], got: " + temperature);
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxTokens must be > 0, got: " + maxTokens);
        }
        provider = normalized;
    }

    /** 从现有 MindBridgeProperties.Ai 配置构建 default profile。 */
    public static AgentModelProfile fromDefault(
            com.mindbridge.agent.config.MindBridgeProperties.Ai ai) {
        String model = "ollama".equals(ai.getProvider())
                ? ai.getOllama().getModel() : ai.getOpenai().getModel();
        return new AgentModelProfile(
                ai.getProvider(),
                model,
                ai.getTemperature(),
                ai.getMaxTokens());
    }
}