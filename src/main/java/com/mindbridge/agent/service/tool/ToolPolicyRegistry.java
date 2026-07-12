package com.mindbridge.agent.service.tool;

import com.mindbridge.agent.domain.RiskLevel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 工具授权策略注册中心。
 *
 * <p>预定义工具策略，提供 {@link #authorize} 方法根据工具名和当前风险等级进行授权决策。
 * 默认拒绝未知工具（deny-by-default）。</p>
 *
 * <p>默认策略：</p>
 * <ul>
 *   <li>{@code excel-report}：最低 LOW（需要报告时即可执行），不需人工审核</li>
 *   <li>{@code risk-alert-email}：最低 HIGH，不需人工审核（保持现有自动预警行为）</li>
 * </ul>
 */
@Service
public class ToolPolicyRegistry {

    public static final String TOOL_EXCEL_REPORT = "excel-report";
    public static final String TOOL_RISK_ALERT_EMAIL = "risk-alert-email";

    private final Map<String, ToolPolicy> policies;

    public ToolPolicyRegistry() {
        Map<String, ToolPolicy> map = new LinkedHashMap<>();
        map.put(TOOL_EXCEL_REPORT, new ToolPolicy(TOOL_EXCEL_REPORT, RiskLevel.LOW, false));
        map.put(TOOL_RISK_ALERT_EMAIL, new ToolPolicy(TOOL_RISK_ALERT_EMAIL, RiskLevel.HIGH, false));
        this.policies = Map.copyOf(map);
    }

    /**
     * 授权决策。
     *
     * @param toolName  工具名称
     * @param riskLevel 当前风险等级
     * @return 授权结果
     */
    public AuthorizationResult authorize(String toolName, RiskLevel riskLevel) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");

        ToolPolicy policy = policies.get(toolName);
        if (policy == null) {
            return AuthorizationResult.deny(toolName, riskLevel, "Unknown tool: " + toolName);
        }
        if (riskMeetsMinimum(riskLevel, policy.minRiskLevel()) == false) {
            return AuthorizationResult.deny(toolName, riskLevel,
                    "Risk level " + riskLevel + " below minimum " + policy.minRiskLevel());
        }
        if (policy.requireHumanReview()) {
            return AuthorizationResult.reviewRequired(toolName, riskLevel,
                    "Tool requires human review before execution");
        }
        return AuthorizationResult.allow(toolName, riskLevel);
    }

    /**
     * 判断风险等级是否满足最低要求。
     * RiskLevel ordinal: LOW=0, MEDIUM=1, HIGH=2
     */
    private boolean riskMeetsMinimum(RiskLevel actual, RiskLevel minimum) {
        return actual.ordinal() >= minimum.ordinal();
    }

    /**
     * 返回所有已注册策略。
     */
    public Map<String, ToolPolicy> policies() {
        return policies;
    }

    // ────────────── 授权结果 ──────────────

    /**
     * 工具授权决策结果（不可变）。
     *
     * @param toolName     工具名称
     * @param riskLevel    当前风险等级
     * @param decision     授权决策：ALLOWED, DENIED, REVIEW_REQUIRED
     * @param reason       决策原因摘要
     */
    public record AuthorizationResult(
            String toolName,
            RiskLevel riskLevel,
            Decision decision,
            String reason
    ) {
        public enum Decision {
            ALLOWED,
            DENIED,
            REVIEW_REQUIRED
        }

        public boolean allowed() {
            return decision == Decision.ALLOWED;
        }

        public boolean denied() {
            return decision == Decision.DENIED;
        }

        public boolean reviewRequired() {
            return decision == Decision.REVIEW_REQUIRED;
        }

        static AuthorizationResult allow(String toolName, RiskLevel riskLevel) {
            return new AuthorizationResult(toolName, riskLevel, Decision.ALLOWED, "Authorized");
        }

        static AuthorizationResult deny(String toolName, RiskLevel riskLevel, String reason) {
            return new AuthorizationResult(toolName, riskLevel, Decision.DENIED, reason);
        }

        static AuthorizationResult reviewRequired(String toolName, RiskLevel riskLevel, String reason) {
            return new AuthorizationResult(toolName, riskLevel, Decision.REVIEW_REQUIRED, reason);
        }
    }
}