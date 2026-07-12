package com.mindbridge.agent.service.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.PsychologicalReport;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.ToolJob;
import com.mindbridge.agent.domain.ToolJob.JobStatus;
import com.mindbridge.agent.repository.PsychologicalReportRepository;
import com.mindbridge.agent.service.ToolOrchestrationService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

class ToolOrchestrationServiceTest {

    private ToolQueueService queueService;
    private PsychologicalReportRepository reportRepository;
    private ToolOrchestrationService service;

    @BeforeEach
    void setUp() {
        queueService = mock(ToolQueueService.class);
        reportRepository = mock(PsychologicalReportRepository.class);
        var syncExecutor = new SyncTaskExecutor();
        service = new ToolOrchestrationService(queueService, reportRepository, syncExecutor);
    }

    @Test
    void handleAsyncShouldEnqueueJobsForHighRisk() {
        var report = report(RiskLevel.HIGH);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.handleAsync(1L);

        verify(queueService).enqueue(1L, RiskLevel.HIGH);
    }

    @Test
    void handleAsyncShouldEnqueueJobsForLowRisk() {
        var report = report(RiskLevel.LOW);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.handleAsync(1L);

        verify(queueService).enqueue(1L, RiskLevel.LOW);
    }

    @Test
    void handleAsyncShouldNotThrowWhenReportNotFound() {
        when(reportRepository.findById(999L)).thenReturn(Optional.empty());

        // 不应抛异常（异步执行中捕获）
        service.handleAsync(999L);
    }

    @Test
    void handleShouldEnqueueForExistingReport() {
        var report = report(RiskLevel.MEDIUM);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.handle(1L);

        verify(queueService).enqueue(1L, RiskLevel.MEDIUM);
    }

    private PsychologicalReport report(RiskLevel riskLevel) {
        var r = new PsychologicalReport();
        org.springframework.test.util.ReflectionTestUtils.setField(r, "id", 1L);
        r.setRiskLevel(riskLevel);
        return r;
    }
}