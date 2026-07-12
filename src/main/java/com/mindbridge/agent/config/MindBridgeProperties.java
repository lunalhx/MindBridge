package com.mindbridge.agent.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mindbridge")
/**
 * mindbridge.* 配置映射。
 *
 * <p>所有业务配置集中在这里，便于通过 application.yml 或环境变量切换模型、
 * RAG、知识库切块、Excel 写入和邮件预警行为。</p>
 */
public class MindBridgeProperties {

    private final Ai ai = new Ai();
    private final Chat chat = new Chat();
    private final Memory memory = new Memory();
    private final Embedding embedding = new Embedding();
    private final Knowledge knowledge = new Knowledge();
    private final RagEval ragEval = new RagEval();
    private final Mcp mcp = new Mcp();
    private final Agent agent = new Agent();
    private final AgentModels agentModels = new AgentModels();
    private final ToolQueue toolQueue = new ToolQueue();
    private final Checkpoint checkpoint = new Checkpoint();

    public Ai getAi() {
        return ai;
    }

    public Chat getChat() {
        return chat;
    }

    public Memory getMemory() {
        return memory;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Knowledge getKnowledge() {
        return knowledge;
    }

    public RagEval getRagEval() {
        return ragEval;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Agent getAgent() {
        return agent;
    }

    public AgentModels getAgentModels() {
        return agentModels;
    }

    public ToolQueue getToolQueue() {
        return toolQueue;
    }

    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    public static class Agent {
        /** 声明式调度候选置信度阈值，低于此值的 Agent 不参与竞争。 */
        private double decisionThreshold = 0.6;
        /** Agent 运行时模式：SEQUENTIAL、GRAPH 或 EVENT_DRIVEN。默认 SEQUENTIAL。 */
        private String runtimeMode = "SEQUENTIAL";
        /** EVENT_DRIVEN 模式最大协调轮次。 */
        private int maxRounds = 8;
        /** EVENT_DRIVEN 模式最大 revision 次数（安全检查不通过后的修正上限）。 */
        private int maxRevisions = 2;

        public double getDecisionThreshold() {
            return decisionThreshold;
        }

        public void setDecisionThreshold(double decisionThreshold) {
            this.decisionThreshold = decisionThreshold;
        }

        public String getRuntimeMode() {
            return runtimeMode;
        }

        public void setRuntimeMode(String runtimeMode) {
            this.runtimeMode = runtimeMode;
        }

        public int getMaxRounds() {
            return maxRounds;
        }

        public void setMaxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
        }

        public int getMaxRevisions() {
            return maxRevisions;
        }

        public void setMaxRevisions(int maxRevisions) {
            this.maxRevisions = maxRevisions;
        }
    }

    public static class AgentModels {
        /** Per-Agent 模型 override 配置，key 为 Agent 名称（如 risk-guardian、counselor）。 */
        private Map<String, AgentModelProfileConfig> overrides = new LinkedHashMap<>();

        public Map<String, AgentModelProfileConfig> getOverrides() {
            return overrides;
        }

        public void setOverrides(Map<String, AgentModelProfileConfig> overrides) {
            this.overrides = overrides;
        }
    }

    public static class AgentModelProfileConfig {
        private String provider;
        private String model;
        private Double temperature;
        private Integer maxTokens;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class Ai {
        /** 模型提供方：ollama 或 openai。 */
        private String provider = "ollama";
        /** 生成温度，值越高回答越发散。 */
        private double temperature = 0.35;
        /** 学生端单次回复的最大生成 token 数，避免本地模型无边界扩写。 */
        private int maxTokens = 512;
        private final Ollama ollama = new Ollama();
        private final OpenAi openai = new OpenAi();
        private final Resilience resilience = new Resilience();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Ollama getOllama() {
            return ollama;
        }

        public OpenAi getOpenai() {
            return openai;
        }

        public Resilience getResilience() {
            return resilience;
        }
    }

    public static class Resilience {
        /** 是否启用 LLM 调用弹性（重试+断路器）。 */
        private boolean enabled = true;
        /** 最大尝试次数（含首次）。 */
        private int maxAttempts = 3;
        /** 初始退避间隔（毫秒）。 */
        private long initialBackoffMs = 1000;
        /** 最大退避间隔（毫秒），退避不超过此值。 */
        private long maxBackoffMs = 30000;
        /** 连续失败多少次后打开断路器。 */
        private int failureThreshold = 5;
        /** 断路器打开持续时间（毫秒），过后进入半开。 */
        private long openDurationMs = 60000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
        public long getMaxBackoffMs() { return maxBackoffMs; }
        public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public long getOpenDurationMs() { return openDurationMs; }
        public void setOpenDurationMs(long openDurationMs) { this.openDurationMs = openDurationMs; }
    }

    public static class Ollama {
        /** 本地模型服务地址。 */
        private String baseUrl = "http://localhost:11434";
        /** MindBridge 项目模型名称。 */
        private String model = "mindbridge-qwen2.5-7b-ft:latest";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class OpenAi {
        /** OpenAI 兼容接口地址。 */
        private String baseUrl = "https://api.openai.com";
        /** OpenAI API Key，未配置时不能启用 openai provider。 */
        private String apiKey = "";
        /** OpenAI 聊天模型名称。 */
        private String model = "gpt-4o-mini";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Chat {
        /** 保留给模型的历史轮次数，服务层会换算成用户/助手消息条数。 */
        private int historyLimit = 10;
        /** Redis 短期记忆 TTL，过期后可从 MySQL 长期记忆恢复最近上下文。 */
        private long shortMemoryTtlHours = 24;
        /** Per-Agent 私有记忆 TTL（小时），默认对齐短期记忆 TTL。 */
        private long privateMemoryTtlHours = 24;
        /** Per-Agent 私有记忆压缩阈值，消息数超过此值后触发 compact。 */
        private int privateMemoryCompactThreshold = 10;
        /** Per-Agent 私有记忆 compact 后保留的最近消息条数。 */
        private int privateMemoryKeepRecent = 4;

        public int getHistoryLimit() {
            return historyLimit;
        }

        public void setHistoryLimit(int historyLimit) {
            this.historyLimit = historyLimit;
        }

        public long getShortMemoryTtlHours() {
            return shortMemoryTtlHours;
        }

        public void setShortMemoryTtlHours(long shortMemoryTtlHours) {
            this.shortMemoryTtlHours = shortMemoryTtlHours;
        }

        public long getPrivateMemoryTtlHours() {
            return privateMemoryTtlHours;
        }

        public void setPrivateMemoryTtlHours(long privateMemoryTtlHours) {
            this.privateMemoryTtlHours = privateMemoryTtlHours;
        }

        public int getPrivateMemoryCompactThreshold() {
            return privateMemoryCompactThreshold;
        }

        public void setPrivateMemoryCompactThreshold(int privateMemoryCompactThreshold) {
            this.privateMemoryCompactThreshold = privateMemoryCompactThreshold;
        }

        public int getPrivateMemoryKeepRecent() {
            return privateMemoryKeepRecent;
        }

        public void setPrivateMemoryKeepRecent(int privateMemoryKeepRecent) {
            this.privateMemoryKeepRecent = privateMemoryKeepRecent;
        }
    }

    public static class Memory {
        /** 是否启用 Chroma 作为用户画像长期记忆的语义索引。 */
        private boolean useChroma;
        private String chromaBaseUrl = "http://localhost:8000";
        private String chromaCollection = "mindbridge_user_memory";
        /** 每轮按当前输入召回的画像记忆数量。 */
        private int topK = 6;

        public boolean isUseChroma() {
            return useChroma;
        }

        public void setUseChroma(boolean useChroma) {
            this.useChroma = useChroma;
        }

        public String getChromaBaseUrl() {
            return chromaBaseUrl;
        }

        public void setChromaBaseUrl(String chromaBaseUrl) {
            this.chromaBaseUrl = chromaBaseUrl;
        }

        public String getChromaCollection() {
            return chromaCollection;
        }

        public void setChromaCollection(String chromaCollection) {
            this.chromaCollection = chromaCollection;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }
    }

    public static class Embedding {
        /** Embedding 服务地址。 */
        private String baseUrl = "https://api.openai.com";
        /** Embedding API Key，留空时自动走本地检索兜底。 */
        private String apiKey = "";
        /** 文档要求的默认 embedding 模型。 */
        private String model = "text-embedding-3-small";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Knowledge {
        /** 每次 RAG 检索返回的候选片段数量。 */
        private int topK = 4;
        /** 是否启用二阶段 reranker。 */
        private boolean rerankerEnabled = true;
        /** 初排后交给 reranker 的最大候选数量。 */
        private int rerankerCandidateLimit = 20;
        /** 送入 reranker 的单个 chunk 最大字符数，避免 prompt 过长。 */
        private int rerankerMaxContentChars = 700;
        /** 是否启用外部 Chroma 向量库。 */
        private boolean useChroma;
        private String chromaBaseUrl = "http://localhost:8000";
        private String chromaCollection = "mindbridge_knowledge";
        private int chunkSize = 512;
        private int chunkOverlap = 64;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public boolean isRerankerEnabled() {
            return rerankerEnabled;
        }

        public void setRerankerEnabled(boolean rerankerEnabled) {
            this.rerankerEnabled = rerankerEnabled;
        }

        public int getRerankerCandidateLimit() {
            return rerankerCandidateLimit;
        }

        public void setRerankerCandidateLimit(int rerankerCandidateLimit) {
            this.rerankerCandidateLimit = rerankerCandidateLimit;
        }

        public int getRerankerMaxContentChars() {
            return rerankerMaxContentChars;
        }

        public void setRerankerMaxContentChars(int rerankerMaxContentChars) {
            this.rerankerMaxContentChars = rerankerMaxContentChars;
        }

        public boolean isUseChroma() {
            return useChroma;
        }

        public void setUseChroma(boolean useChroma) {
            this.useChroma = useChroma;
        }

        public String getChromaBaseUrl() {
            return chromaBaseUrl;
        }

        public void setChromaBaseUrl(String chromaBaseUrl) {
            this.chromaBaseUrl = chromaBaseUrl;
        }

        public String getChromaCollection() {
            return chromaCollection;
        }

        public void setChromaCollection(String chromaCollection) {
            this.chromaCollection = chromaCollection;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }
    }

    public static class RagEval {
        /** 是否在启动后生成 RAGAS 输入报告。 */
        private boolean enabled;
        /** 评测集 JSON 路径，支持 classpath: 或文件系统路径。 */
        private String dataset = "classpath:rag-eval/mindbridge-rag-eval.json";
        /** 评测链路使用的 TopK 元数据。 */
        private int topK = 4;
        /** 是否在报告生成后退出应用，便于命令行/CI 单独跑评测。 */
        private boolean exitAfterRun;
        /** RAGAS 输入 JSON 报告输出路径，留空则只打印控制台摘要。 */
        private String outputPath = "target/rag-eval-report.json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDataset() {
            return dataset;
        }

        public void setDataset(String dataset) {
            this.dataset = dataset;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public boolean isExitAfterRun() {
            return exitAfterRun;
        }

        public void setExitAfterRun(boolean exitAfterRun) {
            this.exitAfterRun = exitAfterRun;
        }

        public String getOutputPath() {
            return outputPath;
        }

        public void setOutputPath(String outputPath) {
            this.outputPath = outputPath;
        }
    }

    public static class Mcp {
        private final Excel excel = new Excel();
        private final Email email = new Email();

        public Excel getExcel() {
            return excel;
        }

        public Email getEmail() {
            return email;
        }
    }

    public static class Excel {
        /** Excel 写入模式：local、http 或 mcp。 */
        private String mode = "local";
        private String url = "http://localhost:8081";
        private String localPath = "./data/mindbridge-reports.xlsx";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getLocalPath() {
            return localPath;
        }

        public void setLocalPath(String localPath) {
            this.localPath = localPath;
        }
    }

    public static class Email {
        /** 邮件预警模式：log、smtp、http 或 mcp。 */
        private String mode = "log";
        private String url = "http://localhost:8082";
        private String from = "mindbridge@example.com";
        private List<String> recipients = new ArrayList<>(List.of("counselor@example.com"));
        private int maxRetries = 2;
        /** MCP Server 收到 send_risk_alert 工具调用后实际投递方式：log 或 smtp。 */
        private String mcpServerDeliveryMode = "log";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public List<String> getRecipients() {
            return recipients;
        }

        public void setRecipients(List<String> recipients) {
            this.recipients = recipients;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public String getMcpServerDeliveryMode() {
            return mcpServerDeliveryMode;
        }

        public void setMcpServerDeliveryMode(String mcpServerDeliveryMode) {
            this.mcpServerDeliveryMode = mcpServerDeliveryMode;
        }
    }

    public static class ToolQueue {
        /** 最大尝试次数（含首次）。 */
        private int maxAttempts = 3;
        /** 初始退避间隔（秒）。 */
        private long initialBackoffSeconds = 10;
        /** 退避乘数（指数退避因子）。 */
        private double backoffMultiplier = 2.0;
        /** Worker 轮询间隔（毫秒）。 */
        private long pollIntervalMs = 5000;
        /** 每次轮询最多领取的作业数。 */
        private int batchSize = 10;
        /** Job lease 超时秒数。Worker 崩溃后，其他 Worker 可在 lease 过期后重新领取。 */
        private int leaseSeconds = 120;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getInitialBackoffSeconds() { return initialBackoffSeconds; }
        public void setInitialBackoffSeconds(long initialBackoffSeconds) { this.initialBackoffSeconds = initialBackoffSeconds; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getLeaseSeconds() { return leaseSeconds; }
        public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }
    }

    public static class Checkpoint {
        /**
         * 是否启用 Agent 运行时 Checkpoint/中断恢复。
         * 默认 false，避免升级后改变生产行为。
         */
        private boolean enabled = false;
        /** Checkpoint 在 Redis 中的存活时间（秒），过期后自动清理。 */
        private long ttlSeconds = 3600;
        /** Checkpoint 数据 schema 版本，不兼容时安全地从新运行开始。 */
        private int schemaVersion = 2;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        public int getSchemaVersion() { return schemaVersion; }
        public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    }
}
