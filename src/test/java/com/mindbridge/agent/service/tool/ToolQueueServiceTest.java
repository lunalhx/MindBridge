package com.mindbridge.agent.service.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.ToolJob;
import com.mindbridge.agent.domain.ToolJob.JobStatus;
import com.mindbridge.agent.repository.ToolJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolQueueServiceTest {

    private ToolJobRepository jobRepository;
    private MindBridgeProperties properties;
    private ToolQueueService queueService;

    @BeforeEach
    void setUp() {
        jobRepository = mock(ToolJobRepository.class);
        properties = new MindBridgeProperties();
        properties.getToolQueue().setMaxAttempts(3);
        queueService = new ToolQueueService(jobRepository, properties);
    }

    // ────────── 入队 ──────────

    @Test
    void enqueueForLowRiskShouldCreateOnlyExcelJob() {
        when(jobRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(jobRepository.save(any(ToolJob.class))).thenAnswer(inv -> inv.getArgument(0));

        queueService.enqueue(1L, RiskLevel.LOW);

        verify(jobRepository).save(any(ToolJob.class));
    }

    @Test
    void enqueueForHighRiskShouldCreateExcelAndAlertJobs() {
        when(jobRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        // First save (Excel job) returns a job with id=10
        when(jobRepository.save(any(ToolJob.class))).thenAnswer(inv -> {
            ToolJob job = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(job, "id", 10L);
            return job;
        });

        queueService.enqueue(1L, RiskLevel.HIGH);

        // Should save 2 jobs: Excel + Alert
        verify(jobRepository, org.mockito.Mockito.times(2)).save(any(ToolJob.class));
    }

    // ────────── 幂等 ──────────

    @Test
    void duplicateEnqueueShouldNotCreateDuplicateJobs() {
        ToolJob existingExcel = new ToolJob();
        existingExcel.setIdempotencyKey(ToolQueueService.idempotencyKey(1L, ToolQueueService.TYPE_EXCEL_REPORT));
        when(jobRepository.findByIdempotencyKey(ToolQueueService.idempotencyKey(1L, ToolQueueService.TYPE_EXCEL_REPORT)))
                .thenReturn(Optional.of(existingExcel));
        when(jobRepository.findByIdempotencyKey(ToolQueueService.idempotencyKey(1L, ToolQueueService.TYPE_RISK_ALERT_EMAIL)))
                .thenReturn(Optional.empty());

        queueService.enqueue(1L, RiskLevel.HIGH);

        // Only 1 save (for the alert job — Excel already exists)
        verify(jobRepository, org.mockito.Mockito.times(1)).save(any(ToolJob.class));
    }

    @Test
    void idempotencyKeyShouldCombineReportIdAndType() {
        assertThat(ToolQueueService.idempotencyKey(42L, "excel-report"))
                .isEqualTo("42:excel-report");
        assertThat(ToolQueueService.idempotencyKey(42L, "risk-alert-email"))
                .isEqualTo("42:risk-alert-email");
    }

    @Test
    void fullyIdempotentEnqueueShouldNotSaveAnything() {
        ToolJob existingExcel = new ToolJob();
        ToolJob existingAlert = new ToolJob();
        when(jobRepository.findByIdempotencyKey(any())).thenReturn(Optional.of(existingExcel));
        // Override for alert specifically
        when(jobRepository.findByIdempotencyKey(ToolQueueService.idempotencyKey(1L, ToolQueueService.TYPE_RISK_ALERT_EMAIL)))
                .thenReturn(Optional.of(existingAlert));

        queueService.enqueue(1L, RiskLevel.HIGH);

        verify(jobRepository, never()).save(any(ToolJob.class));
    }
}