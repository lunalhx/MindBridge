package com.mindbridge.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 工具队列作业（JPA entity）。
 *
 * <p>代表一个待执行的后台工具任务，如 Excel 报告写入或高风险邮件预警。
 * 支持依赖链（dependsOn 指向上游 Job ID）、幂等键防重、指数退避重试和死信状态。</p>
 *
 * <p><b>安全要求：</b>不在 payload 中复制敏感报告正文，仅保存 reportId 引用。</p>
 */
@Entity
@Table(name = "tool_jobs")
public class ToolJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 工具类型，如 "excel-report"、"risk-alert-email" */
    @Column(nullable = false, length = 50)
    private String type;

    /** 作业状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status = JobStatus.PENDING;

    /** 关联的心理报告 ID（不保存报告正文） */
    @Column(name = "report_id")
    private Long reportId;

    /** 依赖的上游 Job ID（null 表示无依赖） */
    @Column(name = "depends_on")
    private Long dependsOn;

    /** 当前尝试次数 */
    @Column(nullable = false)
    private int attempts = 0;

    /** 最大尝试次数 */
    @Column(nullable = false)
    private int maxAttempts = 3;

    /** 下次尝试时间（用于退避） */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt = Instant.now();

    /** 幂等键（reportId:type 组合，防止重复入队） */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    /** 错误摘要（不含敏感内容） */
    @Column(length = 500)
    private String errorSummary;

    /** 乐观锁版本号，防止并发重复领取 */
    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    // ────────────── Getters / Setters ──────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }

    public Long getDependsOn() { return dependsOn; }
    public void setDependsOn(Long dependsOn) { this.dependsOn = dependsOn; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    // ────────────── 作业状态枚举 ──────────────

    public enum JobStatus {
        /** 待执行 */
        PENDING,
        /** 正在执行 */
        RUNNING,
        /** 执行成功 */
        SUCCESS,
        /** 重试中（等待退避后再次执行） */
        RETRY,
        /** 死信（达到最大重试次数） */
        DEAD_LETTER,
        /** 被阻塞（依赖未满足） */
        BLOCKED
    }
}