package com.mindbridge.agent.service.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindbridge.agent.domain.RiskLevel;
import org.junit.jupiter.api.Test;

class ToolPolicyRegistryTest {

    private final ToolPolicyRegistry registry = new ToolPolicyRegistry();

    // ────────── 预定义策略 ──────────

    @Test
    void shouldHaveExcelReportPolicyWithMinLow() {
        var policy = registry.policies().get(ToolPolicyRegistry.TOOL_EXCEL_REPORT);
        assertThat(policy).isNotNull();
        assertThat(policy.minRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(policy.requireHumanReview()).isFalse();
    }

    @Test
    void shouldHaveRiskAlertEmailPolicyWithMinHigh() {
        var policy = registry.policies().get(ToolPolicyRegistry.TOOL_RISK_ALERT_EMAIL);
        assertThat(policy).isNotNull();
        assertThat(policy.minRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(policy.requireHumanReview()).isFalse();
    }

    // ────────── Excel 授权 ──────────

    @Test
    void excelReportShouldBeAllowedAtLowRisk() {
        var result = registry.authorize(ToolPolicyRegistry.TOOL_EXCEL_REPORT, RiskLevel.LOW);
        assertThat(result.allowed()).isTrue();
        assertThat(result.decision()).isEqualTo(ToolPolicyRegistry.AuthorizationResult.Decision.ALLOWED);
    }

    @Test
    void excelReportShouldBeAllowedAtMediumRisk() {
        var result = registry.authorize(ToolPolicyRegistry.TOOL_EXCEL_REPORT, RiskLevel.MEDIUM);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void excelReportShouldBeAllowedAtHighRisk() {
        var result = registry.authorize(ToolPolicyRegistry.TOOL_EXCEL_REPORT, RiskLevel.HIGH);
        assertThat(result.allowed()).isTrue();
    }

    // ────────── 邮件预警授权 ──────────

    @Test
    void riskAlertEmailShouldBeDeniedAtLowRisk() {
        var result = registry.authorize(ToolPolicyRegistry.TOOL_RISK_ALERT_EMAIL, RiskLevel.LOW);
        assertThat(result.denied()).isTrue();
        assertThat(result.reason()).contains("below minimum");
    }

    @Test
    void riskAlertEmailShouldBeDeniedAtMediumRisk() {
        var result = registry.authorize(ToolPolicyRegistry.TOOL_RISK_ALERT_EMAIL, RiskLevel.MEDIUM);
        assertThat(result.denied()).isTrue();
    }

    @Test
    void riskAlertEmailShouldBeAllowedAtHighRisk() {
        var result = registry.authorize(ToolPolicyRegistry.TOOL_RISK_ALERT_EMAIL, RiskLevel.HIGH);
        assertThat(result.allowed()).isTrue();
    }

    // ────────── 未知工具默认拒绝 ──────────

    @Test
    void unknownToolShouldBeDenied() {
        var result = registry.authorize("unknown-tool", RiskLevel.HIGH);
        assertThat(result.denied()).isTrue();
        assertThat(result.reason()).contains("Unknown tool");
    }

    // ────────── requireHumanReview ──────────

    @Test
    void reviewRequiredShouldReturnReviewRequiredDecision() {
        // 构造一个需要人工审核的策略
        var customRegistry = new ToolPolicyRegistry();
        // 默认策略不需要人工审核，验证 requireHumanReview=true 的行为
        // 通过自定义策略模拟
        var policy = new ToolPolicy("sensitive-tool", RiskLevel.MEDIUM, true);
        // 直接测试 AuthorizationResult 的 reviewRequired 判断
        var result = ToolPolicyRegistry.AuthorizationResult.reviewRequired(
                "sensitive-tool", RiskLevel.MEDIUM, "requires review");
        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.allowed()).isFalse();
        assertThat(result.denied()).isFalse();
        assertThat(result.decision()).isEqualTo(
                ToolPolicyRegistry.AuthorizationResult.Decision.REVIEW_REQUIRED);
    }

    // ────────── 确定性 ──────────

    @Test
    void authorizeShouldBeDeterministicAcrossCalls() {
        var first = registry.authorize(ToolPolicyRegistry.TOOL_EXCEL_REPORT, RiskLevel.LOW);
        var second = registry.authorize(ToolPolicyRegistry.TOOL_EXCEL_REPORT, RiskLevel.LOW);
        assertThat(first.decision()).isEqualTo(second.decision());
        assertThat(first.reason()).isEqualTo(second.reason());
    }

    // ────────── ToolPolicy 校验 ──────────

    @Test
    void policyShouldRejectBlankToolName() {
        try {
            new ToolPolicy("  ", RiskLevel.LOW, false);
            throw new AssertionError("Expected exception");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    void policyShouldRejectNullToolName() {
        try {
            new ToolPolicy(null, RiskLevel.LOW, false);
            throw new AssertionError("Expected exception");
        } catch (NullPointerException e) {
            // expected
        }
    }
}