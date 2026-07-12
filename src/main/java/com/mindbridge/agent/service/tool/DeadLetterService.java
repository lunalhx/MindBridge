package com.mindbridge.agent.service.tool;

import com.mindbridge.agent.domain.ToolJob;
import com.mindbridge.agent.domain.ToolJob.JobStatus;
import com.mindbridge.agent.repository.ToolJobRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 死信服务：记录和查询进入死信状态的作业。
 */
@Service
public class DeadLetterService {

    private final ToolJobRepository jobRepository;

    public DeadLetterService(ToolJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * 查询所有死信作业。
     */
    public List<ToolJob> deadLetterJobs() {
        return jobRepository.findByStatusOrderByUpdatedAtDesc(JobStatus.DEAD_LETTER);
    }

    /**
     * 查询指定报告的死信作业。
     */
    public List<ToolJob> deadLetterJobsByReportId(Long reportId) {
        return jobRepository.findByReportIdOrderByCreatedAtAsc(reportId).stream()
                .filter(job -> job.getStatus() == JobStatus.DEAD_LETTER)
                .toList();
    }

    /**
     * 查询被阻塞的作业（依赖未满足）。
     */
    public List<ToolJob> blockedJobs() {
        return jobRepository.findByStatusOrderByUpdatedAtDesc(JobStatus.BLOCKED);
    }

    /**
     * 查询等待人工审核的作业。
     */
    public List<ToolJob> reviewRequiredJobs() {
        return jobRepository.findByStatusOrderByUpdatedAtDesc(JobStatus.REVIEW_REQUIRED);
    }
}