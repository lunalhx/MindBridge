package com.mindbridge.agent.service.agent.blackboard;

import com.mindbridge.agent.service.agent.AgentName;
import java.time.Instant;

/**
 * Agent 状态变更事件（不可变，只追加审计记录）。
 *
 * <p>每个 event 记录一次有意义的操作或状态变化，
 * 包含事件类型、触发 Agent、时间和摘要。</p>
 *
 * <p><b>安全要求：</b>summary 不得包含敏感的完整学生输入。
 * 应使用抽象的概述描述，如 "已分类用户意图为 CHAT" 而非 "用户说：xxx"。</p>
 *
 * @param eventType 事件类型标识，如 "MEMORY_LOADED", "INTENT_CLASSIFIED"
 * @param agent     触发该事件的 Agent
 * @param timestamp 事件发生时间
 * @param summary   人类可读的事件摘要（不含敏感学生输入）
 */
public record AgentEvent(
        String eventType,
        AgentName agent,
        Instant timestamp,
        String summary
) {
    /** 常见事件类型常量 */
    public static final String TYPE_MEMORY_LOADED = "MEMORY_LOADED";
    public static final String TYPE_INTENT_CLASSIFIED = "INTENT_CLASSIFIED";
    public static final String TYPE_KNOWLEDGE_RETRIEVED = "KNOWLEDGE_RETRIEVED";
    public static final String TYPE_RISK_ASSESSED = "RISK_ASSESSED";
    public static final String TYPE_RESPONSE_PLANNED = "RESPONSE_PLANNED";
    public static final String TYPE_LOOP_FINISHED = "LOOP_FINISHED";

    /**
     * 创建事件（默认 timestamp 为当前时刻）。
     */
    public AgentEvent(String eventType, AgentName agent, String summary) {
        this(eventType, agent, Instant.now(), summary);
    }
}
