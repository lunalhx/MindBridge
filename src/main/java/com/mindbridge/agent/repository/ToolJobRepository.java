package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ToolJob;
import com.mindbridge.agent.domain.ToolJob.JobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 工具队列作业的数据访问接口。
 */
public interface ToolJobRepository extends JpaRepository<ToolJob, Long> {

    /** 按幂等键查找（防重复入队） */
    Optional<ToolJob> findByIdempotencyKey(String idempotencyKey);

    /** 查询可执行的作业：状态为 PENDING 或 RETRY，且下次尝试时间已到 */
    List<ToolJob> findByStatusInAndNextAttemptAtBeforeOrderByCreatedAtAsc(
            List<JobStatus> statuses, Instant cutoff);

    /** 按 reportId 查询所有关联作业 */
    List<ToolJob> findByReportIdOrderByCreatedAtAsc(Long reportId);

    /** 按状态查询作业（用于死信查询等） */
    List<ToolJob> findByStatusOrderByUpdatedAtDesc(JobStatus status);

    /** 按 reportId 和 type 查询（用于依赖检查） */
    Optional<ToolJob> findByReportIdAndType(Long reportId, String type);
}