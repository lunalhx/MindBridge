package com.mindbridge.agent.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.ai.AiMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AgentModelRegistryTest {

    private MindBridgeProperties properties;
    private AiClient defaultClient;
    private AiClientFactory factory;

    @BeforeEach
    void setUp() {
        properties = new MindBridgeProperties();
        defaultClient = new StubAiClient("default-reply");
        factory = new AiClientFactory(properties);
    }

    private AgentModelRegistry newRegistry() {
        return new AgentModelRegistry(properties, defaultClient, factory);
    }

    // ────────── AgentModelProfile 校验 ──────────

    @Test
    void profileShouldAcceptValidOllama() {
        var p = new AgentModelProfile("ollama", "qwen2.5:7b", 0.7, 512);
        assertThat(p.provider()).isEqualTo("ollama");
        assertThat(p.model()).isEqualTo("qwen2.5:7b");
        assertThat(p.temperature()).isEqualTo(0.7);
        assertThat(p.maxTokens()).isEqualTo(512);
    }

    @Test
    void profileShouldAcceptValidOpenAi() {
        var p = new AgentModelProfile("openai", "gpt-4o-mini", 0.3, 256);
        assertThat(p.provider()).isEqualTo("openai");
    }

    @Test
    void profileShouldNormalizeProviderCase() {
        var p = new AgentModelProfile("OLLAMA", "qwen2.5:7b", 0.7, 512);
        assertThat(p.provider()).isEqualTo("ollama");
    }

    @Test
    void profileShouldRejectInvalidProvider() {
        assertThatThrownBy(() -> new AgentModelProfile("anthropic", "claude-3", 0.7, 512))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported provider")
                .hasMessageContaining("anthropic");
    }

    @Test
    void profileShouldRejectBlankModel() {
        assertThatThrownBy(() -> new AgentModelProfile("ollama", "  ", 0.7, 512))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileShouldRejectTemperatureOutOfRange() {
        assertThatThrownBy(() -> new AgentModelProfile("ollama", "model", -0.1, 512))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentModelProfile("ollama", "model", 2.1, 512))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileShouldRejectNonPositiveMaxTokens() {
        assertThatThrownBy(() -> new AgentModelProfile("ollama", "model", 0.7, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentModelProfile("ollama", "model", 0.7, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileShouldRejectNullProvider() {
        assertThatThrownBy(() -> new AgentModelProfile(null, "model", 0.7, 512))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void profileFromDefaultShouldBuildFromProperties() {
        var p = AgentModelProfile.fromDefault(properties.getAi());
        assertThat(p.provider()).isEqualTo("ollama");
        assertThat(p.model()).isEqualTo(properties.getAi().getOllama().getModel());
        assertThat(p.temperature()).isEqualTo(properties.getAi().getTemperature());
        assertThat(p.maxTokens()).isEqualTo(properties.getAi().getMaxTokens());
    }

    // ────────── 默认兼容 ──────────

    @Test
    void noOverridesShouldReturnDefaultClientForAllAgents() {
        var registry = newRegistry();

        for (AgentName name : AgentName.values()) {
            assertThat(registry.clientFor(name)).isSameAs(defaultClient);
            assertThat(registry.hasOverride(name)).isFalse();
        }
    }

    @Test
    void noOverridesShouldUseDefaultProfile() {
        var registry = newRegistry();
        var profile = registry.profileFor(AgentName.COUNSELOR_AGENT);

        assertThat(profile.provider()).isEqualTo("ollama");
        assertThat(profile.model()).isEqualTo(properties.getAi().getOllama().getModel());
    }

    // ────────── 单 Agent override ──────────

    @Test
    void singleOverrideShouldReturnOverrideClientForThatAgent() {
        properties.getAi().setProvider("ollama");
        var overrideConfig = new MindBridgeProperties.AgentModelProfileConfig();
        overrideConfig.setProvider("ollama");
        overrideConfig.setModel("qwen2.5:14b");
        overrideConfig.setTemperature(0.8);
        overrideConfig.setMaxTokens(1024);
        properties.getAgentModels().setOverrides(
                Map.of("counselor", overrideConfig));

        var registry = newRegistry();

        assertThat(registry.hasOverride(AgentName.COUNSELOR_AGENT)).isTrue();
        assertThat(registry.hasOverride(AgentName.COMPANION_AGENT)).isFalse();

        // Override agent gets different client
        AiClient counselorClient = registry.clientFor(AgentName.COUNSELOR_AGENT);
        assertThat(counselorClient).isNotSameAs(defaultClient);

        // Non-override agent gets default
        assertThat(registry.clientFor(AgentName.COMPANION_AGENT)).isSameAs(defaultClient);

        // Override profile should reflect the override config
        var profile = registry.profileFor(AgentName.COUNSELOR_AGENT);
        assertThat(profile.model()).isEqualTo("qwen2.5:14b");
        assertThat(profile.temperature()).isEqualTo(0.8);
        assertThat(profile.maxTokens()).isEqualTo(1024);
    }

    // ────────── 缓存复用 ──────────

    @Test
    void sameProfileShouldReuseCachedClient() {
        properties.getAi().setProvider("ollama");
        var cfg = new MindBridgeProperties.AgentModelProfileConfig();
        cfg.setProvider("ollama");
        cfg.setModel("qwen2.5:14b");
        cfg.setTemperature(0.8);
        cfg.setMaxTokens(512);
        properties.getAgentModels().setOverrides(Map.of(
                "counselor", cfg,
                "companion", cfg)); // same profile for two agents

        var registry = newRegistry();

        AiClient counselorClient = registry.clientFor(AgentName.COUNSELOR_AGENT);
        AiClient companionClient = registry.clientFor(AgentName.COMPANION_AGENT);

        // Same profile → same cached client instance
        assertThat(counselorClient).isSameAs(companionClient);
    }

    @Test
    void differentProfilesShouldReturnDifferentClients() {
        properties.getAi().setProvider("ollama");
        var cfg1 = new MindBridgeProperties.AgentModelProfileConfig();
        cfg1.setProvider("ollama");
        cfg1.setModel("qwen2.5:7b");
        cfg1.setTemperature(0.7);
        cfg1.setMaxTokens(512);

        var cfg2 = new MindBridgeProperties.AgentModelProfileConfig();
        cfg2.setProvider("ollama");
        cfg2.setModel("qwen2.5:14b");
        cfg2.setTemperature(0.8);
        cfg2.setMaxTokens(1024);

        properties.getAgentModels().setOverrides(Map.of(
                "companion", cfg1,
                "counselor", cfg2));

        var registry = newRegistry();

        AiClient companionClient = registry.clientFor(AgentName.COMPANION_AGENT);
        AiClient counselorClient = registry.clientFor(AgentName.COUNSELOR_AGENT);

        assertThat(companionClient).isNotSameAs(counselorClient);
        assertThat(companionClient).isNotSameAs(defaultClient);
        assertThat(counselorClient).isNotSameAs(defaultClient);
    }

    // ────────── OpenAI override 缺少 API key ──────────

    @Test
    void openAiOverrideWithoutApiKeyShouldThrowOnClientCreation() {
        properties.getAi().setProvider("ollama");
        properties.getAi().getOpenai().setApiKey("");

        var cfg = new MindBridgeProperties.AgentModelProfileConfig();
        cfg.setProvider("openai");
        cfg.setModel("gpt-4o-mini");
        cfg.setTemperature(0.3);
        cfg.setMaxTokens(256);
        properties.getAgentModels().setOverrides(Map.of("risk-guardian", cfg));

        var registry = newRegistry();

        assertThatThrownBy(() -> registry.clientFor(AgentName.RISK_GUARDIAN_AGENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openai")
                .hasMessageContaining("API_KEY");
    }

    // ────────── 无效 provider override ──────────

    @Test
    void invalidProviderInOverrideShouldFail() {
        var cfg = new MindBridgeProperties.AgentModelProfileConfig();
        cfg.setProvider("anthropic");
        cfg.setModel("claude-3");
        cfg.setTemperature(0.7);
        cfg.setMaxTokens(512);
        properties.getAgentModels().setOverrides(Map.of("counselor", cfg));

        assertThatThrownBy(() -> newRegistry())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported provider");
    }

    // ────────── AgentName 解析 ──────────

    @Test
    void overrideKeyShouldMatchAgentNameCaseInsensitive() {
        var cfg = new MindBridgeProperties.AgentModelProfileConfig();
        cfg.setProvider("ollama");
        cfg.setModel("test-model");
        cfg.setTemperature(0.7);
        cfg.setMaxTokens(512);
        properties.getAgentModels().setOverrides(Map.of("RISK-GUARDIAN", cfg));

        var registry = newRegistry();
        assertThat(registry.hasOverride(AgentName.RISK_GUARDIAN_AGENT)).isTrue();
    }

    @Test
    void unknownOverrideKeyShouldBeSkipped() {
        var cfg = new MindBridgeProperties.AgentModelProfileConfig();
        cfg.setProvider("ollama");
        cfg.setModel("test-model");
        cfg.setTemperature(0.7);
        cfg.setMaxTokens(512);
        properties.getAgentModels().setOverrides(Map.of("nonexistent-agent", cfg));

        var registry = newRegistry();
        // Unknown key → no overrides active
        for (AgentName name : AgentName.values()) {
            assertThat(registry.hasOverride(name)).isFalse();
        }
    }

    // ────────── 部分 override 字段使用 default ──────────

    @Test
    void overrideWithOnlyModelShouldInheritOtherFieldsFromDefault() {
        var cfg = new MindBridgeProperties.AgentModelProfileConfig();
        cfg.setModel("qwen2.5:14b");
        // provider, temperature, maxTokens not set → inherit from default
        properties.getAgentModels().setOverrides(Map.of("counselor", cfg));

        var registry = newRegistry();
        var profile = registry.profileFor(AgentName.COUNSELOR_AGENT);

        assertThat(profile.model()).isEqualTo("qwen2.5:14b");
        assertThat(profile.provider()).isEqualTo(properties.getAi().getProvider());
        assertThat(profile.temperature()).isEqualTo(properties.getAi().getTemperature());
        assertThat(profile.maxTokens()).isEqualTo(properties.getAi().getMaxTokens());
    }

    // ────────── 多 Agent override ──────────

    @Test
    void multipleOverridesShouldRouteCorrectly() {
        var cfg1 = new MindBridgeProperties.AgentModelProfileConfig();
        cfg1.setProvider("ollama");
        cfg1.setModel("qwen2.5:7b");
        cfg1.setTemperature(0.7);
        cfg1.setMaxTokens(512);

        var cfg2 = new MindBridgeProperties.AgentModelProfileConfig();
        cfg2.setProvider("ollama");
        cfg2.setModel("qwen2.5:14b");
        cfg2.setTemperature(0.8);
        cfg2.setMaxTokens(1024);

        var cfg3 = new MindBridgeProperties.AgentModelProfileConfig();
        cfg3.setProvider("ollama");
        cfg3.setModel("qwen2.5:32b");
        cfg3.setTemperature(0.3);
        cfg3.setMaxTokens(256);

        properties.getAgentModels().setOverrides(new LinkedHashMap<>(Map.of(
                "companion", cfg1,
                "counselor", cfg2,
                "risk-guardian", cfg3)));

        var registry = newRegistry();

        assertThat(registry.hasOverride(AgentName.COMPANION_AGENT)).isTrue();
        assertThat(registry.hasOverride(AgentName.COUNSELOR_AGENT)).isTrue();
        assertThat(registry.hasOverride(AgentName.RISK_GUARDIAN_AGENT)).isTrue();
        assertThat(registry.hasOverride(AgentName.MEMORY_AGENT)).isFalse();

        // Each gets a different client (different profiles)
        var companionClient = registry.clientFor(AgentName.COMPANION_AGENT);
        var counselorClient = registry.clientFor(AgentName.COUNSELOR_AGENT);
        var riskGuardianClient = registry.clientFor(AgentName.RISK_GUARDIAN_AGENT);
        var memoryClient = registry.clientFor(AgentName.MEMORY_AGENT);

        assertThat(companionClient).isNotSameAs(counselorClient);
        assertThat(counselorClient).isNotSameAs(riskGuardianClient);
        assertThat(memoryClient).isSameAs(defaultClient); // no override → default
    }

    // ────────── Helpers ──────────

    private static class StubAiClient implements AiClient {
        private final String reply;
        StubAiClient(String reply) { this.reply = reply; }
        @Override public String complete(List<AiMessage> messages) { return reply; }
        @Override public Flux<String> stream(List<AiMessage> messages) { return Flux.just(reply); }
    }
}