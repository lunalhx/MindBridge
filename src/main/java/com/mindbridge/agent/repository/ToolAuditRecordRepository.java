package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ToolAuditRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 工具调用审计记录的数据访问接口。
 */
public interface ToolAuditRecordRepository extends JpaRepository<ToolAuditRecord, Long> {

    /** 按报告 ID 查询关联的审计记录。 */
    List<ToolAuditRecord> findByReportIdOrderByCreatedAtAsc(Long reportId);

    /** 管理员后台最近 100 条审计记录。 */
    List<ToolAuditRecord> findTop100ByOrderByCreatedAtDesc();
}