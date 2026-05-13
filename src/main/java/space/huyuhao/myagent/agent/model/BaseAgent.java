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


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
                    emitter.send("错误：无法从该状态运行代理: " + this.state);
                    emitter.complete();
                    return;
                }
                if (userPrompt.isEmpty()) {
                    emitter.send("错误：不能使用空提示词运行代理");
                    emitter.complete();
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

                try {
                    for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                        int stepNumber = i + 1;
                        currentStep = stepNumber;
                        log.info("Executing step " + stepNumber + "/" + maxSteps);

                        // 单步执行
                        String stepResult = step();
                        String result = "Step " + stepNumber + ": " + stepResult + "\n";

                        // 发送每一步的结果
                        emitter.send(result);
                    }
                    // 检查是否超出步骤限制
                    if (currentStep >= maxSteps) {
                        state = AgentState.FINISHED;
                        emitter.send("执行结束: 达到最大步骤 (" + maxSteps + ")");
                    }
                    // 正常完成
                    emitter.complete();
                } catch (Exception e) {
                    state = AgentState.ERROR;
                    log.error("执行智能体失败", e);
                    try {
                        emitter.send("执行错误: " + e.getMessage());
                        emitter.complete();
                    } catch (Exception ex) {
                        emitter.completeWithError(ex);
                    }
                } finally {
                    // 将本轮新增的消息持久化到 ChatMemory
                    persistNewMessages(savedIndex);
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
     * 将 messageList 中从 startIndex 开始的新消息保存到持久化 ChatMemory
     */
    private void persistNewMessages(int startIndex) {
        if (chatMemory == null || conversationId == null) {
            return;
        }
        if (startIndex >= messageList.size()) {
            return;
        }
        List<Message> newMessages = new ArrayList<>(messageList.subList(startIndex, messageList.size()));
        if (!newMessages.isEmpty()) {
            chatMemory.add(conversationId, newMessages);
            log.info("保存了 {} 条新消息到会话记忆", newMessages.size());
        }
    }


    /**
     * 执行单个步骤
     *
     * @return 步骤执行结果
     */
    public abstract String step();

    /**
     * 注入 RAG 上下文到系统提示词
     */
    private void injectRagContext(String userPrompt) {
        if (vectorStore == null) {
            return;
        }
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(userPrompt).topK(4).build()
            );
            if (docs != null && !docs.isEmpty()) {
                String context = docs.stream()
                        .map(Document::getText)
                        .collect(Collectors.joining("\n\n---\n\n"));
                String ragPrompt = "以下是与用户问题相关的参考资料：\n\n" + context
                        + "\n\n可以选择性结合这些参考资料回答用户问题。如果答案不在参考资料中，请如实告知。";
                this.systemPrompt = ragPrompt + "\n\n---\n\n" + this.systemPrompt;
                log.info("RAG 已注入 {} 条参考资料到系统提示词", docs.size());
            }
        } catch (Exception e) {
            log.warn("RAG 检索失败，继续不带上下文执行: {}", e.getMessage());
        }
    }

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }
}