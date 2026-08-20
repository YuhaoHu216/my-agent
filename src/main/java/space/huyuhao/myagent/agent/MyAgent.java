package space.huyuhao.myagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.RedisTemplate;
import space.huyuhao.myagent.advisor.MyLoggerAdvisor;
import space.huyuhao.myagent.chatmemory.RedisChatMemory;
import space.huyuhao.myagent.config.PromptProperties;

public class MyAgent extends ToolCallAgent {

    private MyAgent(ToolCallback[] mergedTools,
                    ChatModel chatModel,
                    ChatOptions chatOptions,
                    VectorStore vectorStore,
                    RedisTemplate<String, byte[]> redisTemplate,
                    PromptProperties promptProperties) {
        super(mergedTools, chatOptions);
        this.setName("myManus");
        this.setVectorStore(vectorStore);
        this.setChatMemory(new RedisChatMemory(redisTemplate));
        this.setSystemPrompt(promptProperties.getAgent().getSystem());
        this.setNextStepPrompt(promptProperties.getAgent().getNextStep());
        this.setRagPrefix(promptProperties.getRag().getPrefix());
        this.setRagSuffix(promptProperties.getRag().getSuffix());
        this.setRagSeparator(promptProperties.getRag().getSeparator());
        this.setMaxSteps(20);
        // 初始化客户端
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }

    /**
     * 创建实例：工具已在调用方合并，接收已解析的 provider 专属 ChatModel 与 ChatOptions。
     */
    public static MyAgent create(ToolCallback[] mergedTools,
                                 ChatModel chatModel,
                                 ChatOptions chatOptions,
                                 VectorStore vectorStore,
                                 RedisTemplate<String, byte[]> redisTemplate,
                                 PromptProperties promptProperties) {
        return new MyAgent(mergedTools,
                chatModel,
                chatOptions,
                vectorStore, redisTemplate, promptProperties);
    }
}
