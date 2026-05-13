# Spring AI MCP 工具在流式对话中的线程阻塞问题

## 问题现象

流式对话（`doChatByStream`）中，当模型调用 MCP 外部工具时，后端报错：

```
java.lang.IllegalStateException: block()/blockFirst()/blockLast() are blocking,
which is not supported in thread reactor-http-nio-2
    at reactor.core.publisher.BlockingSingleSubscriber.blockingGet(...)
    at reactor.core.publisher.Mono.block(Mono.java:1779)
    at io.modelcontextprotocol.client.McpSyncClient.callTool(McpSyncClient.java:204)
    at org.springframework.ai.mcp.SyncMcpToolCallback.call(SyncMcpToolCallback.java:110)
    at org.springframework.ai.tool.ToolCallback.call(ToolCallback.java:59)
    at org.springframework.ai.chat.model.AbstractToolCallSupport.executeFunctions(...)
    at org.springframework.ai.chat.model.AbstractToolCallSupport.handleToolCalls(...)
```

## 问题复现条件

1. 接口使用 SSE 流式返回（`ChatClient.stream().content()`）
2. 工具列表中包含 MCP 外部工具
3. 模型在对话中决定调用 MCP 工具

## 根本原因

### 完整调用链路

```
SSE 请求 → Netty 线程 (reactor-http-nio-2)
  → ChatClient.stream()
    → 模型返回 tool_call 决定
      → AbstractToolCallSupport.handleToolCalls()
        → AbstractToolCallSupport.executeFunctions()
          → SyncMcpToolCallback.call()
            → McpSyncClient.callTool()
              → Mono.block()  ← 💥 炸在这里
```

### 核心矛盾

- **流式对话**基于 WebFlux/Netty 的非阻塞 I/O 线程模型，Reactor 严禁在 `reactor-http-nio` 线程上调用 `block()` / `blockFirst()` / `blockLast()`
- **MCP 客户端**采用同步模式（`McpSyncClient`），其 `callTool()` 方法内部通过 `Mono.block()` 等待 MCP 服务返回结果
- 当模型在流式过程中决定调用 MCP 工具时，`SyncMcpToolCallback.call()` 直接在 Netty 线程上被同步调用，触发 Reactor 的线程安全检查

### 为什么 `subscribeOn` 无法解决

`subscribeOn` 只影响 Reactor 订阅（subscribe）发生的线程，但 `SyncMcpToolCallback.call()` 是 Spring AI 内部 `AbstractToolCallSupport` 在模型返回 `tool_call` 后**同步调用**的，不经过 Reactor 调度链，因此切换调度器对它无效。

## 解决方案

### 技术方案

利用 Java 21 虚拟线程（Virtual Threads），为每个 MCP 工具回调创建代理包装层：

```java
private static FunctionCallback[] wrapForBlocking(FunctionCallback[] originals) {
    return Arrays.stream(originals)
            .map(tool -> new FunctionCallback() {
                @Override
                public String call(String toolInput) {
                    try {
                        // 将阻塞调用提交到虚拟线程执行
                        return blockingExecutor.submit(() -> tool.call(toolInput))
                                .get(120, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException("MCP tool call failed: "
                                + tool.getName(), e);
                    }
                }
                // getName(), getDescription(), getInputTypeSchema() 委托给原工具
                ...
            })
            .toArray(FunctionCallback[]::new);
}
```

### 执行流程

```
Netty 线程调用 wrapper.call(input)
  → Future.get() 挂起 Netty 线程（LockSupport.park，合法）
  → 虚拟线程执行 real.call(input)
    → McpSyncClient.callTool()
      → Mono.block()  ← 发生在虚拟线程上，Reactor 允许 ✓
  → 虚拟线程返回结果
  → Future.get() 解除挂起，返回结果给 Netty 线程
```

### 关键点

| 对比项 | 直接调用 | 虚拟线程包装后 |
|--------|----------|----------------|
| `Mono.block()` 发生位置 | Netty 线程 | 虚拟线程 |
| Reactor 检查 | ❌ 抛异常 | ✅ 允许 |
| Netty 线程状态 | 异常中断 | `LockSupport.park` 挂起 |

## 涉及文件

- `src/main/java/space/huyuhao/myagent/app/MyApp.java` — 主修改文件，`doChatByStream` 方法中包装 MCP 工具
- `src/main/resources/application.yml` — MCP 客户端配置
- `src/main/resources/mcp-servers.json` — MCP 服务端配置（高德地图等）

## 技术栈

- Spring AI 1.0.0-M6
- Reactor Core 3.7.12
- Java 21 (Virtual Threads)
- Spring Boot 3.x / WebFlux

## 经验总结

1. **同步与反应的边界**：在一个以 reactive 为主的链路中引入同步阻塞组件时，必须在边界处做线程隔离
2. **不要试图改框架调度链**：`subscribeOn` / `publishOn` 只对 Reactor 操作符生效，对框架内部同步调用的工具执行不生效
3. **虚拟线程适合做"阻塞转非阻塞"的胶水层**：虚拟线程极其轻量，挂起/恢复成本远低于平台线程，非常适合用来包装"遗留"的同步阻塞调用
