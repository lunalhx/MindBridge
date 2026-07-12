package com.mindbridge.agent.service.tool;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ToolJob;
import com.mindbridge.agent.domain.ToolJob.JobStatus;
import com.mindbridge.agent.repository.ToolJobRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具队列 Worker：轮询待执行作业并执行。
 *
 * <p>使用 @Scheduled 定期轮询 PENDING/RETRY/BLOCKED/REVIEW_REQUIRED 状态且 nextAttemptAt 已到的作业。
 * 通过原子 UPDATE（claimJob）防止并发重复领取，并使用 lease 机制恢复崩溃的 Worker。
 * 指数退避：失败后按 initialBackoff × multiplier^attempts 计算下次尝试时间。
 * 达到 maxAttempts 后进入 DEAD_LETTER。
 * 依赖未满足（上游 BLOCKED/DEAD_LETTER/RETRY）时设为 BLOCKED。
 * 授权返回 REVIEW_REQUIRED 时进入人工审核状态，等待 approve/reject。</p>
 *
 * <p>注意：实际的 Job 处理（含外部 I/O 调用）委托给独立的 {@link ToolJobProcessor} Bean，
 * 以避免 Spring 代理自调用导致事务边界失效。{@link #poll()} 中的
 * {@code toolJobProcessor.processClaimedJob(...)} 调用经过 Spring 代理，
 * 确保外部工具（Excel/邮件）在数据库事务之外执行。</p>
 */
@Service
public class ToolQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(ToolQueueWorker.class);

    private final ToolJobRepository jobRepository;
    private final MindBridgeProperties properties;
    private final ToolJobProcessor toolJobProcessor;

    private final String workerId = UUID.randomUUID().toString().substring(0, 8);

    public ToolQueueWorker(
            ToolJobRepository jobRepository,
            MindBridgeProperties properties,
            ToolJobProcessor toolJobProcessor
    ) {
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.toolJobProcessor = toolJobProcessor;
    }

    /**
     * 定期轮询并处理待执行作业。
     * 测试中可直接调用此方法，不依赖定时触发。
     */
    @Scheduled(fixedDelayString = "${mindbridge.tool-queue.poll-interval-ms:5000}")
    @Transactional
    public void poll() {
        Instant now = Instant.now();
        // 1. Handle expired leases (RUNNING jobs with expired lease)
        List<ToolJob> expiredLeases = jobRepository.findExpiredLeases(now);
        for (ToolJob expired : expiredLeases) {
            expired.setStatus(JobStatus.RETRY);
            expired.setLeaseUntil(null);
            expired.setClaimedAt(null);
            expired.setWorkerId(null);
            expired.setNextAttemptAt(now.plusSeconds(10));
            jobRepository.save(expired);
            log.warn("[tool-queue] Lease expired for job={}, type={}, reset to RETRY",
                    expired.getId(), expired.getType());
        }

        // 2. Find candidate jobs (IDs only for atomic claim)
        List<ToolJob> candidates = jobRepository.findReadyJobs(
                List.of(JobStatus.PENDING, JobStatus.RETRY, JobStatus.BLOCKED, JobStatus.REVIEW_REQUIRED), now);

        int processed = 0;
        int batchSize = properties.getToolQueue().getBatchSize();
        for (ToolJob candidate : candidates) {
            if (processed >= batchSize) break;

            // 3. Atomic claim — only one worker can claim each job
            Instant leaseUntil = now.plusSeconds(properties.getToolQueue().getLeaseSeconds());
            int claimed = jobRepository.claimJob(candidate.getId(), workerId, now, leaseUntil, now);
            if (claimed == 0) {
                continue; // Another worker already claimed this job
            }

            // 4. Re-read after claim to get latest state
            ToolJob claimedJob = jobRepository.findById(candidate.getId()).orElse(null);
            if (claimedJob == null || claimedJob.getStatus() != JobStatus.RUNNING) {
                continue;
            }

            processed++;

            // 5. Process outside the poll transaction — delegate to ToolJobProcessor (goes through Spring proxy)
            toolJobProcessor.processClaimedJob(claimedJob);
        }
    }

    // ────────────── 退避计算 ──────────────

    /**
     * 计算退避时间（返回 Instant），供测试使用。
     */
    Instant calculateBackoff(int attempt) {
        long base = properties.getToolQueue().getInitialBackoffSeconds();
        double multiplier = properties.getToolQueue().getBackoffMultiplier();
        long delaySeconds = (long) (base * Math.pow(multiplier, attempt));
        return Instant.now().plus(delaySeconds, ChronoUnit.SECONDS);
    }
}