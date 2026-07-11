package com.mindbridge.agent.service.agent.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.agent.AgentAction;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentDecision;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import com.mindbridge.agent.service.agent.blackboard.AgentArtifact;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRegistryTest {

    // ────────── AgentCapability ──────────

    @Test
    void capabilityShouldAcceptValidConfidence() {
        new AgentCapability("test", 0.0, "x");
        new AgentCapability("test", 1.0, "x");
        new AgentCapability("test", 0.5, "x");
    }

    @Test
    void capabilityShouldRejectConfidenceOutOfRange() {
        assertThatThrownBy(() -> new AgentCapability("test", -0.1, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentCapability("test", 1.1, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capabilityShouldRejectNullName() {
        assertThatThrownBy(() -> new AgentCapability(null, 0.5, "x"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void capabilityShouldCopyRequiresArtifactsDefensively() {
        var reqs = new java.util.ArrayList<>(List.of("a", "b"));
        var cap = new AgentCapability("test", 0.5, "x", reqs);
        reqs.add("c");
        assertThat(cap.requiresArtifacts()).containsExactly("a", "b");
        assertThatThrownBy(() -> cap.requiresArtifacts().add("d"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ────────── AgentRegistry: 重复名称 ──────────

    @Test
    void registryShouldRejectDuplicateAgentNames() {
        MindBridgeAgent agent1 = stubAgent(AgentName.MEMORY_AGENT);
        MindBridgeAgent agent2 = stubAgent(AgentName.MEMORY_AGENT);

        assertThatThrownBy(() -> new AgentRegistry(List.of(agent1, agent2), 0.6))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate agent name");
    }

    // ────────── AgentRegistry: 空能力 ──────────

    @Test
    void registryShouldReturnEmptyCandidatesWhenNoAgentDeclaresCapability() {
        MindBridgeAgent silent = stubAgent(AgentName.MEMORY_AGENT, bb -> List.of());
        var registry = new AgentRegistry(List.of(silent), 0.6);

        assertThat(registry.candidates(AgentBlackboard.empty())).isEmpty();
    }

    // ────────── AgentRegistry: 阈值过滤 ──────────

    @Test
    void registryShouldFilterCandidatesBelowThreshold() {
        MindBridgeAgent lowConf = stubAgent(AgentName.MEMORY_AGENT, bb -> List.of(
                new AgentCapability("cap-low", 0.3, "memory")));
        var registry = new AgentRegistry(List.of(lowConf), 0.6);

        assertThat(registry.candidates(AgentBlackboard.empty())).isEmpty();
    }

    @Test
    void registryShouldIncludeCandidatesAtOrAboveThreshold() {
        MindBridgeAgent exact = stubAgent(AgentName.MEMORY_AGENT, bb -> List.of(
                new AgentCapability("cap-exact", 0.6, "memory")));
        var registry = new AgentRegistry(List.of(exact), 0.6);

        var candidates = registry.candidates(AgentBlackboard.empty());
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).capability().confidence()).isEqualTo(0.6);
    }

    @Test
    void registryShouldSupportCustomThresholdOverride() {
        MindBridgeAgent agent = stubAgent(AgentName.MEMORY_AGENT, bb -> List.of(
                new AgentCapability("cap", 0.5, "memory")));
        var registry = new AgentRegistry(List.of(agent), 0.8);

        // 默认阈值 0.8 过滤掉 0.5
        assertThat(registry.candidates(AgentBlackboard.empty())).isEmpty();
        // 自定义阈值 0.4 允许 0.5
        assertThat(registry.candidates(AgentBlackboard.empty(), 0.4)).hasSize(1);
    }

    // ────────── AgentRegistry: 排序 ──────────

    @Test
    void registryShouldSortByConfidenceDescending() {
        MindBridgeAgent low = stubAgent(AgentName.MEMORY_AGENT, bb -> List.of(
                new AgentCapability("low", 0.7, "memory")));
        MindBridgeAgent high = stubAgent(AgentName.SUPERVISOR_AGENT, bb -> List.of(
                new AgentCapability("high", 0.95, "intent")));

        var registry = new AgentRegistry(List.of(low, high), 0.6);
        var candidates = registry.candidates(AgentBlackboard.empty());

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).capability().confidence()).isEqualTo(0.95);
        assertThat(candidates.get(1).capability().confidence()).isEqualTo(0.7);
    }

    // ────────── AgentRegistry: 同分稳定性 ──────────

    @Test
    void registryShouldBreakTiesByAgentNameOrdinal() {
        MindBridgeAgent counselor = stubAgent(AgentName.COUNSELOR_AGENT, bb -> List.of(
                new AgentCapability("cap", 0.9, "response")));
        MindBridgeAgent companion = stubAgent(AgentName.COMPANION_AGENT, bb -> List.of(
                new AgentCapability("cap", 0.9, "response")));

        var registry = new AgentRegistry(List.of(counselor, companion), 0.6);
        var candidates = registry.candidates(AgentBlackboard.empty());

        // COMPANION_AGENT.ordinal()=4 < COUNSELOR_AGENT.ordinal()=5
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).agentName()).isEqualTo(AgentName.COMPANION_AGENT);
        assertThat(candidates.get(1).agentName()).isEqualTo(AgentName.COUNSELOR_AGENT);
    }

    @Test
    void registryTieBreakShouldBeDeterministicAcrossRuns() {
        MindBridgeAgent a = stubAgent(AgentName.RISK_GUARDIAN_AGENT, bb -> List.of(
                new AgentCapability("cap", 0.8, "assessment")));
        MindBridgeAgent b = stubAgent(AgentName.KNOWLEDGE_AGENT, bb -> List.of(
                new AgentCapability("cap", 0.8, "knowledge")));

        var registry = new AgentRegistry(List.of(a, b), 0.6);

        // 多次调用应产生相同排序
        var first = registry.candidates(AgentBlackboard.empty());
        var second = registry.candidates(AgentBlackboard.empty());

        // KNOWLEDGE_AGENT.ordinal()=2 < RISK_GUARDIAN_AGENT.ordinal()=3
        assertThat(first).extracting(AgentRegistry.Candidate::agentName)
                .containsExactly(AgentName.KNOWLEDGE_AGENT, AgentName.RISK_GUARDIAN_AGENT);
        assertThat(second).extracting(AgentRegistry.Candidate::agentName)
                .containsExactly(AgentName.KNOWLEDGE_AGENT, AgentName.RISK_GUARDIAN_AGENT);
    }

    // ────────── AgentRegistry: 多能力展开 ──────────

    @Test
    void registryShouldExpandMultipleCapabilitiesFromOneAgent() {
        MindBridgeAgent multi = stubAgent(AgentName.SUPERVISOR_AGENT, bb -> List.of(
                new AgentCapability("cap-a", 0.9, "intent"),
                new AgentCapability("cap-b", 0.7, "assessment")));

        var registry = new AgentRegistry(List.of(multi), 0.6);
        var candidates = registry.candidates(AgentBlackboard.empty());

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).capability().name()).isEqualTo("cap-a");
        assertThat(candidates.get(1).capability().name()).isEqualTo("cap-b");
    }

    // ────────── AgentRegistry: 默认阈值 ──────────

    @Test
    void registryDefaultThresholdShouldBe06() {
        assertThat(AgentRegistry.DEFAULT_THRESHOLD).isEqualTo(0.6);
    }

    // ────────── AgentRegistry: 基本属性 ──────────

    @Test
    void registryShouldExposeSizeAndProfiles() {
        MindBridgeAgent a = stubAgent(AgentName.MEMORY_AGENT);
        MindBridgeAgent b = stubAgent(AgentName.SUPERVISOR_AGENT);

        var registry = new AgentRegistry(List.of(a, b), 0.6);

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.profiles()).hasSize(2);
        assertThat(registry.profile(AgentName.MEMORY_AGENT)).isNotNull();
        assertThat(registry.profile(AgentName.MEMORY_AGENT).agentName())
                .isEqualTo(AgentName.MEMORY_AGENT);
    }

    // ────────── 真实 6 Agent 集成 ──────────

    @Test
    void realAgentsShouldDeclareCorrectCapabilitiesOnEmptyBlackboard() {
        var registry = new AgentRegistry(List.of(
                memoryAgentStub(),
                supervisorAgentStub(),
                knowledgeAgentStub(),
                riskGuardianAgentStub(),
                companionAgentStub(),
                counselorAgentStub()
        ), 0.6);

        // 空 Blackboard: 只有 MemoryAgent 应声明能力
        var candidates = registry.candidates(AgentBlackboard.empty());
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).agentName()).isEqualTo(AgentName.MEMORY_AGENT);
        assertThat(candidates.get(0).capability().name()).isEqualTo("load-memory");
    }

    @Test
    void realAgentsShouldDeclareCorrectCapabilitiesAfterMemoryLoaded() {
        var registry = new AgentRegistry(List.of(
                memoryAgentStub(),
                supervisorAgentStub(),
                knowledgeAgentStub(),
                riskGuardianAgentStub(),
                companionAgentStub(),
                counselorAgentStub()
        ), 0.6);

        var board = AgentBlackboard.empty().setFlag(AgentFlag.MEMORY_LOADED);
        var candidates = registry.candidates(board);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).agentName()).isEqualTo(AgentName.SUPERVISOR_AGENT);
        assertThat(candidates.get(0).capability().name()).isEqualTo("route-intent");
    }

    @Test
    void realAgentsShouldDeclareKnowledgeAndRiskAfterIntentRoutedForConsult() {
        var registry = new AgentRegistry(List.of(
                memoryAgentStub(), supervisorAgentStub(), knowledgeAgentStub(),
                riskGuardianAgentStub(), companionAgentStub(), counselorAgentStub()
        ), 0.6);

        var board = AgentBlackboard.empty()
                .setFlag(AgentFlag.MEMORY_LOADED)
                .setFlag(AgentFlag.INTENT_ROUTED)
                .addArtifact(new AgentArtifact(
                        AgentArtifact.NAME_INTENT, AgentName.SUPERVISOR_AGENT,
                        Instant.now(), IntentType.CONSULT));

        var candidates = registry.candidates(board);
        // KnowledgeAgent (0.9) 和 RiskGuardianAgent 需要 KNOWLEDGE_HANDLED 才声明
        // 只有 KnowledgeAgent 应声明（RiskGuardianAgent 需要 knowledgeHandled）
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).agentName()).isEqualTo(AgentName.KNOWLEDGE_AGENT);
    }

    @Test
    void realAgentsShouldDeclareRiskAfterKnowledgeHandledForConsult() {
        var registry = new AgentRegistry(List.of(
                memoryAgentStub(), supervisorAgentStub(), knowledgeAgentStub(),
                riskGuardianAgentStub(), companionAgentStub(), counselorAgentStub()
        ), 0.6);

        var board = AgentBlackboard.empty()
                .setFlag(AgentFlag.MEMORY_LOADED)
                .setFlag(AgentFlag.INTENT_ROUTED)
                .setFlag(AgentFlag.KNOWLEDGE_HANDLED)
                .addArtifact(new AgentArtifact(
                        AgentArtifact.NAME_INTENT, AgentName.SUPERVISOR_AGENT,
                        Instant.now(), IntentType.CONSULT));

        var candidates = registry.candidates(board);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).agentName()).isEqualTo(AgentName.RISK_GUARDIAN_AGENT);
    }

    @Test
    void realAgentsShouldDeclareCounselorAfterRiskAssessedForConsult() {
        var registry = new AgentRegistry(List.of(
                memoryAgentStub(), supervisorAgentStub(), knowledgeAgentStub(),
                riskGuardianAgentStub(), companionAgentStub(), counselorAgentStub()
        ), 0.6);

        var board = AgentBlackboard.empty()
                .setFlag(AgentFlag.MEMORY_LOADED)
                .setFlag(AgentFlag.INTENT_ROUTED)
                .setFlag(AgentFlag.KNOWLEDGE_HANDLED)
                .setFlag(AgentFlag.RISK_ASSESSED)
                .addArtifact(new AgentArtifact(
                        AgentArtifact.NAME_INTENT, AgentName.SUPERVISOR_AGENT,
                        Instant.now(), IntentType.CONSULT));

        var candidates = registry.candidates(board);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).agentName()).isEqualTo(AgentName.COUNSELOR_AGENT);
    }

    @Test
    void realAgentsShouldDeclareCompanionForChatIntent() {
        var registry = new AgentRegistry(List.of(
                memoryAgentStub(), supervisorAgentStub(), knowledgeAgentStub(),
                riskGuardianAgentStub(), companionAgentStub(), counselorAgentStub()
        ), 0.6);

        var board = AgentBlackboard.empty()
                .setFlag(AgentFlag.MEMORY_LOADED)
                .setFlag(AgentFlag.INTENT_ROUTED)
                .addArtifact(new AgentArtifact(
                        AgentArtifact.NAME_INTENT, AgentName.SUPERVISOR_AGENT,
                        Instant.now(), IntentType.CHAT));

        var candidates = registry.candidates(board);
        // CHAT 路径: KnowledgeAgent 和 RiskGuardianAgent 不声明，CompanionAgent 声明
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).agentName()).isEqualTo(AgentName.COMPANION_AGENT);
    }

    @Test
    void realAgentsShouldReturnEmptyWhenAllWorkDone() {
        var registry = new AgentRegistry(List.of(
                memoryAgentStub(), supervisorAgentStub(), knowledgeAgentStub(),
                riskGuardianAgentStub(), companionAgentStub(), counselorAgentStub()
        ), 0.6);

        var board = AgentBlackboard.empty()
                .setFlag(AgentFlag.MEMORY_LOADED)
                .setFlag(AgentFlag.INTENT_ROUTED)
                .setFlag(AgentFlag.KNOWLEDGE_HANDLED)
                .setFlag(AgentFlag.RISK_ASSESSED)
                .setFlag(AgentFlag.RESPONSE_PLANNED)
                .setFlag(AgentFlag.FINISHED)
                .addArtifact(new AgentArtifact(
                        AgentArtifact.NAME_INTENT, AgentName.SUPERVISOR_AGENT,
                        Instant.now(), IntentType.CONSULT));

        assertThat(registry.candidates(board)).isEmpty();
    }

    // ────────── MindBridgeAgent 默认行为 ──────────

    @Test
    void unmigratedAgentShouldReturnEmptyDecideByDefault() {
        MindBridgeAgent unmigrated = new MindBridgeAgent() {
            @Override
            public AgentName name() { return AgentName.MEMORY_AGENT; }
            @Override
            public boolean supports(AgentContext context) { return false; }
            @Override
            public AgentDecision act(AgentContext context) {
                return AgentDecision.continueWith(AgentAction.READ_MEMORY, "stub");
            }
        };

        assertThat(unmigrated.decide(AgentBlackboard.empty())).isEmpty();
    }

    // ────────── Helpers ──────────

    @FunctionalInterface
    private interface DecideFunction {
        List<AgentCapability> decide(AgentBlackboard bb);
    }

    private static MindBridgeAgent stubAgent(AgentName name) {
        return stubAgent(name, bb -> List.of());
    }

    private static MindBridgeAgent stubAgent(AgentName name, DecideFunction decideFn) {
        return new MindBridgeAgent() {
            @Override
            public AgentName name() { return name; }
            @Override
            public boolean supports(AgentContext context) { return false; }
            @Override
            public AgentDecision act(AgentContext context) {
                return AgentDecision.continueWith(AgentAction.READ_MEMORY, "stub");
            }
            @Override
            public List<AgentCapability> decide(AgentBlackboard blackboard) {
                return decideFn.decide(blackboard);
            }
        };
    }

    private static MindBridgeAgent memoryAgentStub() {
        return new MindBridgeAgent() {
            @Override
            public AgentName name() { return AgentName.MEMORY_AGENT; }
            @Override
            public boolean supports(AgentContext ctx) { return !ctx.memoryLoaded(); }
            @Override
            public AgentDecision act(AgentContext ctx) {
                return AgentDecision.continueWith(AgentAction.READ_MEMORY, "stub");
            }
            @Override
            public List<AgentCapability> decide(AgentBlackboard bb) {
                if (bb.hasFlag(AgentFlag.MEMORY_LOADED)) return List.of();
                return List.of(new AgentCapability("load-memory", 1.0, "memory"));
            }
        };
    }

    private static MindBridgeAgent supervisorAgentStub() {
        return new MindBridgeAgent() {
            @Override
            public AgentName name() { return AgentName.SUPERVISOR_AGENT; }
            @Override
            public boolean supports(AgentContext ctx) { return ctx.memoryLoaded() && !ctx.intentRouted(); }
            @Override
            public AgentDecision act(AgentContext ctx) {
                return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "stub");
            }
            @Override
            public List<AgentCapability> decide(AgentBlackboard bb) {
                if (!bb.hasFlag(AgentFlag.MEMORY_LOADED) || bb.hasFlag(AgentFlag.INTENT_ROUTED))
                    return List.of();
                return List.of(new AgentCapability("route-intent", 1.0, "intent", List.of("memory")));
            }
        };
    }

    private static MindBridgeAgent knowledgeAgentStub() {
        return new MindBridgeAgent() {
            @Override
            public AgentName name() { return AgentName.KNOWLEDGE_AGENT; }
            @Override
            public boolean supports(AgentContext ctx) {
                return ctx.intentRouted() && !ctx.knowledgeHandled() && ctx.intent() != IntentType.CHAT;
            }
            @Override
            public AgentDecision act(AgentContext ctx) {
                return AgentDecision.continueWith(AgentAction.RETRIEVE_KNOWLEDGE, "stub");
            }
            @Override
            public List<AgentCapability> decide(AgentBlackboard bb) {
                if (!bb.hasFlag(AgentFlag.INTENT_ROUTED) || bb.hasFlag(AgentFlag.KNOWLEDGE_HANDLED))
                    return List.of();
                IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                        .filter(a -> a.payload() instanceof IntentType)
                        .map(a -> (IntentType) a.payload())
                        .orElse(null);
                if (intent == IntentType.CHAT) return List.of();
                return List.of(new AgentCapability("retrieve-knowledge", 0.9, "knowledge", List.of("intent")));
            }
        };
    }

    private static MindBridgeAgent riskGuardianAgentStub() {
        return new MindBridgeAgent() {
            @Override
            public AgentName name() { return AgentName.RISK_GUARDIAN_AGENT; }
            @Override
            public boolean supports(AgentContext ctx) {
                return ctx.knowledgeHandled() && !ctx.riskAssessed() && ctx.intent() != IntentType.CHAT;
            }
            @Override
            public AgentDecision act(AgentContext ctx) {
                return AgentDecision.continueWith(AgentAction.ASSESS_RISK, "stub");
            }
            @Override
            public List<AgentCapability> decide(AgentBlackboard bb) {
                if (!bb.hasFlag(AgentFlag.KNOWLEDGE_HANDLED) || bb.hasFlag(AgentFlag.RISK_ASSESSED))
                    return List.of();
                IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                        .filter(a -> a.payload() instanceof IntentType)
                        .map(a -> (IntentType) a.payload())
                        .orElse(null);
                if (intent == IntentType.CHAT) return List.of();
                return List.of(new AgentCapability("assess-risk", 0.95, "assessment",
                        List.of("intent", "knowledge")));
            }
        };
    }

    private static MindBridgeAgent companionAgentStub() {
        return new MindBridgeAgent() {
            @Override
            public AgentName name() { return AgentName.COMPANION_AGENT; }
            @Override
            public boolean supports(AgentContext ctx) {
                return ctx.intentRouted() && ctx.intent() == IntentType.CHAT && !ctx.responsePlanned();
            }
            @Override
            public AgentDecision act(AgentContext ctx) {
                return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "stub");
            }
            @Override
            public List<AgentCapability> decide(AgentBlackboard bb) {
                if (bb.hasFlag(AgentFlag.RESPONSE_PLANNED) || !bb.hasFlag(AgentFlag.INTENT_ROUTED))
                    return List.of();
                IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                        .filter(a -> a.payload() instanceof IntentType)
                        .map(a -> (IntentType) a.payload())
                        .orElse(null);
                if (intent != IntentType.CHAT) return List.of();
                return List.of(new AgentCapability("plan-companion-response", 0.9, "response",
                        List.of("intent")));
            }
        };
    }

    private static MindBridgeAgent counselorAgentStub() {
        return new MindBridgeAgent() {
            @Override
            public AgentName name() { return AgentName.COUNSELOR_AGENT; }
            @Override
            public boolean supports(AgentContext ctx) {
                return ctx.riskAssessed() && ctx.intent() != IntentType.CHAT && !ctx.responsePlanned();
            }
            @Override
            public AgentDecision act(AgentContext ctx) {
                return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "stub");
            }
            @Override
            public List<AgentCapability> decide(AgentBlackboard bb) {
                if (bb.hasFlag(AgentFlag.RESPONSE_PLANNED) || !bb.hasFlag(AgentFlag.RISK_ASSESSED))
                    return List.of();
                IntentType intent = bb.getArtifact(AgentArtifact.NAME_INTENT)
                        .filter(a -> a.payload() instanceof IntentType)
                        .map(a -> (IntentType) a.payload())
                        .orElse(null);
                if (intent == IntentType.CHAT || intent == null) return List.of();
                return List.of(new AgentCapability("plan-counselor-response", 0.95, "response",
                        List.of("intent", "knowledge", "assessment")));
            }
        };
    }
}