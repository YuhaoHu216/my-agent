package space.huyuhao.myagent.config;

import io.milvus.client.MilvusServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.huyuhao.myagent.rag.*;
import space.huyuhao.myagent.service.DocumentChunkService;
import space.huyuhao.myagent.service.MilvusSearchService;
import space.huyuhao.myagent.service.VectorEmbeddingService;

import java.util.List;

@Configuration
public class MilvusVectorStoreConfig {

    private static final Logger logger = LoggerFactory.getLogger(MilvusVectorStoreConfig.class);

    @Bean
    VectorStore milvusVectorStore(MilvusServiceClient milvusClient,
                                  VectorEmbeddingService embeddingService,
                                  MilvusSearchService searchService,
                                  DocumentChunkService chunkService,
                                  MyAppDocumentLoader documentLoader) {
        MilvusVectorStore vectorStore = new MilvusVectorStore(milvusClient, embeddingService, searchService, chunkService);

        try {
            List<Document> documents = documentLoader.loadMarkdowns();
            if (!documents.isEmpty()) {
                logger.info("启动时加载 {} 个文档到 Milvus", documents.size());
                vectorStore.add(documents);
                logger.info("启动时文档入库完成");
            } else {
                logger.info("resources/documents/ 目录下未找到 .md 文档，跳过启动入库");
            }
        } catch (Exception e) {
            logger.error("启动时文档入库失败，VectorStore 仍可正常使用: {}", e.getMessage(), e);
        }

        return vectorStore;
    }
}
