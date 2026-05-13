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
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import space.huyuhao.myagent.advisor.MyLoggerAdvisor;
import space.huyuhao.myagent.context.UserContext;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    /** 虚拟线程执行器，用于包装 MCP 等需要在流式线程中执行阻塞调用的工具 */
    private static final ExecutorService blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();


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
        // 将 MCP 工具包装为可安全阻塞的方式，避免在 Netty 线程上 block()
        FunctionCallback[] mcpTools = wrapForBlocking(toolCallbackProvider.getToolCallbacks());
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .tools(mergeToolCallbacks(allTools, mcpTools))
                .advisors(new QuestionAnswerAdvisor(milvusVectorStore))
                .stream()
                .content();
    }

    /** 将工具回调包装为可在 reactive 线程中安全执行的方式，通过虚拟线程执行阻塞调用 */
    private static FunctionCallback[] wrapForBlocking(FunctionCallback[] originals) {
        return Arrays.stream(originals)
                .map(tool -> new FunctionCallback() {
                    @Override
                    public String getName() {
                        return tool.getName();
                    }
                    @Override
                    public String getDescription() {
                        return tool.getDescription();
                    }
                    @Override
                    public String getInputTypeSchema() {
                        return tool.getInputTypeSchema();
                    }
                    @Override
                    public String call(String toolInput) {
                        try {
                            return blockingExecutor.submit(() -> tool.call(toolInput))
                                    .get(120, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException("MCP tool call failed: " + tool.getName(), e);
                        }
                    }
                })
                .toArray(FunctionCallback[]::new);
    }

    /** 合并两个工具数组 */
    private static FunctionCallback[] mergeToolCallbacks(FunctionCallback[] tools1, FunctionCallback[] tools2) {
        FunctionCallback[] merged = new FunctionCallback[tools1.length + tools2.length];
        System.arraycopy(tools1, 0, merged, 0, tools1.length);
        System.arraycopy(tools2, 0, merged, tools1.length, tools2.length);
        return merged;
    }





}