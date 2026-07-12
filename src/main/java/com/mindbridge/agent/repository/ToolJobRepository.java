package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ToolJob;
import com.mindbridge.agent.domain.ToolJob.JobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 工具队列作业的数据访问接口。
 */
public interface ToolJobRepository extends JpaRepository<ToolJob, Long> {

    /** 按幂等键查找（防重复入队） */
    Optional<ToolJob> findByIdempotencyKey(String idempotencyKey);

    /** 按 reportId 查询所有关联作业 */
    List<ToolJob> findByReportIdOrderByCreatedAtAsc(Long reportId);

    /** 按状态查询作业（用于死信查询等） */
    List<ToolJob> findByStatusOrderByUpdatedAtDesc(JobStatus status);

    /** 按 reportId 和 type 查询（用于依赖检查） */
    Optional<ToolJob> findByReportIdAndType(Long reportId, String type);

    /**
     * 包含 BLOCKED 和 REVIEW_REQUIRED 的轮询查询（修复 BLOCKED 永不轮询的 bug）。
     */
    @Query("SELECT j FROM ToolJob j WHERE j.status IN (:statuses) AND j.nextAttemptAt <= :now ORDER BY j.createdAt ASC")
    List<ToolJob> findReadyJobs(@Param("statuses") List<JobStatus> statuses, @Param("now") Instant now);

    /**
     * 原子领取：仅当状态为 PENDING/RETRY/BLOCKED/REVIEW_REQUIRED 且到期时更新。
     * 使用原生 SQL 确保 H2 和 MySQL 兼容。
     * 返回受影响行数（1=领取成功，0=已被其他 Worker 领取或状态已变更）。
     */
    @Modifying
    @Query(value = "UPDATE tool_jobs SET status = 'RUNNING', worker_id = :workerId, " +
            "claimed_at = :claimedAt, lease_until = :leaseUntil, attempts = attempts + 1, " +
            "updated_at = :claimedAt WHERE id = :id AND status IN ('PENDING', 'RETRY', 'BLOCKED', 'REVIEW_REQUIRED') " +
            "AND next_attempt_at <= :now", nativeQuery = true)
    int claimJob(@Param("id") Long id, @Param("workerId") String workerId,
                 @Param("claimedAt") Instant claimedAt, @Param("leaseUntil") Instant leaseUntil,
                 @Param("now") Instant now);

    /**
     * 查找过期 Lease（RUNNING 但 leaseUntil 已过）。
     */
    @Query("SELECT j FROM ToolJob j WHERE j.status = 'RUNNING' AND j.leaseUntil <= :now")
    List<ToolJob> findExpiredLeases(@Param("now") Instant now);

    /**
     * 审批：将 REVIEW_REQUIRED Job 更新为 PENDING（审批通过）或 DEAD_LETTER（审批拒绝）。
     */
    @Modifying
    @Query("UPDATE ToolJob j SET j.status = :newStatus, j.reviewDecision = :reviewDecision, j.reviewer = :reviewer, j.updatedAt = :now WHERE j.id = :id AND j.status = 'REVIEW_REQUIRED'")
    int reviewJob(@Param("id") Long id, @Param("newStatus") JobStatus newStatus,
                  @Param("reviewDecision") String reviewDecision, @Param("reviewer") String reviewer,
                  @Param("now") Instant now);
}