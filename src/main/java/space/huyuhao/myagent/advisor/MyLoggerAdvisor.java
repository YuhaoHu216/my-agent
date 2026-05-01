package space.huyuhao.myagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.model.function.FunctionCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 自定义日志 Advisor
 * 打印 info 级别日志，输出用户提示词、AI 回复、系统提示词等信息
 */
@Slf4j
public class MyLoggerAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 1;
    }

    private AdvisedRequest before(AdvisedRequest request) {
        // 记录用户输入
        log.info(">>> AI Request: {}", request.userText());
        
        // 记录系统提示词
        if (request.systemText() != null && !request.systemText().isEmpty()) {
            log.info(">>> System Prompt: {}", request.systemText());
        }
        
        // 工具的调用
//        List<FunctionCallback> functionCallbacks = request.functionCallbacks();
//        log.info(">>> Function Callbacks: {}", functionCallbacks);

        return request;
    }

    private void observeAfter(AdvisedResponse advisedResponse) {
        // 记录 AI 回复
        log.info("<<< AI Response: {}", advisedResponse.response().getResult().getOutput().getText());
    }

    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        advisedRequest = this.before(advisedRequest);                       // 前置处理 ，记录用户输入、系统提示词、工具调用
        AdvisedResponse advisedResponse = chain.nextAroundCall(advisedRequest); // 执行链式调用
        this.observeAfter(advisedResponse);                                 // 后置处理 ，记录 AI 回复
        return advisedResponse;                                             // 将响应返回给上层调用者
    }

    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        advisedRequest = this.before(advisedRequest);
        Flux<AdvisedResponse> advisedResponses = chain.nextAroundStream(advisedRequest);
        return (new MessageAggregator()).aggregateAdvisedResponse(advisedResponses, this::observeAfter);
    }
}