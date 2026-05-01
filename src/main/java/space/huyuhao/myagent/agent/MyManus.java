package space.huyuhao.myagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;
import space.huyuhao.myagent.advisor.MyLoggerAdvisor;

@Component
public class MyManus extends ToolCallAgent {

    public MyManus(ToolCallback[] allTools, ToolCallbackProvider toolCallbackProvider, ChatModel dashscopeChatModel) {
        super(mergeToolCallbacks(allTools, (ToolCallback[]) toolCallbackProvider.getToolCallbacks()));
//        super(allTools);
        this.setName("myManus");
        String SYSTEM_PROMPT = """  
                你是MyManus，一个全能的人工智能助手，旨在解决用户提出的任何任务。您可以使用各种工具来有效地完成复杂的请求。
                请全程使用中文，包括思考过程以及最终的结果输出。
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        String NEXT_STEP_PROMPT = """  
                根据用户需求，主动选择最合适的工具或工具组合。对于复杂的任务，您可以分解问题并逐步使用不同的工具来解决它。
                在使用每个工具后，清楚地解释执行结果并建议下一步。如果您想在任何时候停止交互，请使用‘ doTerminate ’工具/函数调用。
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