package com.mindbridge.agent.service.agent.registry;

import java.util.List;
import java.util.Objects;

/**
 * Agent 能力声明（不可变）。
 *
 * <p>声明式调度中，Agent 通过 {@code decide()} 返回一组 Capability，
 * 表达自己对当前 Blackboard 状态中某个任务的胜任程度。</p>
 *
 * @param name             能力名称，如 "load-memory", "route-intent"
 * @param confidence       置信度，范围 [0.0, 1.0]，越高表示越适合执行
 * @param producesArtifact 该能力产出的 artifact 类型名称，如 "intent", "knowledge"
 * @param requiresArtifacts 该能力执行前所需已有的 artifact 类型名称列表（可为空）
 */
public record AgentCapability(
        String name,
        double confidence,
        String producesArtifact,
        List<String> requiresArtifacts
) {
    public AgentCapability {
        Objects.requireNonNull(name, "capability name must not be null");
        Objects.requireNonNull(producesArtifact, "producesArtifact must not be null");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException(
                    "confidence must be in [0.0, 1.0], got: " + confidence);
        }
        requiresArtifacts = requiresArtifacts == null ? List.of() : List.copyOf(requiresArtifacts);
    }

    /** 简化构造：不声明前置 artifact 依赖。 */
    public AgentCapability(String name, double confidence, String producesArtifact) {
        this(name, confidence, producesArtifact, List.of());
    }
}