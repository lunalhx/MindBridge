package com.mindbridge.agent.service.agent.checkpoint;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 实现的 {@link CheckpointStore}。
 *
 * <p>使用 {@link StringRedisTemplate} 存储序列化后的 JSON checkpoint。
 * Redis 不可用时安全降级：save/delete 静默跳过，load 返回 null。</p>
 */
@Component
public class RedisCheckpointStore implements CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCheckpointStore.class);

    private final StringRedisTemplate redisTemplate;

    public RedisCheckpointStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.debug("[checkpoint-store] save skipped: key={}, error={}", key, e.getMessage());
        }
    }

    @Override
    public String load(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.debug("[checkpoint-store] load skipped: key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.debug("[checkpoint-store] delete skipped: key={}, error={}", key, e.getMessage());
        }
    }
}
