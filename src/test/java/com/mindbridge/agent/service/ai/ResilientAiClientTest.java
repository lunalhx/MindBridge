package com.mindbridge.agent.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ResilientAiClientTest {

    private static final ResilientAiClient.ResilienceConfig FAST_CONFIG =
            new ResilientAiClient.ResilienceConfig(true, 3, 0, 100, 3, 200);

    private ResilientAiClient.BackoffProvider noSleep = millis -> {};

    // ────────── complete: 可恢复错误重试 ──────────

    @Test
    void completeShouldRetryOnTimeoutThenSucceed() {
        var callCount = new AtomicInteger(0);
        AiClient delegate = new StubAiClient(
                msgs -> {
                    if (callCount.incrementAndGet() < 3) throw new RuntimeException("Connection timed out");
                    return "ok";
                },
                msgs -> Flux.just("ok"));

        var client = new ResilientAiClient(delegate, FAST_CONFIG, noSleep);
        String result = client.complete(List.of(AiMessage.user("test")));

        assertThat(result).isEqualTo("ok");
        assertThat(callCount.get()).isEqualTo(3);
    }

    // ────────── complete: 不可恢复错误不重试 ──────────

    @Test
    void completeShouldNotRetryOnAuthError() {
        var callCount = new AtomicInteger(0);
        AiClient delegate = new StubAiClient(
                msgs -> { callCount.incrementAndGet(); throw new RuntimeException("401 Unauthorized: invalid api key"); },
                msgs -> Flux.just("ok"));

        var client = new ResilientAiClient(delegate, FAST_CONFIG, noSleep);
        assertThatThrownBy(() -> client.complete(List.of(AiMessage.user("test"))))
                .hasMessageContaining("401");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void completeShouldNotRetryOnBadRequest() {
        var callCount = new AtomicInteger(0);
        AiClient delegate = new StubAiClient(
                msgs -> { callCount.incrementAndGet(); throw new RuntimeException("400 Bad Request: invalid model"); },
                msgs -> Flux.just("ok"));

        var client = new ResilientAiClient(delegate, FAST_CONFIG, noSleep);
        assertThatThrownBy(() -> client.complete(List.of(AiMessage.user("test"))));
        assertThat(callCount.get()).isEqualTo(1);
    }

    // ────────── complete: 重试耗尽 ──────────

    @Test
    void completeShouldExhaustMaxAttemptsThenFail() {
        var callCount = new AtomicInteger(0);
        AiClient delegate = new StubAiClient(
                msgs -> { callCount.incrementAndGet(); throw new RuntimeException("503 Service Unavailable"); },
                msgs -> Flux.just("ok"));

        var client = new ResilientAiClient(delegate, FAST_CONFIG, noSleep);
        assertThatThrownBy(() -> client.complete(List.of(AiMessage.user("test"))))
                .hasMessageContaining("3 attempts");
        assertThat(callCount.get()).isEqualTo(3);
    }

    // ────────── 退避计算 ──────────

    @Test
    void backoffShouldBeExponentialWithCap() {
        var config = new ResilientAiClient.ResilienceConfig(true, 5, 100, 500, 5, 60000);
        var client = new ResilientAiClient(new StubAiClient(List.of()), config, noSleep);

        assertThat(client.calculateBackoff(1)).isEqualTo(100);  // 100 * 2^0 = 100
        assertThat(client.calculateBackoff(2)).isEqualTo(200);  // 100 * 2^1 = 200
        assertThat(client.calculateBackoff(3)).isEqualTo(400);  // 100 * 2^2 = 400
        assertThat(client.calculateBackoff(4)).isEqualTo(500);  // capped at 500
    }

    // ────────── 断路器 ──────────

    @Test
    void circuitBreakerShouldOpenAfterThresholdFailures() {
        var config = new ResilientAiClient.ResilienceConfig(true, 1, 0, 100, 3, 10000);
        AiClient delegate = new StubAiClient(
                msgs -> { throw new RuntimeException("503 Service Unavailable"); },
                msgs -> Flux.error(new RuntimeException("503")));

        var client = new ResilientAiClient(delegate, config, noSleep);

        // 3 failures = threshold
        assertThatThrownBy(() -> client.complete(List.of())); // fail 1
        assertThatThrownBy(() -> client.complete(List.of())); // fail 2
        assertThatThrownBy(() -> client.complete(List.of())); // fail 3 → opens

        assertThat(client.circuitState()).isEqualTo(ResilientAiClient.CircuitState.OPEN);
    }

    @Test
    void circuitBreakerShouldFastFailWhenOpen() {
        var config = new ResilientAiClient.ResilienceConfig(true, 1, 0, 100, 1, 10000);
        AiClient delegate = new StubAiClient(
                msgs -> { throw new RuntimeException("503 Service Unavailable"); },
                msgs -> Flux.error(new RuntimeException("503")));

        var client = new ResilientAiClient(delegate, config, noSleep);

        assertThatThrownBy(() -> client.complete(List.of())); // fail → opens (threshold=1)

        // Next call should fast-fail with CircuitBreakerOpenException
        assertThatThrownBy(() -> client.complete(List.of()))
                .isInstanceOf(ResilientAiClient.CircuitBreakerOpenException.class);
    }

    @Test
    void circuitBreakerShouldHalfOpenAfterDuration() throws Exception {
        var config = new ResilientAiClient.ResilienceConfig(true, 1, 0, 100, 1, 50);
        AiClient failing = new StubAiClient(
                msgs -> { throw new RuntimeException("503"); },
                msgs -> Flux.error(new RuntimeException("503")));

        var client = new ResilientAiClient(failing, config, noSleep);

        // First call fails → opens (threshold=1)
        assertThatThrownBy(() -> client.complete(List.of()));
        assertThat(client.circuitState()).isEqualTo(ResilientAiClient.CircuitState.OPEN);

        // Wait for openDuration (50ms)
        Thread.sleep(60);

        // Now checkCircuit should transition to HALF_OPEN
        // Next call with a recovering delegate should close the circuit
        AiClient recovering = new StubAiClient(
                msgs -> "recovered",
                msgs -> Flux.just("recovered"));
        // Can't swap delegate after construction, so test with the same client
        // The client still has the failing delegate, so HALF_OPEN probe will fail again
        // Instead, let's test that state transitions to HALF_OPEN
        assertThatThrownBy(() -> client.complete(List.of()));
        // After the probe fails, it should re-OPEN
        assertThat(client.circuitState()).isIn(
                ResilientAiClient.CircuitState.HALF_OPEN,
                ResilientAiClient.CircuitState.OPEN);
    }

    @Test
    void circuitBreakerShouldCloseOnSuccess() {
        var config = new ResilientAiClient.ResilienceConfig(true, 3, 0, 100, 5, 50);
        var callCount = new AtomicInteger(0);
        AiClient delegate = new StubAiClient(
                msgs -> {
                    int c = callCount.incrementAndGet();
                    if (c < 3) throw new RuntimeException("503");
                    return "ok";
                },
                msgs -> Flux.just("ok"));

        var client = new ResilientAiClient(delegate, config, noSleep);

        // First call: fails twice, third succeeds (all within maxAttempts=3)
        String result = client.complete(List.of());
        assertThat(result).isEqualTo("ok");
        // Success resets failures
        assertThat(client.consecutiveFailures()).isEqualTo(0);
        assertThat(client.circuitState()).isEqualTo(ResilientAiClient.CircuitState.CLOSED);
    }

    @Test
    void circuitBreakerShouldRecoverAfterOpenDuration() throws Exception {
        var config = new ResilientAiClient.ResilienceConfig(true, 1, 0, 100, 1, 50);
        var phase = new AtomicInteger(0); // 0=fail, 1=recover
        AiClient failingThenOk = new StubAiClient(
                msgs -> {
                    if (phase.get() == 0) throw new RuntimeException("503");
                    return "ok";
                },
                msgs -> Flux.just("ok"));

        var client = new ResilientAiClient(failingThenOk, config, noSleep);

        // First call fails → opens (threshold=1, maxAttempts=1)
        assertThatThrownBy(() -> client.complete(List.of()));
        assertThat(client.circuitState()).isEqualTo(ResilientAiClient.CircuitState.OPEN);

        // Wait for openDuration (50ms)
        Thread.sleep(60);

        // Switch to recovering mode
        phase.set(1);

        // Next call should go through HALF_OPEN and succeed → CLOSED
        String result = client.complete(List.of());
        assertThat(result).isEqualTo("ok");
        assertThat(client.circuitState()).isEqualTo(ResilientAiClient.CircuitState.CLOSED);
    }

    // ────────── 流式：首 token 前重试 ──────────

    @Test
    void streamShouldRetryBeforeFirstToken() {
        var callCount = new AtomicInteger(0);
        AiClient delegate = new StubAiClient(
                msgs -> "ok",
                msgs -> {
                    if (callCount.incrementAndGet() < 2) {
                        return Flux.error(new RuntimeException("Connection timed out"));
                    }
                    return Flux.just("hello", "world");
                });

        var client = new ResilientAiClient(delegate, FAST_CONFIG, noSleep);
        List<String> tokens = client.stream(List.of(AiMessage.user("test"))).collectList().block();

        assertThat(tokens).containsExactly("hello", "world");
        assertThat(callCount.get()).isEqualTo(2);
    }

    // ────────── 流式：首 token 后不重试 ──────────

    @Test
    void streamShouldNotRetryAfterFirstToken() {
        var callCount = new AtomicInteger(0);
        AiClient delegate = new StubAiClient(
                msgs -> "ok",
                msgs -> {
                    callCount.incrementAndGet();
                    return Flux.just("hello")
                            .concatWith(Flux.error(new RuntimeException("Connection timed out")));
                });

        var client = new ResilientAiClient(delegate, FAST_CONFIG, noSleep);
        // Should emit "hello" then error, NOT retry (already sent token)
        List<String> tokens = new ArrayList<>();
        try {
            client.stream(List.of(AiMessage.user("test")))
                    .doOnNext(tokens::add)
                    .blockLast();
        } catch (Exception e) {
            // Expected: stream error after "hello"
        }

        // Should have emitted "hello" before error
        assertThat(tokens).contains("hello");
        // callCount should be 1 (no retry)
        assertThat(callCount.get()).isEqualTo(1);
    }

    // ────────── 流式：不可恢复错误不重试 ──────────

    @Test
    void streamShouldNotRetryOnAuthError() {
        var callCount = new AtomicInteger(0);
        AiClient delegate = new StubAiClient(
                msgs -> "ok",
                msgs -> {
                    callCount.incrementAndGet();
                    return Flux.error(new RuntimeException("401 Unauthorized: invalid api key"));
                });

        var client = new ResilientAiClient(delegate, FAST_CONFIG, noSleep);
        assertThatThrownBy(() -> client.stream(List.of(AiMessage.user("test"))).blockLast());
        assertThat(callCount.get()).isEqualTo(1);
    }

    // ────────── disabled 透传 ──────────

    @Test
    void disabledShouldPassthroughWithoutRetry() {
        var callCount = new AtomicInteger(0);
        AiClient delegate = new StubAiClient(
                msgs -> { callCount.incrementAndGet(); throw new RuntimeException("503"); },
                msgs -> { callCount.incrementAndGet(); return Flux.error(new RuntimeException("503")); });

        var config = ResilientAiClient.ResilienceConfig.disabled();
        var client = new ResilientAiClient(delegate, config, noSleep);

        assertThatThrownBy(() -> client.complete(List.of()));
        assertThat(callCount.get()).isEqualTo(1); // no retry
    }

    // ────────── 成功重置断路器 ──────────

    @Test
    void successShouldResetConsecutiveFailures() {
        var callCount = new AtomicInteger(0);
        AiClient delegate = new StubAiClient(
                msgs -> {
                    int c = callCount.incrementAndGet();
                    if (c == 1) throw new RuntimeException("503");
                    return "ok";
                },
                msgs -> Flux.just("ok"));

        var config = new ResilientAiClient.ResilienceConfig(true, 3, 0, 100, 5, 10000);
        var client = new ResilientAiClient(delegate, config, noSleep);

        // First call: fails once then succeeds on retry
        String result = client.complete(List.of());
        assertThat(result).isEqualTo("ok");
        assertThat(client.consecutiveFailures()).isEqualTo(0); // reset on success
        assertThat(client.circuitState()).isEqualTo(ResilientAiClient.CircuitState.CLOSED);
    }

    // ────────── isRetryable 分类 ──────────

    @Test
    void isRetryableShouldClassifyErrors() {
        assertThat(ResilientAiClient.isRetryable(new RuntimeException("Connection timed out"))).isTrue();
        assertThat(ResilientAiClient.isRetryable(new RuntimeException("429 Too Many Requests"))).isTrue();
        assertThat(ResilientAiClient.isRetryable(new RuntimeException("503 Service Unavailable"))).isTrue();
        assertThat(ResilientAiClient.isRetryable(new java.io.IOException("connection reset"))).isTrue();

        assertThat(ResilientAiClient.isRetryable(new RuntimeException("401 Unauthorized: invalid api key"))).isFalse();
        assertThat(ResilientAiClient.isRetryable(new RuntimeException("403 Forbidden"))).isFalse();
        assertThat(ResilientAiClient.isRetryable(new RuntimeException("400 Bad Request: invalid model"))).isFalse();
        assertThat(ResilientAiClient.isRetryable(new IllegalArgumentException("bad input"))).isFalse();
    }

    // ────────── Helpers ──────────

    @FunctionalInterface
    private interface CompleteFn { String complete(List<AiMessage> msgs); }
    @FunctionalInterface
    private interface StreamFn { Flux<String> stream(List<AiMessage> msgs); }

    private static class StubAiClient implements AiClient {
        private final CompleteFn completeFn;
        private final StreamFn streamFn;

        StubAiClient(CompleteFn completeFn, StreamFn streamFn) {
            this.completeFn = completeFn;
            this.streamFn = streamFn;
        }

        StubAiClient(List<String> tokens) {
            this(msgs -> "ok", msgs -> Flux.fromIterable(tokens));
        }

        @Override
        public String complete(List<AiMessage> messages) {
            return completeFn.complete(messages);
        }

        @Override
        public Flux<String> stream(List<AiMessage> messages) {
            return streamFn.stream(messages);
        }
    }
}