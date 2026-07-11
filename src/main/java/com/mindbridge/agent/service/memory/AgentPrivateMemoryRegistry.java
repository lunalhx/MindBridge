package com.mindbridge.agent.service.memory;

import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Per-Agent 私有记忆统一访问器。
 *
 * <p>各 Agent 通过此服务读写自己的私有记忆，避免六份重复 Redis 逻辑。
 * 内部委托给 {@link AgentPrivateMemory} 实现，按 {@code agentName + sessionId} 隔离。</p>
 *
 * <p>每个 Agent 只能访问自己命名空间的记忆，不会泄漏给无关 Agent。</p>
 */
@Service
public class AgentPrivateMemoryRegistry {

    private final AgentPrivateMemory privateMemory;

    public AgentPrivateMemoryRegistry(AgentPrivateMemory privateMemory) {
        this.privateMemory = privateMemory;
    }

    /** 追加一条消息到指定 Agent 的私有记忆。 */
    public void append(AgentName agentName, String sessionId, MemoryMessage message) {
        privateMemory.append(agentName, sessionId, message);
    }

    /** 读取指定 Agent 的最近私有记忆。 */
    public List<MemoryMessage> recent(AgentName agentName, String sessionId) {
        return privateMemory.recent(agentName, sessionId);
    }

    /** 获取指定 Agent 当前记忆的摘要。 */
    public String summarize(AgentName agentName, String sessionId) {
        return privateMemory.summarize(agentName, sessionId);
    }

    /** 压缩指定 Agent 的私有记忆。 */
    public void compact(AgentName agentName, String sessionId) {
        privateMemory.compact(agentName, sessionId);
    }
}