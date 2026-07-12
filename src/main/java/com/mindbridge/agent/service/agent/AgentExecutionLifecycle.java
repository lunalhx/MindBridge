package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.MessageRole;
import com.mindbridge.agent.service.PrivacySanitizer;
import com.mindbridge.agent.service.memory.AgentPrivateMemoryRegistry;
import com.mindbridge.agent.service.memory.ShortTermMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 执行生命周期钩子。
 *
 * <p>统一为三种运行时（Sequential / Graph / EventDriven）提供步骤前后的私有记忆读写：
 * <ul>
 *   <li>{@link #beforeStep} 在 agent.act() 前注入当前 Agent 的私有记忆摘要到 {@link AgentContext}</li>
 *   <li>{@link #afterStep} 在 agent.act() 成功后将脱敏观察写入当前 Agent 的私有 Redis key 并触发 compact</li>
 * </ul>
 *
 * <p>私有记忆加载/写入失败不影响主链路，仅记录 debug 日志。</p>
 */
@Component
public class AgentExecutionLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionLifecycle.class);

    private final AgentPrivateMemoryRegistry privateMemory;
    private final PrivacySanitizer sanitizer;

    public AgentExecutionLifecycle(AgentPrivateMemoryRegistry privateMemory, PrivacySanitizer sanitizer) {
        this.privateMemory = privateMemory;
        this.sanitizer = sanitizer;
    }

    /**
     * Agent 步骤执行前的钩子：注入当前 Agent 的私有记忆摘要到 Context。
     * 仅当前正在执行的 Agent 可读取。
     */
    public void beforeStep(AgentContext context, AgentName agent, String sessionId) {
        try {
            String summary = privateMemory.summarize(agent, sessionId);
            context.setCurrentAgentPrivateMemory(summary != null ? summary : "");
        } catch (Exception e) {
            log.debug("Failed to load private memory for agent={}, error={}", agent, e.getClass().getSimpleName());
            // 私有记忆加载失败不影响主链路
        }
    }

    /**
     * Agent 步骤成功后的钩子：写入脱敏观察摘要到当前 Agent 的私有记忆，触发 compact。
     */
    public void afterStep(AgentContext context, AgentName agent, String sessionId, String observation) {
        try {
            String sanitized = sanitizer.sanitize(observation);
            // 使用 ShortTermMemoryService.MemoryMessage 的构造方式
            var message = new ShortTermMemoryService.MemoryMessage(MessageRole.SYSTEM, sanitized);
            privateMemory.append(agent, sessionId, message);
            privateMemory.compact(agent, sessionId);
        } catch (Exception e) {
            log.debug("Failed to write private memory for agent={}, error={}", agent, e.getClass().getSimpleName());
            // 私有记忆写入失败不影响主链路
        } finally {
            context.clearCurrentAgentPrivateMemory();
        }
    }
}