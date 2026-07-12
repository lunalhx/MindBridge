package com.mindbridge.agent.service.tool;

import com.mindbridge.agent.domain.RiskLevel;
import java.util.Objects;

/**
 * 工具授权策略（不可变）。
 *
 * <p>声明某个工具所需的最低风险等级和是否需要人工审核。
 * 授权决策由 {@link ToolPolicyRegistry} 根据 RiskLevel 判断。</p>
 *
 * @param toolName           工具名称，如 "excel-report", "risk-alert-email"
 * @param minRiskLevel      最低风险等级，低于此等级拒绝执行
 * @param requireHumanReview 是否需要人工审核才能执行
 */
public record ToolPolicy(
        String toolName,
        RiskLevel minRiskLevel,
        boolean requireHumanReview
) {
    public ToolPolicy {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(minRiskLevel, "minRiskLevel must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
    }
}