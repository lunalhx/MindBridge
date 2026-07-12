package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatMessage;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.MessageRole;
import com.mindbridge.agent.repository.ChatMessageRepository;
import com.mindbridge.agent.service.PrivacySanitizer;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import com.mindbridge.agent.service.agent.registry.AgentCapability;
import com.mindbridge.agent.service.memory.AgentPrivateMemoryRegistry;
import com.mindbridge.agent.service.memory.ShortTermMemoryService;
import com.mindbridge.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import com.mindbridge.agent.service.memory.UserProfileMemoryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆 Agent。
 *
 * <p>优先读取 Redis 短期记忆和用户画像；短期记忆过期时，从 MySQL 长期聊天记录恢复最近上下文。</p>
 */
@Component
public class MemoryAgent implements MindBridgeAgent {

    private static final Logger log = LoggerFactory.getLogger(MemoryAgent.class);

    private final ChatMessageRepository chatMessageRepository;
    private final ShortTermMemoryService shortTermMemoryService;
    private final MindBridgeProperties properties;
    private final PrivacySanitizer privacySanitizer;
    private final AiClient aiClient;
    private final UserProfileMemoryService userProfileMemoryService;
    private final AgentPrivateMemoryRegistry privateMemoryRegistry;

    /**
     * Spring 主构造：注入私有记忆 registry（可选，Spring 容器中有则注入）。
     */
    @Autowired
    public MemoryAgent(
            ChatMessageRepository chatMessageRepository,
            ShortTermMemoryService shortTermMemoryService,
            MindBridgeProperties properties,
            PrivacySanitizer privacySanitizer,
            AiClient aiClient,
            UserProfileMemoryService userProfileMemoryService,
            AgentPrivateMemoryRegistry privateMemoryRegistry
    ) {
        this.chatMessageRepository = chatMessageRepository;
        this.shortTermMemoryService = shortTermMemoryService;
        this.properties = properties;
        this.privacySanitizer = privacySanitizer;
        this.aiClient = aiClient;
        this.userProfileMemoryService = userProfileMemoryService;
        this.privateMemoryRegistry = privateMemoryRegistry;
    }

    /**
     * 测试兼容构造：不注入私有记忆 registry，私有记忆功能跳过。
     */
    public MemoryAgent(
            ChatMessageRepository chatMessageRepository,
            ShortTermMemoryService shortTermMemoryService,
            MindBridgeProperties properties,
            PrivacySanitizer privacySanitizer,
            AiClient aiClient,
            UserProfileMemoryService userProfileMemoryService
    ) {
        this(chatMessageRepository, shortTermMemoryService, properties,
                privacySanitizer, aiClient, userProfileMemoryService, null);
    }

    @Override
    public AgentName name() {
        return AgentName.MEMORY_AGENT;
    }

    @Override
    public AgentAction getExpectedAction() {
        return AgentAction.READ_MEMORY;
    }

    @Override
    public boolean supports(AgentContext context) {
        return !context.memoryLoaded();
    }

    @Override
    public List<AgentCapability> decide(AgentBlackboard blackboard) {
        if (blackboard.hasFlag(AgentFlag.MEMORY_LOADED)) {
            return List.of();
        }
        return List.of(new AgentCapability(
                "load-memory", 1.0, "memory",
                List.of()));
    }

    @Override
    public AgentDecision act(AgentContext context) {
        List<MemoryMessage> redisHistory = shortTermMemoryService.recent(context.session().getPublicId());
        List<AiMessage> previousHistory;
        String source;
        if (!redisHistory.isEmpty()) {
            previousHistory = redisHistory.stream()
                    .map(this::toAiMessage)
                    .toList();
            source = "Redis";
        } else {
            List<ChatMessage> databaseHistory = recentHistory(context.session());
            shortTermMemoryService.refresh(context.session().getPublicId(), databaseHistory.stream()
                    .map(message -> new MemoryMessage(message.getRole(), message.getContent()))
                    .toList());
            previousHistory = databaseHistory.stream()
                    .map(this::toAiMessage)
                    .toList();
            source = "MySQL";
        }

        String profileBrief = userProfileMemoryService.profileBrief(context.user(), context.modelInput());
        String historyBrief = summarizeMemory(previousHistory, context.modelInput());
        String privateMemoryBrief = loadPrivateMemorySummaries(context.session().getPublicId());
        context.setPreviousHistory(previousHistory);
        context.setModelHistory(withCurrentUser(previousHistory, context.modelInput()));
        context.setMemoryBrief(combineMemoryBrief(profileBrief, historyBrief, privateMemoryBrief));
        context.markMemoryLoaded();
        return AgentDecision.continueWith(
                AgentAction.READ_MEMORY,
                "%s loaded %d messages; memory brief prepared".formatted(source, previousHistory.size()));
    }

    private List<ChatMessage> recentHistory(ChatSession session) {
        if (session.getId() == null) {
            return List.of();
        }
        List<ChatMessage> history = chatMessageRepository.findTop20BySession_IdOrderByCreatedAtDesc(session.getId());
        Collections.reverse(history);
        return history;
    }

    private List<AiMessage> withCurrentUser(List<AiMessage> previousHistory, String currentInput) {
        List<AiMessage> history = new ArrayList<>(previousHistory);
        history.add(AiMessage.user(currentInput));
        int limit = Math.max(2, properties.getChat().getHistoryLimit() * 2);
        return history.stream()
                .skip(Math.max(0, history.size() - limit))
                .toList();
    }

    private String summarizeMemory(List<AiMessage> history, String currentInput) {
        if (history.isEmpty()) {
            return "无相关历史记忆。";
        }
        try {
            String summary = aiClient.complete(List.of(
                    AiMessage.system("""
                            你是 MindBridge 的 MemoryAgent。
                            你的任务是从最近对话中提取对当前输入有用的短期/长期记忆。
                            只输出 1-3 条中文要点，不要输出风险等级、诊断结论或后台标签。
                            如果历史与当前输入无关，只输出：无相关历史记忆。
                            """),
                    AiMessage.user("""
                            当前输入：
                            %s

                            最近历史：
                            %s
                            """.formatted(currentInput, formatHistory(history)))
            )).trim();
            return summary.isBlank() ? "无相关历史记忆。" : shorten(summary, 400);
        } catch (Exception e) {
            log.warn("[agent] MemoryAgent summarizeMemory degraded: error={}", e.getClass().getSimpleName());
            return "无相关历史记忆。";
        }
    }

    private String combineMemoryBrief(String profileBrief, String historyBrief, String privateMemoryBrief) {
        boolean hasProfile = profileBrief != null && !profileBrief.equals("无已保存用户画像。");
        boolean hasHistory = historyBrief != null && !historyBrief.equals("无相关历史记忆。");
        boolean hasPrivate = privateMemoryBrief != null && !privateMemoryBrief.isBlank();
        if (!hasProfile && !hasHistory && !hasPrivate) {
            return "无相关历史记忆。";
        }
        StringBuilder sb = new StringBuilder();
        if (hasProfile) {
            sb.append("用户画像：\n").append(profileBrief);
        }
        if (hasHistory) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("最近对话记忆：\n").append(historyBrief);
        }
        if (hasPrivate) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Agent 私有记忆：\n").append(privateMemoryBrief);
        }
        return sb.toString();
    }

    /**
     * 加载各 Agent 的私有记忆摘要（如 registry 可用）。
     *
     * <p>只加载摘要，不加载完整消息，避免将某个 Agent 的私有内容泄漏给无关 Agent。
     * 摘要按 Agent 名称标注，各 Agent 可在后续步骤中读取自己的私有记忆。</p>
     */
    private String loadPrivateMemorySummaries(String sessionId) {
        if (privateMemoryRegistry == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentName agentName : List.of(
                AgentName.SUPERVISOR_AGENT,
                AgentName.KNOWLEDGE_AGENT,
                AgentName.RISK_GUARDIAN_AGENT,
                AgentName.COMPANION_AGENT,
                AgentName.COUNSELOR_AGENT)) {
            try {
                String summary = privateMemoryRegistry.summarize(agentName, sessionId);
                if (summary != null && !summary.isBlank()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("[").append(agentName.name()).append("] ").append(summary);
                }
            } catch (Exception e) {
                log.debug("[agent] MemoryAgent private memory load skipped: agent={}, error={}",
                        agentName, e.getClass().getSimpleName());
                // 私有记忆读取失败不影响主链路
            }
        }
        return sb.toString();
    }

    private String formatHistory(List<AiMessage> history) {
        return String.join("\n", history.stream()
                .skip(Math.max(0, history.size() - 12))
                .map(message -> message.role() + ": " + message.content())
                .toList());
    }

    private String shorten(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private AiMessage toAiMessage(ChatMessage chatMessage) {
        String content = privacySanitizer.sanitize(chatMessage.getContent());
        return switch (chatMessage.getRole()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> AiMessage.system(content);
            case USER -> AiMessage.user(content);
        };
    }

    private AiMessage toAiMessage(MemoryMessage memoryMessage) {
        String content = privacySanitizer.sanitize(memoryMessage.content());
        return switch (memoryMessage.role()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> AiMessage.system(content);
            case USER -> AiMessage.user(content);
        };
    }
}
