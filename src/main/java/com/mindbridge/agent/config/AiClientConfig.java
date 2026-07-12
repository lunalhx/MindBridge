package com.mindbridge.agent.config;

import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 大模型客户端装配配置。
 *
 * <p>根据 application.yml 或环境变量选择本地项目模型或 OpenAI 客户端，
 * 让业务服务只依赖统一的 {@link AiClient} 接口。</p>
 *
 * <p>批次 11 后，默认 AiClient Bean 使用 {@link com.mindbridge.agent.service.ai.ResilientAiClient} 包装底层客户端，
 * 提供重试和断路器弹性能力。resilience.enabled=false 时透传原始客户端。</p>
 *
 * <p>Phase 2 后，客户端创建逻辑统一委托给 {@link AiClientFactory}，保证默认客户端与 per-agent
 * override 客户端使用相同的创建路径和弹性包装策略。</p>
 */
@Configuration
public class AiClientConfig {

    @Bean
    public AiClient aiClient(AiClientFactory aiClientFactory) {
        return aiClientFactory.createDefaultClient();
    }
}