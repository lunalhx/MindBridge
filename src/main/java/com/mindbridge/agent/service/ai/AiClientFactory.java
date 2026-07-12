package com.mindbridge.agent.service.ai;

import com.mindbridge.agent.config.MindBridgeProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * 统一 AiClient 工厂。
 *
 * <p>集中创建 Ollama/OpenAI 底层客户端，统一应用 {@link ResilientAiClient} 弹性包装。
 * 相同 profile 缓存复用，避免重复创建底层连接资源。</p>
 */
@Component
public class AiClientFactory {

    private final MindBridgeProperties properties;
    private final Map<String, AiClient> cache = new ConcurrentHashMap<>();

    public AiClientFactory(MindBridgeProperties properties) {
        this.properties = properties;
    }

    /**
     * 创建默认 AiClient（使用全局配置），应用 ResilientAiClient 包装。
     */
    public AiClient createDefaultClient() {
        var profile = AgentModelProfile.fromDefault(properties.getAi());
        return createClient(profile);
    }

    /**
     * 根据 profile 创建 AiClient，应用 ResilientAiClient 包装。
     * 相同 profile 缓存复用。
     */
    public AiClient createClient(AgentModelProfile profile) {
        String cacheKey = profile.provider() + ":" + profile.model() + ":"
                + profile.temperature() + ":" + profile.maxTokens();
        return cache.computeIfAbsent(cacheKey, k -> {
            AiClient raw = createRawClient(profile);
            var resilience = properties.getAi().getResilience();
            if (!resilience.isEnabled()) {
                return raw;
            }
            var config = new ResilientAiClient.ResilienceConfig(
                    true,
                    Math.max(1, resilience.getMaxAttempts()),
                    resilience.getInitialBackoffMs(),
                    resilience.getMaxBackoffMs(),
                    Math.max(1, resilience.getFailureThreshold()),
                    resilience.getOpenDurationMs()
            );
            return new ResilientAiClient(raw, config);
        });
    }

    /**
     * 创建原始 AiClient（不做弹性包装），用于 AgentModelRegistry 兼容。
     */
    public AiClient createRawClient(AgentModelProfile profile) {
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
                    "Provider=openai requires OPENAI_API_KEY. "
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
}