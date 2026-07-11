package com.mindbridge.agent.service.agent.runtime;

import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.blackboard.AgentFlag;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 运行时执行失败时抛出。
 *
 * <p>包含运行模式、已完成步骤数、已执行 Agent 序列和当前 Blackboard 标记状态，
 * 便于运维定位问题。<b>不包含学生原始输入</b>。</p>
 */
public class AgentRuntimeExecutionException extends RuntimeException {

    private final RuntimeMode mode;
    private final int stepsCompleted;
    private final List<AgentName> agentSequence;
    private final Set<AgentFlag> flags;

    public AgentRuntimeExecutionException(
            String message,
            RuntimeMode mode,
            AgentContext context) {
        super(message);
        this.mode = mode;
        this.stepsCompleted = context.steps().size();
        this.agentSequence = context.steps().stream()
                .map(step -> step.agent())
                .toList();
        this.flags = context.blackboard().flags();
    }

    public RuntimeMode mode() {
        return mode;
    }

    public int stepsCompleted() {
        return stepsCompleted;
    }

    public List<AgentName> agentSequence() {
        return agentSequence;
    }

    public Set<AgentFlag> flags() {
        return flags;
    }

    @Override
    public String getMessage() {
        return super.getMessage()
                + " [mode=" + mode
                + ", steps=" + stepsCompleted
                + ", agents=" + agentSequence.stream().map(Enum::name).collect(Collectors.joining("->"))
                + ", flags=" + flags + "]";
    }
}