package space.huyuhao.myagent.rag;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * PostgreSQL Vector Store 配置类
 * 配置使用 PgVector 作为向量存储的 Bean
 * 注意：当前被注释掉 @Configuration 注解，如果需要启用此配置，需要取消注释
 */
//@Configuration
public class PgVectorVectorStoreConfig {

    /**
     * 创建并配置 PgVectorStore 实例
     * 这是一个用于存储和检索向量嵌入的向量数据库实现
     *
     * @param jdbcTemplate       JDBC 模板，用于与 PostgreSQL 数据库进行交互
     * @param dashscopeEmbeddingModel 嵌入模型，用于生成文本的向量表示
     * @param batchingStrategy   批处理策略，用于优化向量存储操作的性能
     * @return 配置好的 VectorStore 实例
     */
    @Bean
    public VectorStore pgVectorVectorStore(
            JdbcTemplate jdbcTemplate, 
            EmbeddingModel dashscopeEmbeddingModel,
            BatchingStrategy batchingStrategy) {
        // 使用构建器模式创建 PgVectorStore 实例，并配置以下参数：
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1536)                    // 设置向量维度为 1536（对应 DashScope 模型输出的向量大小）
                .distanceType(COSINE_DISTANCE)       // 使用余弦距离计算向量相似度
                .indexType(HNSW)                     // 使用 HNSW 索引提高查询效率
                .initializeSchema(true)              // 自动初始化数据库表结构
                .schemaName("public")                // 指定数据库 schema 名称
                .vectorTableName("vector_store")     // 指定向量存储表名称
                .batchingStrategy(batchingStrategy)  // 应用批处理策略以优化性能
                .build();
        return vectorStore;
    }
}