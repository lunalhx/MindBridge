package com.mindbridge.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.MessageRole;
import com.mindbridge.agent.harness.TestAgentModelRegistry;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.ai.AgentModelRegistry;
import com.mindbridge.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Flux;

class RedisAgentPrivateMemoryTest {

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOps;
    private ValueOperations<String, String> valueOps;
    private MindBridgeProperties properties;
    private FakeAiClient aiClient;
    private RedisAgentPrivateMemory privateMemory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        properties = new MindBridgeProperties();
        properties.getChat().setPrivateMemoryTtlHours(24);
        properties.getChat().setPrivateMemoryCompactThreshold(5);
        properties.getChat().setPrivateMemoryKeepRecent(2);

        aiClient = new FakeAiClient();
        AgentModelRegistry modelRegistry = TestAgentModelRegistry.withFixedClient(aiClient);
        privateMemory = new RedisAgentPrivateMemory(
                redisTemplate, new ObjectMapper(), modelRegistry, properties);
    }

    // ────────── Key 隔离 ──────────

    @Test
    void memoryKeyShouldIsolateByAgentNameAndSessionId() {
        assertThat(privateMemory.memoryKey(AgentName.MEMORY_AGENT, "session-1"))
                .isEqualTo("mindbridge:agent:mem:MEMORY_AGENT:session-1");
        assertThat(privateMemory.memoryKey(AgentName.SUPERVISOR_AGENT, "session-1"))
                .isEqualTo("mindbridge:agent:mem:SUPERVISOR_AGENT:session-1");
        assertThat(privateMemory.memoryKey(AgentName.MEMORY_AGENT, "session-2"))
                .isEqualTo("mindbridge:agent:mem:MEMORY_AGENT:session-2");
    }

    @Test
    void summaryKeyShouldAppendSummarySuffix() {
        assertThat(privateMemory.summaryKey(AgentName.MEMORY_AGENT, "sess-1"))
                .isEqualTo("mindbridge:agent:mem:MEMORY_AGENT:sess-1:summary");
    }

    // ────────── Key 注入防护 ──────────

    @Test
    void shouldRejectNullSessionId() {
        assertThatThrownByNull(() -> privateMemory.memoryKey(AgentName.MEMORY_AGENT, null));
    }

    @Test
    void shouldRejectBlankSessionId() {
        assertThatThrownByNull(() -> privateMemory.memoryKey(AgentName.MEMORY_AGENT, "  "));
    }

    @Test
    void shouldSanitizeUnsafeCharactersInKey() {
        // 包含冒号和空格的 sessionId 应被规范化
        String key = privateMemory.memoryKey(AgentName.MEMORY_AGENT, "sess:ion with space");
        assertThat(key).startsWith("mindbridge:agent:mem:MEMORY_AGENT:");
        // 不安全字符被替换为下划线
        assertThat(key).contains("sess_ion_with_space");
        // 规范化后的 key 不应包含空格
        assertThat(key).doesNotContain(" ");
    }

    // ────────── append ──────────

    @Test
    void appendShouldPushToCorrectKeyAndSetTtl() {
        var msg = new MemoryMessage(MessageRole.USER, "hello");
        privateMemory.append(AgentName.MEMORY_AGENT, "sess-1", msg);

        verify(listOps).rightPush(eq("mindbridge:agent:mem:MEMORY_AGENT:sess-1"), anyString());
        verify(redisTemplate).expire(eq("mindbridge:agent:mem:MEMORY_AGENT:sess-1"), any(Duration.class));
    }

    @Test
    void appendShouldSilentlySkipOnRedisError() {
        when(listOps.rightPush(anyString(), anyString())).thenThrow(new RuntimeException("Redis down"));

        privateMemory.append(AgentName.MEMORY_AGENT, "sess-1",
                new MemoryMessage(MessageRole.USER, "hello"));
        // 不应抛异常
    }

    // ────────── recent ──────────

    @Test
    void recentShouldReturnMessagesFromCorrectKey() {
        when(listOps.range("mindbridge:agent:mem:MEMORY_AGENT:sess-1", 0, -1))
                .thenReturn(List.of(
                        "{\"role\":\"USER\",\"content\":\"hello\"}",
                        "{\"role\":\"ASSISTANT\",\"content\":\"hi\"}"));

        List<MemoryMessage> messages = privateMemory.recent(AgentName.MEMORY_AGENT, "sess-1");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(MessageRole.USER);
        assertThat(messages.get(0).content()).isEqualTo("hello");
        assertThat(messages.get(1).role()).isEqualTo(MessageRole.ASSISTANT);
    }

    @Test
    void recentShouldReturnEmptyOnRedisError() {
        when(listOps.range(anyString(), eq(0L), eq(-1L))).thenThrow(new RuntimeException("Redis down"));

        List<MemoryMessage> messages = privateMemory.recent(AgentName.MEMORY_AGENT, "sess-1");
        assertThat(messages).isEmpty();
    }

    @Test
    void recentShouldReturnEmptyWhenNoData() {
        when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(null);

        assertThat(privateMemory.recent(AgentName.MEMORY_AGENT, "sess-1")).isEmpty();
    }

    // ────────── summarize ──────────

    @Test
    void summarizeShouldReturnValueFromSummaryKey() {
        when(valueOps.get("mindbridge:agent:mem:MEMORY_AGENT:sess-1:summary"))
                .thenReturn("用户偏好简洁回复");

        assertThat(privateMemory.summarize(AgentName.MEMORY_AGENT, "sess-1"))
                .isEqualTo("用户偏好简洁回复");
    }

    @Test
    void summarizeShouldReturnEmptyWhenNoSummary() {
        when(valueOps.get(anyString())).thenReturn(null);

        assertThat(privateMemory.summarize(AgentName.MEMORY_AGENT, "sess-1")).isEmpty();
    }

    @Test
    void summarizeShouldReturnEmptyOnRedisError() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        assertThat(privateMemory.summarize(AgentName.MEMORY_AGENT, "sess-1")).isEmpty();
    }

    // ────────── compact ──────────

    @Test
    void compactShouldNotTriggerBelowThreshold() {
        when(listOps.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(List.of("{\"role\":\"USER\",\"content\":\"m1\"}"));

        privateMemory.compact(AgentName.MEMORY_AGENT, "sess-1");

        // 阈值 5，只有 1 条消息，不应触发压缩
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void compactShouldSummarizeAndKeepRecentWhenAboveThreshold() {
        // 6 条消息，阈值 5，保留最近 2 条
        List<String> rawMessages = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            rawMessages.add("{\"role\":\"USER\",\"content\":\"msg" + i + "\"}");
        }
        when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(rawMessages);

        aiClient.setReply("压缩摘要：用户讨论了焦虑问题");

        privateMemory.compact(AgentName.MEMORY_AGENT, "sess-1");

        // 应删除旧 key
        verify(redisTemplate).delete("mindbridge:agent:mem:MEMORY_AGENT:sess-1");
        // 应写入摘要
        verify(valueOps).set(
                eq("mindbridge:agent:mem:MEMORY_AGENT:sess-1:summary"),
                anyString(),
                any(Duration.class));
        // 应重新写入最近 2 条消息
        verify(listOps, atLeast(2)).rightPush(eq("mindbridge:agent:mem:MEMORY_AGENT:sess-1"), anyString());
    }

    @Test
    void compactShouldKeepOriginalOnSummaryFailure() {
        List<String> rawMessages = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            rawMessages.add("{\"role\":\"USER\",\"content\":\"msg" + i + "\"}");
        }
        when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(rawMessages);

        // LLM 摘要失败
        aiClient.setThrowException(true);

        privateMemory.compact(AgentName.MEMORY_AGENT, "sess-1");

        // 摘要失败时不应删除 key，只 trim 到阈值内
        verify(redisTemplate, never()).delete("mindbridge:agent:mem:MEMORY_AGENT:sess-1");
        // 不应写入摘要
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        // 应 trim 到最近 5 条（阈值）
        verify(listOps).trim(eq("mindbridge:agent:mem:MEMORY_AGENT:sess-1"), eq(-5L), eq(-1L));
    }

    @Test
    void compactShouldMergeWithExistingSummary() {
        List<String> rawMessages = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            rawMessages.add("{\"role\":\"USER\",\"content\":\"msg" + i + "\"}");
        }
        when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(rawMessages);
        when(valueOps.get("mindbridge:agent:mem:MEMORY_AGENT:sess-1:summary"))
                .thenReturn("已有摘要");

        aiClient.setReply("新摘要");

        privateMemory.compact(AgentName.MEMORY_AGENT, "sess-1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(
                eq("mindbridge:agent:mem:MEMORY_AGENT:sess-1:summary"),
                captor.capture(),
                any(Duration.class));
        assertThat(captor.getValue()).contains("已有摘要");
        assertThat(captor.getValue()).contains("新摘要");
    }

    // ────────── Redis 降级 ──────────

    @Test
    void compactShouldSilentlySkipOnRedisError() {
        when(listOps.range(anyString(), eq(0L), eq(-1L)))
                .thenThrow(new RuntimeException("Redis down"));

        privateMemory.compact(AgentName.MEMORY_AGENT, "sess-1");
        // 不应抛异常
    }

    // ────────── AgentPrivateMemoryRegistry ──────────

    @Test
    void registryShouldDelegateToPrivateMemory() {
        var mockPrivate = mock(AgentPrivateMemory.class);
        var registry = new AgentPrivateMemoryRegistry(mockPrivate);
        var msg = new MemoryMessage(MessageRole.USER, "test");

        registry.append(AgentName.MEMORY_AGENT, "sess-1", msg);
        verify(mockPrivate).append(AgentName.MEMORY_AGENT, "sess-1", msg);

        registry.recent(AgentName.MEMORY_AGENT, "sess-1");
        verify(mockPrivate).recent(AgentName.MEMORY_AGENT, "sess-1");

        registry.summarize(AgentName.MEMORY_AGENT, "sess-1");
        verify(mockPrivate).summarize(AgentName.MEMORY_AGENT, "sess-1");

        registry.compact(AgentName.MEMORY_AGENT, "sess-1");
        verify(mockPrivate).compact(AgentName.MEMORY_AGENT, "sess-1");
    }

    // ────────── 默认配置 ──────────

    @Test
    void defaultConfigShouldAlignTtlWithShortMemory() {
        var props = new MindBridgeProperties();
        assertThat(props.getChat().getPrivateMemoryTtlHours())
                .isEqualTo(props.getChat().getShortMemoryTtlHours());
    }

    @Test
    void defaultCompactThresholdShouldBe10() {
        assertThat(new MindBridgeProperties().getChat().getPrivateMemoryCompactThreshold()).isEqualTo(10);
    }

    @Test
    void defaultKeepRecentShouldBe4() {
        assertThat(new MindBridgeProperties().getChat().getPrivateMemoryKeepRecent()).isEqualTo(4);
    }

    // ────────── Helpers ──────────

    private void assertThatThrownByNull(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("Expected exception not thrown");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    /** Fake AiClient for testing compact/summarize without real LLM */
    private static class FakeAiClient implements AiClient {
        private String reply = "fake summary";
        private boolean throwException = false;

        void setReply(String reply) { this.reply = reply; }
        void setThrowException(boolean throwException) { this.throwException = throwException; }

        @Override
        public String complete(List<AiMessage> messages) {
            if (throwException) throw new RuntimeException("LLM unavailable");
            return reply;
        }

        @Override
        public Flux<String> stream(List<AiMessage> messages) {
            return Flux.just(reply);
        }
    }
}