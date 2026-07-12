package com.mindbridge.agent.service.agent.checkpoint;

import java.time.Duration;

/**
 * Checkpoint 存储抽象。
 *
 * <p>将 Redis 读写与 {@link CheckpointService} 解耦，便于测试使用内存实现替代真实 Redis。</p>
 *
 * <p>所有方法都应安全处理异常：Redis 不可用时不应抛出，由调用方降级处理。</p>
 */
public interface CheckpointStore {

    /** 保存字符串值并设置 TTL。 */
    void save(String key, String value, Duration ttl);

    /** 读取字符串值，不存在或失败时返回 null。 */
    String load(String key);

    /** 删除 key，不存在时静默忽略。 */
    void delete(String key);
}
