package com.mindbridge.agent.service.agent.registry;

import java.util.Objects;

/**
 * 声明式调度中的任务模型（不可变）。
 *
 * <p>声明式运行时扫描 Blackboard 后生成 AgentTask，
 * 每个 task 代表一个尚未完成的产出需求。Agent 通过 {@code decide()} 声明
 * 自己对该 task 的能力与置信度，Registry 按置信度排序后选择最佳候选。</p>
 *
 * @param name              任务名称，如 "produce-intent", "produce-knowledge"
 * @param desiredArtifact   该任务期望产出的 artifact 类型名称
 * @param description       人类可读的任务描述（不含敏感学生输入）
 */
public record AgentTask(
        String name,
        String desiredArtifact,
        String description
) {
    public AgentTask {
        Objects.requireNonNull(name, "task name must not be null");
        Objects.requireNonNull(desiredArtifact, "desiredArtifact must not be null");
        description = description == null ? "" : description;
    }
}