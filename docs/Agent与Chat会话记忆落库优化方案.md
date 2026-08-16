# Agent 与 Chat 会话记忆落库优化方案

## 背景

本次改动统一并优化 Agent 与 Chat 两种模式的会话记忆落库逻辑，核心目标：

1. **新会话即时显示**：发送第一条消息后，新会话立即出现在左侧「历史会话」列表，无需等回复完成。
2. **结构化事件落库**：Agent 的思考 / 工具调用 / 工具结果 / 最终回答正确落库，刷新后还原为单气泡。
3. **断线不丢结果**：客户端中途断开时，Agent 仍在后台继续执行并完整落库。

## 改动概览

| 文件 | 模式 | 主要改动 |
|------|------|----------|
| `chatmemory/RedisChatMemory.java` | 公共 | 消息落库拆分为 `addUserMessage` / `addAssistantMessage`，`add` 增加 USER 幂等去重 |
| `agent/model/BaseAgent.java` | Agent | 执行开始预写 USER、SSE 安全发送、仅追加 ASSISTANT |
| `app/MyApp.java` | Chat | 流式调用前预写 USER，与 Agent 对齐 |

## 详细改动

### 1. RedisChatMemory.java —— 消息落库分步化

原 `addAgentExchange`（一次性落 USER + ASSISTANT）拆分为两个独立方法：

- `addUserMessage(conversationId, userText)`：追加一条用户消息；会话为空时以首条消息截取会话名称。Agent / Chat 在开始执行时即调用，使会话在回复完成前就出现在列表。
- `addAssistantMessage(conversationId, assistantText, events)`：追加一条助手消息（携带结构化事件），用于整轮执行结束后落库完整回答。

同时 `add(String, List<Message>)` 增加 USER 幂等去重：待写入的 USER 与当前会话最后一条消息相同（角色 + 文本一致）则跳过，避免「预写 USER + `MessageChatMemoryAdvisor.before` 再次落库同一 USER」导致重复气泡。去重仅针对 USER，不影响正常多轮对话。

### 2. BaseAgent.java —— Agent 模式会话逻辑

- `runStream` 执行开始时调用 `persistUserMessage`，立即预写 USER，使会话在回复完成前出现在列表。
- `persistNewMessages` 仅追加 ASSISTANT（USER 已在执行开始预写），携带结构化事件，刷新后还原单气泡。
- 新增 `safeSend` / `safeComplete`：客户端断开后 SSE 发送 / 完成不再抛异常，让 Agent 在后台继续执行并最终持久化完整结果。
- 原 `emitter.send(...)` / `emitter.complete()` 全部改为 `safeSend` / `safeComplete`。

### 3. MyApp.java —— Chat 模式会话逻辑

- `RedisChatMemory` 由构造函数局部变量提升为字段，供 `doChatByStream` 复用。
- `doChatByStream` 在 `registerConversationUser` 之后、返回 Flux 之前调用 `addUserMessage` 预写 USER，使新会话即时出现在历史列表，行为与 Agent 模式对齐。
- 移除不再使用的 `ChatMemory` import。

## 验证

1. 编译通过：`mvn -q -DskipTests compile`（仅验证编译，不做测试）。
2. 手动验证：
   - Agent / Chat 两种模式下，新建会话 → 发送第一条消息 → 左侧「历史会话」列表立即出现该会话（无需等回复完成）。
   - 消息无重复气泡；Agent 刷新后结构化事件还原为单气泡。
