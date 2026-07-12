package com.mindbridge.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 工具调用审计记录。
 *
 * <p>记录每次工具执行的授权决策和结果，不包含完整学生消息、模型 Prompt、密钥或邮件正文。</p>
 */
@Entity
@Table(name = "tool_audit_records")
public class ToolAuditRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的心理报告 ID */
    @Column(name = "report_id")
    private Long reportId;

    /** 工具名称 */
    @Column(nullable = false, length = 50)
    private String toolName;

    /** 触发时的风险等级 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RiskLevel riskLevel;

    /** 授权决策：ALLOWED, DENIED, REVIEW_REQUIRED */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthorizationDecision decision;

    /** 执行结果：SUCCESS, FAILED, SKIPPED, NOT_EXECUTED */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ToolStatus result;

    /** 决策原因或执行摘要（不含敏感内容） */
    @Column(length = 500)
    private String summary;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // ────────────── Getters / Setters ──────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }

    public AuthorizationDecision getDecision() { return decision; }
    public void setDecision(AuthorizationDecision decision) { this.decision = decision; }

    public ToolStatus getResult() { return result; }
    public void setResult(ToolStatus result) { this.result = result; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    // ────────────── 授权决策枚举 ──────────────

    public enum AuthorizationDecision {
        ALLOWED,
        DENIED,
        REVIEW_REQUIRED
    }
}