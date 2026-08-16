package space.huyuhao.myagent.agent.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import space.huyuhao.myagent.chatmemory.RedisChatMemory;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 *
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    // 核心属性
    private String name;

    // 提示
    private String systemPrompt;
    private String nextStepPrompt;

    // 状态
    private AgentState state = AgentState.IDLE;

    // 执行控制
    private int maxSteps = 10;
    private int currentStep = 0;

    // 工具结果持久化最大长度，超出部分截断
    private static final int MAX_TOOL_RESULT_LENGTH = 100;

    // LLM
    private ChatClient chatClient;

    // Memory（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();

    // 持久化 ChatMemory（可选），用于与正常 chat 共享记忆
    private ChatMemory chatMemory;
    private String conversationId;

    // RAG 向量存储（可选），volatile 确保异步线程可见
    private volatile VectorStore vectorStore;

    public void setVectorStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        if (userPrompt.isEmpty()) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        // 更改状态
        state = AgentState.RUNNING;
        // 注入 RAG 上下文
        injectRagContext(userPrompt);
        // 记录消息上下文
        messageList.add(new UserMessage(userPrompt));
        // 保存结果列表
        List<String> results = new ArrayList<>();
        try {
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step " + stepNumber + "/" + maxSteps);
                // 单步执行
                String stepResult = step();
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
            }
            // 检查是否超出步骤限制
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("Error executing agent", e);
            return "执行错误" + e.getMessage();
        } finally {
            // 清理资源
            this.cleanup();
        }
    }


    /**
     * 运行代理（流式输出），复用持久化 ChatMemory 中的历史对话
     *
     * @param userPrompt     用户提示词
     * @param conversationId 会话ID，用于加载/保存持久化记忆
     * @return SseEmitter实例
     */
    public SseEmitter runStream(String userPrompt, String conversationId) {
        this.conversationId = conversationId;
        // 创建SseEmitter，设置较长的超时时间
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 使用线程异步处理，避免阻塞主线程
        CompletableFuture.runAsync(() -> {
            try {
                if (this.state != AgentState.IDLE) {
                    safeSend(emitter, AgentStepEvent.builder()
                            .type("error")
                            .step(0)
                            .content("错误：无法从该状态运行代理: " + this.state)
                            .build()
                            .toSseData());
                    safeComplete(emitter);
                    return;
                }
                if (userPrompt.isEmpty()) {
                    safeSend(emitter, AgentStepEvent.builder()
                            .type("error")
                            .step(0)
                            .content("错误：不能使用空提示词运行代理")
                            .build()
                            .toSseData());
                    safeComplete(emitter);
                    return;
                }

                // 更改状态
                state = AgentState.RUNNING;
                // 注入 RAG 上下文
                injectRagContext(userPrompt);

                // 从持久化记忆加载历史对话
                int historySize = 0;
                if (chatMemory != null && conversationId != null) {
                    List<Message> pastMessages = chatMemory.get(conversationId, 10);
                    if (pastMessages != null && !pastMessages.isEmpty()) {
                        messageList.addAll(pastMessages);
                        historySize = pastMessages.size();
                    }
                }

                // 记录当前用户消息
                messageList.add(new UserMessage(userPrompt));
                int savedIndex = messageList.size() - 1; // 从用户消息开始算新消息
                // 立即将用户消息预写入持久化记忆，使会话在回复完成前就出现在列表
                persistUserMessage(userPrompt);
                // 累积本轮全部结构化事件，用于持久化
                List<AgentStepEvent> allEvents = new ArrayList<>();

                try {
                    for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                        int stepNumber = i + 1;
                        currentStep = stepNumber;
                        log.info("Executing step " + stepNumber + "/" + maxSteps);

                        // 发送步骤开始事件
                        safeSend(emitter, AgentStepEvent.builder()
                                .type("step_start")
                                .step(stepNumber)
                                .content("")
                                .build()
                                .toSseData());

                        // 文本分块回调：将最终回答逐字流式推送给前端（answer 事件）
                        Consumer<String> onToken = chunk -> safeSend(emitter, AgentStepEvent.builder()
                                .type("answer")
                                .step(stepNumber)
                                .content(chunk)
                                .build()
                                .toSseData());

                        // 使用结构化事件执行步骤
                        List<AgentStepEvent> stepEvents = executeStepWithEvents(stepNumber, onToken);
                        allEvents.addAll(stepEvents);
                        for (AgentStepEvent event : stepEvents) {
                            safeSend(emitter, event.toSseData());
                        }

                        // 发送步骤结束事件
                        safeSend(emitter, AgentStepEvent.builder()
                                .type("step_end")
                                .step(stepNumber)
                                .content("")
                                .build()
                                .toSseData());
                    }
                    // 检查是否超出步骤限制
                    if (currentStep >= maxSteps) {
                        state = AgentState.FINISHED;
                        AgentStepEvent maxStepsEvent = AgentStepEvent.builder()
                                .type("max_steps")
                                .step(currentStep)
                                .content("执行结束: 达到最大步骤 (" + maxSteps + ")")
                                .build();
                        allEvents.add(maxStepsEvent);
                        safeSend(emitter, maxStepsEvent.toSseData());
                    }
                    // 正常完成
                    safeComplete(emitter);
                } catch (Exception e) {
                    state = AgentState.ERROR;
                    log.error("执行智能体失败", e);
                    AgentStepEvent errorEvent = AgentStepEvent.builder()
                            .type("error")
                            .step(currentStep)
                            .content("执行错误: " + e.getMessage())
                            .build();
                    allEvents.add(errorEvent);
                    safeSend(emitter, errorEvent.toSseData());
                    safeComplete(emitter);
                } finally {
                    // 将本轮新增的消息持久化到 ChatMemory
                    persistNewMessages(savedIndex, allEvents);
                    // 清理资源
                    this.cleanup();
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        // 设置超时和完成回调
        emitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timed out");
        });

        emitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });

        return emitter;
    }

    /**
     * 在 Agent 开始执行时立即将用户消息写入持久化记忆，
     * 使会话在回复完成前就出现在列表、并供前端识别“进行中”。
     */
    private void persistUserMessage(String userText) {
        if (chatMemory == null || conversationId == null) {
            return;
        }
        try {
            if (chatMemory instanceof RedisChatMemory redisMemory) {
                redisMemory.addUserMessage(conversationId, userText);
            }
        } catch (Exception e) {
            // 预写入失败不阻断 Agent 执行，最终结果仍在 persistNewMessages 中落库
            log.warn("预写入用户消息失败: {}", e.getMessage());
        }
    }

    /**
     * 将 messageList 中从 startIndex 开始的新消息保存到持久化 ChatMemory
     */
    private void persistNewMessages(int startIndex, List<AgentStepEvent> allEvents) {
        if (chatMemory == null || conversationId == null) {
            return;
        }
        if (startIndex >= messageList.size()) {
            return;
        }

        // RedisChatMemory：仅追加 ASSISTANT（USER 已在执行开始时预写入），携带结构化事件还原单气泡
        if (chatMemory instanceof RedisChatMemory redisMemory) {
            String finalAnswer = extractFinalAnswer(allEvents);
            List<Map<String, Object>> events = new ArrayList<>();
            if (allEvents != null) {
                for (AgentStepEvent event : allEvents) {
                    events.add(eventToMap(event));
                }
            }
            redisMemory.addAssistantMessage(conversationId, finalAnswer, events);
            log.info("保存 Agent 结构化消息到会话记忆，事件数: {}", events.size());
            return;
        }

        // 其他 ChatMemory：回退纯文本持久化
        List<Message> newMessages = new ArrayList<>(messageList.subList(startIndex, messageList.size()));
        if (!newMessages.isEmpty()) {
            chatMemory.add(conversationId, newMessages);
            log.info("保存了 {} 条新消息到会话记忆", newMessages.size());
        }
    }

    /**
     * 安全发送 SSE 数据：客户端断开后发送会抛异常，此处吞掉，
     * 让 Agent 在后台继续执行并最终持久化完整结果。
     */
    private void safeSend(SseEmitter emitter, String data) {
        try {
            emitter.send(data);
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /**
     * 安全完成 SSE：客户端断开后 complete 也可能抛异常，吞掉。
     */
    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE 完成失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /**
     * 提取最终回答：优先取最后一个 finish 事件 content；无 finish 时回退非空占位。
     * 占位保证 text 非空，前端 v-if="message.content" 才能命中结构化渲染分支。
     */
    private String extractFinalAnswer(List<AgentStepEvent> allEvents) {
        if (allEvents != null) {
            for (int i = allEvents.size() - 1; i >= 0; i--) {
                AgentStepEvent event = allEvents.get(i);
                if ("finish".equals(event.type()) && event.content() != null && !event.content().isBlank()) {
                    return event.content();
                }
            }
        }
        return "已完成";
    }

    /**
     * 截断过长的工具结果，避免前端实时展示过长 / 控制台日志刷屏。
     */
    protected String truncateToolResult(String content) {
        if (content == null) {
            return null;
        }
        if (content.length() <= MAX_TOOL_RESULT_LENGTH) {
            return content;
        }
        return content.substring(0, MAX_TOOL_RESULT_LENGTH) + "...";
    }

    /**
     * 将结构化事件转换为 {type, step, content} Map，供 Redis 持久化。
     */
    private Map<String, Object> eventToMap(AgentStepEvent event) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", event.type());
        map.put("step", event.step());
        map.put("content", event.content());
        return map;
    }


    /**
     * 执行单个步骤
     *
     * @return 步骤执行结果
     */
    public abstract String step();

    /**
     * 执行单个步骤并返回结构化事件列表（用于流式 SSE 推送）
     * 默认实现：调用 step() 并包装为单一 think 事件
     * ReActAgent 子类会覆写此方法以提供 think/act 分离的事件
     *
     * @param stepNumber 当前步骤编号
     * @param onToken    文本分块回调（默认实现忽略）
     * @return 步骤事件列表
     */
    protected List<AgentStepEvent> executeStepWithEvents(int stepNumber, Consumer<String> onToken) {
        String stepResult = step();
        return List.of(AgentStepEvent.builder()
                .type("think")
                .step(stepNumber)
                .content(stepResult)
                .build());
    }

    /**
     * 注入 RAG 上下文到系统提示词
     */
    private void injectRagContext(String userPrompt) {
        if (vectorStore == null) {
            log.warn("RAG 跳过：vectorStore 未注入");
            return;
        }
        try {
            log.info("RAG 开始检索: query=\"{}\", vectorStoreType={}", userPrompt, vectorStore.getClass().getSimpleName());
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(userPrompt).topK(4).build()
            );
            if (docs != null && !docs.isEmpty()) {
                // 打印检索到的文档信息，方便排查
                for (int i = 0; i < docs.size(); i++) {
                    Document doc = docs.get(i);
                    log.info("  RAG[{}] source={}, fileName={}",
                            i + 1,
                            doc.getMetadata().getOrDefault("_source", "N/A"),
                            doc.getMetadata().getOrDefault("_file_name", "N/A"));
                }
                String context = docs.stream()
                        .map(Document::getText)
                        .collect(Collectors.joining("\n\n---\n\n"));
                String ragPrompt = "以下是与用户问题相关的参考资料：\n\n" + context
                        + "\n\n可以选择性结合这些参考资料回答用户问题。如果答案不在参考资料中，请如实告知。";
                this.systemPrompt = ragPrompt + "\n\n---\n\n" + this.systemPrompt;
                log.info("RAG 已注入 {} 条参考资料到系统提示词", docs.size());
            } else {
                log.warn("RAG 检索返回 0 条结果！query=\"{}\"，请检查向量库中是否有相关文档", userPrompt);
            }
        } catch (Exception e) {
            log.warn("RAG 检索失败，继续不带上下文执行: {}", e.getMessage(), e);
        }
    }

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }
}