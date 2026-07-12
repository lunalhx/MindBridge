package com.mindbridge.agent.service;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.AlertRecord;
import com.mindbridge.agent.domain.PsychologicalReport;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.ToolAuditRecord;
import com.mindbridge.agent.domain.ToolAuditRecord.AuthorizationDecision;
import com.mindbridge.agent.domain.ToolStatus;
import com.mindbridge.agent.repository.AlertRecordRepository;
import com.mindbridge.agent.repository.PsychologicalReportRepository;
import com.mindbridge.agent.repository.ToolAuditRecordRepository;
import com.mindbridge.agent.service.mcp.AlertNotifier;
import com.mindbridge.agent.service.mcp.ExcelReportWriter;
import com.mindbridge.agent.service.tool.ToolPolicyRegistry;
import com.mindbridge.agent.service.tool.ToolPolicyRegistry.AuthorizationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 后台工具编排服务。
 *
 * <p>心理报告生成后，按"写 Excel → 高风险发预警"的顺序执行工具链并持久化状态。
 * 每次工具执行前通过 {@link ToolPolicyRegistry} 授权，审计记录写入 {@link ToolAuditRecord}。</p>
 *
 * <p>工具失败不影响学生 SSE 主链路，但记录结构化日志和审计状态。</p>
 */
@Service
public class ToolOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ToolOrchestrationService.class);

    private final ExcelReportWriter excelReportWriter;
    private final AlertNotifier alertNotifier;
    private final PsychologicalReportRepository reportRepository;
    private final AlertRecordRepository alertRecordRepository;
    private final ToolAuditRecordRepository toolAuditRecordRepository;
    private final ToolPolicyRegistry policyRegistry;
    private final MindBridgeProperties properties;
    private final TaskExecutor mcpTaskExecutor;
    private final TransactionTemplate transactionTemplate;

    public ToolOrchestrationService(
            ExcelReportWriter excelReportWriter,
            AlertNotifier alertNotifier,
            PsychologicalReportRepository reportRepository,
            AlertRecordRepository alertRecordRepository,
            ToolAuditRecordRepository toolAuditRecordRepository,
            ToolPolicyRegistry policyRegistry,
            MindBridgeProperties properties,
            @Qualifier("mcpTaskExecutor")
            TaskExecutor mcpTaskExecutor,
            TransactionTemplate transactionTemplate
    ) {
        this.excelReportWriter = excelReportWriter;
        this.alertNotifier = alertNotifier;
        this.reportRepository = reportRepository;
        this.alertRecordRepository = alertRecordRepository;
        this.toolAuditRecordRepository = toolAuditRecordRepository;
        this.policyRegistry = policyRegistry;
        this.properties = properties;
        this.mcpTaskExecutor = mcpTaskExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    public void handleAsync(Long reportId) {
        mcpTaskExecutor.execute(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> handleInTransaction(reportId));
            } catch (Exception e) {
                log.error("[tool-orchestration] reportId={}, error={}", reportId, e.getMessage(), e);
            }
        });
    }

    @Transactional
    public void handle(Long reportId) {
        handleInTransaction(reportId);
    }

    private void handleInTransaction(Long reportId) {
        PsychologicalReport managedReport = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));
        RiskLevel riskLevel = managedReport.getRiskLevel();

        // 工具 1: Excel 报告写入（授权检查）
        AuthorizationResult excelAuth = policyRegistry.authorize(
                ToolPolicyRegistry.TOOL_EXCEL_REPORT, riskLevel);
        if (excelAuth.allowed()) {
            writeExcel(managedReport, reportId);
        } else {
            recordAudit(reportId, ToolPolicyRegistry.TOOL_EXCEL_REPORT, riskLevel,
                    excelAuth, ToolStatus.NOT_EXECUTED, excelAuth.reason());
            managedReport.setExcelStatus(ToolStatus.SKIPPED);
            log.info("[tool-orchestration] Excel report skipped: tool={}, risk={}, decision={}",
                    ToolPolicyRegistry.TOOL_EXCEL_REPORT, riskLevel, excelAuth.decision());
        }

        // 工具 2: 高风险邮件预警（授权检查）
        if (managedReport.getExcelStatus() == ToolStatus.SUCCESS
                && riskLevel == RiskLevel.HIGH) {
            AuthorizationResult emailAuth = policyRegistry.authorize(
                    ToolPolicyRegistry.TOOL_RISK_ALERT_EMAIL, riskLevel);
            if (emailAuth.allowed()) {
                sendAlerts(managedReport, reportId);
            } else {
                recordAudit(reportId, ToolPolicyRegistry.TOOL_RISK_ALERT_EMAIL, riskLevel,
                        emailAuth, ToolStatus.NOT_EXECUTED, emailAuth.reason());
                managedReport.setEmailStatus(ToolStatus.SKIPPED);
                log.info("[tool-orchestration] Risk alert skipped: tool={}, risk={}, decision={}",
                        ToolPolicyRegistry.TOOL_RISK_ALERT_EMAIL, riskLevel, emailAuth.decision());
            }
        }

        reportRepository.save(managedReport);
    }

    private void writeExcel(PsychologicalReport report, Long reportId) {
        try {
            excelReportWriter.write(report);
            report.setExcelStatus(ToolStatus.SUCCESS);
            recordAudit(reportId, ToolPolicyRegistry.TOOL_EXCEL_REPORT, report.getRiskLevel(),
                    AuthorizationResult.Decision.ALLOWED, ToolStatus.SUCCESS, "Excel report written");
        } catch (Exception e) {
            report.setExcelStatus(ToolStatus.FAILED);
            report.setToolError(shorten(e.getMessage()));
            recordAudit(reportId, ToolPolicyRegistry.TOOL_EXCEL_REPORT, report.getRiskLevel(),
                    AuthorizationResult.Decision.ALLOWED, ToolStatus.FAILED, shorten(e.getMessage()));
            log.error("[tool-orchestration] Excel write failed: reportId={}, error={}",
                    reportId, e.getMessage(), e);
        }
    }

    private void sendAlerts(PsychologicalReport report, Long reportId) {
        boolean allSuccess = true;
        for (String recipient : properties.getMcp().getEmail().getRecipients()) {
            AlertRecord alertRecord = new AlertRecord();
            alertRecord.setReport(report);
            alertRecord.setRecipient(recipient);
            alertRecordRepository.save(alertRecord);

            boolean sent = false;
            int maxAttempts = Math.max(1, properties.getMcp().getEmail().getMaxRetries() + 1);
            for (int attempt = 0; attempt < maxAttempts && !sent; attempt++) {
                try {
                    alertRecord.incrementAttempts();
                    alertNotifier.notify(alertRecord, report);
                    alertRecord.setStatus(ToolStatus.SUCCESS);
                    sent = true;
                } catch (Exception e) {
                    alertRecord.setStatus(ToolStatus.FAILED);
                    alertRecord.setErrorMessage(shorten(e.getMessage()));
                    log.error("[tool-orchestration] Alert send failed: reportId={}, recipient={}, attempt={}, error={}",
                            reportId, recipient, attempt + 1, e.getMessage(), e);
                }
            }
            alertRecordRepository.save(alertRecord);
            allSuccess = allSuccess && sent;
        }
        report.setEmailStatus(allSuccess ? ToolStatus.SUCCESS : ToolStatus.FAILED);
        recordAudit(reportId, ToolPolicyRegistry.TOOL_RISK_ALERT_EMAIL, report.getRiskLevel(),
                AuthorizationResult.Decision.ALLOWED,
                allSuccess ? ToolStatus.SUCCESS : ToolStatus.FAILED,
                "Alerts sent to " + properties.getMcp().getEmail().getRecipients().size() + " recipients");
    }

    /**
     * 记录工具审计。
     *
     * <p>不含完整学生消息、模型 Prompt、密钥或邮件正文。
     * summary 仅包含工具名称、风险等级和简要结果。</p>
     */
    private void recordAudit(Long reportId, String toolName, RiskLevel riskLevel,
                              AuthorizationResult authResult, ToolStatus result, String summary) {
        recordAudit(reportId, toolName, riskLevel, authResult.decision(), result, summary);
    }

    private void recordAudit(Long reportId, String toolName, RiskLevel riskLevel,
                              AuthorizationResult.Decision decision, ToolStatus result, String summary) {
        try {
            ToolAuditRecord audit = new ToolAuditRecord();
            audit.setReportId(reportId);
            audit.setToolName(toolName);
            audit.setRiskLevel(riskLevel);
            audit.setDecision(AuthorizationDecision.valueOf(decision.name()));
            audit.setResult(result);
            audit.setSummary(shorten(summary));
            toolAuditRecordRepository.save(audit);
        } catch (Exception e) {
            log.error("[tool-orchestration] Failed to save audit record: tool={}, error={}",
                    toolName, e.getMessage(), e);
        }
    }

    private String shorten(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}