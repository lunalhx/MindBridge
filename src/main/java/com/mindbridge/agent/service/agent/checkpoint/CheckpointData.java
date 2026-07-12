package com.mindbridge.agent.service.agent.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.ai.AiMessage;
import java.time.Instant;
import java.util.List;

/**
 * Agent 运行时的 Checkpoint 数据。
 *
 * <p>记录一轮 Agent loop 的可恢复状态，用于进程中断后从上一步继续执行。</p>
 *
 * <p><b>不序列化的内容：</b>
 * <ul>
 *   <li>Spring Bean、AiClient —— 不属于 AgentContext/Blackboard</li>
 *   <li>JPA 实体快照 —— 只存 userId / sessionId 等稳定 ID，恢复时从 Repository 重新加载</li>
 *   <li>密钥 —— 不包含任何 API key 或密码</li>
 * </ul></p>
 *
 * <p><b>包含的字段：</b>
 * <ul>
 *   <li>{@code schemaVersion} —— 数据格式版本，不兼容时安全地从新运行开始</li>
 *   <li>{@code runtimeMode} —— 记录产生此 checkpoint 的运行时模式</li>
 *   <li>{@code runId} —— 本轮唯一运行标识，避免同一会话并发请求互相覆盖</li>
 *   <li>{@code userId / sessionId / sessionPublicId} —— 稳定 ID，用于恢复时重新加载实体</li>
 *   <li>{@code originalInput / modelInput} —— 原始输入和脱敏后输入</li>
 *   <li>{@code stepNumber} —— 上次成功完成的步骤号</li>
 *   <li>{@code round} —— 当前轮次（EVENT_DRIVEN 用）</li>
 *   <li>{@code createdAt} —— checkpoint 创建时间</li>
 *   <li>{@code status} —— "RUNNING" 或 "COMPLETED"</li>
 *   <li>{@code blackboard} —— Blackboard 快照（flags + artifacts + events）</li>
 *   <li>{@code steps} —— 已完成的步骤列表</li>
 *   <li>{@code previousHistory / modelHistory} —— Memory Agent 产出的历史消息</li>
 *   <li>{@code memoryBrief / knowledgeQuery / riskLevel} —— Agent 上下文中间字段</li>
 * </ul></p>
 */
public record CheckpointData(
        int schemaVersion,
        String runtimeMode,
        String runId,
        long userId,
        long sessionId,
        String sessionPublicId,
        String originalInput,
        String modelInput,
        int stepNumber,
        int round,
        Instant createdAt,
        String status,
        CheckpointBlackboard blackboard,
        List<CheckpointStep> steps,
        List<AiMessage> previousHistory,
        List<AiMessage> modelHistory,
        String memoryBrief,
        String knowledgeQuery,
        String riskLevel
) {
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** 从 AgentContext 构建一个 RUNNING 状态的 checkpoint。 */
    public static CheckpointData from(AgentContext context, String runId, String runtimeMode,
                                      int schemaVersion, int round, ObjectMapper mapper) {
        AgentBlackboard bb = context.blackboard();
        return new CheckpointData(
                schemaVersion,
                runtimeMode,
                runId,
                context.user() != null && context.user().getId() != null ? context.user().getId() : 0L,
                context.session() != null && context.session().getId() != null ? context.session().getId() : 0L,
                context.session() != null ? context.session().getPublicId() : null,
                context.originalInput(),
                context.modelInput(),
                context.steps().size(),
                round,
                Instant.now(),
                STATUS_RUNNING,
                CheckpointBlackboard.from(bb, mapper),
                context.steps().stream().map(CheckpointStep::from).toList(),
                context.previousHistory(),
                context.modelHistory(),
                context.memoryBrief(),
                context.knowledgeQuery(),
                context.riskLevel() != null ? context.riskLevel().name() : null
        );
    }
}
