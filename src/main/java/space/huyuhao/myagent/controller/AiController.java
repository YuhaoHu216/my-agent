package space.huyuhao.myagent.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import space.huyuhao.myagent.agent.MyAgent;
import space.huyuhao.myagent.agent.model.AgentStepEvent;
import space.huyuhao.myagent.app.MyApp;
import space.huyuhao.myagent.config.PromptProperties;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.exception.LlmNotConfiguredException;
import space.huyuhao.myagent.mcp.UserMcpToolManager;
import space.huyuhao.myagent.model.ModelEnum;
import space.huyuhao.myagent.model.ModelRouter;
import space.huyuhao.myagent.model.UserChatModelManager;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private MyApp myApp;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ModelRouter modelRouter;

    @Resource
    private UserMcpToolManager userMcpToolManager;

    @Resource
    private UserChatModelManager userChatModelManager;

    @Resource
    private VectorStore vectorStore;

    @Resource
    private RedisTemplate<String, byte[]> redisTemplate;

    @Resource
    private PromptProperties promptProperties;

    @GetMapping("/my_app/chat/sync")
    public String doChatWithMyAppSync(String message, String chatId) {
        return myApp.doChat(message, chatId);
    }

    /**
     * 异步调用MyApp的doChatByStream方法，返回Flux<String>
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/my_app/chat/sse/one", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithMyAppSSEOne(String message, String chatId,
                                              @RequestParam(defaultValue = "qwen") String model,
                                              @RequestParam(required = false) String modelName) {
        ModelEnum modelEnum = ModelEnum.fromCode(model);
        log.info("[Chat] 使用模型: code={}, provider={}", modelEnum.getCode(), modelEnum.getProvider());
        return myApp.doChatByStream(message, chatId, modelEnum, modelName);
    }

    /**
     * 异步调用MyApp的doChatByStream方法，返回Flux<ServerSentEvent<String>>
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/my_app/chat/sse/two", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> doChatWithMyAppSSETwo(String message, String chatId,
                                                               @RequestParam(required = false) String modelName) {
        return myApp.doChatByStream(message, chatId, ModelEnum.QWEN, modelName)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * 异步调用MyApp的doChatByStream方法，返回SseEmitter
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping("/my_app/chat/sse/emitter")
    public SseEmitter doChatWithMyAppSseEmitter(String message, String chatId,
                                                @RequestParam(required = false) String modelName) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter emitter = new SseEmitter(180000L); // 3分钟超时
        // 获取 Flux 数据流并直接订阅
        myApp.doChatByStream(message, chatId, ModelEnum.QWEN, modelName)
                .subscribe(
                        // 处理每条消息
                        chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        // 处理错误
                        emitter::completeWithError,
                        // 处理完成
                        emitter::complete
                );
        // 返回emitter
        return emitter;
    }

    /**
     * 流式调用 Manus 超级智能体，与正常 chat 共享同一会话记忆
     *
     * @param message 用户消息
     * @param chatId  会话ID，用于加载/保存持久化记忆
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message, String chatId,
                                      @RequestParam(defaultValue = "qwen") String model,
                                      @RequestParam(required = false) String modelName) {
        ModelEnum modelEnum = ModelEnum.fromCode(model);
        UserContext.registerConversationUser(chatId);
        Long userId = UserContext.getUserId();
        // 按用户自定义配置获取模型；未配置时返回 error 事件提示去配置
        ChatModel chatModel;
        try {
            chatModel = userChatModelManager.getChatModel(userId, modelEnum, modelName);
        } catch (LlmNotConfiguredException e) {
            log.warn("用户未配置 LLM: userId={}, model={}", userId, modelEnum.getCode());
            return buildErrorEmitter(e.getMessage());
        }
        log.info("[Agent] 使用模型: code={}, provider={}, modelName={}",
                modelEnum.getCode(), modelEnum.getProvider(), modelName);
        ToolCallback[] mcpTools = userMcpToolManager.getToolsForUser(userId);
        ToolCallback[] mergedTools = mergeToolCallbacks(allTools, mcpTools);
        ChatOptions chatOptions = modelRouter.createChatOptions(modelEnum, mergedTools);
        MyAgent myAgent = MyAgent.create(mergedTools, chatModel, chatOptions,
                vectorStore, redisTemplate, promptProperties);
        return myAgent.runStream(message, chatId);
    }

    /** 合并系统工具与用户 MCP 工具 */
    private static ToolCallback[] mergeToolCallbacks(ToolCallback[] tools1, ToolCallback[] tools2) {
        ToolCallback[] merged = new ToolCallback[tools1.length + tools2.length];
        System.arraycopy(tools1, 0, merged, 0, tools1.length);
        System.arraycopy(tools2, 0, merged, tools1.length, tools2.length);
        return merged;
    }

    /** 构造只发一个 error 事件的 SseEmitter（用户未配置 LLM 时提示） */
    private static SseEmitter buildErrorEmitter(String message) {
        SseEmitter emitter = new SseEmitter(30000L);
        try {
            emitter.send(AgentStepEvent.builder()
                    .type("error")
                    .step(0)
                    .content(message)
                    .build()
                    .toSseData());
        } catch (IOException ignored) {
        }
        emitter.complete();
        return emitter;
    }


}

