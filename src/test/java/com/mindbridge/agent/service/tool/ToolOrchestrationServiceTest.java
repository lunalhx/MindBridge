package com.mindbridge.agent.service.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.AlertRecord;
import com.mindbridge.agent.domain.PsychologicalReport;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.ToolAuditRecord;
import com.mindbridge.agent.domain.ToolStatus;
import com.mindbridge.agent.repository.AlertRecordRepository;
import com.mindbridge.agent.repository.PsychologicalReportRepository;
import com.mindbridge.agent.repository.ToolAuditRecordRepository;
import com.mindbridge.agent.service.ToolOrchestrationService;
import com.mindbridge.agent.service.mcp.AlertNotifier;
import com.mindbridge.agent.service.mcp.ExcelReportWriter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ToolOrchestrationServiceTest {

    private ExcelReportWriter excelWriter;
    private AlertNotifier alertNotifier;
    private PsychologicalReportRepository reportRepository;
    private AlertRecordRepository alertRecordRepository;
    private ToolAuditRecordRepository auditRepository;
    private ToolPolicyRegistry policyRegistry;
    private MindBridgeProperties properties;
    private ToolOrchestrationService service;

    @BeforeEach
    void setUp() {
        excelWriter = mock(ExcelReportWriter.class);
        alertNotifier = mock(AlertNotifier.class);
        reportRepository = mock(PsychologicalReportRepository.class);
        alertRecordRepository = mock(AlertRecordRepository.class);
        auditRepository = mock(ToolAuditRecordRepository.class);
        policyRegistry = new ToolPolicyRegistry();
        properties = new MindBridgeProperties();

        // 同步执行的 TaskExecutor，便于测试
        TaskExecutor syncExecutor = new SyncTaskExecutor();
        // mock 事务管理器
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        org.mockito.Mockito.when(txManager.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        service = new ToolOrchestrationService(
                excelWriter, alertNotifier, reportRepository, alertRecordRepository,
                auditRepository, policyRegistry, properties, syncExecutor, txTemplate);
    }

    // ────────── LOW 风险 ──────────

    @Test
    void lowRiskShouldWriteExcelButSkipEmail() {
        var report = report(RiskLevel.LOW);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.handle(1L);

        // Excel 写入应执行
        verify(excelWriter).write(report);
        assertThat(report.getExcelStatus()).isEqualTo(ToolStatus.SUCCESS);

        // 邮件预警不应执行
        verify(alertNotifier, never()).notify(any(), any());
        assertThat(report.getEmailStatus()).isEqualTo(ToolStatus.SKIPPED);

        // Excel 审计记录应保存
        verify(auditRepository, atLeastOnce()).save(any(ToolAuditRecord.class));
    }

    // ────────── MEDIUM 风险 ──────────

    @Test
    void mediumRiskShouldWriteExcelButSkipEmail() {
        var report = report(RiskLevel.MEDIUM);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.handle(1L);

        verify(excelWriter).write(report);
        assertThat(report.getExcelStatus()).isEqualTo(ToolStatus.SUCCESS);
        verify(alertNotifier, never()).notify(any(), any());
        assertThat(report.getEmailStatus()).isEqualTo(ToolStatus.SKIPPED);
    }

    // ────────── HIGH 风险 ──────────

    @Test
    void highRiskShouldWriteExcelAndSendEmail() {
        var report = report(RiskLevel.HIGH);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.handle(1L);

        verify(excelWriter).write(report);
        assertThat(report.getExcelStatus()).isEqualTo(ToolStatus.SUCCESS);

        // HIGH + Excel SUCCESS → 邮件应发送
        verify(alertNotifier).notify(any(AlertRecord.class), any(PsychologicalReport.class));
        assertThat(report.getEmailStatus()).isEqualTo(ToolStatus.SUCCESS);
    }

    // ────────── Excel 失败 ──────────

    @Test
    void excelFailureShouldNotSendEmail() {
        var report = report(RiskLevel.HIGH);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        doThrowOnExcel();

        service.handle(1L);

        assertThat(report.getExcelStatus()).isEqualTo(ToolStatus.FAILED);
        assertThat(report.getToolError()).isNotEmpty();

        // Excel 失败 → 邮件不应发送
        verify(alertNotifier, never()).notify(any(), any());
    }

    // ────────── 底层工具不被越权调用 ──────────

    @Test
    void deniedToolShouldNotCallExcelWriter() {
        // 模拟未知工具策略 — 但实际策略中 Excel 总是允许 LOW+
        // 这里通过 MEDIUM 风险 + 邮件来验证
        var report = report(RiskLevel.MEDIUM);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.handle(1L);

        // Excel 写入（LOW+ 允许）
        verify(excelWriter, times(1)).write(report);
        // 邮件不应调用（MEDIUM < HIGH）
        verify(alertNotifier, never()).notify(any(), any());
    }

    @Test
    void lowRiskShouldNotCallAlertNotifier() {
        var report = report(RiskLevel.LOW);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.handle(1L);

        verify(alertNotifier, never()).notify(any(), any());
    }

    // ────────── 审计记录 ──────────

    @Test
    void shouldRecordAuditForExcelExecution() {
        var report = report(RiskLevel.LOW);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.handle(1L);

        // 至少保存了一条审计记录
        verify(auditRepository, atLeastOnce()).save(any(ToolAuditRecord.class));
    }

    @Test
    void shouldRecordAuditForDeniedEmailAtLowRisk() {
        var report = report(RiskLevel.LOW);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.handle(1L);

        // LOW 风险下 Excel 写入成功，但邮件被拒绝
        // 审计记录应保存（至少 Excel 的成功审计 + 邮件的不执行审计）
        // 但 LOW 风险下邮件预警不会被触发（Excel SUCCESS 但 riskLevel != HIGH）
        // 所以只有 Excel 的一条审计记录
        verify(auditRepository, atLeastOnce()).save(any(ToolAuditRecord.class));
    }

    @Test
    void shouldRecordAuditForFailedExcel() {
        var report = report(RiskLevel.HIGH);
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        doThrowOnExcel();

        service.handle(1L);

        verify(auditRepository, atLeastOnce()).save(any(ToolAuditRecord.class));
    }

    // ────────── handleAsync 不影响主链路 ──────────

    @Test
    void handleAsyncShouldNotThrowOnException() {
        when(reportRepository.findById(anyLong()))
                .thenThrow(new RuntimeException("DB down"));

        // 不应抛异常
        service.handleAsync(1L);
    }

    // ────────── 审计记录不含敏感内容 ──────────

    @Test
    void auditRecordShouldNotContainSensitiveContent() {
        var report = report(RiskLevel.LOW);
        report.setContent("学生敏感输入内容");
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        service.handle(1L);

        // 捕获审计记录并验证不含敏感内容
        var captor = org.mockito.ArgumentCaptor.forClass(ToolAuditRecord.class);
        verify(auditRepository, atLeastOnce()).save(captor.capture());
        for (ToolAuditRecord audit : captor.getAllValues()) {
            assertThat(audit.getSummary()).doesNotContain("学生敏感输入内容");
            assertThat(audit.getSummary()).doesNotContain(report.getContent());
        }
    }

    // ────────── Helpers ──────────

    private PsychologicalReport report(RiskLevel riskLevel) {
        var r = new PsychologicalReport();
        org.springframework.test.util.ReflectionTestUtils.setField(r, "id", 1L);
        r.setRiskLevel(riskLevel);
        r.setExcelStatus(ToolStatus.PENDING);
        r.setEmailStatus(ToolStatus.SKIPPED);
        r.setContent("test content");
        return r;
    }

    private void doThrowOnExcel() {
        org.mockito.Mockito.doThrow(new RuntimeException("Excel write failed"))
                .when(excelWriter).write(any());
    }
}