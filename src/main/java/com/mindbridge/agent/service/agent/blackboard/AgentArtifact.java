package com.mindbridge.agent.service.agent.blackboard;

import com.mindbridge.agent.service.agent.AgentName;
import java.time.Instant;

/**
 * Agent 产出物（不可变）。
 *
 * <p>每个 artifact 由某个 Agent 在某一时刻产出，带有具名标识和实际负载。
 * 至少能表达 intent、knowledge、assessment、response 等产出类型。</p>
 *
 * @param name     产出物名称，如 "intent", "knowledge", "assessment", "response"
 * @param producer 产出该 artifact 的 Agent
 * @param timestamp 产出时间
 * @param payload  实际负载数据（IntentType、SearchResult 列表、PsychologyAssessment、String 等）
 */
public record AgentArtifact(
        String name,
        AgentName producer,
        Instant timestamp,
        Object payload
) {
    /** 常见 artifact 名称常量 */
    public static final String NAME_INTENT = "intent";
    public static final String NAME_KNOWLEDGE = "knowledge";
    public static final String NAME_ASSESSMENT = "assessment";
    public static final String NAME_RESPONSE = "response";
}
