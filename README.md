# MindBridge Agent

MindBridge 是一个校园心理健康智能体

- 动态路由 RAG：先识别 `CHAT / CONSULT / RISK`，闲聊不查知识库，咨询与风险消息才进入检索增强。
- SSE 流式输出：`/api/chat/stream` 返回 `text/event-stream`，适合前端做打字机效果。
- 后台心理状态识别：记录情绪标签、情绪分数、风险等级和置信度，但学生端不展示评估结果。
- 用户画像记忆：从对话中抽取稳定偏好、沟通方式和支持需求，MySQL 保存可审计记录，Chroma 负责语义召回。
- 数据闭环：咨询/风险消息写入数据库，高风险先写 Excel，再触发邮件或 HTTP MCP 预警。
- Spring AI 模型接入：默认通过 `ollama` 调用项目模型，也可按需切到 `openai`。
- 可替换知识库：默认本地轻量检索，可打开 Chroma 镜像和查询。
- 多 Agent loop：每轮输入由 MemoryAgent、SupervisorAgent、KnowledgeAgent、RiskGuardianAgent 和回复 Agent 协作完成

大模型 LoRA 微调、合并、GGUF 转换和 Ollama 接入流程见：[docs/qwen25-7b-lora-finetune-guide.md](docs/qwen25-7b-lora-finetune-guide.md)。

## 目录

```text
src/main/java/com/mindbridge/agent
├── config                 # 配置、安全、AI/MCP Bean
├── controller             # Chat / Knowledge / Report API
├── domain                 # JPA 实体与枚举
├── dto                    # 请求与响应对象
├── repository             # Spring Data JPA
├── security               # 当前用户与认证查询
└── service
	    ├── ai                 # Spring AI 模型适配器与 Prompt
	    ├── agent              # 多 Agent loop：记忆、路由、知识检索、风险守护与回复规划
	    ├── knowledge          # 切块、检索、Chroma 网关
	    ├── memory             # Redis 短期记忆与用户画像长期记忆
	    └── mcp                # Excel 与邮件/HTTP 预警工具
```

## Agent loop 与多 Agent 分工

每轮对话进入一个有限步 agent loop，最多执行 8 步，防止心理安全场景中出现无限自主循环：

```text
MemoryAgent
-> SupervisorAgent
-> KnowledgeAgent
-> RiskGuardianAgent
-> CompanionAgent / CounselorAgent
```

各 Agent 分工：

- `MemoryAgent`：读取 Redis 短期记忆，并用当前输入从 Chroma 召回相关用户画像；Redis 为空时从 MySQL 长期聊天记录恢复。
- `SupervisorAgent`：调用模型判断 `CHAT / CONSULT / RISK`，决定后续交给普通陪伴还是心理支持链路。
- `KnowledgeAgent`：调用模型改写 Chroma/RAG 检索 query，并判断检索结果是否足够，不足时二次检索。
- `RiskGuardianAgent`：调用模型做后台心理状态评估，同时保留高风险词库硬兜底。
- `CompanionAgent`：调用模型生成普通聊天回复策略，并组装普通助手回复 prompt。
- `CounselorAgent`：调用模型生成心理支持回复策略，并结合记忆、RAG、风险守护结果组装回复 prompt。

最终回复仍通过 Spring AI 流式调用项目模型输出给学生端；后台风险报告、Excel 和预警工具链仍按安全规则执行。

## 快速启动

运行环境要求：

- JDK 17
- Maven 3.9+
- Ollama（使用本地模型时需要）

最省事的方式是直接运行：

```bash
cd MindBridge
./scripts/run-dev.sh
```

启动后打开：

```text
http://localhost:8080
```

如果想手动分两步启动，先在一个终端启动 Ollama：

```bash
cd MindBridge
./scripts/start-ollama.sh
```

再在另一个终端运行项目：

```bash
cd MindBridge
mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

也可以先打包，再运行 jar：

```bash
cd MindBridge
mvn -Dmaven.repo.local=.m2/repository package
java -jar target/mindbridge-agent-0.1.0.jar --server.address=127.0.0.1 --server.port=8080
```

默认使用 H2 文件数据库、Ollama 大模型、本地 Excel 文件和日志预警。页面左上角会显示当前模型模式；如果本机没有启动 Ollama，聊天接口会提示模型连接失败。首次启动会创建两个账号：

```text
admin / admin123
student / student123
```

## 调用示例

```bash
curl -N -u student:student123 \
  -H 'Content-Type: application/json' \
  -d '{"message":"我最近很焦虑，晚上总是睡不着"}' \
  http://localhost:8080/api/chat/stream
```

高风险示例会触发报告、Excel 写入和预警：

```bash
curl -N -u student:student123 \
  -H 'Content-Type: application/json' \
  -d '{"message":"我不想活了，感觉撑不下去了"}' \
  http://localhost:8080/api/chat/stream
```

管理员查看后台报告：

```bash
curl -u admin:admin123 http://localhost:8080/api/admin/reports
```

查看当前是否接入真实大模型：

```bash
curl -u student:student123 http://localhost:8080/api/agent/status
```

查看当前学生账号的画像记忆：

```bash
curl -u student:student123 http://localhost:8080/api/profile/memory
```

管理员追加知识库：

默认内置知识库位于 `src/main/resources/knowledge/`，启动时会按来源文件补齐到 RAG 库中；当前包含校园心理健康、风险策略、焦虑睡眠、低落社交、学业压力、校园求助、人际家庭冲突、危机安全计划和隐私边界等 Markdown 文档。

```bash
curl -u admin:admin123 \
  -H 'Content-Type: application/json' \
  -d '{"source":"sleep-guide","content":"失眠时可先固定起床时间，减少睡前屏幕刺激，必要时联系校心理中心。"}' \
  http://localhost:8080/api/admin/knowledge
```

## 接入 Ollama / LoRA 模型

默认模型配置就是本地 Ollama 路线，模型名为：

```text
mindbridge-qwen2.5-7b-ft:latest
```

本地模型由这个 GGUF 权重创建：

```text
models/mindbridge-qwen2.5-7b-ft/mindbridge-qwen2.5-7b-ft-q4_k_m.gguf
```

首次运行或重新导入模型时执行：

```bash
cd MindBridge
./scripts/create-finetuned-model.sh
```

之后直接启动项目：

```bash
cd MindBridge
./scripts/run-dev.sh
```

如果终端提示 `ollama: command not found`，说明只是命令链接没建好；本项目脚本会直接调用 `/Applications/Ollama.app/Contents/Resources/ollama`。

也可以不用脚本，手动指定本地模型启动：

```bash
cd MindBridge
AI_PROVIDER=ollama \
OLLAMA_BASE_URL=http://localhost:11434 \
OLLAMA_MODEL=mindbridge-qwen2.5-7b-ft:latest \
JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home" \
  .tools/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

## 接入 OpenAI

```bash
cd MindBridge
AI_PROVIDER=openai \
OPENAI_API_KEY=你的_API_Key \
OPENAI_MODEL=gpt-4o-mini \
JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home" \
  .tools/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

## 使用 MySQL、Chroma、SMTP

启动依赖：

```bash
docker compose up -d mysql redis chroma mailpit
```

使用 MySQL profile：

```bash
AI_PROVIDER=ollama \
USE_CHROMA=true \
MEMORY_USE_CHROMA=true \
MCP_EMAIL_MODE=smtp \
ALERT_MAIL_RECIPIENTS=counselor@example.com \
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

默认会使用两个 Chroma collection：

- `mindbridge_knowledge`：RAG 知识库切块检索。
- `mindbridge_user_memory`：用户画像/偏好长期语义记忆召回。

Mailpit 管理页面：`http://localhost:8025`

## MCP 工具模式

Excel 工具：

- `MCP_EXCEL_MODE=local`：默认写入 `./data/mindbridge-reports.xlsx`
- `MCP_EXCEL_MODE=http`：调用 `MCP_EXCEL_URL/write`
- `MCP_EXCEL_MODE=mcp`：通过标准 Model Context Protocol Client 调用 MCP Server 暴露的 `mindbridge_write_excel_report` 工具

邮件工具：

- `MCP_EMAIL_MODE=log`：默认只记录日志，便于本地演示
- `MCP_EMAIL_MODE=smtp`：使用 Spring Mail 发送
- `MCP_EMAIL_MODE=http`：调用 `MCP_EMAIL_URL/send`
- `MCP_EMAIL_MODE=mcp`：通过标准 Model Context Protocol Client 调用 MCP Server 暴露的 `mindbridge_send_risk_alert` 工具

标准 MCP：

- `MCP_SERVER_ENABLED=true`：启用 Spring AI MCP WebFlux Server，默认 SSE 端点为 `/sse`，消息端点为 `/mcp/messages`
- `MCP_CLIENT_ENABLED=true`：启用 Spring AI MCP WebFlux Client，默认连接 `MCP_SERVER_URL`
- `MCP_EMAIL_SERVER_DELIVERY_MODE=log|smtp`：MCP Server 收到邮件工具调用后的实际投递方式

高风险链路按文档实现为：写入报告 -> 写入 Excel -> Excel 成功后发送预警 -> 更新状态。

## RAGAS 评测

项目使用 RAGAS 做 RAG 质量评测。Java 主工程不引入 RAGAS 依赖，只负责执行检索和单次 RAG 回答生成，导出 RAGAS 输入报告；RAGAS 作为 `eval/` 目录下的可选 Python 工具运行。

Java 输入报告包含每条样本的：

- `question`：用户问题
- `answer`：模型最终回答
- `retrievedContexts`：RAG 检索到的上下文
- `referenceAnswer`：参考答案
- `retrievedSources`、`expectedIntent`、`expectedRiskLevel`：用于人工分析的元数据

运行评测：

```bash
SPRING_MAIN_WEB_APPLICATION_TYPE=none \
AI_PROVIDER=ollama \
OLLAMA_BASE_URL=http://localhost:11434 \
OLLAMA_MODEL=mindbridge-qwen2.5-7b-ft:latest \
USE_CHROMA=false \
RAG_EVAL_ENABLED=true \
RAG_EVAL_EXIT_AFTER_RUN=true \
DB_URL='jdbc:h2:mem:mindbridge-rag-eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1' \
JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home" \
  .tools/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

默认评测集：`src/main/resources/rag-eval/mindbridge-rag-eval.json`

默认 Java 输入报告：`target/rag-eval-report.json`

评测集中的每条样本包含：

- `question`：待检索问题
- `expectedSources`：应该命中的知识库来源文件
- `expectedTerms`：人工分析检索命中的辅助关键词
- `referenceAnswer`：RAGAS 使用的参考答案
- `expectedIntent` / `expectedRiskLevel`：路由和风险分级期望，作为 RAGAS 输出里的元数据保留

`eval/run-ragas-eval.py` 读取 `target/rag-eval-report.json` 后计算：

- `LLMContextPrecisionWithReference`：检索片段排序是否把相关内容排在前面
- `LLMContextRecall`：检索内容是否覆盖参考答案需要的信息
- `ResponseRelevancy`：回答是否切题
- `Faithfulness`：回答中的事实是否能被检索上下文支持
- `FactualCorrectness`：若当前 RAGAS 版本支持，则对比参考答案检查事实正确性

安装 RAGAS 依赖：

```bash
python3 -m pip install -r eval/requirements-ragas.txt
```

使用 OpenAI 评审模型：

```bash
OPENAI_API_KEY=... \
python3 eval/run-ragas-eval.py \
  --provider openai \
  --input target/rag-eval-report.json \
  --output target/ragas-report.json
```

使用本地 Ollama 评审模型：

```bash
/Applications/Ollama.app/Contents/Resources/ollama pull nomic-embed-text

python3 eval/run-ragas-eval.py \
  --provider ollama \
  --judge-model qwen2.5:7b \
  --embedding-model nomic-embed-text \
  --input target/rag-eval-report.json \
  --output target/ragas-report.json
```

RAGAS 输出报告：`target/ragas-report.json`
