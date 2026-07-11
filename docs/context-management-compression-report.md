# MindBridge 上下文管理与上下文压缩技术报告

## 1. 报告范围

这份报告只讨论 MindBridge 项目里的上下文管理和上下文压缩。在这个项目里，上下文的范围比聊天历史大得多。它由几类数据共同组成：本轮输入、最近会话历史、用户长期画像、校园心理知识库命中片段、风险评估结果、回复策略，以及 Agent loop 的运行状态。

项目的业务场景是校园心理健康陪伴。这个场景对上下文有两类要求。第一，系统要记得足够多，否则会反复问学生已经说过的事情，回复也会显得割裂。第二，系统不能把所有东西都塞进模型，尤其不能把敏感标识、后台风险标签和过长历史原样暴露给生成模型。MindBridge 的实现基本围绕这两个要求展开：多来源收集、按任务筛选、先压缩再注入、全程留下审计记录。

从代码结构看，相关实现主要分布在这些位置：

| 模块 | 主要文件 | 职责 |
| --- | --- | --- |
| 聊天主流程 | `ChatService` | 接收请求、脱敏输入、启动 Agent loop、保存消息、流式输出 |
| Agent 运行时 | `AgentRuntimeService`、`AgentContext` | 在一轮对话内传递状态，按固定顺序调度各 Agent |
| 短期记忆 | `ShortTermMemoryService` | 用 Redis 保存最近 N 轮会话上下文 |
| 记忆 Agent | `MemoryAgent` | 读取短期历史和用户画像，生成 `memoryBrief` |
| 长期画像 | `UserProfileMemoryService`、`UserMemoryChromaGateway` | 抽取、保存、召回用户稳定偏好和支持需求 |
| 知识上下文 | `KnowledgeService`、`KnowledgeChunker`、`KnowledgeReranker` | 对心理知识库切块、混合检索、重排和邻居扩展 |
| 风险上下文 | `PsychologicalAssessmentService`、`RiskGuardianAgent` | 结合当前输入和历史判断情绪与风险，输出后台评估 |
| Prompt 模板 | `PromptTemplates` | 区分意图分类、风险评估和学生端回复的上下文注入方式 |
| 运行追踪 | `AgentRunTraceService`、`AgentRunTrace` | 保存每轮 Agent 的摘要、query、策略和步骤 |

## 2. 上下文在项目里的含义

MindBridge 里的上下文可以分成五层。

第一层是请求上下文。它包含当前用户、当前会话、用户原始输入和脱敏后的模型输入。`ChatService.prepare()` 会先拿到用户输入，再调用 `PrivacySanitizer` 得到 `modelInput`。原始输入用于落库、报告和管理员回看，脱敏输入用于模型调用和 Agent 判断。这个拆分很直接，但很有必要：系统可以保留审计材料，同时减少手机号、学号、证件号、真实姓名进入模型上下文的概率。

第二层是会话历史上下文。完整历史保存在 `chat_messages` 表里，最近窗口保存在 Redis list 里。Redis 负责快读快写，数据库负责长期留存。每次保存用户消息或助手回复时，`ChatService.saveMessage()` 都会调用 `ShortTermMemoryService.append()`，把消息推入 Redis，并裁剪到最近窗口。

第三层是用户画像上下文。它不是完整聊天记录，而是从聊天里抽取出来的稳定信息，例如沟通方式、偏好、支持需求、个人背景和反复出现的状态模式。画像保存在 `user_memory_items` 表里，也可以镜像到 Chroma，下一轮对话再按当前输入语义召回。

第四层是知识库上下文。校园心理知识文档会被切成 `knowledge_chunks`，进入 H2 或 MySQL，并可同步到 Chroma。咨询和风险场景会检索这些片段，把少量命中内容放进最终回答 prompt。普通聊天不会触发 RAG，也不会触发心理风险评估。

第五层是运行状态上下文。`AgentContext` 是一轮 Agent loop 内部的共享黑板，包含 `memoryBrief`、`intent`、`knowledgeQuery`、`retrievedKnowledge`、`assessment`、`riskLevel`、`responsePlan`、`responseMessages` 和步骤记录。它只在本轮请求内存在，跨轮复用依赖 Redis、数据库、用户画像和知识库索引。

## 3. 一轮对话的上下文生命周期

一次聊天请求从 `/api/chat/stream` 进入后，`ChatService` 先做准备工作。它会裁剪输入首尾空白，保留 `originalInput`，再生成 `modelInput`。如果请求没有传入会话 id，系统会创建一个新的 `ChatSession`，并用输入前 36 个字符作为标题。

随后系统调用 `AgentRuntimeService.run()`。这里有一个容易忽略的顺序：Agent loop 先运行，当前用户消息后保存。因此，MemoryAgent 从 Redis 或数据库读取到的是本轮之前的历史。为了让后续模型仍然看到当前问题，MemoryAgent 会手动把 `modelInput` 追加到 `modelHistory` 里。这避免了还没落库的当前输入在历史读取中丢失。

Agent loop 的顺序固定为 MemoryAgent、SupervisorAgent、KnowledgeAgent、RiskGuardianAgent、CompanionAgent 或 CounselorAgent。每个 Agent 通过 `supports(context)` 判断自己是否接手，完成后更新 `AgentContext` 里的标记位。运行时最多执行 8 步，不允许无限循环。这个设计适合心理安全场景，因为处理链路要可预测，不能让 Agent 自己随意扩展任务。

MemoryAgent 完成后，SupervisorAgent 根据当前输入和模型历史判断意图。意图只有三类：`CHAT`、`CONSULT` 和 `RISK`。如果是普通聊天，系统直接标记知识处理和风险评估已完成，交给 CompanionAgent 规划回复。如果是咨询或风险输入，系统才进入 KnowledgeAgent 和 RiskGuardianAgent。

KnowledgeAgent 会使用 `memoryBrief` 和当前输入改写检索 query。它要求模型只输出中文查询词，不解释，长度不超过 40 个字。检索后，它还会让模型判断结果是否足够。如果不够，会生成更具体的 query 再查一次。最后，检索 query 和知识片段都写回 `AgentContext`。

RiskGuardianAgent 在知识处理之后运行。它调用 `PsychologicalAssessmentService.assess()`，把当前输入和模型历史交给后台心理状态评估。评估结果进入报告和回复规划，但学生端 prompt 明确禁止输出风险等级、评估分数、诊断结论和后台标签。

最后由 CompanionAgent 或 CounselorAgent 生成回复策略，并组装最终传给模型的 `responseMessages`。Agent loop 到这里还没有生成完整回答。学生端回答是在 `ChatService.streamPrepared()` 中通过 `aiClient.stream()` 以 SSE token 形式返回的。流式回复完成后，助手消息才保存到数据库和 Redis。这样，下一轮对话才会把这次助手回复纳入短期记忆。

## 4. AgentContext 的上下文编排

`AgentContext` 不保存全量数据，只保存本轮需要流转的中间状态。它把每一步产物留在一个对象里，后续 Agent 不必重新查库或重复判断。

在输入层，它保存用户、会话、原始输入和模型输入。原始输入对应真实审计材料，模型输入对应脱敏后的文本。这个区分贯穿后续流程。

在记忆层，它保存 `previousHistory`、`modelHistory` 和 `memoryBrief`。`previousHistory` 是当前轮之前的历史。`modelHistory` 是历史加当前输入，并已按配置裁剪。`memoryBrief` 是短期历史摘要和用户画像摘要的合并结果。

在检索层，它保存 `knowledgeQuery` 和 `retrievedKnowledge`。KnowledgeAgent 可以把 query 改写、二次检索和最终命中的结果留给 CounselorAgent，避免回复 Agent 再做一次检索。

在安全层，它保存 `assessment` 和 `riskLevel`。这里要注意，评估结果是后台状态，不等于学生可见内容。最终 prompt 会使用风险等级来选择安全规则，但不会让模型向学生输出“你是高风险”这类表达。

在输出层，它保存 `responsePlan`、`responseMessages` 和 `responseAgent`。最终生成会先由 Agent 制定一段策略，再让流式模型按策略输出，避免退回到“把历史加进去直接回答”的粗糙模式。

在控制层，它保存 `memoryLoaded`、`intentRouted`、`knowledgeHandled`、`riskAssessed`、`responsePlanned` 和 `finished`。这些标记位构成了一个轻量状态机。状态机的好处是流程容易追踪，坏处是字段变多后组合状态需要小心维护。项目文档里也提到，未来可以考虑用显式状态枚举减少 boolean 组合带来的维护成本。

## 5. 短期记忆管理

短期记忆由 `ShortTermMemoryService` 实现，底层是 Redis list。key 格式是 `mindbridge:chat:short-memory:{sessionId}`。每条消息保存为 JSON，内容包括角色和文本。

每次追加消息时，服务会做三件事。先把新消息 `rightPush` 到 list 尾部，再调用 `trim` 只保留最近窗口，最后设置 TTL。默认配置里，`CHAT_HISTORY_LIMIT` 是 10，服务层按用户和助手两侧消息换算成 20 条。`CHAT_SHORT_MEMORY_TTL_HOURS` 默认是 24 小时。

这个设计把“最近对话”定义为一个滑动窗口，避免消息列表无限增长。它直接减少了模型输入长度，也减少了 Redis 占用。窗口长度用轮次配置，对产品侧也比较好解释：保留最近 10 轮，比保留某个难懂的 token 数更直观。

Redis 只是短期入口。如果 Redis 没有数据，MemoryAgent 会从数据库读取最近 20 条消息，按时间正序恢复，然后刷新 Redis。这样可以处理 Redis 过期、重启或短暂不可用后的恢复问题。Redis 读写异常只打 debug 日志，不中断聊天流程。这个容错策略更适合用户正在对话的场景，因为记忆短暂降级比聊天接口失败更可接受。

短期记忆进入模型前还会被脱敏。MemoryAgent 把 `ChatMessage` 或 Redis 中的 `MemoryMessage` 转成 `AiMessage` 时，会调用 `PrivacySanitizer.sanitize()`。因此，数据库里保存的原文和模型看到的历史不是完全同一份文本。

## 6. 长期用户画像管理

长期画像由 `UserProfileMemoryService` 管理。它保存的不是完整对话，而是对后续有稳定帮助的信息。抽取类型包括偏好、沟通方式、支持需求、个人背景和状态模式。

每轮用户消息保存后，`ChatService.rememberUserProfile()` 会调用 `rememberUserInput()`。这个动作发生在 Agent loop 之后，使用本轮用户输入和当前的 `memoryBrief` 作为抽取依据。服务会先脱敏输入，长度小于 6 的内容直接跳过，避免把过短、含义不完整的消息写入画像。

画像抽取主要依赖模型。Prompt 明确限制了保存范围：只提取稳定偏好、沟通风格、支持需求、个人背景和反复出现的状态模式；不要保存诊断结论、风险等级、手机号、学号、证件号、真实姓名、详细地址或一次性临时任务。模型必须输出 JSON 数组，每项包含类型、摘要、证据和置信度。

保存前还有服务端校验。置信度低于 0.55 的候选会被丢弃，摘要长度小于 4 或大于 80 的候选会被丢弃，摘要里出现敏感占位符、诊断结论或风险等级也会被丢弃。如果模型调用失败，系统只对“请记住”“我喜欢”“以后不要”等明确偏好句做简单兜底抽取。

画像写入采用 upsert。系统会按类型和归一化摘要查重，如果已有同类同摘要记忆，就刷新证据、置信度和时间；否则新增一条。每个用户最多保留 40 条，超过后按更新时间保留最新的部分，并删除被裁剪条目的 Chroma 索引。

召回时，`profileBrief(user, currentInput)` 会优先用 Chroma 按当前输入查询用户自己的画像，查询条件里带 `userId` 过滤。召回结果再回到数据库取完整实体，保证不会只信外部索引。如果 Chroma 没返回结果，系统回退到数据库中最近更新的 12 条，再在 profile brief 中最多使用 8 条。

这套机制把“长期记忆”做成了可解释的画像条目，没有走“把全部历史对话向量化后随意召回”的路线。它牺牲了一部分细节，但换来更好的可控性。对于心理陪伴场景，这个取舍是合理的：系统更应该记住“用户希望回答直接一点”“用户不太敢找辅导员”这种稳定信息，而不是把所有情绪表达原样长期塞进模型。

## 7. memoryBrief 的生成和作用

`memoryBrief` 是项目里最典型的上下文压缩产物。它由两部分合成：用户画像摘要和最近对话摘要。

用户画像摘要来自 `UserProfileMemoryService.profileBrief()`。格式上，它会按类型输出若干条短句，例如偏好、沟通方式、支持需求、个人背景或状态模式。这里的压缩发生在画像抽取阶段：原始输入先被转成简短 summary，后续只召回 summary，不再召回完整会话。

最近对话摘要来自 `MemoryAgent.summarizeMemory()`。MemoryAgent 会把最近历史格式化后交给模型，要求只输出 1 到 3 条中文要点，不输出风险等级、诊断结论或后台标签。如果历史与当前输入无关，模型应输出“无相关历史记忆。”。服务端还会把结果截断到 400 字。

用于摘要的历史本身也被裁剪。`formatHistory()` 只取最近 12 条消息。也就是说，短期压缩不会拿完整会话做摘要，而是在最近窗口内再做一次相关性提取。

合并时，MemoryAgent 会判断画像摘要和历史摘要是否为空。如果两者都有，就输出“用户画像”和“最近对话记忆”两段；如果只有其中之一，就只保留存在的部分；如果都没有，就统一返回“无相关历史记忆。”。这个统一默认值让后续 Agent 不需要处理太多 null 或空字符串。

`memoryBrief` 后续会被三个地方使用。KnowledgeAgent 用它改写 RAG query，让检索更贴近用户最近困扰和长期偏好。CompanionAgent 用它制定普通聊天回复策略。CounselorAgent 用它制定心理支持回复策略，尤其是避免重复问学生已经表达过的信息。

它不会直接替代 `modelHistory`。最终 prompt 里仍会带最近若干条历史消息。也就是说，MindBridge 采用的是“摘要加窗口”的组合：摘要提供跨消息的高密度线索，窗口保留最近对话的原话和语气。

## 8. RAG 知识上下文的压缩

知识库压缩从入库时就开始了。`KnowledgeChunker` 会先统一换行并去掉首尾空白，再按配置切分文本。默认 `KNOWLEDGE_CHUNK_SIZE` 是 512，`KNOWLEDGE_CHUNK_OVERLAP` 是 64。实际执行时，chunk size 至少为 120，overlap 不超过 chunk size 的一半。切分时优先在换行、中文句号、英文句号和问号附近断开，只有找不到合适边界时才按固定长度切。

切块不只是为了省长度。它要同时保证两个目标：片段不能太长，否则召回后 prompt 容易膨胀；片段也不能切得太碎，否则安全流程、求助方式和情绪调节建议会被截断。overlap 用来缓解边界断裂问题。

检索阶段采用混合召回。`KnowledgeService.retrieve()` 会先计算候选上限，取 `topK * 4`、20 和 reranker candidate limit 中的较大值。向量检索优先走 Chroma，如果 Chroma 没有结果，就尝试使用本地 embedding 余弦相似度。与此同时，BM25 会在数据库中的全部 chunk 上执行。最后，系统把向量结果和 BM25 结果合并，向量权重是 0.65，BM25 权重是 0.35。

混合召回后还有 reranker。`KnowledgeReranker` 会把候选片段交给模型打相关性分数，默认最多处理 20 个候选，每个 chunk 送入 reranker 的最大字符数是 700。重排分数按 0.85 的 rerank 权重和 0.15 的初排权重合并。如果模型不可用或输出解析失败，系统回退到初排结果。

最终进入回答 prompt 的候选只有 `RAG_TOP_K` 条，默认 4 条。检索排序完成后，系统还会对排名第一的 chunk 做邻居扩展：找到最佳命中的原始 chunk 后，取同一 source 下前一块、当前块、后一块，按顺序拼接成更完整的上下文。后续命中则保持原样，并跳过与扩展结果重复的 chunk。

这里的判断很朴素：最相关的一条通常值得补齐上下文，其他条目则应该节制。否则每个命中都扩展相邻块，prompt 会迅速变长。

KnowledgeAgent 还在检索前做 query 压缩。用户输入可能很长，也可能夹杂情绪表达、背景故事和不适合直接检索的句子。KnowledgeAgent 会结合 `memoryBrief`，把它改写成不超过 40 字的中文查询词。检索结果不足时再做一次 query refinement。这里压缩的是“检索意图”，用户原本的表达仍然保留在模型历史里。

## 9. 模型输入的最终组装

最终传给模型的上下文由回复 Agent 组装。ChatService 不在这里简单拼接历史和答案。

普通聊天走 CompanionAgent。它会先调用模型生成一句简短回复策略，再构造学生端回答消息。系统 prompt 使用 CHAT 模式，明确要求不要主动做心理测评，不要输出风险等级，不要把普通学习、编程、校园事务强行引导成心理咨询。随后追加一个系统消息，里面包含 `memoryBrief` 和回复策略，最后追加 `modelHistory`。

心理咨询和风险场景走 CounselorAgent。它也会先生成回复策略，但输入更多：`memoryBrief`、当前输入、风险守护摘要、知识库 query 和知识库命中。最终回答的 system prompt 会包含 RAG 知识上下文，并根据风险等级加入高风险处理规则。随后追加一个系统消息，写入当前负责回复的 Agent、记忆摘要、检索 query 和回复策略，最后追加 `modelHistory`。

这种组装方式有两个特点。

第一，后台判断被用来约束回答，但不直接展示给学生。Prompt 明确禁止输出风险等级、心理报告、评估分数和后台判断标签。项目用风险信息控制回复边界，而不是把风险信息当作内容告知用户。

第二，历史窗口仍然存在。MemoryAgent 已经把 `modelHistory` 裁剪到 `CHAT_HISTORY_LIMIT * 2` 条，并加入当前输入。ChatService 的兜底 `buildMessages()` 里还有一次窗口限制。正常情况下，Agent 生成的 `responseMessages` 会被使用；如果为空，兜底逻辑仍会按配置控制历史长度。

## 10. 上下文压缩的四种形态

MindBridge 的上下文压缩分布在多个阶段。

第一种是窗口压缩。Redis 只保留最近 `CHAT_HISTORY_LIMIT * 2` 条消息，`modelHistory` 也按同样规则裁剪。它解决的是无限历史导致的输入膨胀。

第二种是摘要压缩。MemoryAgent 把最近历史压成 1 到 3 条要点，并限制到 400 字。UserProfileMemoryService 把用户输入压成长期画像 summary。它解决的是“历史很短但信息密度低”的问题。

第三种是检索压缩。KnowledgeAgent 把当前表达压成短 query，KnowledgeService 从知识库中筛出少量片段，reranker 再把候选压到最终 topK。它解决的是知识库过大、不能全量注入的问题。

第四种是安全压缩。PrivacySanitizer 把敏感标识替换成占位符，PromptTemplates 把后台风险信息转成安全规则，而不是原样给学生展示。它重点处理上下文边界，而不只是长度。

这四种压缩共同作用，形成一个比较稳的输入结构：当前问题保留原意，最近历史保留窗口，长期画像保留稳定线索，知识库只保留命中片段，后台风险只进入控制规则。

## 11. 隐私和安全边界

项目在上下文管理上有几个明确边界。

原始输入和模型输入分开。原始输入用于数据库、报告和 trace，模型输入是脱敏后的文本。脱敏规则覆盖手机号、学号、证件号、中文姓名表达等。它还算不上完整的数据防泄漏系统，但已经把最常见的学生身份标识挡在模型上下文外。

用户画像不保存敏感标识和诊断类结论。抽取 prompt 和服务端校验都禁止保存风险等级、诊断结论、手机号、学号、证件号、真实姓名和详细地址。画像条目还带 evidence 和 confidence，便于后续审计。

普通聊天不会进入心理链路。IntentClassifier 对学习、编程、项目、论文、校园事务等普通任务有直接规则，且高风险表达优先级更高。普通任务不会触发 RAG 和风险评估，避免系统把学生的所有问题都心理化。

高风险表达有硬兜底。`RiskLexicon.hasHighRiskSignal()` 优先于模型判断。即使模型低估风险，RiskGuardianAgent 对 `RISK` 意图也会把风险等级抬到 HIGH。最终 prompt 的高风险规则要求先关注用户当前安全，鼓励联系可信任的人、学校辅导员、心理中心或当地紧急救助，并禁止提供危险操作细节。

后台标签不进入学生可见回答。系统 prompt 多次强调不要输出风险等级、报告、评估分数、诊断结论或后台标签。这样能降低用户被标签化的感受，也减少模型把内部流程当成回复内容的概率。

## 12. 可观测性和审计

上下文压缩会带来一个天然问题：如果回答不理想，开发者需要知道是历史没召回、摘要压错、query 改错、知识片段不够，还是风险评估误判。MindBridge 用 Agent run trace 解决这件事。

每轮 Agent loop 完成后，`AgentRunTraceService.saveRun()` 会保存一条 trace。概要字段包括 trace id、用户、会话、触发消息、原始输入、意图、风险等级、`memoryBrief`、`knowledgeQuery`、`responsePlan`、负责回复的 Agent、步骤数、开始时间和结束时间。

每一步 Agent 的动作也会保存到 `agent_run_trace_steps`。例如 MemoryAgent 会记录从 Redis 还是 MySQL 加载了多少条消息，KnowledgeAgent 会记录 query 和召回数量，RiskGuardianAgent 会记录风险等级和情绪标签。管理员可以按会话查看 trace。

这套记录没有保存完整 prompt，但它保存了足够多的中间结果。对排查来说，`memoryBrief`、`knowledgeQuery`、retrieved count、responsePlan 和 Agent 顺序通常已经能定位大部分问题。如果未来要做更细的调试，可以考虑在安全脱敏后保存最终 prompt 的哈希或结构化片段，无需直接保存全量 prompt。

## 13. 配置参数

上下文管理相关配置集中在 `application.yml` 的 `mindbridge` 下。

| 配置 | 默认值 | 影响 |
| --- | --- | --- |
| `CHAT_HISTORY_LIMIT` | 10 | 模型历史保留轮数，服务层换算成用户和助手两侧消息 |
| `CHAT_SHORT_MEMORY_TTL_HOURS` | 24 | Redis 短期记忆过期时间 |
| `MEMORY_USE_CHROMA` | 跟随 `USE_CHROMA`，默认 true | 是否启用用户画像语义索引 |
| `MEMORY_CHROMA_COLLECTION` | `mindbridge_user_memory` | 用户画像 Chroma collection |
| `MEMORY_TOP_K` | 6 | 每轮按当前输入召回的画像数量 |
| `RAG_TOP_K` | 4 | 最终进入回答上下文的知识片段数量 |
| `RAG_RERANKER_ENABLED` | true | 是否启用二阶段 reranker |
| `RAG_RERANKER_CANDIDATE_LIMIT` | 20 | 初排后交给 reranker 的最大候选数量 |
| `RAG_RERANKER_MAX_CONTENT_CHARS` | 700 | 单个 chunk 送入 reranker 的最大字符数 |
| `KNOWLEDGE_CHUNK_SIZE` | 512 | 知识库切块目标长度 |
| `KNOWLEDGE_CHUNK_OVERLAP` | 64 | 相邻知识块重叠长度 |
| `AI_MAX_TOKENS` | 512 | 学生端单次回复最大生成 token 数 |

这些参数共同控制输入和输出规模。`CHAT_HISTORY_LIMIT` 太小会让对话失去连续性，太大则会增加延迟和误触发历史意图的概率。`RAG_TOP_K` 太小可能漏掉知识，太大则会让模型分心。`chunkSize` 和 `overlap` 需要结合知识库文档形态调试，心理流程类文档通常不适合切得太碎。

## 14. 现有实现的取舍

当前实现没有做精确 token 预算。它主要用消息条数、字符数、topK 和固定最大输出 token 来控制上下文长度。这种方式实现简单，也便于配置，但无法精确适配不同模型的上下文窗口。如果未来接入更长或更短上下文的模型，需要增加 token 估算层。

项目没有保存跨轮滚动摘要。`memoryBrief` 是每轮临时生成的，trace 会保存当轮 brief，但下一轮不会直接读取上一轮 brief 作为长期摘要。跨轮长期信息主要靠用户画像条目承接。这让长期记忆更可控，但也可能丢掉一些连续叙事。

原始聊天历史没有做语义检索。系统不会把所有历史消息向量化后按当前输入召回，主要从最近窗口和抽取画像中取上下文。这降低了隐私和误召回风险，但如果用户很久以前说过某个重要背景，而它没有被抽成画像，就可能无法召回。

用户画像抽取依赖模型质量。服务端有置信度、长度和敏感信息过滤，但候选摘要本身仍由模型生成。如果模型把一次性情绪误判为长期模式，可能会产生不合适的画像。当前的 upsert 和 40 条上限能控制规模，但不能完全解决语义误存。

RAG 只扩展第一条最佳命中。这个取舍能控制 prompt 长度，也能修复最重要命中的边界问题。但如果第二或第三条命中同样处在流程中间，模型可能仍看到不完整片段。

Redis 失败不会中断主流程。这对可用性友好，但短期内可能降低上下文连续性。项目通过数据库回退弥补了一部分问题，但数据库只取最近 20 条，也不做语义筛选。

## 15. 可以继续改进的方向

第一，可以引入 token 预算器。预算器按模型上下文窗口，把 system prompt、memoryBrief、RAG 片段、历史窗口和当前输入分配不同预算。超出预算时，优先压缩历史，再减少低分知识片段，最后压缩画像摘要。这样比单纯按消息条数更稳。

第二，可以增加会话级滚动摘要。每轮结束后，把旧历史合并进一个可审计的 session summary，下一轮先读 summary，再读最近窗口。这样能保留长对话的连续叙事，同时避免把几十轮历史都塞进模型。

第三，可以给用户画像增加人工可编辑和过期策略。现在用户可以通过接口查看和删除记忆，但画像自身没有语义过期规则。心理状态模式尤其需要谨慎，过久的状态不应一直影响当前判断。

第四，可以记录最终 prompt 的结构化调试视图。重点不是保存完整敏感文本，而是保存各部分长度、条目数、来源、chunk id、分数和是否被截断。这样排查上下文压缩问题会更快。

第五，可以对 RAG 邻居扩展做动态策略。比如只对分数高于阈值的命中扩展相邻块，或者根据 chunk 是否包含流程编号、标题、列表来判断是否需要扩展。这样可以在完整性和长度之间更细地调节。

第六，可以把 `memoryBrief` 生成改成结构化输出。现在 brief 是自然语言文本，后续 Agent 只能整体读取。结构化后可以区分“稳定画像”“最近事件”“用户偏好”“安全相关但不可展示信息”，再由不同 Agent 按需使用。

## 16. 总结

MindBridge 没有把聊天历史简单拼到 prompt 后面。它先把一轮对话放进受控 Agent loop，再按任务拆分上下文：MemoryAgent 负责记忆，SupervisorAgent 负责路由，KnowledgeAgent 负责知识检索，RiskGuardianAgent 负责后台风险判断，回复 Agent 负责最终输入组装。

上下文压缩也不靠单一摘要算法。短期历史靠 Redis 窗口裁剪，长期信息靠用户画像抽取，知识库靠切块、混合检索、reranker 和 topK 收束，敏感信息靠脱敏和 prompt 边界控制。最终模型看到的是一份经过筛选的上下文，不是系统掌握的全部数据。

这套设计的优点是边界清楚、故障可降级、审计材料比较完整。它适合当前校园心理陪伴场景，尤其是普通聊天和心理支持需要分流的产品形态。主要不足在于还没有精确 token 预算、跨轮滚动摘要和更细的画像生命周期管理。后续如果项目要支持更长会话、更复杂的个性化陪伴或更多模型供应商，这几个方向会比继续堆 prompt 更值得投入。
