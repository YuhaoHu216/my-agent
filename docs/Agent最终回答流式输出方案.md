# Agent 最终回答流式输出方案

## Context

当前 Agent 模式（`MyAgent` → `ToolCallAgent` → `ReActAgent` → `BaseAgent`）虽然已经通过 `runStream` 用 SSE 按「步骤/事件」粒度推送（`step_start`/`think`/`tool_call`/`tool_result`/`finish`/`step_end` 等），但 **`think()` 内部用的是阻塞的 `.call()`**，导致最终回答文本被打包在**一个 `finish` 事件里一次性返回**，前端看到的是整段文字瞬间出现，而非逐字流式。

而 Chat 模式（`MyApp.doChatByStream`）已经用 `.stream().content()` 做到了逐字流式，可作参照。

**核心问题：** `ToolCallAgent.think()` 用 `.call()` 一次性拿到完整响应，最终回答的文本没有流式分块推送给前端。

**目标（已确认）：**
1. **仅最终回答逐字流式**（打字机效果），思考过程/工具调用仍按现有折叠面板展示。
2. **保留现有前端结构**（步骤折叠面板 + 工具调用区 + 最终回答），不做大改。

## 方案概述

把 `think()` 从 `.call()` 换成 `.stream()`，在流式接收 token 的同时逐块推送给前端（新增 `answer` 事件类型）；流结束后再根据是否包含工具调用，决定本步是「最终回答」还是「思考」。前端把 `answer` 分块累积成实时滚动的最终回答文本。

## 影响范围

- **Chat 模式**：完全不受影响（独立的 `Flux<String>` 代码路径，`MyApp.doChatByStream` 已流式）。
- **非流式 `run()`**：保持可用（`think()` 无参版本委托 `think(null)`，内部改为流式后缓冲拼接，语义不变）。main 中已无 `run()` 调用，测试亦被注释。
- **向后兼容**：`answer` 是新事件类型；旧前端不识别会忽略（只显示 `finish` 完整文本）。历史会话仍走旧的 `content` 纯文本回退。

## 实现步骤

### 步骤 1：`AgentStepEvent.java` 补充 `answer` 事件类型说明

**文件：** `backend/.../agent/model/AgentStepEvent.java`

在类注释的事件类型说明中新增一行：
```
 * - answer: 最终回答的流式分块（逐字推送，前端累积拼接）
```
无需改代码（`type` 是 `String`）。

### 步骤 2：`ToolCallAgent.java` — think() 改为流式

**文件：** `backend/.../agent/ToolCallAgent.java`

- 新增重载 `public String think(Consumer<String> onToken)`，原 `think()` 改为 `return think(null);`。
- 将原来：
  ```java
  ChatResponse chatResponse = getChatClient().prompt(prompt)
          .system(getSystemPrompt())
          .tools(availableTools)
          .call()
          .chatResponse();
  ```
  改为：
  ```java
  Flux<ChatResponse> responseFlux = getChatClient().prompt(prompt)
          .system(getSystemPrompt())
          .tools(availableTools)
          .stream()
          .chatResponse();
  ```
- 逐个消费分块：
  - 累加每块 `assistantMessage.getText()` 到 `fullText`，并对非空 `onToken` 调用 `onToken.accept(text)`；
  - 聚合出最终 `AssistantMessage`（完整文本 + 工具调用列表），赋值给 `this.toolCallChatResponse`。
- 后续逻辑（`toolCallList.isEmpty()` 判断、`finalAnswerText` 赋值、`getMessageList().add(...)`、`act()`/`getToolCallInfo()`）保持原语义不变。

> **聚合方式**：优先使用 Spring AI 的 `MessageAggregator`（`MyLoggerAdvisor` 已在用 `new MessageAggregator()`）把 `Flux<ChatResponse>` 聚合为完整 `ChatResponse`；若 API 不便，则手工累加 `fullText` + 取最后一块的 `getToolCalls()` 重建 `AssistantMessage`。**实现时先验证哪种方式能正确拿到工具调用。**

### 步骤 3：`ReActAgent.java` — executeStepWithEvents 透传 onToken

**文件：** `backend/.../agent/model/ReActAgent.java`

- `executeStepWithEvents(int stepNumber)` 改为 `executeStepWithEvents(int stepNumber, Consumer<String> onToken)`，把 `onToken` 透传给 `think(onToken)`。
- 其余事件组装逻辑不变：
  - 无工具调用 → `finish`（完整文本）；
  - `isOnlyTerminateToolCall` → `act()` + `finish`；
  - 正常工具调用 → `think`(若有文本) + `tool_call` + `tool_result`。

### 步骤 4：`BaseAgent.java` — runStream 构造 onToken

**文件：** `backend/.../agent/model/BaseAgent.java`

- 默认/抽象方法 `executeStepWithEvents` 签名同步增加 `Consumer<String> onToken` 参数（默认实现可忽略该参数）。
- `runStream` 循环内，为当前 `stepNumber` 构造 `onToken`，实现为：
  ```java
  Consumer<String> onToken = chunk -> {
      try {
          emitter.send(AgentStepEvent.builder()
                  .type("answer")
                  .step(stepNumber)
                  .content(chunk)
                  .build()
                  .toSseData());
      } catch (IOException e) {
          emitter.completeWithError(e);
      }
  };
  ```
  传给 `executeStepWithEvents(stepNumber, onToken)`。

> **说明**：`answer` 分块只会在「模型产出文本」时出现。Qwen 在调用工具时 `content` 通常为空，因此实践中 `answer` 几乎只在最终回答这一步流式出现，正好符合「仅最终回答逐字流式」。极少数「工具调用前带解释文字」的场景下，该段文字会先以 `answer` 流出、随后又出现 `think`/`tool_call`，属可接受的轻微冗余，后续可再优化。

### 步骤 5：`ChatRoom.vue` — 前端累积渲染流式回答

**文件：** `frontend/my-agent-frontend/src/views/ChatRoom.vue`

- `sendMessage` 中处理 `answer` 类型：与其它事件一样 push 进 `aiMsg.events`、更新 `currentStep`，但**不**拼进 `aiMsg.content`（`finish` 会带完整文本，避免重复）。
- 新增 helper：
  ```js
  const getAnswerText = (stepEvents) => {
    const finish = stepEvents.find(e => e.type === 'finish')
    if (finish) return finish.content
    return stepEvents.filter(e => e.type === 'answer').map(e => e.content).join('')
  }
  ```
- 模板中「最终回答」区（原来 `stepEvents.filter(e => e.type === 'finish')` 的 `v-for`）改为：
  ```html
  <div v-if="getAnswerText(stepEvents)" class="finish-answer">{{ getAnswerText(stepEvents) }}</div>
  ```
  （`finish-answer` 样式已存在，无需改样式。）

## 关键风险点

- **DashScope 流式 + `withProxyToolCalls`（实际解析版本为 `internalToolExecutionEnabled`）下，工具调用是否随流式分块正确送达。** 需先做最小验证：把 `think()` 切到 `.stream()` 后，确认「调用工具」的请求里 `getToolCalls()` 仍能拿到工具调用（否则工具不会执行）。若流式下工具调用拿不到，需回退为「仅最终回答用单独流式调用」的备选方案。

## 涉及文件清单

- `backend/my-agent/src/main/java/space/huyuhao/myagent/agent/model/AgentStepEvent.java`（仅注释）
- `backend/my-agent/src/main/java/space/huyuhao/myagent/agent/ToolCallAgent.java`
- `backend/my-agent/src/main/java/space/huyuhao/myagent/agent/model/ReActAgent.java`
- `backend/my-agent/src/main/java/space/huyuhao/myagent/agent/model/BaseAgent.java`
- `frontend/my-agent-frontend/src/views/ChatRoom.vue`

## 验证方式

1. **编译**：`mvn -q compile` 通过（后端）。
2. **端到端**：启动后端（`/api/ai/manus/chat`）与前端，在 Agent 模式下提问一个需要工具的多步任务：
   - 确认思考/工具调用仍按步骤折叠展示；
   - 确认最终回答是**逐字流式**出现的（而非整段瞬间出现）；
   - 确认一句闲聊（触发 `doTerminate`）也能流式回复。
3. **工具调用回归**：确认需要工具的请求（如「下载某文件」）仍能正确调用工具并返回结果。
4. **历史会话回归**：刷新/重进历史会话，消息仍能正常展示（走旧 `content` 文本回退）。