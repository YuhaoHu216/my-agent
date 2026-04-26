package space.huyuhao.myagent.rag;


import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

    @Bean
    public BatchingStrategy batchingStrategy() {
        return new TokenCountBatchingStrategy(
                EncodingType.CL100K_BASE,
                8000,   // 最大 token 数（DashScope 的限制）
            0.1     // 保留 10% 的缓冲区
        );
    }
}
