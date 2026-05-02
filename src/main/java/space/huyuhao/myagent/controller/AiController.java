package space.huyuhao.myagent.controller;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import space.huyuhao.myagent.agent.MyManus;
import space.huyuhao.myagent.app.MyApp;

import java.io.IOException;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private MyApp myApp;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

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
    public Flux<String> doChatWithMyAppSSEOne(String message, String chatId) {
        return myApp.doChatByStream(message, chatId);
    }

    /**
     * 异步调用MyApp的doChatByStream方法，返回Flux<ServerSentEvent<String>>
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/my_app/chat/sse/two", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> doChatWithMyAppSSETwo(String message, String chatId) {
        return myApp.doChatByStream(message, chatId)
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
    public SseEmitter doChatWithMyAppSseEmitter(String message, String chatId) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter emitter = new SseEmitter(180000L); // 3分钟超时
        // 获取 Flux 数据流并直接订阅
        myApp.doChatByStream(message, chatId)
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
     * 流式调用 Manus 超级智能体
     *
     * @param message
     * @return
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message) {
        MyManus myManus = new MyManus(allTools, toolCallbackProvider, dashscopeChatModel);
        return myManus.runStream(message);
    }


}

