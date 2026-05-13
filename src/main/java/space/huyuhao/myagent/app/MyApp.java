package space.huyuhao.myagent.app;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.redis.core.RedisTemplate;
import space.huyuhao.myagent.chatmemory.RedisChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import space.huyuhao.myagent.advisor.MyLoggerAdvisor;
import space.huyuhao.myagent.context.UserContext;

import java.util.List;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;

@Component
@Slf4j
public class MyApp {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = "你是一个助理,你的master叫 Guyue,你需要回答他的一些问题,他不喜欢长篇大论+" +
                                                "回答的时候可以加一些颜文字,比如 o((>ω< ))o,不要用emoji图标";

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    @Resource
    private VectorStore milvusVectorStore;


    public MyApp(ChatModel dashscopeChatModel, RedisTemplate<String, byte[]> redisTemplate) {
        ChatMemory chatMemory = new RedisChatMemory(redisTemplate);
        // 构造方法中初始化chatClient
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        new MessageChatMemoryAdvisor(chatMemory),
                        new MyLoggerAdvisor()
                )
                .build();
    }

    // 阻塞返回的调用
    public String doChat(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .advisors(new QuestionAnswerAdvisor(milvusVectorStore))
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    public record MyReport(String title, List<String> results) {
    }

    // 限定返回格式的调用
    public MyReport doChatWithReport(String message, String chatId) {
        MyReport myReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成报告，标题为{用户名}的报告，内容为结果列表")
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .advisors(new QuestionAnswerAdvisor(milvusVectorStore))
                .call()
                .entity(MyReport.class);
        log.info("myReport: {}", myReport);
        return myReport;
    }

    // 流式调用
    public Flux<String> doChatByStream(String message, String chatId) {
        // 在请求线程上绑定 userId，解决 reactive 流切换到其他线程后 ThreadLocal 丢失的问题
        UserContext.registerConversationUser(chatId);
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .tools(allTools)
                .advisors(new QuestionAnswerAdvisor(milvusVectorStore))
                .stream()
                .content();
    }

    /**
     * 用于合并本地工具和mcp工具
     * @param tools1
     * @param tools2
     * @return
     */
    private static ToolCallback[] mergeToolCallbacks(ToolCallback[] tools1, ToolCallback[] tools2) {
        ToolCallback[] merged = new ToolCallback[tools1.length + tools2.length];
        System.arraycopy(tools1, 0, merged, 0, tools1.length);
        System.arraycopy(tools2, 0, merged, tools1.length, tools2.length);
        return merged;
    }





}