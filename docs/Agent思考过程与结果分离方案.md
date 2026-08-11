# Agent 思考过程与结果分离方案

## Context

当前 Agent 的 SSE 流式输出将"思考过程"和"工具执行结果"混在一个纯文本字符串中（`"Step N: {thinkResult}\n"`），前端只能通过正则 `/Step \d+:/g` 简单分割显示。用户希望将其分离为结构化的 SSE 事件，使前端可以独立展示思考过程（如可折叠面板）和执行结果。

**核心问题：** `ReActAgent.step()` 返回的只是 `thinkResult`，而 `act()` 的执行结果被丢弃了，从未发送到前端。

## 方案概述

将 SSE 推送从**纯文本**改为 **JSON 结构化事件**，定义事件类型（`think`、`tool_call`、`tool_result`、`finish`、`error`、`max_steps`、`step_start`、`step_end`），后端发送带类型的 JSON 事件，前端解析后分别渲染：思考过程放入可折叠面板，工具调用和结果在面板内分区展示，最终回答独立显示在面板外。

## 影响范围

- **Chat 模式**：完全不受影响（独立的 `Flux<String>` 代码路径）
- **非流式 `run()`**：不受影响（`step()` 方法签名不变）
- **向后兼容**：前端 `JSON.parse` 失败时自动回退为纯文本显示；旧格式消息通过 `events` 字段判空走纯文本分支
- **ManusChat.vue**：已删除，统一使用 ChatRoom.vue 的 Agent 模式

## 实现步骤

### 步骤 1：新建 `AgentStepEvent.java` 数据类

**文件：** `backend/.../agent/model/AgentStepEvent.java`

使用 Java `record` + Lombok `@Builder`，包含三个字段：
- `type` (String)：事件类型 — `think`、`tool_call`、`tool_result`、`finish`、`error`、`max_steps`、`step_start`、`step_end`
- `step` (int)：步骤编号
- `content` (String)：事件内容

提供 `toSseData()` 方法序列化为 JSON 字符串。

**实际实现注意：** Hutool 的 `JSONUtil.toJsonStr(this)` 不识别 Java `record` 的无 `get` 前缀访问器（`type()`、`step()`、`content()`），因此 `toSseData()` 改为手动构建 `JSONObject`：
```java
public String toSseData() {
    JSONObject obj = JSONUtil.createObj();
    obj.set("type", this.type);
    obj.set("step", this.step);
    obj.set("content", this.content);
    return obj.toString();
}
```

### 步骤 2：修改 `BaseAgent.java` — runStream() 发送 JSON 事件

**文件：** `backend/.../agent/model/BaseAgent.java`

1. 新增 `executeStepWithEvents(int stepNumber)` 默认方法：调用 `step()` 并包装为单一 `think` 事件（保证非 ReActAgent 子类兼容）
2. 修改 `runStream()` 中 for 循环：
   - 发送 `step_start` 事件 → 调用 `executeStepWithEvents(stepNumber)` → 逐个发送各类型事件 → 发送 `step_end` 事件
3. 所有错误提示和 max_steps 消息统一改为 `AgentStepEvent` JSON 格式

关键变化：
```java
// 旧: emitter.send("Step " + stepNumber + ": " + stepResult + "\n");
// 新: emitter.send(event.toSseData()); // JSON: {"type":"think","step":1,"content":"..."}
```

### 步骤 3：修改 `ReActAgent.java` — 覆写 executeStepWithEvents()

**文件：** `backend/.../agent/model/ReActAgent.java`

覆写 `executeStepWithEvents()` 方法，核心逻辑分为三个分支：

**分支 A：无工具调用（任务完成）**
1. 调用 `think()` 获取思考结果
2. 调用 `getToolCallInfo()` 检查是否有工具调用
3. 若无工具调用 → 从 `ToolCallAgent.getFinalAnswerText()` 获取最终回复文本，发送单一 `finish` 事件

**分支 B：仅调用了 doTerminate（如用户说"你好"，Agent 直接友好回复后结束）**
1. 通过 `isOnlyTerminateToolCall()` 判断是否只调用了 `doTerminate`
2. 若仅 doTerminate → 将 `thinkResult` 作为最终回答内容发送 `finish` 事件，**不发送思考过程和工具调用事件**，避免对简单问候展示复杂的思考面板

**分支 C：正常工具调用流程**
1. 发送 `think` 事件（仅当 `thinkResult` 非空时，因模型可能只输出工具调用而无文字思考）
2. 发送 `tool_call` 事件（工具调用详情）
3. 执行 `act()` 获取工具执行结果
4. 发送 `tool_result` 事件

**新增辅助方法：**
- `getToolCallInfo()` — 空实现，返回 `""`，由 `ToolCallAgent` 覆写
- `isOnlyTerminateToolCall(String toolCallInfo)` — 解析 JSON 数组，判断是否全部为 `doTerminate`

### 步骤 4：修改 `ToolCallAgent.java` — 提供工具调用详情

**文件：** `backend/.../agent/ToolCallAgent.java`

1. 新增字段 `private String finalAnswerText` — 存储 LLM 最终回答文本（无工具调用时）
2. 在 `think()` 中无工具调用时保存 `this.finalAnswerText = result`（若为空则默认 `"任务完成"`）
3. 覆写 `getToolCallInfo()` — 从 `toolCallChatResponse` 中提取工具名和参数，用 Hutool `JSONArray`/`JSONObject` 构建 JSON 数组返回，格式如 `[{"name":"getWeather","arguments":"{\"city\":\"北京\"}"}]`

**额外优化：NEXT_STEP_PROMPT 注入策略调整**
- 原逻辑：每一步都在消息列表中追加 `NEXT_STEP_PROMPT`
- 改为：仅第一步（`currentStep == 1`）注入，避免每一步都追加导致的 Agent 自我驱动无限循环问题

### 步骤 5：修改 `MyAgent.java` — 优化系统提示词

**文件：** `backend/.../agent/MyAgent.java`

更新 `NEXT_STEP_PROMPT`，增加对无明确需求场景的处理：
- 若用户只说了"你好"或没有明确指令 → 直接友好回复并立即调用 `doTerminate` 结束，不擅自猜测或执行任何工具
- 明确需求场景（查天气、找地点等）→ 正常工具调用流程

此改动配合步骤 3 的"分支 B"逻辑，确保前端对简单问候不展示思考面板。

### 步骤 6：修改前端 `ai.js` — SSE 解析支持 JSON

**文件：** `frontend/.../src/api/ai.js`

修改 `createSseStream()` 中 `data:` 行处理逻辑：
```javascript
const raw = line.slice(5).replace(/^ /, '')
try {
  const event = JSON.parse(raw)
  controller.enqueue(event)  // 结构化事件 → 对象
} catch {
  controller.enqueue(raw)    // 纯文本 → 字符串（向后兼容）
}
```

### 步骤 7：修改 `ChatRoom.vue` — Agent 模式事件化渲染

**文件：** `frontend/.../src/views/ChatRoom.vue`

**标题变更：** "AI 超级智能体" → "AI 智能体"

**数据结构变更：**
- AI 消息新增 `events` 数组字段（存储结构化事件）、`currentStep` 字段（当前步骤编号，用于 Loading 图标展示）
- `activeThinkingPanels` 响应式变量，控制可折叠面板展开状态

**流式处理变更（sendMessage）：**
- 检测 `value` 类型：若是对象且有 `type` 属性 → 结构化事件处理
- 过滤 `step_start` / `step_end` 标记事件（仅用于后端流程控制，前端不展示）
- 结构化事件追加到 `messages[aiIndex].events`，同时维护 `content` 文本用于向后兼容
- 字符串类型 → 追加到 `content`（Chat 模式或旧格式回退）

**模板变更：** Agent 模式的 AI 消息气泡按 `events` 判空分为两条路径：
1. **有新格式 events** → 按 step 分组渲染：
   - 有思考/工具事件的 step → `el-collapse` 可折叠面板，标题"第 N 步思考过程"，内部展示 `think` 内容 + 工具调用信息 + 工具执行结果
   - 当前正在执行的步骤 → 标题旁显示 `<Loading />` 旋转图标
   - `finish` 事件 → 面板外的最终回答
   - `error` / `max_steps` → `el-alert` 警告提示
2. **无 events（旧格式）** → 回退纯文本展示

**新增辅助函数：**
- `groupedEvents(events)` — 按 step 编号分组
- `formatToolCalls(content)` — 解析 JSON 工具调用数组为 `工具名(参数)` 格式
- `hasToolEvents(stepEvents)` — 判断步骤是否包含工具调用/结果事件
- `hasThinkOrToolEvents(stepEvents)` — 判断步骤是否有思考或工具事件
- `getStepNumber(stepEvents)` — 获取步骤编号

**新增样式：** `.step-block`、`.think-event`、`.think-collapse`、`.thinking-content`、`.tool-section`、`.tool-call-info`、`.tool-result`、`.finish-answer` 等

### 步骤 8：删除 `ManusChat.vue` 并更新路由

**删除文件：** `frontend/.../src/views/ManusChat.vue`

ManusChat 页面功能已完全合并到 ChatRoom.vue 的 Agent 模式中，不再需要独立页面。

**路由变更：** `frontend/.../src/router/index.js`
- 删除 `/manus-chat` 路由（原先重定向到 `/chat-room`）

### 附带改动：`RedisChatMemory.java` — 过滤内部消息

**文件：** `backend/.../chatmemory/RedisChatMemory.java`

`SlimMessage.from()` 方法优化：只持久化 `USER` 和 `ASSISTANT` 类型的消息，`TOOL` 等内部消息返回 `null`，调用方跳过。避免 TOOL 消息混入历史对话导致后续 LLM 调用格式异常。

## 事件类型规范

| 事件类型 | 含义 | 发送时机 | 前端展示 |
|---------|------|---------|---------|
| `step_start` | 步骤开始 | 每步执行前 | 不展示（前端过滤） |
| `step_end` | 步骤结束 | 每步执行后 | 不展示（前端过滤） |
| `think` | 思考过程 | LLM 推理输出（非空时） | 可折叠面板内 |
| `tool_call` | 工具调用 | 选定工具后 | 可折叠面板内，"调用工具"标签 |
| `tool_result` | 工具结果 | 工具执行后 | 可折叠面板内 |
| `finish` | 最终回答 | 任务完成/简单对话 | 面板外直接展示 |
| `error` | 错误 | 执行异常 | 警告提示 |
| `max_steps` | 步骤超限 | 达到最大步骤 | 警告提示 |

## 验证方案

1. **后端编译验证**：`./mvnw clean compile` 确保无编译错误
2. **Agent 模式测试**：发送有明确需求的复杂消息，确认 SSE 事件为 JSON 格式，前端正确展示思考面板和结果
3. **简单问候测试**：发送"你好"，确认仅显示最终回复文本，不展示思考面板
4. **Chat 模式回归**：切换到 Chat 模式，确认纯文本流式输出不受影响
5. **向后兼容**：旧会话历史（纯文本格式）正常展示，不会白屏崩溃
6. **错误处理**：模拟异常（如断开 Milvus 连接），确认 error 事件以 `el-alert` 正确展示
