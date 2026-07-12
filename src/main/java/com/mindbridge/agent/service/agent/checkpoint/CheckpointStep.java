package com.mindbridge.agent.service.agent.checkpoint;

import com.mindbridge.agent.service.agent.AgentAction;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentStep;
import java.time.Instant;

/**
 * 可序列化的 AgentStep 快照。
 */
public record CheckpointStep(
        int step,
        String agent,
        String action,
        String observation,
        Instant createdAt
) {
    public static CheckpointStep from(AgentStep s) {
        return new CheckpointStep(
                s.step(),
                s.agent() != null ? s.agent().name() : null,
                s.action() != null ? s.action().name() : null,
                s.observation(),
                s.createdAt());
    }

    public AgentStep toStep() {
        AgentName ag = agent != null ? AgentName.valueOf(agent) : null;
        AgentAction act = action != null ? AgentAction.valueOf(action) : null;
        return new AgentStep(step, ag, act, observation, createdAt != null ? createdAt : Instant.now());
    }
}
