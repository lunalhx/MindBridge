package com.mindbridge.agent.service.agent.checkpoint;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.ChatSessionRepository;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.service.agent.runtime.AgentRuntime;
import com.mindbridge.agent.service.agent.runtime.AgentStepListener;
import com.mindbridge.agent.service.agent.runtime.RuntimeMode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checkpoint 感知的 Agent 运行时包装器。
 *
 * <p>包装一个内部 {@link AgentRuntime}，在执行前后增加 checkpoint 保存/恢复逻辑：</p>
 * <ol>
 *   <li>Runtime 开始时尝试恢复兼容版本且状态为 RUNNING 的 checkpoint</li>
 *   <li>用户和会话通过稳定 ID 从 Repository 重新加载，不反序列化为持久化实体快照</li>
 *   <li>每个成功 Agent 步骤后保存 checkpoint（通过包装监听器）</li>
 *   <li>运行完成后删除 checkpoint</li>
 *   <li>失败或进程中断时保留 checkpoint 至 TTL</li>
 * </ol>
 *
 * <p>恢复后依赖 Blackboard flags/artifacts 防止重复执行已完成的 Agent 步骤，
 * 工具作业通过幂等键防止重复产生。</p>
 *
 * <p>当 {@code checkpoint.enabled=false}（默认）时，此包装器不做任何额外操作，
 * 完全委托给内部运行时，保持与升级前完全一致的行为。</p>
 */
public class CheckpointAwareAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(CheckpointAwareAgentRuntime.class);

    private final AgentRuntime delegate;
    private final CheckpointService checkpointService;
    private final UserAccountRepository userAccountRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final MindBridgeProperties properties;

    public CheckpointAwareAgentRuntime(
            AgentRuntime delegate,
            CheckpointService checkpointService,
            UserAccountRepository userAccountRepository,
            ChatSessionRepository chatSessionRepository,
            MindBridgeProperties properties
    ) {
        this.delegate = delegate;
        this.checkpointService = checkpointService;
        this.userAccountRepository = userAccountRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.properties = properties;
    }

    @Override
    public RuntimeMode mode() {
        return delegate.mode();
    }

    @Override
    public AgentRunResult run(UserAccount user, ChatSession session, String originalInput, String modelInput,
                               AgentStepListener listener) {
        if (!properties.getCheckpoint().isEnabled()) {
            return delegate.run(user, session, originalInput, modelInput, listener);
        }

        String sessionPublicId = session != null ? session.getPublicId() : null;
        String inputFingerprint = CheckpointData.computeFingerprint(modelInput);
        String runId = generateRunId();

        // 尝试恢复 checkpoint
        RestoreResult restoreResult = tryRestore(sessionPublicId, user, session,
                originalInput, modelInput, inputFingerprint);
        AgentContext context;
        if (restoreResult != null) {
            context = restoreResult.context();
            runId = restoreResult.checkpoint().runId();
            log.info("[checkpoint] resumed: session={}, runId={}, step={}",
                    sessionPublicId, runId, restoreResult.checkpoint().stepNumber());
        } else {
            context = new AgentContext(user, session, originalInput, modelInput);
        }

        return runWithContext(context, listener, runId, sessionPublicId, inputFingerprint);
    }

    @Override
    public AgentRunResult run(AgentContext context, AgentStepListener listener) {
        if (!properties.getCheckpoint().isEnabled()) {
            return delegate.run(context, listener);
        }
        String sessionPublicId = context.session() != null ? context.session().getPublicId() : null;
        String inputFingerprint = CheckpointData.computeFingerprint(context.modelInput());
        String runId = generateRunId();
        return runWithContext(context, listener, runId, sessionPublicId, inputFingerprint);
    }

    // ────────────── 核心执行 ──────────────

    private AgentRunResult runWithContext(AgentContext context, AgentStepListener listener,
                                          String runId, String sessionPublicId, String inputFingerprint) {
        String runtimeMode = delegate.mode().name();

        // 包装监听器：每个成功步骤后保存 checkpoint
        AgentStepListener wrappingListener = new AgentStepListener() {
            @Override
            public void onStarted(int step, com.mindbridge.agent.service.agent.AgentName agentName, String action) {
                listener.onStarted(step, agentName, action);
            }

            @Override
            public void onCompleted(int step, com.mindbridge.agent.service.agent.AgentName agentName,
                                     String action, String observation) {
                listener.onCompleted(step, agentName, action, observation);
                checkpointService.saveCheckpoint(context, runId, runtimeMode, 0);
            }

            @Override
            public void onFailed(int step, com.mindbridge.agent.service.agent.AgentName agentName,
                                  String action, String errorSummary) {
                listener.onFailed(step, agentName, action, errorSummary);
            }
        };

        AgentRunResult result = delegate.run(context, wrappingListener);

        // 运行完成后删除 checkpoint（失败时抛异常不会到达这里，checkpoint 保留至 TTL）
        if (sessionPublicId != null) {
            checkpointService.deleteCheckpoint(sessionPublicId, runId, inputFingerprint);
            log.debug("[checkpoint] completed and deleted: session={}, runId={}", sessionPublicId, runId);
        }

        return result;
    }

    // ────────────── 恢复逻辑 ──────────────

    private record RestoreResult(CheckpointData checkpoint, AgentContext context) {}

    private RestoreResult tryRestore(String sessionPublicId, UserAccount fallbackUser,
                                    ChatSession fallbackSession,
                                    String originalInput, String modelInput, String inputFingerprint) {
        if (sessionPublicId == null || sessionPublicId.isBlank()) {
            return null;
        }
        try {
            Optional<CheckpointData> opt = checkpointService.loadUnfinished(sessionPublicId, inputFingerprint);
            if (opt.isEmpty()) {
                return null;
            }
            CheckpointData cp = opt.get();

            // 输入指纹校验：不匹配则清理残留 checkpoint 并从新运行
            if (!inputFingerprint.equals(cp.inputFingerprint())) {
                log.info("[checkpoint] inputFingerprint mismatch, starting fresh: session={}", sessionPublicId);
                checkpointService.deleteCheckpoint(sessionPublicId, cp.runId(), inputFingerprint);
                return null;
            }

            // 用户校验：不匹配则清理残留 checkpoint 并从新运行
            if (fallbackUser == null || fallbackUser.getId() == null
                    || fallbackUser.getId() != cp.userId()) {
                log.info("[checkpoint] userId mismatch, starting fresh: session={}, stored={}, current={}",
                        sessionPublicId, cp.userId(),
                        fallbackUser != null ? fallbackUser.getId() : null);
                checkpointService.deleteCheckpoint(sessionPublicId, cp.runId(), inputFingerprint);
                return null;
            }

            // 运行时模式校验：不匹配则清理残留 checkpoint 并从新运行
            String currentRuntimeMode = delegate.mode().name();
            if (!currentRuntimeMode.equals(cp.runtimeMode())) {
                log.info("[checkpoint] runtimeMode mismatch, starting fresh: session={}, stored={}, current={}",
                        sessionPublicId, cp.runtimeMode(), currentRuntimeMode);
                checkpointService.deleteCheckpoint(sessionPublicId, cp.runId(), inputFingerprint);
                return null;
            }

            // 通过稳定 ID 从 Repository 重新加载实体
            UserAccount restoredUser = userAccountRepository.findById(cp.userId()).orElse(null);
            if (restoredUser == null) {
                log.info("[checkpoint] user not found, starting fresh: userId={}", cp.userId());
                checkpointService.deleteCheckpoint(sessionPublicId, cp.runId(), inputFingerprint);
                return null;
            }
            ChatSession restoredSession = chatSessionRepository
                    .findByPublicIdAndUser_Id(cp.sessionPublicId(), cp.userId()).orElse(null);
            if (restoredSession == null) {
                log.info("[checkpoint] session not found, starting fresh: sessionPublicId={}",
                        cp.sessionPublicId());
                checkpointService.deleteCheckpoint(sessionPublicId, cp.runId(), inputFingerprint);
                return null;
            }

            // 用当前请求的输入构建 context（不使用 checkpoint 中的输入，因为已不再存储明文）
            AgentContext context = new AgentContext(restoredUser, restoredSession, originalInput, modelInput);

            // 恢复 Blackboard
            context.restoreBlackboard(cp.blackboard().toBlackboard(
                    checkpointService.getObjectMapper()));

            // 恢复非 Blackboard 管理的字段
            RiskLevel riskLevel = cp.riskLevel() != null ? RiskLevel.valueOf(cp.riskLevel()) : null;
            context.restoreCheckpointFields(
                    cp.previousHistory(),
                    cp.modelHistory(),
                    cp.memoryBrief(),
                    cp.knowledgeQuery(),
                    riskLevel);

            // 恢复步骤列表（用于步骤号连续性和结果 trace）
            if (cp.steps() != null) {
                for (CheckpointStep cs : cp.steps()) {
                    try {
                        context.addStep(cs.toStep());
                    } catch (Exception e) {
                        // 单个 step 损坏不影响恢复
                    }
                }
            }

            return new RestoreResult(cp, context);
        } catch (Exception e) {
            log.info("[checkpoint] restore failed, starting fresh: session={}, error={}",
                    sessionPublicId, e.getMessage());
            return null;
        }
    }

    private String generateRunId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
