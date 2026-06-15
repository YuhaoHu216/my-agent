# API 连接异常处理方案

## 问题描述

当 DashScope API 出现网络异常（如 `Connection reset`）时，Agent 模式会陷入重试循环，前端一直显示加载状态（"卡住"），而不是及时返回友好的错误提示。

### 典型错误日志

```
WARN  r.netty.http.client.HttpClientConnect:
  The connection observed an error
  java.net.SocketException: Connection reset

ERROR c.a.c.a.d.e.DashScopeEmbeddingModel:
  Error embedding request: [你好]
  org.springframework.web.client.ResourceAccessException:
  I/O error on POST request for "https://dashscope.aliyuncs.com/...": Connection reset
```

## 根因分析

### 当前调用链（Agent 模式）

```
前端 fetch SSE → AiController.doChatWithManus()
  → MyAgent.runStream()
    → for 循环 (最多 maxSteps=20 次)
      → ReActAgent.step()
        → ToolCallAgent.think()  ← 调用 DashScope API
          → ❌ Connection reset 异常
          → catch 后返回 ""（空字符串）  ← 问题点！
        → thinkResult 为空 → 返回 "思考完成 - 无需行动"
      → emitter.send("Step N: 思考完成 - 无需行动")
    → 循环继续，下一步再次调用 think() → 再次失败...
    → 重复 20 次后才结束
```

**关键问题在 `ToolCallAgent.think()` 第 98-103 行：**

```java
} catch (Exception e) {
    log.error(getName() + "的思考过程遇到了问题: " + e.getMessage());
    getMessageList().add(
            new AssistantMessage("处理时遇到错误: " + e.getMessage()));
    return "";  // ← 吞掉了异常，返回空字符串
}
```

API 调用失败时，`think()` 吞掉了异常并返回 `""`。上层 `ReActAgent.step()` 以为"无需行动"就继续循环。但实际上这是 API 不可用的致命错误，继续重试毫无意义。

### 为什么 Chat 模式不受影响

Chat 模式 (`MyApp.doChatByStream()`) 返回 `Flux<String>`，是 Reactive 流。API 异常会作为 `onError` 信号传播，Spring MVC 自动关闭 SSE 连接，前端 `catch` 块能正常捕获并显示错误提示。

### 异常类型判断

需要区分的"连接类异常"特征：
- `org.springframework.web.client.ResourceAccessException`
- `java.net.SocketException`（含 `Connection reset`）
- `java.io.IOException`
- Reactor Netty 的相关异常

判断方法：遍历异常 cause 链，检查是否包含上述类型。

---

## 修复方案

### 修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `agent/ToolCallAgent.java` | `think()` 不再吞异常，改为向上抛出 |
| `agent/model/ReActAgent.java` | `step()` 移除 try-catch，让异常自然传播 |
| `agent/model/BaseAgent.java` | 添加 `isConnectionError()` 工具方法；`runStream()` 和 `run()` 中区分连接错误 vs 一般错误 |
| `frontend/.../views/ManusChat.vue` | 错误提示文案改为"服务器繁忙，请稍后再试" |
| `frontend/.../views/ChatRoom.vue` | 错误提示文案改为"服务器繁忙，请稍后再试" |

### 1. `ToolCallAgent.think()` — 异常向上传播

```java
// 修改前
} catch (Exception e) {
    log.error(getName() + "的思考过程遇到了问题: " + e.getMessage());
    getMessageList().add(
            new AssistantMessage("处理时遇到错误: " + e.getMessage()));
    return "";
}

// 修改后
} catch (Exception e) {
    log.error(getName() + "的思考过程遇到了问题: " + e.getMessage(), e);
    throw new RuntimeException("AI 服务调用失败: " + e.getMessage(), e);
}
```

### 2. `ReActAgent.step()` — 移除 try-catch

```java
// 修改前
@Override
public String step() {
    try {
        String thinkResult = think();
        if (thinkResult.isEmpty()) {
            return "思考完成 - 无需行动";
        }
        act();
        return thinkResult;
    } catch (Exception e) {
        e.printStackTrace();
        return "步骤执行失败: " + e.getMessage();
    }
}

// 修改后
@Override
public String step() {
    String thinkResult = think();
    if (thinkResult.isEmpty()) {
        return "思考完成 - 无需行动";
    }
    act();
    return thinkResult;
}
```

> 说明：`step()` 不再需要 try-catch，因为异常会由上层 `runStream()` / `run()` 统一处理。`act()` 中的工具调用异常同样会向上传播。

### 3. `BaseAgent` — 添加连接错误判断 + 友好提示

#### 3.1 新增 `isConnectionError()` 方法

```java
/**
 * 判断异常是否为网络连接类错误（API 不可达、连接重置、超时等）
 */
private boolean isConnectionError(Throwable e) {
    Throwable current = e;
    while (current != null) {
        if (current instanceof java.net.SocketException
                || current instanceof java.io.IOException
                || current.getClass().getName().contains("ResourceAccessException")) {
            return true;
        }
        current = current.getCause();
    }
    return false;
}
```

#### 3.2 修改 `runStream()` 的错误处理

```java
// 修改前
} catch (Exception e) {
    state = AgentState.ERROR;
    log.error("执行智能体失败", e);
    try {
        emitter.send("执行错误: " + e.getMessage());
        emitter.complete();
    } catch (Exception ex) {
        emitter.completeWithError(ex);
    }
}

// 修改后
} catch (Exception e) {
    state = AgentState.ERROR;
    log.error("执行智能体失败", e);
    try {
        String errorMsg = isConnectionError(e)
                ? "服务器繁忙，请稍后再试"
                : "执行错误: " + e.getMessage();
        emitter.send(errorMsg);
        emitter.complete();
    } catch (Exception ex) {
        emitter.completeWithError(ex);
    }
}
```

#### 3.3 修改 `run()` 的错误处理

```java
// 修改前
return "执行错误" + e.getMessage();

// 修改后
return isConnectionError(e) ? "服务器繁忙，请稍后再试" : "执行错误" + e.getMessage();
```

### 4. 前端 — 统一错误文案

```javascript
// 修改前
messages.value[aiMessageIndex].content = '抱歉，出现了一些问题，请稍后再试。';

// 修改后
messages.value[aiMessageIndex].content = '服务器繁忙，请稍后再试';
```

涉及文件：
- `frontend/my-agent-frontend/src/views/ManusChat.vue` 第 130 行
- `frontend/my-agent-frontend/src/views/ChatRoom.vue` 第 294 行

---

## 修复后预期行为

### 正常流程（不变）
```
用户发消息 → Agent 正常运行 → 逐步返回思考/工具调用结果 → 正常结束
```

### API 异常流程（修复后）
```
用户发消息 → think() 调用 API
  → ❌ Connection reset
  → RuntimeException 向上抛出
  → step() 不捕获
  → runStream() 内层 catch 捕获
  → isConnectionError() → true
  → emitter.send("服务器繁忙，请稍后再试")
  → emitter.complete()
  → 前端收到 SSE 数据，显示 "服务器繁忙，请稍后再试"
```

**整个流程在秒级内完成，不再卡住 20 个步骤。**

### RAG 向量检索异常（不变）

`injectRagContext()` 中的向量检索失败已有容错处理：
```java
} catch (Exception e) {
    log.warn("RAG 检索失败，继续不带上下文执行: {}", e.getMessage(), e);
}
```
RAG 失败时静默降级，不影响主流程。此逻辑保持不变。