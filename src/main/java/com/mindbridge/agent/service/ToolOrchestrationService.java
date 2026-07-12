package com.mindbridge.agent.service;

import com.mindbridge.agent.domain.PsychologicalReport;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.repository.PsychologicalReportRepository;
import com.mindbridge.agent.service.tool.ToolQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台工具编排服务。
 *
 * <p>批次 10 后，handleAsync 只负责按幂等键入队工具作业：
 * 先创建 Excel job，HIGH 风险再创建依赖 Excel 成功的预警 job。
 * 实际执行由 {@link com.mindbridge.agent.service.tool.ToolQueueWorker} 轮询处理。</p>
 *
 * <p>保留 {@link #handle(Long)} 作为测试/兼容入口，内部复用入队逻辑。</p>
 *
 * <p>工具失败不影响学生 SSE 主链路，但记录结构化日志和审计状态。</p>
 */
@Service
public class ToolOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ToolOrchestrationService.class);

    private final ToolQueueService queueService;
    private final PsychologicalReportRepository reportRepository;
    private final TaskExecutor mcpTaskExecutor;

    public ToolOrchestrationService(
            ToolQueueService queueService,
            PsychologicalReportRepository reportRepository,
            @Qualifier("mcpTaskExecutor")
            TaskExecutor mcpTaskExecutor
    ) {
        this.queueService = queueService;
        this.reportRepository = reportRepository;
        this.mcpTaskExecutor = mcpTaskExecutor;
    }

    /**
     * 异步入队：将报告的工具作业加入持久化队列。
     * 幂等：重复调用不会创建重复作业。
     */
    public void handleAsync(Long reportId) {
        mcpTaskExecutor.execute(() -> {
            try {
                handle(reportId);
            } catch (Exception e) {
                log.error("[tool-orchestration] enqueue failed: reportId={}, error={}",
                        reportId, e.getMessage(), e);
            }
        });
    }

    /**
     * 同步入队（测试/兼容入口）。
     */
    @Transactional
    public void handle(Long reportId) {
        PsychologicalReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));
        RiskLevel riskLevel = report.getRiskLevel();
        queueService.enqueue(reportId, riskLevel);
    }
}