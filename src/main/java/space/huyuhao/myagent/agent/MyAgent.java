package space.huyuhao.myagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.RedisTemplate;
import space.huyuhao.myagent.advisor.MyLoggerAdvisor;
import space.huyuhao.myagent.chatmemory.RedisChatMemory;
import space.huyuhao.myagent.config.PromptProperties;
import space.huyuhao.myagent.model.ModelEnum;
import space.huyuhao.myagent.model.ModelRouter;

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
     * 根据模型路由创建实例：合并工具、解析 provider 专属 ChatModel 与 ChatOptions。
     */
    public static MyAgent create(ToolCallback[] allTools,
                                 ToolCallbackProvider toolCallbackProvider,
                                 ModelRouter modelRouter,
                                 ModelEnum model,
                                 VectorStore vectorStore,
                                 RedisTemplate<String, byte[]> redisTemplate,
                                 PromptProperties promptProperties) {
        ToolCallback[] mergedTools = mergeToolCallbacks(allTools,
                (ToolCallback[]) toolCallbackProvider.getToolCallbacks());
        return new MyAgent(mergedTools,
                modelRouter.getChatModel(model),
                modelRouter.createChatOptions(model, mergedTools),
                vectorStore, redisTemplate, promptProperties);
    }

    private static ToolCallback[] mergeToolCallbacks(ToolCallback[] tools1, ToolCallback[] tools2) {
        ToolCallback[] merged = new ToolCallback[tools1.length + tools2.length];
        System.arraycopy(tools1, 0, merged, 0, tools1.length);
        System.arraycopy(tools2, 0, merged, tools1.length, tools2.length);
        return merged;
    }
}
