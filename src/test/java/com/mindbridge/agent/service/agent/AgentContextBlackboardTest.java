package com.mindbridge.agent.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.EmotionLabel;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentContextBlackboardTest {

    private AgentContext context;

    @BeforeEach
    void setUp() {
        context = new AgentContext(user(), session(), "test input", "test input");
    }

    // ────────── Blackboard 初始化 ──────────

    @Test
    void shouldStartWithEmptyBlackboard() {
        var board = context.blackboard();
        assertThat(board.artifacts()).isEmpty();
        assertThat(board.events()).isEmpty();
        assertThat(board.flags()).isEmpty();
    }

    @Test
    void shouldStartWithAllFlagsFalse() {
        assertThat(context.memoryLoaded()).isFalse();
        assertThat(context.intentRouted()).isFalse();
        assertThat(context.knowledgeHandled()).isFalse();
        assertThat(context.riskAssessed()).isFalse();
        assertThat(context.responsePlanned()).isFalse();
        assertThat(context.finished()).isFalse();
    }

    // ────────── Flag 方法委托 ──────────

    @Test
    void markMemoryLoadedShouldSetFlagAndEvent() {
        var oldBoard = context.blackboard();
        context.markMemoryLoaded();

        assertThat(context.memoryLoaded()).isTrue();
        assertThat(context.blackboard()).isNotSameAs(oldBoard);
        assertThat(context.blackboard().hasFlag(AgentFlag.MEMORY_LOADED)).isTrue();
        assertThat(context.blackboard().events()).hasSize(1);
        assertThat(context.blackboard().events().get(0).eventType())
                .isEqualTo(AgentEvent.TYPE_MEMORY_LOADED);
    }

    @Test
    void markIntentRoutedShouldSetFlagAndEvent() {
        context.markIntentRouted();

        assertThat(context.intentRouted()).isTrue();
        assertThat(context.blackboard().hasFlag(AgentFlag.INTENT_ROUTED)).isTrue();
        assertThat(context.blackboard().events()).hasSize(1);
        assertThat(context.blackboard().events().get(0).eventType())
                .isEqualTo(AgentEvent.TYPE_INTENT_CLASSIFIED);
    }

    @Test
    void markKnowledgeHandledShouldSetFlagAndEvent() {
        context.markKnowledgeHandled();

        assertThat(context.knowledgeHandled()).isTrue();
        assertThat(context.blackboard().hasFlag(AgentFlag.KNOWLEDGE_HANDLED)).isTrue();
        assertThat(context.blackboard().events()).hasSize(1);
        assertThat(context.blackboard().events().get(0).eventType())
                .isEqualTo(AgentEvent.TYPE_KNOWLEDGE_RETRIEVED);
    }

    @Test
    void markRiskAssessedShouldSetFlagAndEvent() {
        context.markRiskAssessed();

        assertThat(context.riskAssessed()).isTrue();
        assertThat(context.blackboard().hasFlag(AgentFlag.RISK_ASSESSED)).isTrue();
        assertThat(context.blackboard().events()).hasSize(1);
        assertThat(context.blackboard().events().get(0).eventType())
                .isEqualTo(AgentEvent.TYPE_RISK_ASSESSED);
    }

    @Test
    void markResponsePlannedShouldSetFlagAndEvent() {
        context.markResponsePlanned();

        assertThat(context.responsePlanned()).isTrue();
        assertThat(context.blackboard().hasFlag(AgentFlag.RESPONSE_PLANNED)).isTrue();
        assertThat(context.blackboard().events()).hasSize(1);
        assertThat(context.blackboard().events().get(0).eventType())
                .isEqualTo(AgentEvent.TYPE_RESPONSE_PLANNED);
    }

    @Test
    void finishShouldSetFlagAndEvent() {
        context.finish();

        assertThat(context.finished()).isTrue();
        assertThat(context.blackboard().hasFlag(AgentFlag.FINISHED)).isTrue();
        assertThat(context.blackboard().events()).hasSize(1);
        assertThat(context.blackboard().events().get(0).eventType())
                .isEqualTo(AgentEvent.TYPE_LOOP_FINISHED);
    }

    @Test
    void blackboardReferenceShouldBeReplacedOnEachUpdate() {
        var board0 = context.blackboard();
        context.markMemoryLoaded();
        var board1 = context.blackboard();
        context.markIntentRouted();
        var board2 = context.blackboard();

        assertThat(board1).isNotSameAs(board0);
        assertThat(board2).isNotSameAs(board1);
        // 旧引用不受影响
        assertThat(board0.hasFlag(AgentFlag.MEMORY_LOADED)).isFalse();
        assertThat(board1.hasFlag(AgentFlag.MEMORY_LOADED)).isTrue();
        assertThat(board1.hasFlag(AgentFlag.INTENT_ROUTED)).isFalse();
    }

    // ────────── Artifact 同步 ──────────

    @Test
    void setIntentShouldSyncArtifactToBlackboard() {
        var oldBoard = context.blackboard();
        context.setIntent(IntentType.CONSULT);

        assertThat(context.intent()).isEqualTo(IntentType.CONSULT);
        assertThat(context.blackboard()).isNotSameAs(oldBoard);

        var artifact = context.blackboard().getArtifact(AgentArtifact.NAME_INTENT);
        assertThat(artifact).isPresent();
        assertThat(artifact.get().name()).isEqualTo(AgentArtifact.NAME_INTENT);
        assertThat(artifact.get().producer()).isEqualTo(AgentName.SUPERVISOR_AGENT);
        assertThat(artifact.get().payload()).isEqualTo(IntentType.CONSULT);
    }

    @Test
    void setAssessmentShouldSyncArtifactToBlackboard() {
        var assessment = new PsychologyAssessment(
                EmotionLabel.ANXIETY, 0.8, RiskLevel.HIGH, 0.9, "high risk assessment");
        context.setAssessment(assessment);

        assertThat(context.assessment()).isSameAs(assessment);
        var artifact = context.blackboard().getArtifact(AgentArtifact.NAME_ASSESSMENT);
        assertThat(artifact).isPresent();
        assertThat(artifact.get().name()).isEqualTo(AgentArtifact.NAME_ASSESSMENT);
        assertThat(artifact.get().producer()).isEqualTo(AgentName.RISK_GUARDIAN_AGENT);
        assertThat(artifact.get().payload()).isSameAs(assessment);
    }

    @Test
    void setRetrievedKnowledgeShouldSyncArtifactToBlackboard() {
        var knowledge = List.of(
                new SearchResult(1L, "doc1.md", "content1", 0.9),
                new SearchResult(2L, "doc2.md", "content2", 0.8));
        context.setRetrievedKnowledge(knowledge);

        assertThat(context.retrievedKnowledge()).hasSize(2);
        var artifact = context.blackboard().getArtifact(AgentArtifact.NAME_KNOWLEDGE);
        assertThat(artifact).isPresent();
        assertThat(artifact.get().name()).isEqualTo(AgentArtifact.NAME_KNOWLEDGE);
        assertThat(artifact.get().producer()).isEqualTo(AgentName.KNOWLEDGE_AGENT);
        @SuppressWarnings("unchecked")
        var payload = (List<SearchResult>) artifact.get().payload();
        assertThat(payload).hasSize(2);
    }

    @Test
    void setResponsePlanShouldSyncResponseArtifact() {
        context.setResponseAgent(AgentName.COUNSELOR_AGENT);
        context.setResponsePlan("先共情，再建议");

        var artifact = context.blackboard().getArtifact(AgentArtifact.NAME_RESPONSE);
        assertThat(artifact).isPresent();
        assertThat(artifact.get().producer()).isEqualTo(AgentName.COUNSELOR_AGENT);
        assertThat(artifact.get().payload()).isInstanceOf(AgentContext.ResponseArtifactPayload.class);
        var payload = (AgentContext.ResponseArtifactPayload) artifact.get().payload();
        assertThat(payload.plan()).isEqualTo("先共情，再建议");
        assertThat(payload.messages()).isEmpty();
    }

    @Test
    void setResponseMessagesShouldSyncResponseArtifact() {
        context.setResponseAgent(AgentName.COMPANION_AGENT);
        context.setResponsePlan("自然回答");
        var messages = List.of(new AiMessage("assistant", "Hello"));
        context.setResponseMessages(messages);

        assertThat(context.responseMessages()).hasSize(1);
        var artifact = context.blackboard().getArtifact(AgentArtifact.NAME_RESPONSE);
        assertThat(artifact).isPresent();
        assertThat(artifact.get().producer()).isEqualTo(AgentName.COMPANION_AGENT);
        var payload = (AgentContext.ResponseArtifactPayload) artifact.get().payload();
        assertThat(payload.plan()).isEqualTo("自然回答");
        assertThat(payload.messages()).hasSize(1);
    }

    // ────────── 完整 Agent Loop 模拟 ──────────

    @Test
    void shouldMaintainCorrectStateDuringFullAgentLoop() {
        // Step 1: MemoryAgent
        context.setMemoryBrief("用户偏好独处。");
        context.markMemoryLoaded();
        assertThat(context.memoryLoaded()).isTrue();
        assertThat(context.intentRouted()).isFalse();

        // Step 2: SupervisorAgent
        context.setIntent(IntentType.CONSULT);
        context.setRiskLevel(RiskLevel.LOW);
        context.setResponseAgent(AgentName.COUNSELOR_AGENT);
        context.markIntentRouted();
        assertThat(context.intentRouted()).isTrue();
        assertThat(context.intent()).isEqualTo(IntentType.CONSULT);

        // Step 3: KnowledgeAgent
        context.setKnowledgeQuery("失眠焦虑");
        context.setRetrievedKnowledge(List.of(
                new SearchResult(1L, "doc.md", "content", 0.9)));
        context.markKnowledgeHandled();
        assertThat(context.knowledgeHandled()).isTrue();

        // Step 4: RiskGuardianAgent
        context.setAssessment(new PsychologyAssessment(
                EmotionLabel.ANXIETY, 0.5, RiskLevel.MEDIUM, 0.7, "moderate"));
        context.setRiskLevel(RiskLevel.MEDIUM);
        context.markRiskAssessed();
        assertThat(context.riskAssessed()).isTrue();

        // Step 5: CounselorAgent
        context.setResponsePlan("提供放松技巧");
        context.setResponseMessages(List.of(new AiMessage("assistant", "试试深呼吸")));
        context.markResponsePlanned();
        assertThat(context.responsePlanned()).isTrue();

        // Final: AgentRuntimeService
        context.finish();
        assertThat(context.finished()).isTrue();

        // 验证 Blackboard 最终状态
        var board = context.blackboard();
        assertThat(board.flags()).containsExactlyInAnyOrder(
                AgentFlag.MEMORY_LOADED,
                AgentFlag.INTENT_ROUTED,
                AgentFlag.KNOWLEDGE_HANDLED,
                AgentFlag.RISK_ASSESSED,
                AgentFlag.RESPONSE_PLANNED,
                AgentFlag.FINISHED);
        assertThat(board.artifacts()).containsKeys(
                AgentArtifact.NAME_INTENT,
                AgentArtifact.NAME_KNOWLEDGE,
                AgentArtifact.NAME_ASSESSMENT,
                AgentArtifact.NAME_RESPONSE);

        // 事件包含每个 mark 步骤的事件
        assertThat(board.events()).hasSize(6);
    }

    @Test
    void shouldNotLeakSensitiveStudentInputInEventSummaries() {
        context.setMemoryBrief("用户说过一些个人经历");
        context.markMemoryLoaded();
        context.setIntent(IntentType.RISK);
        context.markIntentRouted();
        context.setRetrievedKnowledge(List.of());
        context.markKnowledgeHandled();
        context.setAssessment(new PsychologyAssessment(
                EmotionLabel.HIGH_RISK, 0.95, RiskLevel.HIGH, 0.9, "urgent"));
        context.markRiskAssessed();
        context.setResponsePlan("安全干预");
        context.markResponsePlanned();
        context.finish();

        for (AgentEvent event : context.blackboard().events()) {
            // 事件摘要不应包含可能敏感的用户输入
            assertThat(event.summary())
                    .as("Event summary should not leak sensitive input")
                    .doesNotContain("test input", "student");
        }
    }

    // ────────── 旧 API 兼容 ──────────

    @Test
    void oldApiGettersShouldBeConsistentWithBlackboard() {
        context.markMemoryLoaded();
        context.setIntent(IntentType.CHAT);
        context.markIntentRouted();

        // 旧 getter 应与 blackboard 标记一致
        assertThat(context.memoryLoaded())
                .isEqualTo(context.blackboard().hasFlag(AgentFlag.MEMORY_LOADED));
        assertThat(context.intentRouted())
                .isEqualTo(context.blackboard().hasFlag(AgentFlag.INTENT_ROUTED));
    }

    @Test
    void agentDecisionContinueWithShouldStillCompile() {
        // 验证 continueWith/finish 仍可正常使用（编译期已验证）
        var decision1 = AgentDecision.continueWith(AgentAction.READ_MEMORY, "ok");
        assertThat(decision1.complete()).isFalse();
        assertThat(decision1.artifacts()).isEmpty();

        var decision2 = AgentDecision.finish(AgentAction.PLAN_RESPONSE, "done");
        assertThat(decision2.complete()).isTrue();
        assertThat(decision2.artifacts()).isEmpty();
    }

    @Test
    void agentDecisionWithArtifactsShouldWork() {
        var artifacts = List.of(
                new AgentArtifact("intent", AgentName.SUPERVISOR_AGENT,
                        java.time.Instant.now(), IntentType.CHAT));

        var decision1 = AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "routed", artifacts);
        assertThat(decision1.complete()).isFalse();
        assertThat(decision1.artifacts()).hasSize(1);

        var decision2 = AgentDecision.finish(AgentAction.PLAN_RESPONSE, "done", artifacts);
        assertThat(decision2.complete()).isTrue();
        assertThat(decision2.artifacts()).hasSize(1);
    }

    // ────────── Helpers ──────────

    private UserAccount user() {
        var u = new UserAccount();
        u.setUsername("testuser");
        u.setDisplayName("Test User");
        return u;
    }

    private ChatSession session() {
        var s = new ChatSession();
        s.setPublicId("test-session");
        s.setTitle("Test Session");
        return s;
    }
}
