package space.huyuhao.myagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import space.huyuhao.myagent.advisor.MyLoggerAdvisor;
import space.huyuhao.myagent.chatmemory.RedisChatMemory;
import space.huyuhao.myagent.config.PromptProperties;

@Component
public class MyAgent extends ToolCallAgent {

    public MyAgent(ToolCallback[] allTools,
                   ToolCallbackProvider toolCallbackProvider,
                   ChatModel dashscopeChatModel,
                   VectorStore vectorStore,
                   RedisTemplate<String, byte[]> redisTemplate,
                   PromptProperties promptProperties) {
        super(mergeToolCallbacks(allTools, (ToolCallback[]) toolCallbackProvider.getToolCallbacks()));
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
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }

    private static ToolCallback[] mergeToolCallbacks(ToolCallback[] tools1, ToolCallback[] tools2) {
        ToolCallback[] merged = new ToolCallback[tools1.length + tools2.length];
        System.arraycopy(tools1, 0, merged, 0, tools1.length);
        System.arraycopy(tools2, 0, merged, tools1.length, tools2.length);
        return merged;
    }
}