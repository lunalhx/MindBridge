package com.mindbridge.agent.service.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.PsychologicalReport;
import com.mindbridge.agent.domain.RiskLevel;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolQueueWorkerTest {

    private ToolJobRepository jobRepository;
    private PsychologicalReportRepository reportRepository;
    private AlertRecordRepository alertRecordRepository;
    private ToolAuditRecordRepository auditRepository;
    private ExcelReportWriter excelWriter;
    private AlertNotifier alertNotifier;
    private ToolPolicyRegistry policyRegistry;
    private MindBridgeProperties properties;
    private ToolJobProcessor processor;
    private ToolQueueWorker worker;

    @BeforeEach
    void setUp() {
        jobRepository = mock(ToolJobRepository.class);
        reportRepository = mock(PsychologicalReportRepository.class);
        alertRecordRepository = mock(AlertRecordRepository.class);
        auditRepository = mock(ToolAuditRecordRepository.class);
        excelWriter = mock(ExcelReportWriter.class);
        alertNotifier = mock(AlertNotifier.class);
        policyRegistry = new ToolPolicyRegistry();
        properties = new MindBridgeProperties();
        properties.getToolQueue().setMaxAttempts(3);
        properties.getToolQueue().setInitialBackoffSeconds(1);
        properties.getToolQueue().setBackoffMultiplier(2.0);
        properties.getToolQueue().setLeaseSeconds(120);

        processor = new ToolJobProcessor(jobRepository, reportRepository, alertRecordRepository,
                auditRepository, excelWriter, alertNotifier, policyRegistry, properties);
        worker = new ToolQueueWorker(jobRepository, properties, processor);
    }

    // ────────── 成功执行 ──────────

    @Test
    void shouldExecuteExcelJobSuccessfully() {
        var job = claimedExcelJob(null);
        var report = report(RiskLevel.LOW);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        processor.processClaimedJob(job);

        verify(excelWriter).write(report);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCESS);
    }

    @Test
    void shouldExecuteAlertJobWhenDependencySucceeds() {
        var excelJob = excelJob(null);
        excelJob.setStatus(JobStatus.SUCCESS);
        excelJob.setId(10L);

        var alertJob = claimedAlertJob(10L);
        var report = report(RiskLevel.HIGH);
        when(jobRepository.findById(10L)).thenReturn(Optional.of(excelJob));
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        processor.processClaimedJob(alertJob);

        verify(alertNotifier).notify(any(), any());
        assertThat(alertJob.getStatus()).isEqualTo(JobStatus.SUCCESS);
    }

    // ────────── 依赖检查 ──────────

    @Test
    void shouldBlockJobWhenDependencyNotComplete() {
        var excelJob = excelJob(null);
        excelJob.setStatus(JobStatus.PENDING);
        excelJob.setId(10L);

        var alertJob = claimedAlertJob(10L);
        when(jobRepository.findById(10L)).thenReturn(Optional.of(excelJob));

        processor.processClaimedJob(alertJob);

        assertThat(alertJob.getStatus()).isEqualTo(JobStatus.BLOCKED);
        verify(alertNotifier, never()).notify(any(), any());
    }

    @Test
    void shouldDeadLetterWhenDependencyIsDeadLetter() {
        var excelJob = excelJob(null);
        excelJob.setStatus(JobStatus.DEAD_LETTER);
        excelJob.setId(10L);

        var alertJob = claimedAlertJob(10L);
        when(jobRepository.findById(10L)).thenReturn(Optional.of(excelJob));

        processor.processClaimedJob(alertJob);

        assertThat(alertJob.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
        assertThat(alertJob.getErrorSummary()).contains("死信");
    }

    @Test
    void shouldDeadLetterWhenDependencyNotFound() {
        var alertJob = claimedAlertJob(99L);
        when(jobRepository.findById(99L)).thenReturn(Optional.empty());

        processor.processClaimedJob(alertJob);

        assertThat(alertJob.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
        assertThat(alertJob.getErrorSummary()).contains("依赖任务不存在");
    }

    // ────────── 重试和退避 ──────────

    @Test
    void shouldRetryWithBackoffOnFailure() {
        var job = claimedExcelJob(null);
        var report = report(RiskLevel.LOW);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        doThrow(new RuntimeException("Excel error")).when(excelWriter).write(any());

        processor.processClaimedJob(job);

        assertThat(job.getStatus()).isEqualTo(JobStatus.RETRY);
        assertThat(job.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(job.getErrorSummary()).contains("Excel error");
    }

    @Test
    void backoffShouldIncreaseExponentially() {
        properties.getToolQueue().setInitialBackoffSeconds(10);
        properties.getToolQueue().setBackoffMultiplier(2.0);

        Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant b1 = worker.calculateBackoff(0); // 10 * 2^0 = 10s
        Instant b2 = worker.calculateBackoff(1); // 10 * 2^1 = 20s
        Instant b3 = worker.calculateBackoff(2); // 10 * 2^2 = 40s

        assertThat(b1).isAfterOrEqualTo(t0.plus(9, ChronoUnit.SECONDS));
        assertThat(b2).isAfterOrEqualTo(t0.plus(19, ChronoUnit.SECONDS));
        assertThat(b3).isAfterOrEqualTo(t0.plus(39, ChronoUnit.SECONDS));
    }

    // ────────── 最大重试 → 死信 ──────────

    @Test
    void shouldDeadLetterAfterMaxAttempts() {
        var job = claimedExcelJob(null);
        job.setAttempts(3); // already at max (claimJob incremented to 3)
        var report = report(RiskLevel.LOW);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        doThrow(new RuntimeException("Persistent failure")).when(excelWriter).write(any());

        processor.processClaimedJob(job);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
        assertThat(job.getErrorSummary()).contains("Persistent failure");
    }

    // ────────── 报告不存在 ──────────

    @Test
    void shouldDeadLetterWhenReportNotFound() {
        var job = claimedExcelJob(null);
        when(reportRepository.findById(1L)).thenReturn(Optional.empty());

        processor.processClaimedJob(job);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
        assertThat(job.getErrorSummary()).contains("报告不存在");
        verify(excelWriter, never()).write(any());
    }

    // ────────── poll 方法 ──────────

    @Test
    void pollShouldProcessPendingJobs() {
        var job = excelJob(null);
        var report = report(RiskLevel.LOW);
        var claimedJob = claimedExcelJob(null);
        when(jobRepository.findExpiredLeases(any())).thenReturn(List.of());
        when(jobRepository.findReadyJobs(any(), any())).thenReturn(List.of(job));
        when(jobRepository.claimJob(eq(1L), anyString(), any(), any(), any())).thenReturn(1);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(claimedJob));
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        worker.poll();

        verify(excelWriter).write(report);
        assertThat(claimedJob.getStatus()).isEqualTo(JobStatus.SUCCESS);
    }

    @Test
    void pollShouldProcessEmptyListGracefully() {
        when(jobRepository.findExpiredLeases(any())).thenReturn(List.of());
        when(jobRepository.findReadyJobs(any(), any())).thenReturn(List.of());

        worker.poll(); // should not throw
    }

    @Test
    void pollShouldSkipJobWhenClaimFails() {
        var job = excelJob(null);
        when(jobRepository.findExpiredLeases(any())).thenReturn(List.of());
        when(jobRepository.findReadyJobs(any(), any())).thenReturn(List.of(job));
        when(jobRepository.claimJob(eq(1L), anyString(), any(), any(), any())).thenReturn(0);

        worker.poll();

        verify(excelWriter, never()).write(any());
    }

    @Test
    void pollShouldResetExpiredLeaseToRetry() {
        var stuck = claimedExcelJob(null);
        stuck.setStatus(JobStatus.RUNNING);
        when(jobRepository.findExpiredLeases(any())).thenReturn(List.of(stuck));
        when(jobRepository.findReadyJobs(any(), any())).thenReturn(List.of());

        worker.poll();

        assertThat(stuck.getStatus()).isEqualTo(JobStatus.RETRY);
        assertThat(stuck.getWorkerId()).isNull();
        assertThat(stuck.getLeaseUntil()).isNull();
    }

    // ────────── 重启恢复 ──────────

    @Test
    void pendingJobShouldBeProcessableAfterRestart() {
        // Simulate a job that was PENDING before restart
        var job = excelJob(null);
        job.setStatus(JobStatus.PENDING);
        job.setAttempts(0);
        job.setNextAttemptAt(Instant.now().minus(1, ChronoUnit.MINUTES)); // overdue

        var claimedJob = claimedExcelJob(null);
        var report = report(RiskLevel.LOW);
        when(jobRepository.findExpiredLeases(any())).thenReturn(List.of());
        when(jobRepository.findReadyJobs(any(), any())).thenReturn(List.of(job));
        when(jobRepository.claimJob(eq(1L), anyString(), any(), any(), any())).thenReturn(1);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(claimedJob));
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        worker.poll();

        assertThat(claimedJob.getStatus()).isEqualTo(JobStatus.SUCCESS);
    }

    @Test
    void retryJobShouldBeProcessableAfterBackoff() {
        var job = excelJob(null);
        job.setStatus(JobStatus.RETRY);
        job.setAttempts(1);
        job.setNextAttemptAt(Instant.now().minus(1, ChronoUnit.MINUTES)); // backoff elapsed

        var claimedJob = claimedExcelJob(null);
        claimedJob.setAttempts(2);
        var report = report(RiskLevel.LOW);
        when(jobRepository.findExpiredLeases(any())).thenReturn(List.of());
        when(jobRepository.findReadyJobs(any(), any())).thenReturn(List.of(job));
        when(jobRepository.claimJob(eq(1L), anyString(), any(), any(), any())).thenReturn(1);
        when(jobRepository.findById(1L)).thenReturn(Optional.of(claimedJob));
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        worker.poll();

        assertThat(claimedJob.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(claimedJob.getAttempts()).isEqualTo(2);
    }

    // ────────── 审计 ──────────

    @Test
    void shouldRecordAuditOnSuccess() {
        var job = claimedExcelJob(null);
        var report = report(RiskLevel.LOW);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        processor.processClaimedJob(job);

        verify(auditRepository).save(any());
    }

    @Test
    void shouldRecordAuditOnFailure() {
        var job = claimedExcelJob(null);
        var report = report(RiskLevel.LOW);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        doThrow(new RuntimeException("fail")).when(excelWriter).write(any());

        processor.processClaimedJob(job);

        verify(auditRepository).save(any());
    }

    // ────────── Helpers ──────────

    private ToolJob excelJob(Long dependsOn) {
        var job = new ToolJob();
        org.springframework.test.util.ReflectionTestUtils.setField(job, "id", 1L);
        job.setType(ToolQueueService.TYPE_EXCEL_REPORT);
        job.setReportId(1L);
        job.setDependsOn(dependsOn);
        job.setStatus(JobStatus.PENDING);
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setNextAttemptAt(Instant.now());
        job.setIdempotencyKey(ToolQueueService.idempotencyKey(1L, ToolQueueService.TYPE_EXCEL_REPORT));
        return job;
    }

    /**
     * A job that has already been atomically claimed (RUNNING, attempts incremented).
     */
    private ToolJob claimedExcelJob(Long dependsOn) {
        var job = excelJob(dependsOn);
        job.setStatus(JobStatus.RUNNING);
        job.setAttempts(1);
        job.setWorkerId("test-worker");
        job.setClaimedAt(Instant.now());
        job.setLeaseUntil(Instant.now().plusSeconds(120));
        return job;
    }

    private ToolJob alertJob(Long dependsOn) {
        var job = new ToolJob();
        org.springframework.test.util.ReflectionTestUtils.setField(job, "id", 2L);
        job.setType(ToolQueueService.TYPE_RISK_ALERT_EMAIL);
        job.setReportId(1L);
        job.setDependsOn(dependsOn);
        job.setStatus(JobStatus.PENDING);
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setNextAttemptAt(Instant.now());
        job.setIdempotencyKey(ToolQueueService.idempotencyKey(1L, ToolQueueService.TYPE_RISK_ALERT_EMAIL));
        return job;
    }

    /**
     * A claimed alert job (RUNNING, attempts incremented).
     */
    private ToolJob claimedAlertJob(Long dependsOn) {
        var job = alertJob(dependsOn);
        job.setStatus(JobStatus.RUNNING);
        job.setAttempts(1);
        job.setWorkerId("test-worker");
        job.setClaimedAt(Instant.now());
        job.setLeaseUntil(Instant.now().plusSeconds(120));
        return job;
    }

    private PsychologicalReport report(RiskLevel riskLevel) {
        var r = new PsychologicalReport();
        org.springframework.test.util.ReflectionTestUtils.setField(r, "id", 1L);
        r.setRiskLevel(riskLevel);
        r.setExcelStatus(ToolStatus.PENDING);
        r.setEmailStatus(ToolStatus.SKIPPED);
        return r;
    }
}