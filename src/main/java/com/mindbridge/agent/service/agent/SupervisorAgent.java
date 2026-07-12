package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.IntentClassifier;
import com.mindbridge.agent.service.ai.AgentModelRegistry;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import com.mindbridge.agent.service.agent.registry.AgentCapability;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 主控 Agent。
 *
 * <p>Supervisor 负责把本轮输入路由到普通陪伴、心理咨询或风险守护链路。</p>
 */
@Component
public class SupervisorAgent implements MindBridgeAgent {

    private final IntentClassifier intentClassifier;
    private final AgentModelRegistry agentModelRegistry;

    public SupervisorAgent(IntentClassifier intentClassifier, AgentModelRegistry agentModelRegistry) {
        this.intentClassifier = intentClassifier;
        this.agentModelRegistry = agentModelRegistry;
    }

    @Override
    public AgentName name() {
        return AgentName.SUPERVISOR_AGENT;
    }

    @Override
    public AgentAction getExpectedAction() {
        return AgentAction.ROUTE_INTENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.memoryLoaded() && !context.intentRouted();
    }

    @Override
    public List<AgentCapability> decide(AgentBlackboard blackboard) {
        if (!blackboard.hasFlag(AgentFlag.MEMORY_LOADED) || blackboard.hasFlag(AgentFlag.INTENT_ROUTED)) {
            return List.of();
        }
        return List.of(new AgentCapability(
                "route-intent", 1.0, "intent",
                List.of("memory")));
    }

    @Override
    public AgentDecision act(AgentContext context) {
        IntentType intent = intentClassifier.classify(
                context.modelInput(),
                context.modelHistory(),
                agentModelRegistry.clientFor(AgentName.SUPERVISOR_AGENT));
        context.setIntent(intent);
        context.markIntentRouted();
        if (intent == IntentType.CHAT) {
            context.markKnowledgeHandled();
            context.markRiskAssessed();
        }
        return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "intent=%s".formatted(intent));
    }
}
