package com.mindbridge.agent.service.ai;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.agent.AgentName;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

/**
 * Agent 模型配置注册中心。
 *
 * <p>管理 default profile 和 per-agent overrides，按 profile 懒加载并缓存 AiClient。
 * 未配置 override 的 Agent 使用 default profile（复用现有 AiClient bean 或按 default 配置创建）。</p>
 *
 * <p>缓存策略：相同 provider+model+temperature+maxTokens 的 profile 复用同一个 AiClient 实例，
 * 避免重复创建底层连接资源。</p>
 */
@Service
public class AgentModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentModelRegistry.class);

    private final MindBridgeProperties properties;
    private final AiClient defaultClient;
    private final AgentModelProfile defaultProfile;
    private final Map<AgentName, AgentModelProfile> overrideProfiles;

    /** profile key → AiClient 缓存 */
    private final Map<String, AiClient> clientCache = new ConcurrentHashMap<>();

    public AgentModelRegistry(MindBridgeProperties properties, AiClient defaultClient) {
        this.properties = properties;
        this.defaultClient = defaultClient;
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
        return clientForProfile(profile);
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

    private AiClient clientForProfile(AgentModelProfile profile) {
        String cacheKey = profile.provider() + ":" + profile.model() + ":"
                + profile.temperature() + ":" + profile.maxTokens();
        return clientCache.computeIfAbsent(cacheKey, k -> createClient(profile));
    }

    private AiClient createClient(AgentModelProfile profile) {
        String provider = profile.provider();
        if ("ollama".equals(provider)) {
            return createOllamaClient(profile);
        }
        if ("openai".equals(provider)) {
            return createOpenAiClient(profile);
        }
        throw new IllegalArgumentException("Unsupported provider: " + provider);
    }

    private AiClient createOllamaClient(AgentModelProfile profile) {
        MindBridgeProperties.Ollama ollama = properties.getAi().getOllama();
        OllamaApi api = OllamaApi.builder()
                .baseUrl(ollama.getBaseUrl())
                .build();
        OllamaOptions options = OllamaOptions.builder()
                .model(profile.model())
                .temperature(profile.temperature())
                .numPredict(profile.maxTokens())
                .topP(0.85)
                .repeatPenalty(1.12)
                .build();
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options)
                .build();
        return new SpringAiChatClient(model, model);
    }

    private AiClient createOpenAiClient(AgentModelProfile profile) {
        MindBridgeProperties.OpenAi openai = properties.getAi().getOpenai();
        if (openai.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "Agent override with provider=openai requires OPENAI_API_KEY to be set. "
                            + "Configure mindbridge.ai.openai.api-key or OPENAI_API_KEY env var.");
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(openai.getBaseUrl())
                .apiKey(openai.getApiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(profile.model())
                .temperature(profile.temperature())
                .maxTokens(profile.maxTokens())
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
        return new SpringAiChatClient(model, model);
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