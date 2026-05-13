# SuperBizAgent Milvus 向量数据库接入文档

## 一、整体架构

```
文件 (.txt / .md)
    |
    v
DocumentChunkService  (文档分片：按 Markdown 标题 + 段落边界切分)
    |
    v
VectorEmbeddingService  (阿里云 DashScope text-embedding-v4 生成 1024 维向量)
    |
    v
VectorIndexService  (插入 → Milvus "biz" 集合)
    |
    v
Milvus Standalone (Docker 部署，端口 19530)
    ^
    |
VectorSearchService  (查询文本 → 向量化 → 向量搜索 → 返回 TopK 结果)
```

## 二、Maven 依赖

`pom.xml` 中引入 Milvus Java SDK：

```xml
<dependency>
    <groupId>io.milvus</groupId>
    <artifactId>milvus-sdk-java</artifactId>
    <version>2.6.10</version>
</dependency>

<!-- 阿里云 DashScope SDK (文本向量化) -->
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>dashscope-sdk-java</artifactId>
            <version>2.17.0</version>
            <exclusions>
                <!-- 排除 slf4j-simple，使用 Spring Boot 默认的 Logback -->
                <exclusion>
                    <groupId>org.slf4j</groupId>
                    <artifactId>slf4j-simple</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
```

相关依赖：
- `com.alibaba:dashscope-sdk-java:2.17.0` — 文本 Embedding
- `com.google.code.gson:gson:2.10.1` — JSON 序列化

## 三、Docker 部署 Milvus（`vector-database.yml`）

使用 Docker Compose 启动 4 个服务组成 Milvus Standalone 环境：

| 服务 | 镜像 | 端口 | 用途 |
|------|------|------|------|
| `etcd` | `quay.io/coreos/etcd:v3.5.18` | 2379 | 元数据存储 |
| `minio` | `minio/minio:RELEASE.2023-03-20` | 9000/9001 | 对象存储 |
| `standalone` | `milvusdb/milvus:v2.5.10` | 19530/9091 | Milvus 主服务 |
| `attu` | `zilliz/attu:v2.5` | 8000→3000 | Web 管理界面 |

启动命令：

```bash
docker compose -f vector-database.yml up -d
```

访问 Attu 管理界面：`http://localhost:8000`

## 四、配置说明

### 4.1 `application.yml` 配置

```yaml
milvus:
  host: localhost        # Milvus 服务地址
  port: 19530            # Milvus 端口
  username: ""           # 用户名（空表示无认证）
  password: ""           # 密码
  database: default      # 数据库名
  timeout: 10000         # 连接超时（毫秒）
```

### 4.2 `MilvusProperties.java` — 配置属性绑定

`src/main/java/org/example/config/MilvusProperties.java`

- `@ConfigurationProperties(prefix = "milvus")` 自动绑定 YAML 配置
- 提供 `getAddress()` 便捷方法返回 `host:port`

### 4.3 `MilvusConstants.java` — 常量定义

`src/main/java/org/example/constant/MilvusConstants.java`

| 常量 | 值 | 说明 |
|------|-----|------|
| `MILVUS_DB_NAME` | `"default"` | 数据库名 |
| `MILVUS_COLLECTION_NAME` | `"biz"` | 集合名称 |
| `VECTOR_DIM` | `1024` | 向量维度（DashScope text-embedding-v4） |
| `ID_MAX_LENGTH` | `256` | ID 字段最大长度 |
| `CONTENT_MAX_LENGTH` | `8192` | Content 字段最大长度 |
| `DEFAULT_SHARD_NUMBER` | `2` | 默认分片数 |

## 五、核心组件详解

### 5.1 `MilvusConfig` — Bean 注册与生命周期管理

`src/main/java/org/example/config/MilvusConfig.java`

Spring `@Configuration` 类，负责：

- 创建 `MilvusServiceClient` 单例 Bean（调用 `MilvusClientFactory.createClient()`）
- 注册 `@PreDestroy` 钩子，应用关闭时自动调用 `milvusClient.close()` 释放连接

### 5.2 `MilvusClientFactory` — 客户端创建与集合初始化

`src/main/java/org/example/client/MilvusClientFactory.java`

`@Component`，是接入 Milvus 的核心初始化类。`createClient()` 方法执行三个步骤：

#### 5.2.1 创建连接

```java
ConnectParam.Builder builder = ConnectParam.newBuilder()
    .withHost(host)
    .withPort(port)
    .withConnectTimeout(timeout, TimeUnit.MILLISECONDS);
// 如果配置了用户名密码，添加认证
builder.withAuthorization(username, password);
return new MilvusServiceClient(builder.build());
```

#### 5.2.2 创建 `biz` 集合（如不存在）

Schema 包含 4 个字段：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `id` | `VarChar(256)` | 主键，UUID 字符串 |
| `vector` | `FloatVector(1024)` | 文档片段的向量表示 |
| `content` | `VarChar(8192)` | 文档片段原文 |
| `metadata` | `JSON` | 元数据（文件来源、分片信息、标题等） |

- `enableDynamicField: false`（关闭动态字段）
- `shardsNum: 2`

#### 5.2.3 创建索引

在 `vector` 字段上创建 IVF_FLAT 索引：

| 参数 | 值 |
|------|-----|
| 索引类型 | `IVF_FLAT` |
| 距离度量 | `L2`（欧氏距离） |
| 额外参数 | `{"nlist": 128}` |
| 同步模式 | `false`（异步构建） |

### 5.3 `VectorEmbeddingService` — 文本向量化

`src/main/java/org/example/service/VectorEmbeddingService.java`

- 使用阿里云 DashScope `TextEmbedding` API
- 模型：`text-embedding-v4`，输出 1024 维 `List<Float>` 向量
- API Key 从 `dashscope.api.key` 配置读取，启动时通过 `@PostConstruct` 初始化
- 支持单条文本向量化（`generateEmbedding`）和批量向量化（`generateEmbeddings`）
- `generateQueryVector(query)` 是对 `generateEmbedding` 的别名封装

### 5.4 `DocumentChunkService` — 文档分片

`src/main/java/org/example/service/DocumentChunkService.java`

将长文档智能切分为语义完整的片段：

1. **按 Markdown 标题分割**：识别 `#` ~ `######` 标题，每个标题段落作为一个 Section
2. **按段落边界分割**：每个 Section 内按 `\n\n` 分段落
3. **大小控制**：遵守 `document.chunk.max-size: 800` 字符上限
4. **重叠窗口**：相邻分片保留 100 字符重叠，在句子边界处截断

### 5.5 `VectorIndexService` — 数据写入（建库）

`src/main/java/org/example/service/VectorIndexService.java`

提供完整的文档索引流水线：

#### 目录索引 `indexDirectory(directoryPath)`

- 扫描目录下的 `.txt` 和 `.md` 文件
- 逐个调用 `indexSingleFile()`，返回 `IndexingResult`（含成功/失败统计）

#### 单文件索引 `indexSingleFile(filePath)`

1. **读取文件**：`Files.readString(path)`
2. **删除旧数据**：根据 `metadata["_source"] == filePath` 表达式删除
3. **文档分片**：调用 `DocumentChunkService.chunkDocument()`
4. **生成向量 + 插入 Milvus**：逐片调用嵌入服务，构建元数据，写入 Milvus

#### 插入 Milvus `insertToMilvus()`

```java
// 1. 加载集合（幂等操作）
milvusClient.loadCollection(...);

// 2. 生成确定性 UUID
UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes());

// 3. 构造 InsertParam，包含 id / content / vector / metadata 四个字段
milvusClient.insert(insertParam);
```

#### 元数据结构

```json
{
  "_source": "/data/docs/product.md",   // 文件路径（正斜杠统一格式）
  "_extension": ".md",                   // 文件扩展名
  "_file_name": "product.md",            // 文件名
  "chunkIndex": 0,                       // 分片序号
  "totalChunks": 5,                      // 总分片数
  "title": "产品功能概述"                 // 所在章节标题（可选）
}
```

### 5.6 `VectorSearchService` — 向量搜索（查询）

`src/main/java/org/example/service/VectorSearchService.java`

```java
public List<SearchResult> searchSimilarDocuments(String query, int topK) {
    // 1. 查询文本向量化
    List<Float> queryVector = embeddingService.generateQueryVector(query);

    // 2. 构建搜索参数
    SearchParam searchParam = SearchParam.newBuilder()
        .withCollectionName("biz")
        .withVectorFieldName("vector")
        .withVectors(Collections.singletonList(queryVector))
        .withTopK(topK)
        .withMetricType(MetricType.L2)
        .withOutFields(List.of("id", "content", "metadata"))
        .withParams("{\"nprobe\": 10}")
        .build();

    // 3. 执行搜索 + 解析结果
    R<SearchResults> response = milvusClient.search(searchParam);
}
```

搜索参数说明：

| 参数 | 值 | 说明 |
|------|-----|------|
| `metricType` | `L2` | 欧氏距离，与索引保持一致 |
| `nprobe` | `10` | 搜索时探测的聚类数量 |
| `topK` | 调用方指定 | 返回最相似的 K 个结果 |

返回值 `SearchResult` 包含：`id`、`content`、`score`、`metadata`

### 5.7 `MilvusCheckController` — 健康检查接口

`src/main/java/org/example/controller/MilvusCheckController.java`

```
GET /milvus/health
```

- 成功 (200)：返回 `{"message": "ok", "collections": ["biz"]}`
- 失败 (503)：返回错误信息

## 六、数据流完整示例

### 建库流程（写入）

```
POST /api/index/directory?path=./docs
    → VectorIndexService.indexDirectory("./docs")
        → 扫描 .txt / .md 文件
        → 逐文件调用 indexSingleFile()
            → Files.readString(file)
            → DocumentChunkService.chunkDocument(content)
                → 按标题分割 Section
                → 按段落边界进一步切分
                → 控制 max-size=800 + overlap=100
            → 逐片调用：
                → VectorEmbeddingService.generateEmbedding(chunk)
                    → DashScope text-embedding-v4 → 1024维 Float 向量
                → MilvusClient.insert(id, content, vector, metadata)
```

### 查询流程

```
POST /api/search?query=产品功能&topK=5
    → VectorSearchService.searchSimilarDocuments("产品功能", 5)
        → VectorEmbeddingService.generateQueryVector("产品功能")
            → DashScope text-embedding-v4 → 1024维 Float 向量
        → MilvusClient.search(collection="biz", vector, topK=5, metric=L2)
        → 返回 List<SearchResult> (id, content, score, metadata)
```

## 七、关键设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 向量维度 | 1024 | 与 DashScope text-embedding-v4 模型输出一致 |
| 索引类型 | IVF_FLAT | 平衡搜索精度与性能，适合中等规模数据 |
| 距离度量 | L2（欧氏距离） | 配合 FloatVector 使用，IVF_FLAT 推荐搭配 |
| nlist | 128 | 聚类单元数，控制索引构建速度与搜索精度 |
| nprobe | 10 | 搜索时探测的聚类数，平衡速度与召回率 |
| 分片策略 | Markdown 标题 + 段落边界 | 保持语义完整性，避免从句子中间截断 |
| 分片大小 | 800 字符 | 适配 embedding 模型的上下文窗口 |
| 重叠窗口 | 100 字符 | 相邻分片间保留上下文，提高检索连贯性 |
| UUID 生成 | `UUID.nameUUIDFromBytes` | 基于文件路径+分片序号确定性生成，重复索引时自动去重 |
| 数据更新策略 | 先删后插（按 `_source` 删除旧数据） | 保证文件重新索引时数据一致性 |

## 八、常用操作

### 启动 Milvus

```bash
docker compose -f vector-database.yml up -d
```

### 查看 Milvus 状态

```bash
curl http://localhost:9091/healthz
```

### 查看集合信息

```bash
curl http://localhost:9900/milvus/health
```

### 删除 biz 集合（重新建库）

运行 `src/main/java/org/example/tool/DropCollection.java` 的 main 方法，或通过 Attu 管理界面操作。
