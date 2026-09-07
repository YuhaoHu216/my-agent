package space.huyuhao.myagent.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import space.huyuhao.myagent.app.MyApp;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.model.ModelEnum;
import space.huyuhao.myagent.service.AgentExecutor;
import space.huyuhao.myagent.service.OrchestratorExecutor;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private MyApp myApp;

    @Resource
    private AgentExecutor agentExecutor;

    @Resource
    private OrchestratorExecutor orchestratorExecutor;

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
     * 流式调用 Manus 超级智能体（系统内置智能体），与正常 chat 共享同一会话记忆。
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message, String chatId,
                                      @RequestParam(defaultValue = "qwen") String model,
                                      @RequestParam(required = false) String modelName) {
        ModelEnum modelEnum = ModelEnum.fromCode(model);
        UserContext.registerConversationUser(chatId);
        Long userId = UserContext.getUserId();
        log.info("[Agent] 使用模型: code={}, provider={}, modelName={}",
                modelEnum.getCode(), modelEnum.getProvider(), modelName);
        return agentExecutor.chatAsManus(userId, message, chatId, modelEnum, modelName);
    }

    /**
     * 流式调用自定义 agent，按 agent 配置参数化构建实例，与正常 chat 共享同一会话记忆。
     */
    @GetMapping("/agent/chat")
    public SseEmitter doChatWithCustomAgent(Long agentId, String message, String chatId) {
        UserContext.registerConversationUser(chatId);
        Long userId = UserContext.getUserId();
        return agentExecutor.chat(userId, agentId, message, chatId);
    }

    /**
     * 流式调用编排器：主 agent 动态委派子 agent 并汇总输出。
     */
    @GetMapping("/orchestrator/chat")
    public SseEmitter doChatWithOrchestrator(Long orchestratorId, String message, String chatId) {
        UserContext.registerConversationUser(chatId);
        Long userId = UserContext.getUserId();
        return orchestratorExecutor.chat(userId, orchestratorId, message, chatId);
    }
}

