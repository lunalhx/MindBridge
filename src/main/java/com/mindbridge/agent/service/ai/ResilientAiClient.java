package com.mindbridge.agent.service.ai;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * LLM 调用弹性包装器：重试 + 断路器。
 *
 * <p>包装底层 {@link AiClient}，在 complete() 和 stream() 之上增加：</p>
 * <ul>
 *   <li>指数退避重试：仅对超时、连接错误、限流等可恢复错误重试</li>
 *   <li>断路器：连续失败达阈值后打开（快速失败），经过 openDuration 进入半开探测</li>
 *   <li>流式安全：一旦已向用户发送 token，不再重试（避免重复文本）</li>
 * </ul>
 *
 * <p>状态线程安全：使用 AtomicInteger/AtomicReference 管理断路器状态。
 * 不使用外部依赖，自实现轻量级断路器。</p>
 */
public class ResilientAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientAiClient.class);

    private final AiClient delegate;
    private final boolean enabled;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final int failureThreshold;
    private final long openDurationMs;

    /** 断路器状态 */
    private final AtomicReference<CircuitState> circuitState = new AtomicReference<>(CircuitState.CLOSED);
    /** 连续失败计数 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    /** 断路器打开时间戳 */
    private volatile long openedAtMs = 0;

    /** backoff 提供器，用于测试注入可控延迟 */
    private final BackoffProvider backoffProvider;

    public ResilientAiClient(AiClient delegate, ResilienceConfig config) {
        this(delegate, config, new ThreadSleepBackoff());
    }

    public ResilientAiClient(AiClient delegate, ResilienceConfig config, BackoffProvider backoffProvider) {
        this.delegate = delegate;
        this.enabled = config.enabled();
        this.maxAttempts = config.maxAttempts();
        this.initialBackoffMs = config.initialBackoffMs();
        this.maxBackoffMs = config.maxBackoffMs();
        this.failureThreshold = config.failureThreshold();
        this.openDurationMs = config.openDurationMs();
        this.backoffProvider = backoffProvider;
    }

    // ────────────── complete ──────────────

    @Override
    public String complete(List<AiMessage> messages) {
        if (!enabled) {
            return delegate.complete(messages);
        }

        // 断路器检查
        CircuitState state = checkCircuit();
        if (state == CircuitState.OPEN) {
            log.warn("[ai-resilience] Circuit breaker OPEN, fast-failing complete()");
            throw new CircuitBreakerOpenException("Circuit breaker is open");
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String result = delegate.complete(messages);
                onSuccess();
                return result;
            } catch (Exception e) {
                lastError = e;
                if (!isRetryable(e)) {
                    log.debug("[ai-resilience] Non-retryable error: {}", e.getClass().getSimpleName());
                    onFailure();
                    throw e;
                }
                if (attempt < maxAttempts) {
                    long delay = calculateBackoff(attempt);
                    log.warn("[ai-resilience] complete() attempt {}/{} failed, retrying in {}ms: {}",
                            attempt, maxAttempts, delay, e.getClass().getSimpleName());
                    backoffProvider.sleep(delay);
                }
            }
        }
        onFailure();
        log.error("[ai-resilience] complete() exhausted {} attempts", maxAttempts);
        throw new RuntimeException("LLM call failed after " + maxAttempts + " attempts", lastError);
    }

    // ────────────── stream ──────────────

    @Override
    public Flux<String> stream(List<AiMessage> messages) {
        if (!enabled) {
            return delegate.stream(messages);
        }

        // 断路器检查
        CircuitState state = checkCircuit();
        if (state == CircuitState.OPEN) {
            log.warn("[ai-resilience] Circuit breaker OPEN, fast-failing stream()");
            return Flux.error(new CircuitBreakerOpenException("Circuit breaker is open"));
        }

        // 流式安全：仅在第一个 token 发出前重试
        return attemptStream(messages, 1);
    }

    private Flux<String> attemptStream(List<AiMessage> messages, int attempt) {
        AtomicInteger emittedTokens = new AtomicInteger(0);

        return delegate.stream(messages)
                .doOnNext(token -> emittedTokens.incrementAndGet())
                .onErrorResume(e -> {
                    Exception ex = e instanceof Exception ee ? ee : new RuntimeException(e);
                    if (!isRetryable(ex)) {
                        log.debug("[ai-resilience] Stream non-retryable: {}", ex.getClass().getSimpleName());
                        onFailure();
                        return Flux.error(ex);
                    }
                    if (emittedTokens.get() > 0) {
                        // 已发送 token，不能再重试（会导致重复文本）
                        log.warn("[ai-resilience] Stream failed after {} tokens, cannot retry", emittedTokens.get());
                        onFailure();
                        return Flux.error(ex);
                    }
                    if (attempt >= maxAttempts) {
                        log.error("[ai-resilience] Stream exhausted {} attempts", maxAttempts);
                        onFailure();
                        return Flux.error(ex);
                    }
                    long delay = calculateBackoff(attempt);
                    log.warn("[ai-resilience] Stream attempt {}/{} failed (0 tokens), retrying in {}ms: {}",
                            attempt, maxAttempts, delay, ex.getClass().getSimpleName());
                    backoffProvider.sleep(delay);
                    return attemptStream(messages, attempt + 1);
                })
                .doOnComplete(this::onSuccess)
                .doOnCancel(this::onSuccess);
    }

    // ────────────── 断路器 ──────────────

    private CircuitState checkCircuit() {
        CircuitState state = circuitState.get();
        if (state == CircuitState.OPEN) {
            long elapsed = System.currentTimeMillis() - openedAtMs;
            if (elapsed >= openDurationMs) {
                // 进入半开
                if (circuitState.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    log.info("[ai-resilience] Circuit breaker transitioning OPEN -> HALF_OPEN");
                }
                return circuitState.get();
            }
            return CircuitState.OPEN;
        }
        return state;
    }

    private void onSuccess() {
        if (circuitState.get() != CircuitState.CLOSED) {
            log.info("[ai-resilience] Circuit breaker closing (HALF_OPEN -> CLOSED)");
        }
        circuitState.set(CircuitState.CLOSED);
        consecutiveFailures.set(0);
    }

    private void onFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            if (circuitState.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
                openedAtMs = System.currentTimeMillis();
                log.warn("[ai-resilience] Circuit breaker OPEN after {} consecutive failures", failures);
            } else if (circuitState.get() == CircuitState.HALF_OPEN) {
                circuitState.set(CircuitState.OPEN);
                openedAtMs = System.currentTimeMillis();
                log.warn("[ai-resilience] Circuit breaker re-OPEN from HALF_OPEN");
            }
        }
    }

    // ────────────── 退避 ──────────────

    long calculateBackoff(int attempt) {
        long delay = (long) (initialBackoffMs * Math.pow(2, attempt - 1));
        return Math.min(delay, maxBackoffMs);
    }

    // ────────────── 错误分类 ──────────────

    /**
     * 判断错误是否可重试。
     *
     * <p>可重试：超时、连接错误、限流（429）、服务端错误（500+）。
     * 不可重试：认证错误（401/403）、无效请求（400）、其他客户端错误。</p>
     */
    static boolean isRetryable(Exception e) {
        String name = e.getClass().getSimpleName();
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // 超时类
        if (name.contains("Timeout") || name.contains("timeout")) return true;
        // 连接类
        if (name.contains("ConnectException") || name.contains("Connection")) return true;
        if (name.contains("IOException")) return true;
        // 限流
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("too many")) return true;
        // 服务端错误
        if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")) return true;
        if (msg.contains("internal server error") || msg.contains("service unavailable")) return true;
        // Reactor 可恢复
        if (name.contains("RetryException")) return true;

        // 认证、无效请求等永久错误
        if (msg.contains("401") || msg.contains("403") || msg.contains("unauthorized")
                || msg.contains("forbidden") || msg.contains("api key")) return false;
        if (msg.contains("400") || msg.contains("bad request") || msg.contains("invalid")) return false;
        if (name.contains("IllegalArgumentException")) return false;

        // 默认：未知错误视为可重试（宁可多试一次也不要静默失败）
        return true;
    }

    // ────────────── 状态查询（测试用） ──────────────

    public CircuitState circuitState() { return circuitState.get(); }
    public int consecutiveFailures() { return consecutiveFailures.get(); }

    // ────────────── 内部类型 ──────────────

    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }

    /** 断路器打开异常 */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) { super(message); }
    }

    /** Backoff 提供器接口，用于测试注入可控延迟 */
    public interface BackoffProvider {
        void sleep(long millis);
    }

    /** 默认实现：Thread.sleep */
    static class ThreadSleepBackoff implements BackoffProvider {
        @Override public void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 弹性配置（不可变 record） */
    public record ResilienceConfig(
            boolean enabled,
            int maxAttempts,
            long initialBackoffMs,
            long maxBackoffMs,
            int failureThreshold,
            long openDurationMs
    ) {
        public static ResilienceConfig defaults() {
            return new ResilienceConfig(true, 3, 1000, 30000, 5, 60000);
        }

        public static ResilienceConfig disabled() {
            return new ResilienceConfig(false, 1, 0, 0, Integer.MAX_VALUE, 0);
        }

        public static ResilienceConfig fromProperties(MindBridgePropertiesAiResilience res) {
            return new ResilienceConfig(
                    res.isEnabled(),
                    Math.max(1, res.getMaxAttempts()),
                    res.getInitialBackoffMs(),
                    res.getMaxBackoffMs(),
                    Math.max(1, res.getFailureThreshold()),
                    res.getOpenDurationMs()
            );
        }
    }

    /** MindBridgeProperties.Ai.Resilience 的镜像接口（避免循环依赖） */
    public interface MindBridgePropertiesAiResilience {
        boolean isEnabled();
        int getMaxAttempts();
        long getInitialBackoffMs();
        long getMaxBackoffMs();
        int getFailureThreshold();
        long getOpenDurationMs();
    }
}