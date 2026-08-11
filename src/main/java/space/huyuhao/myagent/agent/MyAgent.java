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

@Component
public class MyAgent extends ToolCallAgent {

    public MyAgent(ToolCallback[] allTools,
                   ToolCallbackProvider toolCallbackProvider,
                   ChatModel dashscopeChatModel,
                   VectorStore vectorStore,
                   RedisTemplate<String, byte[]> redisTemplate) {
        super(mergeToolCallbacks(allTools, (ToolCallback[]) toolCallbackProvider.getToolCallbacks()));
        this.setName("myManus");
        this.setVectorStore(vectorStore);
        this.setChatMemory(new RedisChatMemory(redisTemplate));
        String SYSTEM_PROMPT = """
                你是MyAgent，一个全能的人工智能助手，旨在解决用户提出的任何任务。您可以使用各种工具来有效地完成复杂的请求。
                请全程使用中文，包括思考过程以及最终的结果输出。
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        String NEXT_STEP_PROMPT = """
                如果用户没有提出具体需求（如只说了"你好"或没有明确指令），请直接友好地回复询问用户需要什么帮助，然后立即调用 doTerminate 结束，不要擅自猜测或执行任何其他工具。
                只有在用户有明确需求时（如查天气、找地点、规划路线、下载文件等），才根据用户需求主动选择最合适的工具或工具组合。对于复杂的任务，您可以分解问题并逐步使用不同的工具来解决它。
                在使用每个工具后，清楚地解释执行结果并建议下一步。
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
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