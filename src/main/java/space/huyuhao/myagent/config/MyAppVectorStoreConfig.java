package space.huyuhao.myagent.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.huyuhao.myagent.rag.MyAppDocumentLoader;

import java.util.List;

@Configuration
@ConditionalOnProperty(name = "vector.store.type", havingValue = "simple")
public class MyAppVectorStoreConfig {

    @Bean
    VectorStore vectorStore(EmbeddingModel dashscopeEmbeddingModel, MyAppDocumentLoader documentLoader) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel)
                .build();
        // 加载文档
        List<Document> documents = documentLoader.loadMarkdowns();
        simpleVectorStore.add(documents);
        return simpleVectorStore;
    }
}