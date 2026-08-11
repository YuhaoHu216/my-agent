# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

这是一个基于 **Spring Boot 3.5.7 + Java 21** 的 AI Agent 后端系统，使用阿里云 DashScope 作为 LLM，集成了 MCP（Model Context Protocol）工具调用、Milvus 向量数据库 RAG、以及自定义的 ReAct 智能体框架。

## 常用命令

```bash
# 编译
./mvnw clean compile

# 打包
./mvnw clean package -DskipTests

# 运行（开发环境，默认 profile）
./mvnw spring-boot:run

# 运行（生产环境）
./mvnw spring-boot:run -Dspring-boot.active=prod

# 运行测试
./mvnw test

# 只运行单个测试类
./mvnw test -Dtest=MyAgentApplicationTests

# Docker Compose 启动公共基础设施（MySQL + Redis）
docker compose -f src/main/resources/docker/docker-compose-infra.yml up -d

# Docker Compose 启动 Milvus 向量数据库栈（etcd + MinIO + Milvus + Attu）
docker compose -f src/main/resources/docker/docker-compose-milvus.yml up -d

# Docker Compose 启动应用层（后端 + Nginx）
docker compose -f src/main/resources/docker/docker-compose.yml up -d
```

应用端口：`8123`，上下文路径：`/api`，Swagger 文档：`http://localhost:8123/api/doc.html`

## 核心架构

### 两大 AI 交互模式

**1. MyApp（简单对话助手）** — `app/MyApp.java`
- 基于 Spring AI `ChatClient` + `MessageChatMemoryAdvisor` 的对话机器人
- 支持 RAG（通过 `QuestionAnswerAdvisor` 连接 Milvus VectorStore）
- 三种调用方式：同步阻塞 (`doChat`)、流式 Flux (`doChatByStream`)、流式 SseEmitter
- MCP 工具在流式调用时通过**虚拟线程**包装，解决 Netty 线程中阻塞调用的问题

**2. MyAgent（ReAct 自主智能体）** — `agent/MyAgent.java`
- 自定义 ReAct 框架：`BaseAgent` → `ReActAgent` → `ToolCallAgent` → `MyAgent`
- `think()` + `act()` 循环，最多 20 步，通过 `TerminateTool.doTerminate()` 结束
- 禁用 Spring AI 内置工具调用（`proxyToolCalls=true`），自行维护消息上下文
- 每一步的思考结果通过 **SSE** 流式推送给前端(`runStream`)
- RAG 上下文在运行开始时自动注入系统提示词

### 向量存储方案

- **MilvusVectorStore**（`rag/MilvusVectorStore.java`）— **当前激活的主方案**，实现 `VectorStore` 接口，直接使用 `MilvusServiceClient` 进行向量 CRUD
- `PgVectorVectorStoreConfig.java` — PostgreSQL PgVector 方案，**已被注释禁用**
- `MyAppVectorStoreConfig.java` — 内存 SimpleVectorStore 方案，**已被注释禁用**
- 向量嵌入使用 DashScope `text-embedding-v3`，通过 `VectorEmbeddingService` 调用

### 三层记忆系统

1. **会话上下文** (`messageList` in `BaseAgent`)：ReAct Agent 单次运行的短期消息列表
2. **持久化 ChatMemory** (`RedisChatMemory`)：Redis 存储，按 `userId:conversationId` 隔离，使用 Jackson 序列化 `SlimMessage`（只保留 role+text）
3. **FileBasedChatMemory**：Kryo 序列化到文件，主要用于调试/备份

### 用户认证

- JWT 拦截器 (`JwtInterceptor`) 注册在 `WebConfig`，排除 `/user/login`、`/user/register`、`/health`、Swagger 路径
- `UserContext` 使用 ThreadLocal 存储 userId/username，并使用 `ConcurrentHashMap` 做 `conversationId → userId` 映射，解决 reactive 流跨线程问题
- 登录/注册接口无需认证，生成 JWT token 返回

### MCP 工具

- 配置文件：`mcp-servers.json`（开发/Windows 用 `npx.cmd`）、`mcp-servers-linux.json`（生产/Docker 用 `npx`）
- 已接入：高德地图 MCP Server (`@amap/amap-maps-mcp-server`)
- Dockerfile 中预装了 Node.js 和 npm 包，确保容器中有 npx 可用

### 自定义工具（Spring AI `@Tool` 注解）

- `FileOperationTool`：文件读写，保存到 `uploads/file/` 目录
- `ResourceDownloadTool`：从 URL 下载资源，保存到 `uploads/download/` 目录
- `TerminateTool`：终止 ReAct Agent 循环

### 数据库

- **MySQL**：用户表 (`user`)、用户文档表 (`user_document`)，使用 MyBatis Plus ORM
- **Redis**：会话记忆、缓存，Lettuce 连接池
- **Milvus**：向量存储，collection 名为 `my_agent_knowledge`（定义在 `MilvusConstants`）

### API 端点概览

| 前缀 | 控制器 | 说明 |
|------|--------|------|
| `/ai/*` | `AiController` | AI 对话（同步/SSE/MyApp/Manus） |
| `/user/*` | `UserController` | 注册/登录/登出/用户信息 |
| `/document/*` | `UserDocumentController` | 文档上传/列表/搜索/删除/下载（走 Milvus 向量化） |
| `/chat-memory/*` | `ChatMemoryController` | 会话列表/消息历史/重命名/删除 |
| `/health` | `HealthController` | 健康检查（无需认证） |
| `/milvus/health` | `MilvusCheckController` | Milvus 连接检查（无需认证） |

### Knowledge Base 文档

`resources/documents/` 和 `resources/other/` 目录存放 Markdown 知识库文档，可在启动时通过 `MyAppDocumentLoader.loadMarkdowns()` 加载到向量库。

### 部署架构

Docker Compose 启动 8 个服务：nginx（前端）→ my_agent（后端）→ {mysql, redis, milvus(standalone) + etcd + minio}，外部访问端口 `5173`，网络名 `my_agent`。

## 项目依赖关键版本

| 依赖 | 版本 | 用途 |
|------|------|------|
| Spring AI Alibaba Starter | 1.0.0-M6.1 | LLM 接入层 |
| Spring AI MCP Client | 1.0.0-M6 | MCP 工具调用 |
| MyBatis Plus | 3.5.10.1 | MySQL ORM |
| Knife4j | 4.4.0 | API 文档 |
| Milvus SDK Java | 2.6.10 | 向量数据库客户端 |
| DashScope SDK | 2.17.0 | 文本向量化 |
| jjwt | 0.12.6 | JWT 认证 |
| Hutool | 5.8.37 | 通用工具 |

## 注意事项

- 环境变量 `ALI_API_KEY` 必须设置，DashScope 和嵌入服务共用
- `PgVectorStoreAutoConfiguration` 已在 `@SpringBootApplication` 中被排除，避免自动配置冲突
- MCP 流式调用场景下，`MyApp.wrapForBlocking()` 使用虚拟线程执行器包装 MCP 工具，避免在 Netty 线程上 `block()` 导致阻塞
- 前端项目位于 `../../frontend/my-agent-frontend`（不在本仓库内）