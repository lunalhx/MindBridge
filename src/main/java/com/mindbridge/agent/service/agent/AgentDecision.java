package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.service.agent.blackboard.AgentArtifact;
import java.util.List;

/**
 * 单个 Agent 执行一步后返回的决策摘要。
 *
 * @param action      当前执行的动作
 * @param observation 动作结果摘要，用于后续调试和可视化 trace
 * @param complete    是否结束本轮 agent loop
 * @param artifacts   本步产出的 artifact 列表（可选）
 */
public record AgentDecision(
        AgentAction action,
        String observation,
        boolean complete,
        List<AgentArtifact> artifacts
) {
    /** 兼容旧 API：不带 artifacts 的三参数构造。 */
    public AgentDecision(AgentAction action, String observation, boolean complete) {
        this(action, observation, complete, List.of());
    }

    /** 继续执行下一 Agent（无 artifact）。 */
    public static AgentDecision continueWith(AgentAction action, String observation) {
        return new AgentDecision(action, observation, false);
    }

    /** 结束 loop（无 artifact）。 */
    public static AgentDecision finish(AgentAction action, String observation) {
        return new AgentDecision(action, observation, true);
    }

    /** 继续执行下一 Agent，附带 artifact 产出。 */
    public static AgentDecision continueWith(AgentAction action, String observation,
                                              List<AgentArtifact> artifacts) {
        return new AgentDecision(action, observation, false, artifacts);
    }

    /** 结束 loop，附带 artifact 产出。 */
    public static AgentDecision finish(AgentAction action, String observation,
                                        List<AgentArtifact> artifacts) {
        return new AgentDecision(action, observation, true, artifacts);
    }
}
