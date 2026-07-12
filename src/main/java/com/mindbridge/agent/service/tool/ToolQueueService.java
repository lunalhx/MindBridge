package com.mindbridge.agent.service.tool;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.PsychologicalReport;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.ToolAuditRecord;
import com.mindbridge.agent.domain.ToolAuditRecord.AuthorizationDecision;
import com.mindbridge.agent.domain.ToolJob;
import com.mindbridge.agent.domain.ToolJob.JobStatus;
import com.mindbridge.agent.domain.ToolStatus;
import com.mindbridge.agent.repository.PsychologicalReportRepository;
import com.mindbridge.agent.repository.ToolAuditRecordRepository;
import com.mindbridge.agent.repository.ToolJobRepository;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具队列入队服务。
 *
 * <p>负责按幂等键创建工具作业，构建依赖链：</p>
 * <ol>
 *   <li>先创建 Excel job（无依赖）</li>
 *   <li>HIGH 风险时再创建 Alert job，dependsOn 指向 Excel job</li>
 * </ol>
 * <p>重复 handleAsync 不会创建重复作业（幂等键 = reportId:type）。</p>
 */
@Service
public class ToolQueueService {

    private static final Logger log = LoggerFactory.getLogger(ToolQueueService.class);

    public static final String TYPE_EXCEL_REPORT = "excel-report";
    public static final String TYPE_RISK_ALERT_EMAIL = "risk-alert-email";

    private final ToolJobRepository jobRepository;
    private final PsychologicalReportRepository reportRepository;
    private final ToolAuditRecordRepository auditRepository;
    private final MindBridgeProperties properties;

    public ToolQueueService(ToolJobRepository jobRepository,
                            PsychologicalReportRepository reportRepository,
                            ToolAuditRecordRepository auditRepository,
                            MindBridgeProperties properties) {
        this.jobRepository = jobRepository;
        this.reportRepository = reportRepository;
        this.auditRepository = auditRepository;
        this.properties = properties;
    }

    /**
     * 为报告入队工具作业。
     *
     * <p>先创建 Excel job（幂等键 = reportId:excel-report）。
     * 如果 riskLevel == HIGH，再创建 Alert job（幂等键 = reportId:risk-alert-email），
     * dependsOn 指向 Excel job。重复调用不会创建重复作业。</p>
     *
     * @param reportId  心理报告 ID
     * @param riskLevel 风险等级
     */
    @Transactional
    public void enqueue(Long reportId, RiskLevel riskLevel) {
        ToolJob excelJob = enqueueJob(reportId, TYPE_EXCEL_REPORT, null);

        if (riskLevel == RiskLevel.HIGH) {
            enqueueJob(reportId, TYPE_RISK_ALERT_EMAIL, excelJob.getId());
        }

        log.info("[tool-queue] Enqueued jobs for reportId={}, risk={}", reportId, riskLevel);
    }

    /**
     * 入队单个作业（幂等：已存在则跳过）。
     *
     * @param reportId  报告 ID
     * @param type      工具类型
     * @param dependsOn 依赖的上游 Job ID（null 表示无依赖）
     * @return 已存在或新创建的 ToolJob
     */
    private ToolJob enqueueJob(Long reportId, String type, Long dependsOn) {
        String idempotencyKey = idempotencyKey(reportId, type);
        Optional<ToolJob> existing = jobRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.debug("[tool-queue] Job already exists: key={}, skipping", idempotencyKey);
            return existing.get();
        }

        ToolJob job = new ToolJob();
        job.setType(type);
        job.setReportId(reportId);
        job.setDependsOn(dependsOn);
        job.setIdempotencyKey(idempotencyKey);
        job.setStatus(JobStatus.PENDING);
        job.setAttempts(0);
        job.setMaxAttempts(properties.getToolQueue().getMaxAttempts());
        job.setNextAttemptAt(Instant.now());
        return jobRepository.save(job);
    }

    /**
     * 构建幂等键：reportId:type
     */
    static String idempotencyKey(Long reportId, String type) {
        return reportId + ":" + type;
    }

    /**
     * 批准人工审核任务，将其放回执行队列（PENDING）。
     */
    @Transactional
    public void approve(Long jobId, String reviewer) {
        Instant now = Instant.now();
        int updated = jobRepository.reviewJob(jobId, JobStatus.PENDING, "APPROVED", reviewer, now);
        if (updated == 0) {
            throw new IllegalStateException("Job " + jobId + " is not in REVIEW_REQUIRED status or does not exist");
        }
        recordReviewAudit(jobId, reviewer, "APPROVED", "人工审核通过");
        log.info("[tool-queue] Job {} approved by {}, status reset to PENDING", jobId, reviewer);
    }

    /**
     * 拒绝人工审核任务，将其移入死信队列。
     */
    @Transactional
    public void reject(Long jobId, String reviewer, String reason) {
        Instant now = Instant.now();
        String reasonText = reason != null ? reason : "审核拒绝";
        int updated = jobRepository.reviewJob(jobId, JobStatus.DEAD_LETTER, "REJECTED: " + reasonText, reviewer, now);
        if (updated == 0) {
            throw new IllegalStateException("Job " + jobId + " is not in REVIEW_REQUIRED status or does not exist");
        }
        // Also record the rejection in errorSummary
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setErrorSummary("审核拒绝: " + reasonText);
            jobRepository.save(job);
        });
        recordReviewAudit(jobId, reviewer, "REJECTED", "审核拒绝: " + reasonText);
        log.info("[tool-queue] Job {} rejected by {}: {}", jobId, reviewer, reasonText);
    }

    /**
     * 记录人工审核的审计记录。
     */
    private void recordReviewAudit(Long jobId, String reviewer, String decision, String summary) {
        try {
            ToolJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            RiskLevel riskLevel = reportRepository.findById(job.getReportId())
                    .map(PsychologicalReport::getRiskLevel).orElse(RiskLevel.LOW);
            ToolAuditRecord audit = new ToolAuditRecord();
            audit.setReportId(job.getReportId());
            audit.setToolName(job.getType());
            audit.setRiskLevel(riskLevel);
            audit.setDecision(AuthorizationDecision.REVIEW_REQUIRED);
            audit.setResult(decision.equals("APPROVED") ? ToolStatus.PENDING : ToolStatus.NOT_EXECUTED);
            audit.setSummary(summary != null && summary.length() > 500 ? summary.substring(0, 500) : summary);
            auditRepository.save(audit);
        } catch (Exception e) {
            log.warn("[tool-queue] Failed to record review audit for job={}: {}", jobId, e.getMessage());
        }
    }
}