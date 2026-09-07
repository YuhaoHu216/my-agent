package space.huyuhao.myagent.util;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import space.huyuhao.myagent.agent.model.AgentStepEvent;

import java.io.IOException;

/** SSE 流式事件构造工具 */
public final class SseEmitterUtil {

    private SseEmitterUtil() {
    }

    /** 构造只发一个 error 事件的 SseEmitter（对话/编排构建失败时提示给前端） */
    public static SseEmitter buildError(String message) {
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