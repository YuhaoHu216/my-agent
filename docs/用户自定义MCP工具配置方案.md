# 用户自定义 MCP 工具配置方案

> 日期：2026-08-20

## Context（背景）

当前系统的 MCP 工具是**静态预装**的：通过 `spring.ai.mcp.client.stdio.servers-configuration` 指向 `mcp-servers.json`（仅预装了 amap-maps 高德地图，stdio/npx 方式），应用启动时由 Spring AI 自动装配成一个固定的 `ToolCallbackProvider` Bean，Agent 模式与 Chat 模式都使用它。

需求：改为**用户自行配置自己的 MCP 服务**——不在系统里预装任何 MCP，用户在前端页面管理自己的 MCP 服务，后端按登录用户动态加载其启用的服务并把工具合并进 Agent/Chat。

**用户已确认的决策：**
1. **仅支持 SSE/HTTP URL 方式**，同时移除现有 stdio 方式（不再用 command/args/env）
2. **完全移除预装的高德 amap-maps**，全部 MCP 由用户自配
3. MCP 工具在 **Agent 模式（/ai/manus/chat）和 Chat 模式（/ai/my_app/chat/sse/one）都生效**

## 技术可行性（已用 javap 确认，MCP SDK 0.7.0）

- `io.modelcontextprotocol.client.transport.HttpClientSseClientTransport(String url)` 可直接 new
- `McpClient.sync(transport).requestTimeout(Duration).build()` → `McpSyncClient`（`initialize()` / `listTools()` / `close()`）
- `org.springframework.ai.mcp.McpToolUtils.getToolCallbacksFromSyncClients(McpSyncClient...)` → `List<ToolCallback>`
- `spring-ai-mcp:1.0.0-M6` 核心库已含上述类，去掉 starter 后仍可用（mcp:0.7.0 由其传递引入）
- Agent 模式工具在 `CompletableFuture.runAsync`（ForkJoin 公共池）中执行，阻塞 MCP 调用安全，无需 wrapForBlocking；Chat 模式在 Netty reactive 线程执行，**必须**保留现有 `wrapForBlocking`

---

## 一、后端

### 1. 数据库（`src/main/resources/my_agent.sql` 末尾追加）

```sql
-- MCP 服务配置表（用户自定义 SSE MCP 服务）
DROP TABLE IF EXISTS user_mcp_server;

CREATE TABLE user_mcp_server (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    server_name VARCHAR(100) NOT NULL COMMENT '服务名称(同一用户内唯一)',
    url VARCHAR(500) NOT NULL COMMENT 'MCP 服务 SSE 端点地址',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
    connect_status TINYINT DEFAULT 0 COMMENT '连接状态: 0-未检测, 1-连接正常, 2-连接失败',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_server_name (user_id, server_name),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户MCP服务配置表';
```

> 部署时需在现有库手动执行一次该 DDL（项目无迁移工具）。删除为硬删除（配置无审计需求）。

### 2. 分层代码（参照现有 UserDocument 的 MyBatis-Plus 模式）

**Entity `entity/UserMcpServer.java`**：`@Data` + `@TableName("user_mcp_server")`，字段与表一一对应（id 用 `@TableId(type = IdType.AUTO)`，userId/serverName/url/enabled/connectStatus/createTime/updateTime）。

**Mapper `mapper/UserMcpServerMapper.java` + `resources/mapper/UserMcpServerMapper.xml`**，接口 `extends BaseMapper<UserMcpServer>`，自定义方法：
- `selectByUserId(Long userId)` — 列表
- `selectEnabledByUserId(Long userId)` — 仅 enabled=1（懒加载工具用）
- `selectByUserIdAndId(userId, id)` — 所有权校验
- `selectByUserIdAndName(userId, serverName)` — 重名校验

**DTO `dto/UserMcpServerRequest.java`**：id（修改时传）、`@NotBlank serverName`、`@NotBlank @Pattern(regexp = "^https?://.+") url`、enabled。
**DTO `dto/UserMcpServerDto.java`**：id/serverName/url/enabled/connectStatus/createTime/updateTime。

**Service `service/UserMcpServerService.java` + `impl/UserMcpServerServiceImpl.java`**，方法返回 `ResponseResult<T>`（已存在于 `dto/ResponseResult.java`）：
- `list()` — `selectByUserId(UserContext.getUserId())` 映射 DTO
- `add(request)` — 重名校验 → insert（enabled 默认 1、connectStatus=0）→ `userMcpToolManager.invalidate(userId)`
- `update(request)` — 所有权校验 → 仅改 serverName/url → updateById → invalidate
- `delete(id)` — 所有权校验 → deleteById → invalidate
- `toggle(id)` — 所有权校验 → 翻转 enabled → updateById → invalidate
- `test(id)` — 所有权校验 → `userMcpToolManager.testConnection(url)` → 按结果更新 connectStatus → 返回 `success("连接成功，发现 N 个工具")` 或 `error(...)`

### 3. 核心：动态 MCP 工具管理器 `mcp/UserMcpToolManager.java`（新建包 `space.huyuhao.myagent.mcp`）

作用：按用户从库中加载启用的 SSE 服务 → 复用长连接客户端 → 用 `McpToolUtils` 转成 `ToolCallback[]`。替换原 `ToolCallbackProvider` 的职责。

**缓存结构**：
- `toolsCache: ConcurrentHashMap<Long, ToolCallback[]>` — userId → 合并后的工具数组
- `clientCache: ConcurrentHashMap<String, McpSyncClient>` — key=`userId:serverId` → SSE 长连接
- `userLocks: ConcurrentHashMap<Long, Object>` — 每用户一把锁，双重检查锁防并发重复建连

**核心方法**：
- `ToolCallback[] getToolsForUser(Long userId)` — 缓存命中直接返回；未命中持锁 `loadAndBuild` 后入缓存

> **2026-08-20 修订：支持 Streamable HTTP**。实测高德 amap 端点（`https://mcp.amap.com/mcp`）只接受 POST JSON-RPC（Streamable HTTP），对传统 SSE 的 GET 握手返回 405，导致 mcp-0.7.0 的 `HttpClientSseClientTransport` 报 "Failed to wait for the message endpoint"。故新增 `mcp/HttpClientStreamableClientTransport.java`（基于 mcp-0.7.0 `ClientMcpTransport` 接口自写：POST JSON-RPC，解析 JSON/SSE 响应，捕获 `Mcp-Session-Id`），并在 `buildClient` 中自动探测协议（POST ping 成功 → Streamable，否则回退 SSE）。
- `loadAndBuild(userId)` — 查 `selectEnabledByUserId` → 逐个 `getOrCreateClient` → 收集成功客户端 → `McpToolUtils.getToolCallbacksFromSyncClients(clients).toArray(...)`
- `buildClient(url)` — 自动探测协议后建连：POST `ping` 若得到合法 JSON-RPC 响应则用 Streamable HTTP transport，否则回退传统 SSE；再 `McpClient.sync(transport).requestTimeout(...).build()` + `client.initialize()`
- `getOrCreateClient(userId, server)` — 缓存取/建 `McpSyncClient`（`buildClient(url)` + `client.initialize()` 验证）。**单服务 try/catch：失败仅 log.warn 并更新 connectStatus=2 后返回 null，跳过该服务，不拖垮整体**；成功更新 connectStatus=1
- `invalidate(Long userId)` — 关闭并移除该用户全部客户端 + 清工具缓存（Service 增删改/启停后调用）
- `McpTestResult testConnection(String url)` — 临时建连 + `listTools()` 数工具，`finally` 关闭，不进入缓存
- `@PreDestroy shutdown()` — 停机关闭所有连接

**配置项**：`application.yml` 新增 `my-agent.mcp.request-timeout-ms: 30000`（`@Value` 读取）。

### 4. 对接改造（替换 ToolCallbackProvider，全库仅 3 处引用）

- **`controller/AiController.java`**：删除 `ToolCallbackProvider` 字段与 import，新增 `@Resource UserMcpToolManager`。`doChatWithManus` 中改为：
  ```java
  Long userId = UserContext.getUserId();
  ToolCallback[] mcpTools = userMcpToolManager.getToolsForUser(userId);
  MyAgent myAgent = MyAgent.create(allTools, mcpTools, modelRouter, modelEnum, vectorStore, redisTemplate, promptProperties);
  ```
- **`app/MyApp.java`**：删除 `ToolCallbackProvider` 字段与 import，新增 `@Resource UserMcpToolManager`。`doChatByStream` 中改为（Chat 模式需保留 wrapForBlocking；`getToolsForUser` 返回的 `ToolCallback[]` 是 `FunctionCallback` 子类型，可直接传 `wrapForBlocking`）：
  ```java
  Long userId = UserContext.getUserIdByConversationId(chatId);
  ToolCallback[] mcpTools = userMcpToolManager.getToolsForUser(userId);
  FunctionCallback[] mcpToolsBlocking = wrapForBlocking(mcpTools);
  ...
  .tools(mergeToolCallbacks(allTools, mcpToolsBlocking))
  ```
- **`agent/MyAgent.java`**：`create` 签名由 `ToolCallbackProvider toolCallbackProvider` 改为 `ToolCallback[] mcpTools`，删除强转，`mergeToolCallbacks(allTools, mcpTools)`。

### 5. 移除静态配置

- **`pom.xml`**：删除 `spring-ai-mcp-client-spring-boot-starter` 依赖；新增 `spring-ai-mcp:1.0.0-M6`（提供 McpToolUtils，传递引入 mcp:0.7.0）
- **`application.yml`**：删除 `spring.ai.mcp.*`（mcp 与 retry 块）；新增 `my-agent.mcp.request-timeout-ms`
- **`application-dev.yml` / `application-prod.yml`**：删除两处 `spring.ai.mcp.client.stdio.servers-configuration` 块
- **删除文件**：`src/main/resources/mcp-servers.json`、`mcp-servers-linux.json`；更新 `resources/docker/dockerfile` 中相关注释
- **清理**：grep 确认无 `ToolCallbackProvider` 残留引用

### 6. API 端点 `controller/UserMcpServerController.java`（`@RestController @RequestMapping("/mcp")`，走 JWT，参照 UserDocumentController 风格）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/mcp/list` | 我的 MCP 服务列表 |
| POST | `/mcp/add` | 新增 |
| PUT | `/mcp/update` | 修改名称/地址 |
| DELETE | `/mcp/{id}` | 删除 |
| PUT | `/mcp/toggle/{id}` | 启停切换 |
| POST | `/mcp/test/{id}` | 测试连接（更新 connectStatus，返回工具数） |

### 7. 服务详情与工具调试（2026-08-20 追加）

在 `/mcp` 下新增两个端点，供前端查看某服务的工具列表并直接调用工具调试：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/mcp/{id}/tools` | 获取服务工具列表（name/description/inputSchema） |
| POST | `/mcp/{id}/tools/call` | 调用工具（body: `{toolName, arguments}`），返回执行结果文本 |

新增 DTO：`McpToolInfoDto`（name/description/inputSchema）、`McpToolCallRequest`（`@NotBlank toolName`、`arguments` Map）、`McpCallResultDto`（success/content）。

`UserMcpToolManager` 新增公开方法（复用 `getOrCreateClient` 连接与缓存）：
- `listTools(userId, server)` — `client.listTools()` 遍历 Tool，用 Jackson 序列化为 Map 提取字段（`McpSchema.JsonSchema` 为包私有类，不能直接访问其方法）
- `callTool(userId, server, toolName, arguments)` — `client.callTool(new CallToolRequest(...))`，解析 `TextContent.text()` 拼接返回文本

Service 层先做所有权校验（`selectByUserIdAndId`），Manager 连接失败抛异常由 Service 转 `ResponseResult.error`。

---

## 二、前端（Vue3 + Element Plus）

### 1. `src/api/mcp.js`（复用 `@/utils/request`，自动带 JWT）

```js
export const mcpApi = {
  list: () => request.get('/mcp/list'),
  add: (data) => request.post('/mcp/add', data),
  update: (data) => request.put('/mcp/update', data),
  deleteById: (id) => request.delete(`/mcp/${id}`),
  toggle: (id) => request.put(`/mcp/toggle/${id}`),
  test: (id) => request.post(`/mcp/test/${id}`),
}
```

### 2. `src/views/McpConfig.vue`（独立全页，与 ChatRoom 同级）

- 顶部：标题「MCP 服务配置」+「新增服务」按钮
- `el-table`：服务名称、服务地址（`show-overflow-tooltip`）、启用开关（`el-switch` 直接调 toggle）、连接状态（`el-tag`：0 未检测/info、1 正常/success、2 失败/danger）、创建时间、操作（测试/编辑/删除）
- 新增/编辑 `el-dialog` + `el-form`：serverName、url（校验 http/https 开头）、enabled switch；提交区分 add/update 后刷新
- 测试连接：调 `mcpApi.test(id)`，`ElMessage` 展示结果，刷新列表
- 删除：`ElMessageBox.confirm` 二次确认

### 3. `src/router/index.js`

在 `/chat-room` 后新增：
```js
{
  path: '/mcp-config',
  name: 'McpConfig',
  component: () => import('../views/McpConfig.vue'),
  meta: { title: 'MCP 配置', auth: true },
},
```

### 4. `src/views/ChatRoom.vue` 顶栏入口

在 `header-right` 的用户下拉菜单中、「退出登录」前加一项：`<el-dropdown-item @click="router.push('/mcp-config')">MCP 配置</el-dropdown-item>`（`SetUp` 图标加入 `@element-plus/icons-vue` import）。

### 5. 服务详情与工具调试（2026-08-20 追加）

- `src/api/mcp.js` 新增：`listTools(id)`、`callTool(id, {toolName, arguments})`
- 新建 `src/views/McpServerDetail.vue`：大弹窗左右布局——左侧工具列表（点击选中），右侧按 `inputSchema.properties` 动态生成参数表单（enum→select、boolean→switch、number→input-number、string→input、array/object→textarea JSON），「执行」按钮调 `mcpApi.callTool`，下方 `<pre>` 展示返回结果（`success=false` 时红色样式）
- `src/views/McpConfig.vue` 操作列新增「详情」按钮，挂载 `McpServerDetail` 组件

---

## 三、实现顺序

1. DDL（my_agent.sql）+ Entity + Mapper + XML → 编译
2. DTO + Service 接口与实现（含 Manager 的 invalidate/test 依赖占位）→ 编译
3. `mcp/UserMcpToolManager.java` → 编译
4. `UserMcpServerController.java` → 编译
5. 对接改造：AiController / MyApp / MyAgent（去掉 ToolCallbackProvider）→ 编译
6. 移除静态配置：pom.xml、三个 yml、删除两个 json、dockerfile 注释 → `mvn -q clean compile` 全量验证 + grep 无 ToolCallbackProvider 残留
7. 前端：mcp.js、McpConfig.vue、router、ChatRoom 入口 → `npm run build` 验证

## 四、验证（仅编译/构建，不跑测试）

- 后端：`mvn -q clean compile`（确认去掉 starter 后 `org.springframework.ai.mcp.McpToolUtils`、`io.modelcontextprotocol.client.*` 仍可解析）
- 前端：`npm run build`
- 冒烟（可选）：启动后端日志不再有 npx/stdio MCP 初始化；登录后 `/mcp/list` 返回空列表；新增一个 SSE 服务并测试连接