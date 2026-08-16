# 修复 Agent 对话刷新后多气泡问题

## 一、问题现象

第一次与 Agent 对话时，回复渲染正常：

- 思考过程以**折叠卡片**展示（「第 N 步思考过程」，可展开查看思考文字、工具调用、工具结果）；
- 最终回复是一个**单个气泡**。

但刷新页面（或退出后重新进入该会话）后，同一条 Agent 回复变成**多个气泡**，样式与实时对话时明显不一致。

## 二、根因分析

问题的本质是「实时流式渲染」与「刷新后历史渲染」走了两条不同的代码路径，二者的消息建模方式不一致。

### 2.1 实时流式路径（正常）

前端 `ChatRoom.vue` 的 `sendMessage` 中，只 push **一条** ai 消息，把后端通过 SSE 推送的结构化事件（`think` / `tool_call` / `tool_result` / `finish` / `answer` 等）全部追加进这条消息的 `events` 数组：

```js
// ChatRoom.vue sendMessage 内
const aiMessageIndex = messages.value.length
messages.value.push({ role: 'ai', content: '', time: getCurrentTime() })
// ... 流式读取中：
if (!aiMsg.events) aiMsg.events = []
aiMsg.events.push(value)   // 事件追加进同一条 ai 消息
```

模板渲染时（`ChatRoom.vue` 模板 138 行）命中 `message.events.length > 0` 的结构化分支，用 `el-collapse` 渲染「折叠卡片 + 单个回复气泡」。

### 2.2 刷新后历史路径（异常）

刷新后，`ChatRoom.vue` 的 `onMounted` / `enterSession` 从后端 `GET /chat-memory/conversations/{id}` 拉取历史消息，仅还原 `role` / `text` / `timestamp`，**没有 `events` 字段**：

```js
messages.value = res.data.map(msg => ({
  role: msg.role === 'USER' ? 'user' : 'ai',
  content: msg.text || '',
  time: formatTimestamp(msg.timestamp)
}))
```

同时，后端持久化时把**多条** ASSISTANT 消息存进了 Redis：

1. `ToolCallAgent.act()` 每一步工具调用都会执行 `setMessageList(toolExecutionResult.conversationHistory())`（`ToolCallAgent.java:173`），向 `messageList` 追加一条「思考 + 工具调用」的 AssistantMessage 和一条工具结果消息。
2. `RedisChatMemory.SlimMessage.from()`（`RedisChatMemory.java:41-51`）只过滤了 `TOOL` 消息，**保留了所有 `ASSISTANT` 消息**（包括每一步的中间思考）。
3. `BaseAgent.persistNewMessages()`（`BaseAgent.java:266`）把用户消息之后的所有消息一次性落库。

最终 Redis 里一条 Agent 回复对应「1 条 USER + 多条 ASSISTANT（每一步思考各一条）」，前端 map 成多条 `{role:'ai'}` → 渲染成**多个气泡**。

### 2.3 一句话总结

> 思考过程（think/tool_call/tool_result）只存在于流式 SSE 内存里、没有落库；而落库的是每一步思考的 AssistantMessage，导致刷新后一条回复退化成多个纯文本气泡。

## 三、解决方案

把 Agent 一次回复的完整结构化事件序列持久化到 Redis 里那条 ASSISTANT 消息上（给 `SlimMessage` 新增 `events` 字段），前端历史加载时还原 `events`、命中既有结构化渲染分支。工具调用结果在持久化前**截断**（过长用 `...` 代替）。

### 3.1 后端改动

#### 1) `chatmemory/RedisChatMemory.java`

- `SlimMessage` record 新增第 4 个字段 `List<Map<String, Object>> events`（可空），补充 `import java.util.Map;`。

  > 说明：`events` 用 `Map` 而非 `AgentStepEvent` 类型，是为了避免 `chatmemory` 包反向依赖 `agent.model` 包，保持依赖单向。

- `SlimMessage.from(Message)` 两处 `new SlimMessage(...)` 补第 4 参 `null`（纯文本路径不变）。
- `toMessage()` 保持不变：只返回 `text`，`events` 不进入 LLM 上下文（`chatMemory.get` 加载历史记忆时不受影响）。
- 新增方法：

```java
public void addAgentExchange(String conversationId,
                             String userText,
                             String assistantText,
                             List<Map<String, Object>> events) {
    // 构造 USER + ASSISTANT(带 events) 两条 SlimMessage 追加到 key
    // 逻辑参照现有 add()，含 isNew 时设置会话名
}
```

#### 2) `agent/model/BaseAgent.java`

- `runStream` 循环前声明 `List<AgentStepEvent> allEvents = new ArrayList<>()`；循环内 `allEvents.addAll(stepEvents)`；`max_steps` 与 `error` 分支构造的事件也先 `allEvents.add` 再 `emitter.send`。
- 改造 `persistNewMessages(int startIndex, String userPrompt, List<AgentStepEvent> allEvents)`：
  - 若 `chatMemory instanceof RedisChatMemory`：提取最终回答 → 截断 `tool_result` → `allEvents` 转 `List<Map<String,Object>>` → 调用 `addAgentExchange`。
  - 否则回退原纯文本 `chatMemory.add(...)` 逻辑（保持通用性）。
- 新增辅助方法：
  - `extractFinalAnswer(allEvents)`：优先取最后一个 `finish` 事件 content；无 `finish` 时回退非空占位（确保 `text` 非空，前端 `v-if="message.content"` 才能命中结构化分支，避免走 typing 动画）。
  - `truncateToolResult(content)`：超过 `MAX_TOOL_RESULT_LENGTH` 时截断并追加 `...`。
  - `eventToMap(AgentStepEvent)`：转成 `{type, step, content}` 的 Map。
- 新增常量 `MAX_TOOL_RESULT_LENGTH = 500`。
- 补充 import：`space.huyuhao.myagent.chatmemory.RedisChatMemory`、`java.util.HashMap`、`java.util.Map`。
- `runStream` finally 调用改为 `persistNewMessages(savedIndex, userPrompt, allEvents)`。

### 3.2 前端改动

#### `frontend/.../src/views/ChatRoom.vue`

`enterSession`（约 503-507 行）与 `onMounted`（约 686-690 行）两处历史消息 map 各加一行：

```js
messages.value = res.data.map(msg => ({
  role: msg.role === 'USER' ? 'user' : 'ai',
  content: msg.text || '',
  events: msg.events || undefined,   // 新增：还原结构化事件
  time: formatTimestamp(msg.timestamp)
}))
```

其余函数（`groupedEvents` / `getAnswerText` / `hasThinkOrToolEvents` 等）无需改动。

## 四、旧数据处理

旧会话数据（现显示为多个气泡的）由用户直接删除，不做迁移：在 Redis 中删除 `chat:memory:*` 前缀的 key（或 `flushdb`）。新对话自动使用新结构。

`SlimMessage` 的 `events` 可空 + `@JsonIgnoreProperties(ignoreUnknown = true)` 已保证对残留旧数据的反序列化兼容。

## 五、涉及文件清单

| 文件 | 改动 |
|------|------|
| `backend/my-agent/src/main/java/space/huyuhao/myagent/chatmemory/RedisChatMemory.java` | `SlimMessage` 加 `events` 字段；新增 `addAgentExchange` |
| `backend/my-agent/src/main/java/space/huyuhao/myagent/agent/model/BaseAgent.java` | `runStream` 累积事件；改造 `persistNewMessages`；新增截断/提取辅助方法 |
| `frontend/my-agent-frontend/src/views/ChatRoom.vue` | 两处历史消息 map 加 `events` 字段 |

## 六、验证方式

1. 启动后端 + 前端（`npm run dev`）。
2. 新建 Agent 会话，发送一个会触发工具调用的请求，观察实时流式：折叠卡片 + 单气泡正常。
3. 刷新页面（或退出重进该会话），确认历史回复恢复为「折叠卡片 + 单个气泡」，思考过程可展开，工具结果内容已截断（末尾 `...`）。
4. 用 `redis-cli` 查看对应 `chat:memory:*` key，确认 ASSISTANT 消息携带 `events` 字段、`tool_result` 已截断。
5. 回归：chat 模式（非 agent）历史加载仍正常显示单气泡；连续多轮对话后 LLM 上下文记忆正常。

## 七、后续补充：验证时发现并修复的三个问题（2026-08-16）

第一批修复上线验证时，又暴露了三个相关问题，一并修复如下。

### 7.1 刷新后只剩结果，丢失思考过程与工具调用

**现象**：修复「多气泡」后，刷新页面（或退出重进会话），Agent 回复退化成单个纯文本气泡，只显示最终回答，思考过程 / 工具调用 / 工具结果全部丢失。

**根因**：前端 `ChatRoom.vue` 的 `chatMode` 是纯内存状态（`const chatMode = ref('chat')`），无任何持久化，刷新后重置为默认 `'chat'`。而模板渲染结构化分支的前置条件是 `chatMode === 'agent'`，刷新后不命中，走 `else` 纯文本分支，只渲染 `message.content`（即后端 `extractFinalAnswer` 提取的最终回答）。切回 Agent 模式后能恢复显示，证明 `events` 本身落库正常，纯粹被 `chatMode` 挡住。

**修复**：把 `chatMode` 持久化到 localStorage。
- 初始化：`const chatMode = ref(localStorage.getItem('chatMode') || 'chat')`
- 切换时保存：`onModeChange` 里 `localStorage.setItem('chatMode', chatMode.value)`

### 7.2 实时对话中工具结果未截断

**现象**：实时流式对话时，前端展示的工具结果是完整内容、未截断；而持久化到 Redis 的记忆是截断的，两者不一致。

**根因**：`truncateToolResult` 截断只作用在持久化路径（`BaseAgent.persistNewMessages`）；实时 SSE 推送路径（`runStream` 里 `emitter.send(stepEvents)`）推送的是 `act()` 返回的完整原始结果，未经过截断。

**修复**：在事件源头截断——`ReActAgent.executeStepWithEvents` 生成 `tool_result` 事件时直接 `.content(truncateToolResult(actResult))`。源头截断后实时推送与持久化天然一致；同时删除 `persistNewMessages` 里冗余的二次截断逻辑。

### 7.3 切换 Chat 模式丢失 Agent 气泡格式

**现象**：Agent 会话对话后，把模式切到 Chat，已有的 Agent 消息结构化气泡（折叠卡片）退化成纯文本。

**根因**：渲染判断耦合了全局 `chatMode`（`v-if="chatMode === 'agent' && message.role === 'ai'"`），一旦切到 `'chat'`，所有 ai 消息命中 `else` 分支。

**修复**：渲染判断改为基于消息自身的 `events` 字段（外层 `v-if="message.role === 'ai'"`，内层再判断 `message.events && message.events.length > 0`）。`chatMode` 现在只负责「新消息走哪个 API」和「用户气泡颜色」，不再影响历史消息的渲染格式。

### 7.4 后续新增 / 修改的文件

| 文件 | 改动 |
|------|------|
| `backend/my-agent/src/main/java/space/huyuhao/myagent/agent/model/ReActAgent.java` | `tool_result` 事件源头截断 |
| `backend/my-agent/src/main/java/space/huyuhao/myagent/agent/ToolCallAgent.java` | 工具结果日志截断 |
| `backend/my-agent/src/main/java/space/huyuhao/myagent/agent/model/BaseAgent.java` | 移除持久化路径冗余的二次截断 |
| `frontend/my-agent-frontend/src/views/ChatRoom.vue` | `chatMode` 持久化；渲染判断去 `chatMode` 依赖 |
