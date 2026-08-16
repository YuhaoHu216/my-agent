package space.huyuhao.myagent.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手动创建 DeepSeek 的 ChatModel Bean（OpenAI 兼容 API），
 * 用 core 模块而非 starter，避免与阿里 DashScope 的自动装配冲突。
 */
@Configuration
public class DeepSeekConfig {

    @Bean
    public OpenAiChatModel deepseekChatModel(
            @Value("${spring.ai.deepseek.api-key}") String apiKey,
            @Value("${spring.ai.deepseek.base-url}") String baseUrl,
            @Value("${spring.ai.deepseek.chat.options.model}") String model) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
    }
}
