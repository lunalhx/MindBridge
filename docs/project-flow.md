# MindBridge 项目流程图说明

正式交付请使用图片版本：

- `docs/mindbridge-project-flow.png`
- `docs/mindbridge-project-flow.svg`

这版流程图按代码真实链路绘制：

- 入口只有学生和管理员。
- 学生聊天进入 `ChatController -> ChatService -> AgentRuntimeService`。
- Agent 编排是最大 8 步的受控 loop。
- `CHAT` 走 `CompanionAgent`。
- `CONSULT / RISK` 走 `KnowledgeAgent -> RiskGuardianAgent -> CounselorAgent`。
- 大模型正式链路只展示 `Ollama / OpenAI`。
- 底部展示数据、工具和评测运行层，不放营销条。
