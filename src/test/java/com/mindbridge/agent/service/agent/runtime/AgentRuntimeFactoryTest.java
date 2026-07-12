package com.mindbridge.agent.service.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.agent.AgentAction;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentDecision;
import com.mindbridge.agent.service.agent.AgentName;
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
        var factory = new AgentRuntimeFactory(emptyAgents(), new MindBridgeProperties(), null);
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
        assertThat(runtime.mode()).isEqualTo(RuntimeMode.SEQUENTIAL);
    }

    @Test
    void factoryShouldReturnSequentialForExplicitSequential() {
        var factory = new AgentRuntimeFactory(emptyAgents(), props("SEQUENTIAL"), null);
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
    }

    @Test
    void factoryShouldHandleCaseInsensitiveMode() {
        var factory = new AgentRuntimeFactory(emptyAgents(), props("sequential"), null);
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
    }

    @Test
    void factoryShouldHandleBlankModeAsSequential() {
        var factory = new AgentRuntimeFactory(emptyAgents(), props(""), null);
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
    }

    @Test
    void factoryShouldHandleNullModeAsSequential() {
        MindBridgeProperties p = new MindBridgeProperties();
        p.getAgent().setRuntimeMode(null);
        var factory = new AgentRuntimeFactory(emptyAgents(), p, null);
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
    }

    @Test
    void factoryShouldRejectUnknownMode() {
        var factory = new AgentRuntimeFactory(emptyAgents(), props("PIPELINE"), null);
        assertThatThrownBy(factory::runtime)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown runtime-mode")
                .hasMessageContaining("PIPELINE")
                .hasMessageContaining("SEQUENTIAL");
    }

    @Test
    void factoryShouldReturnGraphForGraphMode() {
        var factory = new AgentRuntimeFactory(sixStubAgents(), props("GRAPH"), null);
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(GraphAgentRuntime.class);
        assertThat(runtime.mode()).isEqualTo(RuntimeMode.GRAPH);
    }

    @Test
    void factoryShouldFailGraphModeWithoutRequiredAgents() {
        var factory = new AgentRuntimeFactory(emptyAgents(), props("GRAPH"), null);
        assertThatThrownBy(factory::runtime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required agent not found");
    }

    @Test
    void factoryShouldReturnEventDrivenForEventDrivenMode() {
        var factory = new AgentRuntimeFactory(sixStubAgents(), props("EVENT_DRIVEN"), null);
        AgentRuntime runtime = factory.runtime();
        assertThat(runtime).isInstanceOf(EventDrivenAgentRuntime.class);
        assertThat(runtime.mode()).isEqualTo(RuntimeMode.EVENT_DRIVEN);
    }

    @Test
    void factoryRuntimeByModeShouldWorkForSequential() {
        var factory = new AgentRuntimeFactory(emptyAgents(), new MindBridgeProperties(), null);
        AgentRuntime runtime = factory.runtime(RuntimeMode.SEQUENTIAL);
        assertThat(runtime).isInstanceOf(SequentialAgentRuntime.class);
    }

    @Test
    void factoryRuntimeByModeShouldReturnGraph() {
        var factory = new AgentRuntimeFactory(sixStubAgents(), new MindBridgeProperties(), null);
        AgentRuntime runtime = factory.runtime(RuntimeMode.GRAPH);
        assertThat(runtime).isInstanceOf(GraphAgentRuntime.class);
    }

    @Test
    void factoryRuntimeByModeShouldReturnEventDriven() {
        var factory = new AgentRuntimeFactory(sixStubAgents(), new MindBridgeProperties(), null);
        AgentRuntime runtime = factory.runtime(RuntimeMode.EVENT_DRIVEN);
        assertThat(runtime).isInstanceOf(EventDrivenAgentRuntime.class);
    }

    private List<MindBridgeAgent> sixStubAgents() {
        return List.of(
                stubAgent(AgentName.MEMORY_AGENT),
                stubAgent(AgentName.SUPERVISOR_AGENT),
                stubAgent(AgentName.KNOWLEDGE_AGENT),
                stubAgent(AgentName.RISK_GUARDIAN_AGENT),
                stubAgent(AgentName.COMPANION_AGENT),
                stubAgent(AgentName.COUNSELOR_AGENT));
    }

    private static MindBridgeAgent stubAgent(AgentName name) {
        return new MindBridgeAgent() {
            @Override
            public AgentName name() { return name; }
            @Override
            public boolean supports(AgentContext ctx) { return false; }
            @Override
            public AgentDecision act(AgentContext ctx) {
                return AgentDecision.continueWith(AgentAction.READ_MEMORY, "stub");
            }
        };
    }
}