package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.ai.PromptTemplates;
import com.mindbridge.agent.service.agent.blackboard.AgentArtifact;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import com.mindbridge.agent.service.agent.registry.AgentCapability;
import com.mindbridge.agent.service.skill.Skill;
import com.mindbridge.agent.service.skill.SkillRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 普通陪伴 Agent。
 *
 * <p>处理学习、生活、编程、校园事务等普通聊天，不生成后台心理报告。</p>
 */
@Component
public class CompanionAgent implements MindBridgeAgent {

    private static final Logger log = LoggerFactory.getLogger(CompanionAgent.class);

    private final AiClient aiClient;
    private final SkillRegistry skillRegistry;

    @Autowired
    public CompanionAgent(AiClient aiClient, SkillRegistry skillRegistry) {
        this.aiClient = aiClient;
        this.skillRegistry = skillRegistry;
    }

    /** 测试兼容构造：不注入 SkillRegistry，技能注入跳过。 */
    public CompanionAgent(AiClient aiClient) {
        this(aiClient, null);
    }

    @Override
    public AgentName name() {
        return AgentName.COMPANION_AGENT;
    }

    @Override
    public AgentAction getExpectedAction() {
        return AgentAction.PLAN_RESPONSE;
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.intentRouted()
                && context.intent() == IntentType.CHAT
                && !context.responsePlanned();
    }

    @Override
    public List<AgentCapability> decide(AgentBlackboard blackboard) {
        if (blackboard.hasFlag(AgentFlag.RESPONSE_PLANNED)) {
            return List.of();
        }
        if (!blackboard.hasFlag(AgentFlag.INTENT_ROUTED)) {
            return List.of();
        }
        IntentType intent = blackboard.getArtifact(AgentArtifact.NAME_INTENT)
                .filter(a -> a.payload() instanceof IntentType)
                .map(a -> (IntentType) a.payload())
                .orElse(null);
        if (intent != IntentType.CHAT) {
            return List.of();
        }
        return List.of(new AgentCapability(
                "plan-companion-response", 0.9, "response",
                List.of("intent")));
    }

    @Override
    public AgentDecision act(AgentContext context) {
        context.setRiskLevel(RiskLevel.LOW);
        context.setResponseAgent(AgentName.COMPANION_AGENT);
        String plan = planResponse(context);
        context.setResponsePlan(plan);
        context.setResponseMessages(buildResponseMessages(context, plan));
        context.markResponsePlanned();
        return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "normal companion response planned by model");
    }

    private String planResponse(AgentContext context) {
        try {
            String plan = aiClient.complete(List.of(
                    AiMessage.system("""
                            你是 MindBridge 的 CompanionAgent。
                            你负责普通学习、生活、校园事务、编程和日常聊天。
                            请根据当前输入和记忆摘要，制定一句简短回复策略。
                            不要做心理评估，不要输出风险等级，不要替用户下诊断。
                            """),
                    AiMessage.user("""
                            记忆摘要：
                            %s

                            当前输入：
                            %s
                            """.formatted(context.memoryBrief(), context.modelInput()))
            )).trim();
            return plan.isBlank() ? "围绕用户当前问题直接、自然地回答。" : shorten(plan, 300);
        } catch (Exception e) {
            log.warn("[agent] CompanionAgent planResponse degraded: error={}", e.getClass().getSimpleName());
            return "围绕用户当前问题直接、自然地回答。";
        }
    }

    private List<AiMessage> buildResponseMessages(AgentContext context, String plan) {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(PromptTemplates.answerSystemPrompt(
                IntentType.CHAT,
                RiskLevel.LOW,
                "",
                context.user().getDisplayName()));
        // 注入选中技能（安全规则优先，技能为附加指导）
        if (skillRegistry != null) {
            List<Skill> selected = skillRegistry.selectBy(
                    IntentType.CHAT, RiskLevel.LOW, context.modelInput());
            AiMessage skillMsg = PromptTemplates.injectSkills(selected);
            if (skillMsg != null) {
                messages.add(skillMsg);
            }
        }
        messages.add(AiMessage.system("""
                当前由 CompanionAgent 负责回复。
                记忆摘要：
                %s

                回复策略：
                %s
                """.formatted(context.memoryBrief(), plan)));
        messages.addAll(context.modelHistory());
        return messages;
    }

    private String shorten(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
