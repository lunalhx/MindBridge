package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.service.PsychologicalAssessmentService;
import com.mindbridge.agent.service.PsychologyAssessment;
import com.mindbridge.agent.service.agent.blackboard.AgentArtifact;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import com.mindbridge.agent.service.agent.registry.AgentCapability;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 风险守护 Agent。
 *
 * <p>负责后台心理状态评估和高风险硬兜底；安全判断不能完全交给自由生成模型。</p>
 */
@Component
public class RiskGuardianAgent implements MindBridgeAgent {

    private final PsychologicalAssessmentService assessmentService;

    public RiskGuardianAgent(PsychologicalAssessmentService assessmentService) {
        this.assessmentService = assessmentService;
    }

    @Override
    public AgentName name() {
        return AgentName.RISK_GUARDIAN_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.knowledgeHandled()
                && !context.riskAssessed()
                && context.intent() != IntentType.CHAT;
    }

    @Override
    public List<AgentCapability> decide(AgentBlackboard blackboard) {
        if (!blackboard.hasFlag(AgentFlag.KNOWLEDGE_HANDLED) || blackboard.hasFlag(AgentFlag.RISK_ASSESSED)) {
            return List.of();
        }
        IntentType intent = blackboard.getArtifact(AgentArtifact.NAME_INTENT)
                .filter(a -> a.payload() instanceof IntentType)
                .map(a -> (IntentType) a.payload())
                .orElse(null);
        if (intent == IntentType.CHAT) {
            return List.of();
        }
        return List.of(new AgentCapability(
                "assess-risk", 0.95, "assessment",
                List.of("intent", "knowledge")));
    }

    @Override
    public AgentDecision act(AgentContext context) {
        PsychologyAssessment assessment = assessmentService.assess(context.modelInput(), context.modelHistory());
        if (context.intent() == IntentType.RISK && assessment.risk() != RiskLevel.HIGH) {
            assessment = new PsychologyAssessment(
                    assessment.emotion(),
                    Math.max(assessment.emotionScore(), 4.0),
                    RiskLevel.HIGH,
                    assessment.confidence(),
                    assessment.summary());
        }
        context.setAssessment(assessment);
        context.setRiskLevel(assessment.risk());
        context.markRiskAssessed();
        return AgentDecision.continueWith(
                AgentAction.ASSESS_RISK,
                "risk=%s, emotion=%s".formatted(assessment.risk(), assessment.emotion()));
    }
}
