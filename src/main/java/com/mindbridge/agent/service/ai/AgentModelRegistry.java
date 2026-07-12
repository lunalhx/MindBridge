package com.mindbridge.agent.service.ai;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.agent.AgentName;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent 模型配置注册中心。
 *
 * <p>管理 default profile 和 per-agent overrides，按 profile 懒加载并缓存 AiClient。
 * 未配置 override 的 Agent 使用 default profile（复用现有 AiClient bean 或按 default 配置创建）。</p>
 *
 * <p>Phase 2 后，客户端创建统一委托给 {@link AiClientFactory}，保证 override 客户端
 * 也应用 {@link ResilientAiClient} 弹性包装，并与默认 AiClient 使用相同的缓存策略。</p>
 */
@Service
public class AgentModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentModelRegistry.class);

    private final MindBridgeProperties properties;
    private final AiClient defaultClient;
    private final AiClientFactory aiClientFactory;
    private final AgentModelProfile defaultProfile;
    private final Map<AgentName, AgentModelProfile> overrideProfiles;

    public AgentModelRegistry(MindBridgeProperties properties, AiClient defaultClient, AiClientFactory aiClientFactory) {
        this.properties = properties;
        this.defaultClient = defaultClient;
        this.aiClientFactory = aiClientFactory;
        this.defaultProfile = AgentModelProfile.fromDefault(properties.getAi());
        this.overrideProfiles = buildOverrideProfiles();
    }

    /**
     * 返回指定 Agent 的 AiClient。
     *
     * <p>有 override 的 Agent 使用 override profile 创建的客户端；
     * 无 override 的 Agent 返回 default AiClient。</p>
     */
    public AiClient clientFor(AgentName agentName) {
        AgentModelProfile profile = overrideProfiles.get(agentName);
        if (profile == null) {
            return defaultClient;
        }
        return aiClientFactory.createClient(profile);
    }

    /**
     * 返回指定 Agent 的 profile。
     */
    public AgentModelProfile profileFor(AgentName agentName) {
        return overrideProfiles.getOrDefault(agentName, defaultProfile);
    }

    /**
     * 是否有 override 配置。
     */
    public boolean hasOverride(AgentName agentName) {
        return overrideProfiles.containsKey(agentName);
    }

    /**
     * 从配置构建 per-agent override profiles。
     */
    private Map<AgentName, AgentModelProfile> buildOverrideProfiles() {
        Map<AgentName, AgentModelProfile> overrides = new HashMap<>();
        Map<String, MindBridgeProperties.AgentModelProfileConfig> configMap =
                properties.getAgentModels().getOverrides();
        if (configMap == null || configMap.isEmpty()) {
            return Map.of();
        }
        for (Map.Entry<String, MindBridgeProperties.AgentModelProfileConfig> entry : configMap.entrySet()) {
            String key = entry.getKey();
            AgentName agentName = resolveAgentName(key);
            if (agentName == null) {
                log.warn("Agent model override key '{}' does not match any AgentName, skipping", key);
                continue;
            }
            MindBridgeProperties.AgentModelProfileConfig cfg = entry.getValue();
            if (cfg.getProvider() == null || cfg.getProvider().isBlank()) {
                cfg.setProvider(properties.getAi().getProvider());
            }
            if (cfg.getModel() == null || cfg.getModel().isBlank()) {
                cfg.setModel("ollama".equals(cfg.getProvider())
                        ? properties.getAi().getOllama().getModel()
                        : properties.getAi().getOpenai().getModel());
            }
            if (cfg.getTemperature() == null) {
                cfg.setTemperature(properties.getAi().getTemperature());
            }
            if (cfg.getMaxTokens() == null) {
                cfg.setMaxTokens(properties.getAi().getMaxTokens());
            }
            overrides.put(agentName, new AgentModelProfile(
                    cfg.getProvider(),
                    cfg.getModel(),
                    cfg.getTemperature(),
                    cfg.getMaxTokens()));
        }
        log.info("Agent model overrides loaded: {}", overrides.keySet());
        return Map.copyOf(overrides);
    }

    /**
     * 将配置 key 解析为 AgentName。
     * 支持大小写不敏感匹配，忽略下划线：risk-guardian, risk_guardian, RISK_GUARDIAN 均匹配。
     */
    private AgentName resolveAgentName(String key) {
        String normalized = key.toUpperCase().replace("-", "_");
        if (!normalized.endsWith("_AGENT")) {
            normalized = normalized + "_AGENT";
        }
        try {
            return AgentName.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}