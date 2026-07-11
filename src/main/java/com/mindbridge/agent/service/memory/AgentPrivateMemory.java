package com.mindbridge.agent.service.memory;

import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import java.util.List;

/**
 * Per-Agent 私有记忆接口。
 *
 * <p>每个 Agent 拥有独立的记忆空间，按 {@code agentName + sessionId} 隔离。
 * 不同 Agent 之间互不可见，避免跨 Agent 泄漏。</p>
 *
 * <p>核心操作：</p>
 * <ul>
 *   <li>{@link #append} —— 追加一条消息到 Agent 私有记忆</li>
 *   <li>{@link #recent} —— 读取 Agent 最近的私有记忆消息</li>
 *   <li>{@link #summarize} —— 获取当前记忆的 LLM 摘要（如已有）</li>
 *   <li>{@link #compact} —— 达到阈值后压缩记忆，保留摘要 + 最近消息</li>
 * </ul>
 */
public interface AgentPrivateMemory {

    /** 追加一条消息到指定 Agent 的私有记忆。 */
    void append(AgentName agentName, String sessionId, MemoryMessage message);

    /** 读取指定 Agent 的最近私有记忆消息。 */
    List<MemoryMessage> recent(AgentName agentName, String sessionId);

    /** 获取指定 Agent 当前记忆的摘要（如无摘要返回空字符串）。 */
    String summarize(AgentName agentName, String sessionId);

    /** 压缩指定 Agent 的私有记忆，保留 LLM 摘要 + 最近 N 条消息。 */
    void compact(AgentName agentName, String sessionId);
}