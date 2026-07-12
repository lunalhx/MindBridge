package com.mindbridge.agent.service.tool;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.AlertRecord;
import com.mindbridge.agent.domain.PsychologicalReport;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.ToolAuditRecord;
import com.mindbridge.agent.domain.ToolAuditRecord.AuthorizationDecision;
import com.mindbridge.agent.domain.ToolJob;
import com.mindbridge.agent.domain.ToolJob.JobStatus;
import com.mindbridge.agent.domain.ToolStatus;
import com.mindbridge.agent.repository.AlertRecordRepository;
import com.mindbridge.agent.repository.PsychologicalReportRepository;
import com.mindbridge.agent.repository.ToolAuditRecordRepository;
import com.mindbridge.agent.repository.ToolJobRepository;
import com.mindbridge.agent.service.mcp.AlertNotifier;
import com.mindbridge.agent.service.mcp.ExcelReportWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具队列 Worker：轮询待执行作业并执行。
 *
 * <p>使用 @Scheduled 定期轮询 PENDING/RETRY 状态且 nextAttemptAt 已到的作业。
 * 通过 JPA @Version 乐观锁防止并发重复领取。
 * 指数退避：失败后按 initialBackoff × multiplier^attempts 计算下次尝试时间。
 * 达到 maxAttempts 后进入 DEAD_LETTER。
 * 依赖未满足（上游 BLOCKED/DEAD_LETTER/RETRY）时设为 BLOCKED。</p>
 */
@Service
public class ToolQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(ToolQueueWorker.class);

    private final ToolJobRepository jobRepository;
    private final PsychologicalReportRepository reportRepository;
    private final AlertRecordRepository alertRecordRepository;
    private final ToolAuditRecordRepository auditRepository;
    private final ExcelReportWriter excelWriter;
    private final AlertNotifier alertNotifier;
    private final ToolPolicyRegistry policyRegistry;
    private final MindBridgeProperties properties;

    public ToolQueueWorker(
            ToolJobRepository jobRepository,
            PsychologicalReportRepository reportRepository,
            AlertRecordRepository alertRecordRepository,
            ToolAuditRecordRepository auditRepository,
            ExcelReportWriter excelWriter,
            AlertNotifier alertNotifier,
            ToolPolicyRegistry policyRegistry,
            MindBridgeProperties properties
    ) {
        this.jobRepository = jobRepository;
        this.reportRepository = reportRepository;
        this.alertRecordRepository = alertRecordRepository;
        this.auditRepository = auditRepository;
        this.excelWriter = excelWriter;
        this.alertNotifier = alertNotifier;
        this.policyRegistry = policyRegistry;
        this.properties = properties;
    }

    /**
     * 定期轮询并处理待执行作业。
     * 测试中可直接调用此方法，不依赖定时触发。
     */
    @Scheduled(fixedDelayString = "${mindbridge.tool-queue.poll-interval-ms:5000}")
    @Transactional
    public void poll() {
        Instant now = Instant.now();
        List<ToolJob> jobs = jobRepository
                .findByStatusInAndNextAttemptAtBeforeOrderByCreatedAtAsc(
                        List.of(JobStatus.PENDING, JobStatus.RETRY), now);

        int batchSize = Math.min(properties.getToolQueue().getBatchSize(), jobs.size());
        for (int i = 0; i < batchSize; i++) {
            ToolJob job = jobs.get(i);
            processJob(job);
        }
    }

    /**
     * 处理单个作业。测试中可直接调用。
     */
    @Transactional
    public void processJob(ToolJob job) {
        // 依赖检查
        if (job.getDependsOn() != null) {
            ToolJob dependency = jobRepository.findById(job.getDependsOn()).orElse(null);
            if (dependency == null || dependency.getStatus() != JobStatus.SUCCESS) {
                handleBlocked(job, dependency);
                return;
            }
        }

        // 授权检查
        var report = reportRepository.findById(job.getReportId()).orElse(null);
        if (report == null) {
            handleMissingReport(job);
            return;
        }
        RiskLevel riskLevel = report.getRiskLevel();
        var auth = policyRegistry.authorize(job.getType(), riskLevel);
        if (!auth.allowed()) {
            handleDenied(job, auth.reason());
            return;
        }

        // 执行
        job.setStatus(JobStatus.RUNNING);
        job.setAttempts(job.getAttempts() + 1);
        jobRepository.save(job);

        try {
            executeTool(job, report);
            job.setStatus(JobStatus.SUCCESS);
            jobRepository.save(job);
            recordAudit(job, ToolStatus.SUCCESS, "Tool executed successfully");
        } catch (Exception e) {
            handleFailure(job, e);
        }
    }

    // ────────────── 工具执行 ──────────────

    private void executeTool(ToolJob job, PsychologicalReport report) {
        String type = job.getType();
        if (ToolQueueService.TYPE_EXCEL_REPORT.equals(type)) {
            excelWriter.write(report);
            report.setExcelStatus(ToolStatus.SUCCESS);
            reportRepository.save(report);
        } else if (ToolQueueService.TYPE_RISK_ALERT_EMAIL.equals(type)) {
            sendAlerts(report, job.getReportId());
        } else {
            throw new IllegalArgumentException("Unknown tool type: " + type);
        }
    }

    private void sendAlerts(PsychologicalReport report, Long reportId) {
        boolean allSuccess = true;
        for (String recipient : properties.getMcp().getEmail().getRecipients()) {
            AlertRecord alertRecord = new AlertRecord();
            alertRecord.setReport(report);
            alertRecord.setRecipient(recipient);
            alertRecordRepository.save(alertRecord);
            try {
                alertRecord.incrementAttempts();
                alertNotifier.notify(alertRecord, report);
                alertRecord.setStatus(ToolStatus.SUCCESS);
            } catch (Exception e) {
                alertRecord.setStatus(ToolStatus.FAILED);
                alertRecord.setErrorMessage(shorten(e.getMessage()));
                allSuccess = false;
            }
            alertRecordRepository.save(alertRecord);
        }
        report.setEmailStatus(allSuccess ? ToolStatus.SUCCESS : ToolStatus.FAILED);
        reportRepository.save(report);
    }

    // ────────────── 失败/阻塞/拒绝处理 ──────────────

    private void handleFailure(ToolJob job, Exception e) {
        String errorMsg = shorten(e.getMessage());
        log.error("[tool-queue] Job {} failed (attempt {}/{}): {}",
                job.getId(), job.getAttempts(), job.getMaxAttempts(), errorMsg, e);
        job.setErrorSummary(errorMsg);

        if (job.getAttempts() >= job.getMaxAttempts()) {
            job.setStatus(JobStatus.DEAD_LETTER);
            jobRepository.save(job);
            recordAudit(job, ToolStatus.FAILED, "Max attempts reached: " + errorMsg);
        } else {
            job.setStatus(JobStatus.RETRY);
            job.setNextAttemptAt(calculateBackoff(job.getAttempts()));
            jobRepository.save(job);
            recordAudit(job, ToolStatus.FAILED, "Retry scheduled: " + errorMsg);
        }
    }

    private void handleBlocked(ToolJob job, ToolJob dependency) {
        String reason;
        if (dependency == null) {
            reason = "Dependency job not found: " + job.getDependsOn();
            job.setStatus(JobStatus.BLOCKED);
            job.setNextAttemptAt(calculateBackoff(job.getAttempts()));
        } else if (dependency.getStatus() == JobStatus.DEAD_LETTER) {
            reason = "Dependency is in dead letter: " + job.getDependsOn();
            job.setStatus(JobStatus.DEAD_LETTER);
        } else {
            reason = "Dependency not satisfied: " + job.getDependsOn() + " status=" + dependency.getStatus();
            job.setStatus(JobStatus.BLOCKED);
            job.setNextAttemptAt(calculateBackoff(job.getAttempts()));
        }
        job.setErrorSummary(reason);
        jobRepository.save(job);
        log.warn("[tool-queue] Job {} blocked: {}", job.getId(), reason);
        recordAudit(job, ToolStatus.NOT_EXECUTED, reason);
    }

    private void handleDenied(ToolJob job, String reason) {
        job.setStatus(JobStatus.DEAD_LETTER);
        job.setErrorSummary("Authorization denied: " + reason);
        jobRepository.save(job);
        log.warn("[tool-queue] Job {} denied: {}", job.getId(), reason);
        recordAudit(job, ToolStatus.NOT_EXECUTED, "Denied: " + reason);
    }

    private void handleMissingReport(ToolJob job) {
        job.setStatus(JobStatus.DEAD_LETTER);
        job.setErrorSummary("Report not found: " + job.getReportId());
        jobRepository.save(job);
        log.error("[tool-queue] Job {} cannot find report: {}", job.getId(), job.getReportId());
        recordAudit(job, ToolStatus.FAILED, "Report not found");
    }

    // ────────────── 退避计算 ──────────────

    Instant calculateBackoff(int attempt) {
        long base = properties.getToolQueue().getInitialBackoffSeconds();
        double multiplier = properties.getToolQueue().getBackoffMultiplier();
        long delaySeconds = (long) (base * Math.pow(multiplier, attempt));
        return Instant.now().plus(delaySeconds, ChronoUnit.SECONDS);
    }

    // ────────────── 审计 ──────────────

    private void recordAudit(ToolJob job, ToolStatus result, String summary) {
        try {
            var report = reportRepository.findById(job.getReportId()).orElse(null);
            RiskLevel riskLevel = report != null ? report.getRiskLevel() : RiskLevel.LOW;
            ToolAuditRecord audit = new ToolAuditRecord();
            audit.setReportId(job.getReportId());
            audit.setToolName(job.getType());
            audit.setRiskLevel(riskLevel);
            audit.setDecision(AuthorizationDecision.ALLOWED);
            audit.setResult(result);
            audit.setSummary(shorten(summary));
            auditRepository.save(audit);
        } catch (Exception e) {
            log.error("[tool-queue] Failed to save audit: jobId={}, error={}", job.getId(), e.getMessage());
        }
    }

    private String shorten(String msg) {
        if (msg == null) return "";
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}