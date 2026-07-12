package com.mindbridge.agent.service.agent.checkpoint;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 内存实现的 {@link CheckpointStore}，用于测试。
 *
 * <p>不依赖真实 Redis，提供简单的 key-value 存储和可选的失败模拟。</p>
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<String, String> store = new HashMap<>();
    private final Map<String, Long> expiry = new HashMap<>();
    private RuntimeException failure;
    private boolean respectExpiry = true;

    @Override
    public synchronized void save(String key, String value, Duration ttl) {
        if (failure != null) throw failure;
        store.put(key, value);
        if (respectExpiry && ttl != null) {
            expiry.put(key, System.currentTimeMillis() + ttl.toMillis());
        }
    }

    @Override
    public synchronized String load(String key) {
        if (failure != null) throw failure;
        if (respectExpiry && expiry.containsKey(key)) {
            if (System.currentTimeMillis() > expiry.get(key)) {
                store.remove(key);
                expiry.remove(key);
                return null;
            }
        }
        return store.get(key);
    }

    @Override
    public synchronized void delete(String key) {
        if (failure != null) throw failure;
        store.remove(key);
        expiry.remove(key);
    }

    /** 模拟 Redis 不可用。 */
    public void setFailure(RuntimeRuntimeException failure) {
        this.failure = failure == null ? null : failure.inner;
    }

    /** 清空所有数据。 */
    public synchronized void clear() {
        store.clear();
        expiry.clear();
    }

    /** 查看当前存储的 key（用于测试断言）。 */
    public synchronized java.util.Set<String> keys() {
        return new java.util.HashSet<>(store.keySet());
    }

    /** 禁用过期检查（用于测试不依赖时间的场景）。 */
    public void disableExpiry() {
        this.respectExpiry = false;
    }

    /** 手动让某个 key 过期（用于测试 TTL 场景）。 */
    public synchronized void expireKey(String key) {
        store.remove(key);
        expiry.remove(key);
    }

    /** 包装类，方便构造失败异常。 */
    public static class RuntimeRuntimeException {
        final RuntimeException inner;
        public RuntimeRuntimeException(String message) {
            this.inner = new RuntimeException(message);
        }
    }
}
