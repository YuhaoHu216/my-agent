package space.huyuhao.myagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import space.huyuhao.myagent.agent.model.AgentState;
import space.huyuhao.myagent.agent.model.ReActAgent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    // 可用的工具
    private final ToolCallback[] availableTools;

    // 保存了工具调用信息的响应
    private ChatResponse toolCallChatResponse;

    // LLM 最终回答文本（无工具调用时的最终回复）
    private String finalAnswerText;

    // 工具调用管理者
    private final ToolCallingManager toolCallingManager;

    // 禁用内置的工具调用机制，自己维护上下文
    private final ChatOptions chatOptions;

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
        this.chatOptions = DashScopeChatOptions.builder()
                .withProxyToolCalls(true) // 这里设置为 true 自己维护工具调用上下文
                .build();
    }

    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 是否需要执行行动
     */
    @Override
    public String think() {
        List<Message> messageList = getMessageList();
        List<Message> promptMessages = new ArrayList<>(messageList);
        // 只在第一步注入 NEXT_STEP_PROMPT，避免每一步都追加导致 Agent 自我驱动循环
        if (getCurrentStep() == 1 && getNextStepPrompt() != null && !getNextStepPrompt().isEmpty()) {
            promptMessages.add(new UserMessage(getNextStepPrompt()));
        }
        Prompt prompt = new Prompt(promptMessages, chatOptions);
        try {
            // 获取带工具选项的响应
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .tools(availableTools)
                    .call()
                    .chatResponse();
            // 记录响应，用于 Act
            this.toolCallChatResponse = chatResponse;
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 输出提示信息
            String result = assistantMessage.getText();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            log.info(getName() + "的思考: " + result);
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s",
                            toolCall.name(),
                            toolCall.arguments())
                    )
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);
            if (toolCallList.isEmpty()) {
                // 只有不调用工具时，才记录助手消息
                getMessageList().add(assistantMessage);
                // 保存最终回答文本，供 executeStepWithEvents 中 finish 事件使用
                this.finalAnswerText = (result != null && !result.isEmpty()) ? result : "任务完成";
                return "";
            } else {
                // 需要调用工具时，无需记录助手消息，因为调用工具时会自动记录
                return result;
            }
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题: " + e.getMessage());
            getMessageList().add(
                    new AssistantMessage("处理时遇到错误: " + e.getMessage()));
            return "";
        }
    }

    /**
     * 执行工具调用并处理结果
     *
     * @return 执行结果
     */
    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具调用";
        }
        // 调用工具
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        // 记录消息上下文，conversationHistory 已经包含了助手消息和工具调用返回的结果
        setMessageList(toolExecutionResult.conversationHistory());
        // 当前工具调用的结果
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 完成了它的任务！结果: " + response.responseData())
                .collect(Collectors.joining("\n"));
        // 判断是否调用了终止工具
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));
        if (terminateToolCalled) {
            setState(AgentState.FINISHED);
        }
        log.info(results);
        return results;
    }

    /**
     * 覆写父类方法，从当前 ChatResponse 中提取工具调用信息
     * 返回 JSON 数组：[{"name": "工具名", "arguments": "参数JSON"}]
     */
    @Override
    public String getToolCallInfo() {
        if (toolCallChatResponse == null
                || !toolCallChatResponse.hasToolCalls()) {
            return "";
        }
        AssistantMessage assistantMessage = toolCallChatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
        if (toolCallList == null || toolCallList.isEmpty()) {
            return "";
        }

        // 使用 Hutool 构建工具调用信息的 JSON 数组
        JSONArray toolCallsJson = JSONUtil.createArray();
        for (AssistantMessage.ToolCall tc : toolCallList) {
            JSONObject tcObj = JSONUtil.createObj();
            tcObj.set("name", tc.name());
            tcObj.set("arguments", tc.arguments());
            toolCallsJson.add(tcObj);
        }
        return toolCallsJson.toString();
    }

    /**
     * 获取 LLM 最终回答文本（无工具调用时）
     */
    public String getFinalAnswerText() {
        return finalAnswerText;
    }

}