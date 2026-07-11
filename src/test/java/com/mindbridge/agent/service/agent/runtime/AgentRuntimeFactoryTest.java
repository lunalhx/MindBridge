package com.mindbridge.agent.service.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeFactoryTest {

    private MindBridgeProperties props(String runtimeMode) {
        MindBridgeProperties p = new MindBridgeProperties();
        p.getAgent().setRuntimeMode(runtimeMode);
        return p;
    }

    private List<MindBridgeAgent> emptyAgents() {
        return List.of();
    }

    @Test
    void defaultModeShouldBeSequential() {
        MindBridgeProperties p = new MindBridgeProperties();
        assertThat(p.getAgent().getRuntimeMode()).isEqualTo("SEQUENTIAL");
    }

    @Test
    void factoryShouldReturnSequentialForDefaultConfig() {
        var factory = new AgentRuntimeFactory(emptyAgents(), new MindBridgeProperties());
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
        assertThat(runtime.mode()).isEqualTo(RuntimeMode.SEQUENTIAL);
    }

    @Test
    void factoryShouldReturnSequentialForExplicitSequential() {
        var factory = new AgentRuntimeFactory(emptyAgents(), props("SEQUENTIAL"));
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
    }

    @Test
    void factoryShouldHandleCaseInsensitiveMode() {
        var factory = new AgentRuntimeFactory(emptyAgents(), props("sequential"));
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
    }

    @Test
    void factoryShouldHandleBlankModeAsSequential() {
        var factory = new AgentRuntimeFactory(emptyAgents(), props(""));
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
    }

    @Test
    void factoryShouldHandleNullModeAsSequential() {
        MindBridgeProperties p = new MindBridgeProperties();
        p.getAgent().setRuntimeMode(null);
        var factory = new AgentRuntimeFactory(emptyAgents(), p);
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
    }

    @Test
    void factoryShouldRejectUnknownMode() {
        var factory = new AgentRuntimeFactory(emptyAgents(), props("PIPELINE"));
        assertThatThrownBy(factory::runtime)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown runtime-mode")
                .hasMessageContaining("PIPELINE")
                .hasMessageContaining("SEQUENTIAL");
    }

    @Test
    void factoryShouldRejectGraphModeAsNotImplemented() {
        var factory = new AgentRuntimeFactory(emptyAgents(), props("GRAPH"));
        assertThatThrownBy(factory::runtime)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("GRAPH")
                .hasMessageContaining("not yet implemented");
    }

    @Test
    void factoryShouldRejectEventDrivenModeAsNotImplemented() {
        var factory = new AgentRuntimeFactory(emptyAgents(), props("EVENT_DRIVEN"));
        assertThatThrownBy(factory::runtime)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("EVENT_DRIVEN")
                .hasMessageContaining("not yet implemented");
    }

    @Test
    void factoryRuntimeByModeShouldWorkForSequential() {
        var factory = new AgentRuntimeFactory(emptyAgents(), new MindBridgeProperties());
        AgentRuntime runtime = factory.runtime(RuntimeMode.SEQUENTIAL);
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
    }

    @Test
    void factoryRuntimeByModeShouldRejectGraph() {
        var factory = new AgentRuntimeFactory(emptyAgents(), new MindBridgeProperties());
        assertThatThrownBy(() -> factory.runtime(RuntimeMode.GRAPH))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void factoryRuntimeByModeShouldRejectEventDriven() {
        var factory = new AgentRuntimeFactory(emptyAgents(), new MindBridgeProperties());
        assertThatThrownBy(() -> factory.runtime(RuntimeMode.EVENT_DRIVEN))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}