# 阶段 03：Agent 单聊对话

> 日期：2026-09-06
> 依赖：阶段 02（agent 定义已入库）。
> 目标：用户在对话页选中「某个自定义 agent」直接对话；核心是**按 agent 配置参数化构建 ReAct agent 实例**。
> 前置阅读：`00-多Agent机制-总体设计.md`、`02-自定义Agent管理.md`

## 1. 背景与目标

现在 `/manus/chat` 是写死的 `MyAgent`。本阶段把这条链路参数化：根据 `user_agent` 记录（+ 绑定的 skills / mcps）构建 agent 实例并执行流式对话，同时保留 `/manus/chat`（把它视为「系统内置 agent」，复用同一装载逻辑，只是配置来源不同）。

## 2. 核心改造：把 MyAgent 的参数化提取为 AgentExecutor

### 2.1 为什么不直接改 MyAgent

- `MyAgent` 是 `ToolCallAgent` 子类，构造参数写死：systemPrompt/nextStepPrompt/rag 模板来自 `PromptProperties`（yml），工具来自调用方合并。
- 自定义 agent 的 systemPrompt 来自 DB、nextStepPrompt 可空（空则用内置兜底）、工具要按绑定过滤。

**方案**：新增 `AgentExecutor`（`service` 层组件）负责「按 agent 记录拼装一个 `ToolCallAgent` 实例」，生成后可 `run()` 或 `runStream()`。`MyAgent` 保留给 `/manus/chat` 不走，或直接让 `/manus/chat` 也走 AgentExecutor（推荐后者，消除重复逻辑）。

### 2.2 AgentExecutor 职责

```java
@Service
public class AgentExecutor {

    private final UserAgentService userAgentService;
    private final UserAgentSkillMapper userAgentSkillMapper;   // 或 service
    private final UserAgentMcpMapper userAgentMcpMapper;       // 或 service
    private final UserMcpToolManager userMcpToolManager;
    private final UserChatModelManager userChatModelManager;
    private final ModelRouter modelRouter;
    private final ToolCallback[] allTools;
    private final VectorStore vectorStore;
    private final RedisTemplate<String, byte[]> redisTemplate;
    private final PromptProperties promptProperties;

    /** 构建可执行 agent（不含会话，供 stream/run 复用） */
    public ToolCallAgent buildAgent(Long userId, Long agentId, ToolCallback[] extraTools) {
        UserAgent agent = userAgentService.getValidated(agentId);   // 归属 + enabled 校验
        // 1. 拼 systemPrompt = agent.systemPrompt + 各绑定 skill 提示词
        List<UserSkillDto> skills = userAgentSkillMapper.selectSkillsByAgentId(userId, agentId);
        String systemPrompt = buildSystemPrompt(agent, skills);

        // 2. 模型
        ModelEnum modelEnum = ModelEnum.fromProvider(agent.getProvider());  // 新增辅助方法
        ChatModel chatModel = userChatModelManager.getChatModel(userId, modelEnum, agent.getModelName());

        // 3. 工具 = 系统工具 + agent绑定的 MCP 工具（不含未绑定的）
        ToolCallback[] agentMcpTools = userMcpToolManager.getToolsForUserByServerIds(userId, boundMcpServerIds);
        ToolCallback[] tools = merge(merge(allTools, agentMcpTools), extraTools);

        // 4. ChatOptions（provider 专属，DEEPSEEK 关内置工具执行）
        ChatOptions chatOptions = modelRouter.createChatOptions(modelEnum, tools);

        // 5. 装配 ToolCallAgent
        ToolCallAgent a = new ToolCallAgent(tools, chatOptions);
        a.setName(agent.getAgentName());
        a.setUserId(userId);
        a.setVectorStore(vectorStore);
        a.setChatMemory(new RedisChatMemory(redisTemplate));
        a.setSystemPrompt(systemPrompt);
        a.setNextStepPrompt(StringUtils.hasText(agent.getNextStepPrompt())
                ? agent.getNextStepPrompt() : promptProperties.getAgent().getNextStep());
        a.setRagPrefix(promptProperties.getRag().getPrefix());
        a.setRagSuffix(promptProperties.getRag().getSuffix());
        a.setRagSeparator(promptProperties.getRag().getSeparator());
        a.setMaxSteps(20);
        a.setChatClient(ChatClient.builder(chatModel).defaultAdvisors(new MyLoggerAdvisor()).build());
        return a;
    }

    /** 流式对话：SSE 给前端 */
    public SseEmitter chat(Long userId, Long agentId, String message, String chatId) {
        try {
            ToolCallAgent agent = buildAgent(userId, agentId, null);
            return agent.runStream(message, chatId);
        } catch (LlmNotConfiguredException e) {
            return buildErrorEmitter(e.getMessage());   // 复用 AiController 里的思路
        }
    }
}
```

要点：
- `systemPrompt` 拼接顺序：`agent.systemPrompt` + `\n\n` + 每个 skill 的 `skillContent` 用 `\n` 串联。不要把 skillContent 原样拼在最前，让 agent 自己的提示词优先。
- `buildAgent` 里的 `userId` 通过参数传入（执行在 `CompletableFuture` 异步线程，`UserContext` ThreadLocal 可能丢失；`runStream` 内部已处理 conversationId→userId 映射，但**构造阶段**仍用显式 `userId` 参数最稳妥）。
- `ModelEnum` 增加静态 `fromProvider(String provider)`（或复用现有 `fromCode` + 归一化），把 `DASHSCOPE/DEEPSEEK` 字符串映射回枚举。

### 2.3 新增：按 serverId 子集获取 MCP 工具

`mcp/UserMcpToolManager.java` 现有 `getToolsForUser(userId)` 返回**该用户全部启用的 server** 的工具。新增：

```java
/** 只返回绑定到指定 MCP server 的工具（仍按用户缓存） */
public ToolCallback[] getToolsForUserByServerIds(Long userId, List<Long> serverIds) {
    if (serverIds == null || serverIds.isEmpty()) return new ToolCallback[0];
    // 逻辑：getToolsForUser(userId) 取全量后按 server 过滤会很浪费（全量会拉起所有 server 长连接）
    // 更优：仿 loadAndBuild，只遍历 user_agent_mcp 里绑定且 enabled 的 server 构建。
    // 若担心重复建连，可复用 clientCache（key = userId:serverId，惰性缓存天然去重）。
}
```

实现建议：参照 `UserMcpToolManager.loadAndBuild` 的 client 构建 + `McpToolUtils.getToolCallbacksFromSyncClients`，但只处理传入的 `serverIds`（需先 `selectEnabledByUserId` 校验归属 + enabled=1，防越权）。

### 2.4 Controller：`/ai/agent/chat`

`AiController` 新增（或新建 `AgentChatController`）：

```java
/**
 * 自定义 agent 流式对话，与正常 chat 共享同一会话记忆
 */
@GetMapping("/ai/agent/chat")
public SseEmitter doChatWithCustomAgent(Long agentId, String message, String chatId) {
    UserContext.registerConversationUser(chatId);
    Long userId = UserContext.getUserId();
    return agentExecutor.chat(userId, agentId, message, chatId);
}
```

### 2.5 （推荐）`.manus/chat` 走 AgentExecutor

内置 Manus 可作为一个「特殊 agent」：在 `AgentExecutor.buildAgent` 之上加一层 `buildManus(userId, modelEnum, modelName)`，用 `promptProperties.getAgent().getSystem()` + 全部 MCP 工具，逻辑与 `MyAgent.create` 等价。这消除重复，`AiController.doChatWithManus` 改为：`agentExecutor.chatAsManus(userId, message, chatId, modelEnum, modelName)`。
> 若不想动现有 manus 链路，保留 `MyAgent` 不动也完全可以，`/manus/chat` 与 `/agent/chat` 并存。推荐方案是收敛，但改动需避免影响现有功能（此改动请用户确认后再做）。

## 3. 前端实现

### 3.1 ChatRoom.vue 改造

现状（`ChatRoom.vue`）：`chatMode` 有 `'chat'`（普通 QA）和 `'agent'`（AI 智能体，写死调 manus）。`stores/chat.js` 里 `chatMode` + `selectedModel` 持久化在 localStorage。

改造方案：**不改变 chatMode 双档结构**，在 `'agent'` 档下增加一个「选择 agent」下拉：

- `chatStore` 加 `selectedAgentId`（null 表示「系统内置智能体」）。
- 顶部 agent 模式时展示 `el-select`：选项 = `agentApi.list()` 的可启用 agent + 「内置智能体（Manus）」选项。
- 发送时：
  - `selectedAgentId == null` → 走现有 `aiApi.doChatWithManus(...)`。
  - 否则 → `GET /ai/agent/chat?agentId=xxx&message=&chatId=`（`api/ai.js` 加 `doChatWithCustomAgent`，SSE 消费逻辑完全复用现有 `createSseStream`）。

细节：
- SSE 事件解析、消息气泡渲染、会话切换、历史恢复（轮询）全部复用现有逻辑，无需修改。
- `chatMode + selectedAgentId` 一起持久化（localStorage key 沿用同一模式），刷新后恢复。

## 4. 验证

1. 编译通过。
2. 前端新建一个简单 agent（提示词如「你是翻译助手」），在对话页 agent 档选择它，对话流式输出正常（think/finish 事件渲染正常）。
3. agent 绑定 1 个 MCP server 后，对话中能调用该 server 工具（tool_call/tool_result 事件正常）。
4. 未配置对应供应商 LLM 时，返回 error 事件提示配置；普通 chat（chat 档）不受影响。

## 5. 注意点

1. **异步线程与 userId**：`runStream` 用 `CompletableFuture.runAsync`，RAG 检索走 `vectorStore.similaritySearch(searchRequest)`，`Filter` 需要 userId（`BaseAgent` 已有 `userId` 字段 + conversationUserMap 兜底）。善用 `setUserId` 显式设置。
2. **SSE 协议兼容**：继续用 `AgentStepEvent{type,step,content}`，前端不改解析逻辑。
3. **RAG**：自定义 agent 也注入 `ragPrefix/RagSuffix/RagSeparator`（与 manus 一致），即 agent 也能基于用户文档回答；若不需要，可将这三段 set 为空字符串。
4. **模型枚举映射**：库中存的是 `DASHSCOPE/DEEPSEEK` 字符串，`ModelEnum.fromProvider` 需兼容（未知回退 QWEN，与 `fromCode` 行为一致）。