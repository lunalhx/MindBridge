package com.mindbridge.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;

@JsonInclude(JsonInclude.Include.NON_NULL)
/**
 * SSE 流式聊天事件。
 *
 * <p>事件顺序：meta → 零到多个 agent-step → token → done（失败时 error 替代 done）。</p>
 *
 * <p>旧字段 type/sessionId/content/intent/riskLevel 保持兼容；
 * agent-step 事件使用 agentName/action/status/observation 字段。</p>
 */
public record ChatStreamEvent(
        String type,
        String sessionId,
        String content,
        IntentType intent,
        RiskLevel riskLevel,
        String agentName,
        String action,
        String status,
        String observation
) {
    public static ChatStreamEvent meta(String sessionId) {
        return new ChatStreamEvent("meta", sessionId, "", null, null, null, null, null, null);
    }

    public static ChatStreamEvent token(String sessionId, String content) {
        return new ChatStreamEvent("token", sessionId, content, null, null, null, null, null, null);
    }

    public static ChatStreamEvent done(String sessionId) {
        return new ChatStreamEvent("done", sessionId, "", null, null, null, null, null, null);
    }

    public static ChatStreamEvent error(String sessionId, String content) {
        return new ChatStreamEvent("error", sessionId, content, null, null, null, null, null, null);
    }

    public static ChatStreamEvent agentStep(String sessionId, String agentName,
                                              String action, String status, String observation) {
        return new ChatStreamEvent("agent-step", sessionId, null, null, null,
                agentName, action, status, observation);
    }
}