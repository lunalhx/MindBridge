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
import com.mindbridge.agent.service.tool.ToolPolicyRegistry.AuthorizationResult;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具 Job 处理器（独立 Bean，处理外部 I/O 调用不在数据库事务内）。
 *
 * <p>从 {@link ToolQueueWorker} 分离出来，解决 Spring 代理自调用导致的事务边界失效问题。
 * 此 Bean 的方法调用确保外部工具（Excel/邮件）在数据库事务之外执行。</p>
 */
@Service
public class ToolJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(ToolJobProcessor.class);

    private final ToolJobRepository jobRepository;
    private final PsychologicalReportRepository reportRepository;
    private final AlertRecordRepository alertRecordRepository;
    private final ToolAuditRecordRepository auditRepository;
    private final ExcelReportWriter excelWriter;
    private final AlertNotifier alertNotifier;
    private final ToolPolicyRegistry policyRegistry;
    private final MindBridgeProperties properties;

    public ToolJobProcessor(
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
     * 处理已原子领取的 Job。此方法不是 @Transactional，外部工具调用不在数据库事务内。
     *
     * <p>注意：此方法不是 @Transactional，外部工具调用（Excel 写入、邮件发送）
     * 不会持有数据库事务。状态更新通过各自的短事务方法完成。</p>
     */
    public void processClaimedJob(ToolJob job) {
        // Step 1: Dependency check (short transaction — just reads)
        ToolJob dependency = null;
        if (job.getDependsOn() != null) {
            dependency = jobRepository.findById(job.getDependsOn()).orElse(null);
        }

        // Step 2: Dependency handling (short transaction for status update)
        if (dependency != null && dependency.getStatus() != JobStatus.SUCCESS) {
            handleBlockedJob(job, dependency);
            return;
        }
        if (dependency == null && job.getDependsOn() != null) {
            handleMissingDependency(job);
            return;
        }

        // Step 3: Authorization check (short transaction — reads report)
        PsychologicalReport report = reportRepository.findById(job.getReportId()).orElse(null);
        if (report == null) {
            handleMissingReportJob(job);
            return;
        }

        AuthorizationResult auth = policyRegistry.authorize(job.getType(), report.getRiskLevel());
        if (!auth.allowed()) {
            handleAuthorizationDecision(job, auth);
            return;
        }

        // Step 4: Execute tool — NO TRANSACTION
        ToolStatus result = ToolStatus.FAILED;
        String errorSummary = null;
        try {
            executeTool(job, report);
            result = ToolStatus.SUCCESS;
        } catch (Exception e) {
            log.error("[tool-queue] Job {} execution failed: {}", job.getId(), e.getMessage());
            errorSummary = e.getMessage();
            result = ToolStatus.FAILED;
        }

        // Step 5: Update status (short transaction)
        updateJobAfterExecution(job, result, errorSummary, auth);
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

    // ────────────── 依赖/授权/失败处理（短事务） ──────────────

    @Transactional
    public void handleBlockedJob(ToolJob job, ToolJob dependency) {
        if (dependency.getStatus() == JobStatus.DEAD_LETTER) {
            job.setStatus(JobStatus.DEAD_LETTER);
            job.setErrorSummary("上游任务 " + dependency.getId() + " 已进入死信队列");
            jobRepository.save(job);
            recordAuditWithDecision(job, AuthorizationDecision.ALLOWED, ToolStatus.FAILED,
                    "上游死信，级联进入死信");
        } else {
            // 上游仍在重试/执行中，保持 BLOCKED 并重置检查时间
            job.setStatus(JobStatus.BLOCKED);
            job.setNextAttemptAt(Instant.now().plusSeconds(30));
            job.setLeaseUntil(null);
            job.setWorkerId(null);
            job.setClaimedAt(null);
            jobRepository.save(job);
        }
    }

    @Transactional
    public void handleMissingDependency(ToolJob job) {
        job.setStatus(JobStatus.DEAD_LETTER);
        job.setErrorSummary("依赖任务不存在: " + job.getDependsOn());
        jobRepository.save(job);
        recordAuditWithDecision(job, AuthorizationDecision.ALLOWED, ToolStatus.FAILED, "依赖任务不存在");
    }

    @Transactional
    public void handleMissingReportJob(ToolJob job) {
        job.setStatus(JobStatus.DEAD_LETTER);
        job.setErrorSummary("关联的报告不存在: " + job.getReportId());
        jobRepository.save(job);
        recordAuditWithDecision(job, AuthorizationDecision.ALLOWED, ToolStatus.FAILED, "报告不存在");
    }

    @Transactional
    public void handleAuthorizationDecision(ToolJob job, AuthorizationResult auth) {
        AuthorizationDecision decision = toAuditDecision(auth.decision());
        if (decision == AuthorizationDecision.DENIED) {
            job.setStatus(JobStatus.DEAD_LETTER);
            job.setErrorSummary("授权拒绝: " + auth.reason());
            jobRepository.save(job);
            recordAuditWithDecision(job, AuthorizationDecision.DENIED, ToolStatus.NOT_EXECUTED, auth.reason());
        } else if (decision == AuthorizationDecision.REVIEW_REQUIRED) {
            job.setStatus(JobStatus.REVIEW_REQUIRED);
            job.setNextAttemptAt(null);
            job.setLeaseUntil(null);
            job.setWorkerId(null);
            job.setClaimedAt(null);
            jobRepository.save(job);
            recordAuditWithDecision(job, AuthorizationDecision.REVIEW_REQUIRED, ToolStatus.NOT_EXECUTED,
                    "需要人工审核");
        }
    }

    @Transactional
    public void updateJobAfterExecution(ToolJob job, ToolStatus result, String errorSummary,
                                         AuthorizationResult auth) {
        AuthorizationDecision decision = toAuditDecision(auth.decision());
        if (result == ToolStatus.SUCCESS) {
            job.setStatus(JobStatus.SUCCESS);
            jobRepository.save(job);
            recordAuditWithDecision(job, decision, result, "执行成功");
        } else {
            int attempts = job.getAttempts();
            int maxAttempts = job.getMaxAttempts();
            if (attempts >= maxAttempts) {
                job.setStatus(JobStatus.DEAD_LETTER);
                job.setErrorSummary(errorSummary);
                jobRepository.save(job);
                recordAuditWithDecision(job, decision, ToolStatus.FAILED, errorSummary);
            } else {
                long delaySeconds = calculateBackoffSeconds(attempts);
                job.setStatus(JobStatus.RETRY);
                job.setNextAttemptAt(Instant.now().plusSeconds(delaySeconds));
                job.setLeaseUntil(null);
                job.setWorkerId(null);
                job.setClaimedAt(null);
                job.setErrorSummary(errorSummary);
                jobRepository.save(job);
                recordAuditWithDecision(job, decision, ToolStatus.FAILED,
                        "执行失败(MaxRetry " + attempts + "/" + maxAttempts + "): " + errorSummary);
            }
        }
    }

    /**
     * 将 ToolPolicyRegistry.AuthorizationResult.Decision 转换为 ToolAuditRecord.AuthorizationDecision。
     */
    private AuthorizationDecision toAuditDecision(ToolPolicyRegistry.AuthorizationResult.Decision decision) {
        if (decision == null) {
            return AuthorizationDecision.ALLOWED;
        }
        return switch (decision) {
            case ALLOWED -> AuthorizationDecision.ALLOWED;
            case DENIED -> AuthorizationDecision.DENIED;
            case REVIEW_REQUIRED -> AuthorizationDecision.REVIEW_REQUIRED;
        };
    }

    // ────────────── 退避计算 ──────────────

    private long calculateBackoffSeconds(int attempt) {
        long base = properties.getToolQueue().getInitialBackoffSeconds();
        double multiplier = properties.getToolQueue().getBackoffMultiplier();
        return (long) (base * Math.pow(multiplier, attempt));
    }

    // ────────────── 审计 ──────────────

    /**
     * 记录工具审计（使用真实授权结果）。
     */
    private void recordAuditWithDecision(ToolJob job, AuthorizationDecision decision,
                                          ToolStatus result, String summary) {
        try {
            RiskLevel riskLevel = job.getReportId() != null
                    ? reportRepository.findById(job.getReportId())
                            .map(PsychologicalReport::getRiskLevel).orElse(RiskLevel.LOW)
                    : RiskLevel.LOW;
            ToolAuditRecord audit = new ToolAuditRecord();
            audit.setReportId(job.getReportId());
            audit.setToolName(job.getType());
            audit.setRiskLevel(riskLevel);
            audit.setDecision(decision != null ? decision : AuthorizationDecision.ALLOWED);
            audit.setResult(result);
            audit.setSummary(shorten(summary));
            auditRepository.save(audit);
        } catch (Exception e) {
            log.warn("[tool-queue] Failed to record audit for job={}: {}", job.getId(), e.getMessage());
        }
    }

    private String shorten(String msg) {
        if (msg == null) return "";
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}