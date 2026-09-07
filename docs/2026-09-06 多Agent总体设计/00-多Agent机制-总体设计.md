# 多 Agent 机制 — 总体设计

> 日期：2026-09-06
> 状态：分阶段实现，每阶段独立产出文档。本文档为总览，具体实现见各阶段文档。

## 1. 背景与目标

当前系统的「智能体」是**写死的一个**：`AiController.doChatWithManus` 固定调 `MyAgent`，提示词来自 `prompt.yml` 静态配置，工具固定为「系统工具 + 该用户全部启用的 MCP 工具」。

目标：让用户**自定义 agent**，并能把多个 agent **组合编排**起来。

- 用户可创建 agent：定义**系统提示词**、选择**模型**（供应商 + 模型名）、绑定 **SKILL** 和 **MCP**。
- 单个 agent 可以直接对话（点选某个 agent 开始聊天）。
- 多个 agent 可以组合：采用**主从编排（Orchestrator）**，一个主 agent 根据任务动态决定调用哪个子 agent。

## 2. 可行性结论

**高度可行**。现有代码已具备绝大部分基础，主要是「新增数据实体 + 参数化已有执行链 + 一个小的编排层」。

### 2.1 已有可复用的核心能力

| 能力 | 现有实现 | 复用方式 |
|---|---|---|
| ReAct 执行引擎 | `agent/model/BaseAgent.java`（状态机+步骤循环+RAG注入）→ `agent/model/ReActAgent.java`（think/act 抽象+结构化事件）→ `agent/ToolCallAgent.java`（工具注入与手动工具循环）→ `agent/MyAgent.java`（一次性装配） | 直接复用；`MyAgent` 的一次性装配模式推广为「按用户配置参数化创建任意 agent」 |
| 模型按用户动态构建 | `model/UserChatModelManager.getChatModel(userId, modelEnum, modelName)` | 直接调用，agent 记录它要用的 provider + modelName |
| 供应商专属 ChatOptions | `model/ModelRouter.createChatOptions(modelEnum, tools)`（DEEPSEEK 已关内置工具执行，由 ReAct 手动驱动） | 直接复用 |
| MCP 工具获取 | `mcp/UserMcpToolManager.getToolsForUser(userId)` 返回 `ToolCallback[]` | 需要按「绑定到指定 server 子集」过滤（新增一个方法） |
| 系统工具 | `tool/ToolRegistration.allTools` Bean（文件读写/下载/终止） | 全局共享，对所有 agent 注入 |
| SSE 流式事件协议 | `agent/model/AgentStepEvent{type,step,content}`（think/tool_call/tool_result/finish/answer/error/max_steps/step_start/step_end） | 直接复用，编排层沿用该协议 |
| 会话记忆 | `chatmemory/RedisChatMemory` + `BaseAgent.runStream(message, conversationId)` 的加载/持久化 | 复用，自定义 agent 对话走同样的会话记忆 |
| 数据持久化范式 | entity + mapper(+XML) + service + controller + dto，见 `user_mcp_server` 整套 | 照葫芦画瓢 |
| 前端配置页范式 | `McpConfig.vue`（表格+弹窗）、`LlmConfig.vue`（配置卡） | 照葫芦画瓢 |
| 前端 SSE 流式对话 | `ChatRoom.vue` + `api/ai.js` 的 `createSseStream` | 复用，对话页加「选 agent」能力 |

### 2.2 需要新增的部分

1. **SKILL 实体与管理**（阶段 01）—— 提示词知识包。
2. **Agent 实体与管理**（阶段 02）—— 自定义 agent 定义，含绑定关系（skill / mcp / 模型）。
3. **Agent 直接对话**（阶段 03）—— 参数化执行链：从 DB 读 agent 配置 → 拼装工具/提示词 → 跑现有 ReAct 引擎。
4. **主从编排**（阶段 04）—— 编排器配置 + 子 agent 即工具 + 执行流程。

## 3. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│  前端 Vue3 (my-agent-frontend)                                │
│   /chat       对话页（可选 agent / 编排器）                     │
│   /agents     Agent 管理页（含 Skill 管理入口）                 │
│   /mcp-config MCP 配置页（已有）                               │
│   /llm-config LLM 配置页（已有）                               │
└───────────────┬─────────────────────────────────────────────┘
                │ REST + SSE（/api 前缀）
┌───────────────▼─────────────────────────────────────────────┐
│  后端 Spring Boot                                            │
│                                                              │
│  Controller 层                                               │
│   AiController（已有 /manus/chat）                            │
│   ├─ AgentChatController  （阶段03: /ai/agent 对话）           │
│   ├─ OrchestratorController（阶段04: /ai/orchestrator 对话）   │
│   ├─ AgentController       （阶段02: /agent CRUD）             │
│   └─ SkillController       （阶段01: /skill CRUD）             │
│                                                              │
│  Service 层（新增）                                           │
│   ├─ UserAgentService / UserSkillService（CRUD + 绑定）        │
│   ├─ AgentExecutor         （阶段03: 按 agent 配置拼装实例）    │
│   └─ OrchestratorService   （阶段04: 装配编排器 + 驱动子agent）  │
│                                                              │
│  领域复用（不新增，参数化使用）                                  │
│   ├─ BaseAgent / ReActAgent / ToolCallAgent（ReAct 引擎）     │
│   ├─ UserChatModelManager（按用户构建 ChatModel）              │
│   ├─ ModelRouter（provider 专属 ChatOptions）                 │
│   ├─ UserMcpToolManager（MCP 工具）                           │
│   └─ ToolRegistration.allTools（系统工具）                    │
└───────────────┬─────────────────────────────────────────────┘
                │ MySQL
┌───────────────▼─────────────────────────────────────────────┐
│  表（新增）                                                   │
│   user_skill（阶段01）                                        │
│   user_agent、user_agent_skill、user_agent_mcp（阶段02）      │
│   user_orchestrator、user_orchestrator_agent（阶段04）        │
└─────────────────────────────────────────────────────────────┘
```

## 4. 数据模型总览

新增 6 张表（全部遵循现有 DDL 规范：`id` 自增主键 + `user_id` + `idx_user_id` 索引 + `create_time/update_time` + 用户域唯一键）。各阶段文档含完整 DDL。

| 表 | 阶段 | 说明 |
|---|---|---|
| `user_skill` | 01 | 技能提示词知识包（用户可增删改） |
| `user_agent` | 02 | 自定义 agent 定义（名称/提示词/模型/开关） |
| `user_agent_skill` | 02 | agent ↔ skill 多对多绑定 |
| `user_agent_mcp` | 02 | agent ↔ 用户 MCP server 多对多绑定 |
| `user_orchestrator` | 04 | 编排器定义（提示词/模型/开关） |
| `user_orchestrator_agent` | 04 | 编排器 ↔ 子 agent 关联 |

## 5. 分阶段实施计划

| 阶段 | 文档 | 内容 | 依赖 |
|---|---|---|---|
| 01 | `01-Skill技能管理.md` | Skill 知识包 CRUD（后端 8 处配套 + 前端管理页） | 无 |
| 02 | `02-自定义Agent管理.md` | Agent CRUD + 绑定 skill/mcp/模型（后端 + 前端管理页） | 阶段 01 |
| 03 | `03-Agent单聊对话.md` | ChatRoom 选 agent 直接对话；`AgentExecutor` 参数化执行链 | 阶段 02 |
| 04 | `04-主从编排.md` | 编排器配置 + 子 agent 即工具 + 执行流程 + 前端 | 阶段 02/03 |

建议按 01→04 顺序推进，每个阶段独立可验证、独立一次提交。

## 6. 关键设计决策（已与用户确认）

1. **SKILL = 提示词知识包**：不包含可执行代码，本质是一段领域指导语，绑定到 agent 后**拼接进其 systemPrompt**，让 agent 获得该领域的行为指引。
2. **组合形态 = 主从编排（Orchestrator）**：不采用顺序 chain，也不做可视化图编排。
3. **分阶段实现**：拆分成上面 5 个文档依次落地。

## 7. 新增实体/表的配套改动清单（后端，每个实体都要做）

以新增 `Xxx` 实体为例（参考 `user_mcp_server` 全套，阶段 01 会演示第一遍）：

1. `resources/my_agent.sql` 加 CREATE TABLE。
2. `entity/Xxx.java`：`@Data` + `@TableName` + `@TableId(type=IdType.AUTO)` + `@TableField("snake_case")`。
3. `mapper/XxxMapper.java`：`@Mapper interface XxxMapper extends BaseMapper<Xxx>`（单表 CRUD 无需 XML）。
4. （可选）`resources/mapper/XxxMapper.xml`：仅自定义查询时写（如带条件的 select / 绑定关系查询）。
5. `service/XxxService.java` + `service/impl/XxxServiceImpl.java`：构造器注入 mapper，方法首行 `Long userId = UserContext.getUserId();`，注意归属校验（`selectByUserIdAndId` 防越权），变更后调对应缓存失效。
6. `controller/XxxController.java`：REST 接口。
7. `dto/XxxRequest.java`（`@NotBlank` 校验）+ `dto/XxxDto.java`（返回，敏感字段脱敏）。
8. 前端对应 `api/xxx.js` + `views/Xxx.vue` + 路由 + `AppLayout.vue` 侧边导航。

## 8. 注意事项（贯穿各阶段）

- **Agent 是一次性组装对象**：每次对话 `new` 一个轻量实例，不入 Spring 容器（沿用 `MyAgent.create` 模式）。编排阶段子 agent 也按此方式临时构建，用完即弃。
- **缓存失效**：用户改 LLM 配置后 `UserChatModelManager.invalidate(userId)`（已有）；阶段 04 若编排器缓存了子 agent 工具需同步失效。
- **会话记忆多租户**：自定义 agent 对话复用 `conversationId + RedisChatMemory`，与现有 chat/manus 共享同一套会话历史；子 agent 执行时应使用**独立内存**，不污染主会话记忆（见阶段 04）。
- **SSE 事件协议不变**：前端 `ChatRoom.vue` 对结构化事件（think/tool_call/tool_result/finish）的解析不做改动，编排层额外加的字段（如 `agent_name`）做成可选项，前端不解析也不报错。