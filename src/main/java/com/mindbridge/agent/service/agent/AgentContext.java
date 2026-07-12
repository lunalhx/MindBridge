package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.PsychologyAssessment;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.agent.blackboard.AgentArtifact;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.agent.blackboard.AgentEvent;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一轮对话的 Agent 工作上下文。
 *
 * <p>Agent loop 中的每个专家 Agent 只更新自己负责的字段，下一步 Agent 根据这些状态继续执行。</p>
 *
 * <p>内部状态管理委托给不可变 {@link AgentBlackboard}：
 * 六个布尔标记委托给 {@link AgentFlag}，
 * 主要产出（intent / knowledge / assessment / response）同步写入 Blackboard artifact 和事件。</p>
 */
public class AgentContext {

    // ────────────── 不可变字段（构造时注入） ──────────────

    private final UserAccount user;
    private final ChatSession session;
    private final String originalInput;
    private final String modelInput;
    private final List<AgentStep> steps = new ArrayList<>();

    // ────────────── 可变字段 ──────────────

    private List<AiMessage> previousHistory = List.of();
    private List<AiMessage> modelHistory = List.of();
    private List<AiMessage> responseMessages = List.of();
    private List<SearchResult> retrievedKnowledge = List.of();
    private String memoryBrief = "无相关历史记忆。";
    private String knowledgeQuery;
    private String responsePlan = "自然回答当前问题。";
    private IntentType intent;
    private PsychologyAssessment assessment;
    private RiskLevel riskLevel = RiskLevel.LOW;
    private AgentName responseAgent = AgentName.COMPANION_AGENT;

    // ────────────── Blackboard（flag 和 artifact 的权威来源） ──────────────

    private AgentBlackboard blackboard;

    // ────────────── 构造 ──────────────

    public AgentContext(UserAccount user, ChatSession session, String originalInput, String modelInput) {
        this.user = user;
        this.session = session;
        this.originalInput = originalInput;
        this.modelInput = modelInput;
        this.blackboard = AgentBlackboard.empty();
    }

    /**
     * 从 Blackboard 恢复 AgentContext（声明式调度桥接）。
     *
     * <p>不可变字段（user/session/originalInput/modelInput）无法从 Blackboard 恢复，设为 null。
     * 可变状态（intent/assessment/knowledge/response/flags）从 Blackboard 的 artifact 和 flag 恢复。
     * 此方法为声明式 {@code act(AgentBlackboard, AgentTask)} 默认桥接提供基础，
     * 完整的声明式运行时在批次 3 中实现时会补充不可变字段的传递。</p>
     */
    public static AgentContext from(AgentBlackboard blackboard) {
        var ctx = new AgentContext(null, null, null, null);
        ctx.restoreBlackboard(blackboard);
        return ctx;
    }

    /**
     * 用一个 Blackboard 快照替换当前 Blackboard，并从 artifact 同步可变字段。
     *
     * <p>用于 Checkpoint 恢复：保留构造时注入的 user/session/originalInput/modelInput，
     * 只替换 Blackboard 状态和从 artifact 推导的字段（intent/assessment/knowledge/response）。</p>
     */
    public void restoreBlackboard(AgentBlackboard blackboard) {
        this.blackboard = blackboard;
        blackboard.getArtifact(AgentArtifact.NAME_INTENT)
                .ifPresent(a -> { if (a.payload() instanceof IntentType it) this.intent = it; });
        blackboard.getArtifact(AgentArtifact.NAME_ASSESSMENT)
                .ifPresent(a -> { if (a.payload() instanceof PsychologyAssessment pa) this.assessment = pa; });
        blackboard.getArtifact(AgentArtifact.NAME_KNOWLEDGE)
                .ifPresent(a -> {
                    if (a.payload() instanceof List<?> list) {
                        @SuppressWarnings("unchecked")
                        List<SearchResult> sr = (List<SearchResult>) list;
                        this.retrievedKnowledge = sr;
                    }
                });
        blackboard.getArtifact(AgentArtifact.NAME_RESPONSE)
                .ifPresent(a -> {
                    if (a.payload() instanceof ResponseArtifactPayload rap) {
                        this.responsePlan = rap.plan();
                        this.responseMessages = rap.messages();
                        if (a.producer() != null) this.responseAgent = a.producer();
                    }
                });
    }

    // ────────────── 不可变字段 getter ──────────────

    public UserAccount user()              { return user; }
    public ChatSession session()           { return session; }
    public String originalInput()          { return originalInput; }
    public String modelInput()             { return modelInput; }
    public List<AgentStep> steps()         { return List.copyOf(steps); }

    public void addStep(AgentStep step)    { steps.add(step); }

    // ────────────── 可变字段 getter / setter ──────────────

    public List<AiMessage> previousHistory()       { return previousHistory; }
    public void setPreviousHistory(List<AiMessage> v) { this.previousHistory = List.copyOf(v); }

    public List<AiMessage> modelHistory()           { return modelHistory; }
    public void setModelHistory(List<AiMessage> v)  { this.modelHistory = List.copyOf(v); }

    public String memoryBrief()            { return memoryBrief; }
    public void setMemoryBrief(String v)   { this.memoryBrief = v; }

    public String knowledgeQuery()         { return knowledgeQuery; }
    public void setKnowledgeQuery(String v) { this.knowledgeQuery = v; }

    public String responsePlan()           { return responsePlan; }

    public AgentName responseAgent()       { return responseAgent; }
    public void setResponseAgent(AgentName v) { this.responseAgent = v; }

    // ────────────── 六状态标记（委托给 Blackboard AgentFlag） ──────────────

    public boolean memoryLoaded()      { return blackboard.hasFlag(AgentFlag.MEMORY_LOADED); }
    public boolean intentRouted()      { return blackboard.hasFlag(AgentFlag.INTENT_ROUTED); }
    public boolean knowledgeHandled()  { return blackboard.hasFlag(AgentFlag.KNOWLEDGE_HANDLED); }
    public boolean riskAssessed()      { return blackboard.hasFlag(AgentFlag.RISK_ASSESSED); }
    public boolean responsePlanned()   { return blackboard.hasFlag(AgentFlag.RESPONSE_PLANNED); }
    public boolean finished()          { return blackboard.hasFlag(AgentFlag.FINISHED); }

    public void markMemoryLoaded() {
        this.blackboard = this.blackboard
                .setFlag(AgentFlag.MEMORY_LOADED)
                .addEvent(new AgentEvent(AgentEvent.TYPE_MEMORY_LOADED, AgentName.MEMORY_AGENT,
                        "已加载用户记忆"));
    }

    public void markIntentRouted() {
        this.blackboard = this.blackboard
                .setFlag(AgentFlag.INTENT_ROUTED)
                .addEvent(new AgentEvent(AgentEvent.TYPE_INTENT_CLASSIFIED, AgentName.SUPERVISOR_AGENT,
                        "意图已分类"));
    }

    public void markKnowledgeHandled() {
        this.blackboard = this.blackboard
                .setFlag(AgentFlag.KNOWLEDGE_HANDLED)
                .addEvent(new AgentEvent(AgentEvent.TYPE_KNOWLEDGE_RETRIEVED, AgentName.KNOWLEDGE_AGENT,
                        "知识检索已完成"));
    }

    public void markRiskAssessed() {
        this.blackboard = this.blackboard
                .setFlag(AgentFlag.RISK_ASSESSED)
                .addEvent(new AgentEvent(AgentEvent.TYPE_RISK_ASSESSED, AgentName.RISK_GUARDIAN_AGENT,
                        "风险评估已完成"));
    }

    public void markResponsePlanned() {
        this.blackboard = this.blackboard
                .setFlag(AgentFlag.RESPONSE_PLANNED)
                .addEvent(new AgentEvent(AgentEvent.TYPE_RESPONSE_PLANNED, this.responseAgent,
                        "回复规划已完成"));
    }

    public void finish() {
        this.blackboard = this.blackboard
                .setFlag(AgentFlag.FINISHED)
                .addEvent(new AgentEvent(AgentEvent.TYPE_LOOP_FINISHED, null,
                        "Agent loop 已完成"));
    }

    // ────────────── 主要产出（同步写入 Blackboard artifact） ──────────────

    public IntentType intent() { return intent; }

    /**
     * 设置意图分类，同时同步到 Blackboard。
     * 由 {@link SupervisorAgent} 调用。
     */
    public void setIntent(IntentType intent) {
        this.intent = intent;
        this.blackboard = this.blackboard
                .addArtifact(new AgentArtifact(AgentArtifact.NAME_INTENT,
                        AgentName.SUPERVISOR_AGENT, Instant.now(), intent));
    }

    public PsychologyAssessment assessment() { return assessment; }

    /**
     * 设置风险评估，同时同步到 Blackboard。
     * 由 {@link RiskGuardianAgent} 调用。
     */
    public void setAssessment(PsychologyAssessment assessment) {
        this.assessment = assessment;
        this.blackboard = this.blackboard
                .addArtifact(new AgentArtifact(AgentArtifact.NAME_ASSESSMENT,
                        AgentName.RISK_GUARDIAN_AGENT, Instant.now(), assessment));
    }

    public List<SearchResult> retrievedKnowledge() { return retrievedKnowledge; }

    /**
     * 设置知识检索结果，同时同步到 Blackboard。
     * 由 {@link KnowledgeAgent} 调用。
     */
    public void setRetrievedKnowledge(List<SearchResult> retrievedKnowledge) {
        this.retrievedKnowledge = List.copyOf(retrievedKnowledge);
        this.blackboard = this.blackboard
                .addArtifact(new AgentArtifact(AgentArtifact.NAME_KNOWLEDGE,
                        AgentName.KNOWLEDGE_AGENT, Instant.now(), this.retrievedKnowledge));
    }

    public RiskLevel riskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }

    public List<AiMessage> responseMessages() { return responseMessages; }

    /**
     * 设置回复消息，同时更新 Blackboard 中的 response artifact。
     * response artifact 聚合了 responsePlan 和 responseMessages。
     */
    public void setResponseMessages(List<AiMessage> responseMessages) {
        this.responseMessages = List.copyOf(responseMessages);
        syncResponseArtifact();
    }

    /**
     * 设置回复规划，同时更新 Blackboard 中的 response artifact。
     */
    public void setResponsePlan(String responsePlan) {
        this.responsePlan = responsePlan;
        syncResponseArtifact();
    }

    /**
     * 将当前的 responsePlan + responseMessages 聚合为一个 response artifact。
     */
    private void syncResponseArtifact() {
        var payload = new ResponseArtifactPayload(this.responsePlan, this.responseMessages);
        this.blackboard = this.blackboard
                .addArtifact(new AgentArtifact(AgentArtifact.NAME_RESPONSE,
                        this.responseAgent, Instant.now(), payload));
    }

    /**
     * response artifact 的负载类型，聚合规划和最终消息。
     */
    public record ResponseArtifactPayload(String plan, List<AiMessage> messages) {}

    // ────────────── Blackboard 访问 ──────────────

    /**
     * 返回当前不可变 Blackboard 引用。
     * 每次状态变更（setXxx / markXxx）会替换此引用。
     */
    public AgentBlackboard blackboard() {
        return blackboard;
    }

    /**
     * 从 checkpoint 恢复非 Blackboard 管理的可变字段。
     *
     * <p>这些字段由 MemoryAgent / RiskGuardianAgent 等设置，但未写入 Blackboard artifact，
     * 需要从 checkpoint 单独恢复。</p>
     */
    public void restoreCheckpointFields(
            List<AiMessage> previousHistory,
            List<AiMessage> modelHistory,
            String memoryBrief,
            String knowledgeQuery,
            RiskLevel riskLevel
    ) {
        if (previousHistory != null) this.previousHistory = List.copyOf(previousHistory);
        if (modelHistory != null) this.modelHistory = List.copyOf(modelHistory);
        if (memoryBrief != null) this.memoryBrief = memoryBrief;
        if (knowledgeQuery != null) this.knowledgeQuery = knowledgeQuery;
        if (riskLevel != null) this.riskLevel = riskLevel;
    }
}
