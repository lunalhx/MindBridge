package com.mindbridge.agent.service.agent.blackboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindbridge.agent.service.agent.AgentName;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentBlackboardTest {

    @Test
    void emptyShouldHaveNoState() {
        var board = AgentBlackboard.empty();
        assertThat(board.artifacts()).isEmpty();
        assertThat(board.events()).isEmpty();
        assertThat(board.flags()).isEmpty();
    }

    @Test
    void addArtifactShouldReturnNewInstanceAndPreserveOld() {
        var board1 = AgentBlackboard.empty();
        var artifact = new AgentArtifact("intent", AgentName.SUPERVISOR_AGENT, Instant.now(), "CHAT");

        var board2 = board1.addArtifact(artifact);

        // 旧实例不受影响
        assertThat(board1.artifacts()).isEmpty();
        // 新实例包含新增 artifact
        assertThat(board2.artifacts()).containsKey("intent");
        // 不同实例
        assertThat(board1).isNotSameAs(board2);
    }

    @Test
    void addEventShouldReturnNewInstanceAndPreserveOld() {
        var board1 = AgentBlackboard.empty();
        var event = new AgentEvent("TEST", AgentName.MEMORY_AGENT, "test event");

        var board2 = board1.addEvent(event);

        // 旧实例事件列表不变
        assertThat(board1.events()).isEmpty();
        // 新实例增加了事件
        assertThat(board2.events()).hasSize(1);
        assertThat(board2.events().get(0).eventType()).isEqualTo("TEST");
        // 不同实例
        assertThat(board1).isNotSameAs(board2);
    }

    @Test
    void setFlagShouldReturnNewInstanceAndPreserveOld() {
        var board1 = AgentBlackboard.empty();

        var board2 = board1.setFlag(AgentFlag.MEMORY_LOADED);

        // 旧实例未设置 flag
        assertThat(board1.hasFlag(AgentFlag.MEMORY_LOADED)).isFalse();
        // 新实例已设置 flag
        assertThat(board2.hasFlag(AgentFlag.MEMORY_LOADED)).isTrue();
        // 不同实例
        assertThat(board1).isNotSameAs(board2);
    }

    @Test
    void setFlagShouldBeIdempotent() {
        var board1 = AgentBlackboard.empty();
        var board2 = board1.setFlag(AgentFlag.MEMORY_LOADED);
        var board3 = board2.setFlag(AgentFlag.MEMORY_LOADED);

        // 重复设置同一 flag 应返回同一实例
        assertThat(board3).isSameAs(board2);
    }

    @Test
    void artifactsShouldBeUnmodifiable() {
        var board = AgentBlackboard.empty()
                .addArtifact(new AgentArtifact("a", AgentName.MEMORY_AGENT, Instant.now(), "v"));

        Map<String, AgentArtifact> artifacts = board.artifacts();
        assertThatThrownBy(() -> artifacts.put("b", null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> artifacts.remove("a"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void eventsShouldBeUnmodifiable() {
        var board = AgentBlackboard.empty()
                .addEvent(new AgentEvent("T1", AgentName.MEMORY_AGENT, "s1"));

        List<AgentEvent> events = board.events();
        assertThatThrownBy(() -> events.add(new AgentEvent("T2", AgentName.MEMORY_AGENT, "s2")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> events.set(0, new AgentEvent("T2", AgentName.MEMORY_AGENT, "s2")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void flagsShouldBeUnmodifiable() {
        var board = AgentBlackboard.empty()
                .setFlag(AgentFlag.MEMORY_LOADED);

        Set<AgentFlag> flags = board.flags();
        assertThatThrownBy(() -> flags.add(AgentFlag.FINISHED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void eventsShouldBeAppendOnly() {
        var board1 = AgentBlackboard.empty();
        var event1 = new AgentEvent("T1", AgentName.MEMORY_AGENT, "first");
        var board2 = board1.addEvent(event1);
        var event2 = new AgentEvent("T2", AgentName.SUPERVISOR_AGENT, "second");
        var board3 = board2.addEvent(event2);

        // board2 不应受 board3 影响
        assertThat(board2.events()).hasSize(1);
        assertThat(board2.events().get(0).eventType()).isEqualTo("T1");
        // board3 应按追加顺序包含两个事件
        assertThat(board3.events()).hasSize(2);
        assertThat(board3.events().get(0).eventType()).isEqualTo("T1");
        assertThat(board3.events().get(1).eventType()).isEqualTo("T2");
    }

    @Test
    void shouldSupportMethodChaining() {
        var board = AgentBlackboard.empty()
                .addEvent(new AgentEvent("T1", AgentName.MEMORY_AGENT, "s1"))
                .setFlag(AgentFlag.MEMORY_LOADED)
                .addArtifact(new AgentArtifact("intent", AgentName.SUPERVISOR_AGENT, Instant.now(), "CHAT"))
                .setFlag(AgentFlag.INTENT_ROUTED)
                .addEvent(new AgentEvent("T2", AgentName.SUPERVISOR_AGENT, "s2"));

        assertThat(board.events()).hasSize(2);
        assertThat(board.flags()).contains(AgentFlag.MEMORY_LOADED, AgentFlag.INTENT_ROUTED);
        assertThat(board.artifacts()).containsKey("intent");
    }

    @Test
    void getArtifactShouldReturnByName() {
        var artifact = new AgentArtifact("knowledge", AgentName.KNOWLEDGE_AGENT, Instant.now(), List.of("doc1"));
        var board = AgentBlackboard.empty().addArtifact(artifact);

        assertThat(board.getArtifact("knowledge")).hasValue(artifact);
        assertThat(board.getArtifact("nonexistent")).isEmpty();
    }

    @Test
    void addArtifactShouldOverrideExistingByName() {
        var a1 = new AgentArtifact("intent", AgentName.SUPERVISOR_AGENT, Instant.now(), "CHAT");
        var a2 = new AgentArtifact("intent", AgentName.SUPERVISOR_AGENT, Instant.now(), "CONSULT");

        var board = AgentBlackboard.empty()
                .addArtifact(a1)
                .addArtifact(a2);

        assertThat(board.artifacts()).hasSize(1);
        assertThat(board.getArtifact("intent")).hasValue(a2);
    }

    @Test
    void eventSummaryShouldNotContainSensitiveInputByDesign() {
        // 验证 AgentEvent 的 summary 字段设计上不绑定 student input
        // 实际审计安全由 AgentContext 的 mark/set 方法保证（使用固定摘要文本）
        var event = new AgentEvent("INTENT_CLASSIFIED", AgentName.SUPERVISOR_AGENT, "意图已分类");
        assertThat(event.summary()).doesNotContain("学生", "student", "password");
    }
}
