package space.huyuhao.myagent.agent.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * ReAct (Reasoning and Acting) 模式的代理抽象类
 * 实现了思考-行动的循环模式
 */
@EqualsAndHashCode(callSuper = true)
@Data
public abstract class ReActAgent extends BaseAgent {

    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 是否需要执行行动，true表示需要执行，false表示不需要执行
     */
    public abstract String think();

    /**
     * 处理当前状态并决定下一步行动（流式版本）
     * 默认实现忽略 onToken，直接调用非流式 think()；ToolCallAgent 子类会覆写以支持逐字流式。
     *
     * @param onToken 文本分块回调（可为 null）
     * @return 是否需要执行行动
     */
    public String think(Consumer<String> onToken) {
        return think();
    }

    /**
     * 执行决定的行动
     *
     * @return 行动执行结果
     */
    public abstract String act();

    /**
     * 执行单个步骤：思考和行动
     *
     * @return 步骤执行结果
     */
    @Override
    public String step() {
        try {
            // 当返回的思考结果为空时，表示无需行动
            String thinkResult = think();
            if (thinkResult.isEmpty()) {
                return "思考完成 - 无需行动";
            }
            act();  // 这里是返回工具调用的过程
            return thinkResult;  // 返回思考过程
        } catch (Exception e) {
            // 记录异常日志
            e.printStackTrace();
            return "步骤执行失败: " + e.getMessage();
        }
    }

    /**
     * 执行单个步骤并返回结构化事件列表（用于流式 SSE 推送）
     * 将思考过程和行动结果分离为独立事件，方便前端分开展示
     *
     * @param stepNumber 当前步骤编号
     * @param onToken    文本分块回调，用于最终回答的逐字流式推送
     * @return 步骤事件列表
     */
    @Override
    protected List<AgentStepEvent> executeStepWithEvents(int stepNumber, Consumer<String> onToken) {
        // 1. 思考阶段：调用 LLM 获取当前步骤的思考和工具选择（文本分块实时推送给 onToken）
        String thinkResult = think(onToken);

        // 2. 获取工具调用信息（在 think() 之后获取，因为 think() 会设置 toolCallChatResponse）
        String toolCallInfo = getToolCallInfo();

        // 3. 没有任何工具调用 → 任务完成
        if (toolCallInfo == null || toolCallInfo.isEmpty()) {
            setState(AgentState.FINISHED);
            String finishContent = "思考完成 - 无需行动";
            if (this instanceof space.huyuhao.myagent.agent.ToolCallAgent tca) {
                String finalAnswer = tca.getFinalAnswerText();
                if (finalAnswer != null && !finalAnswer.isEmpty()) {
                    finishContent = finalAnswer;
                }
            }
            return List.of(AgentStepEvent.builder()
                    .type("finish")
                    .step(stepNumber)
                    .content(finishContent)
                    .build());
        }

        // 4. 只调用了 doTerminate → 当前 thinkResult 就是最终回答，不作为"思考过程"
        if (isOnlyTerminateToolCall(toolCallInfo)) {
            act(); // 执行 doTerminate，内部会设置 FINISHED 状态
            String content = (thinkResult != null && !thinkResult.isEmpty()) ? thinkResult : "任务完成";
            return List.of(AgentStepEvent.builder()
                    .type("finish")
                    .step(stepNumber)
                    .content(content)
                    .build());
        }

        // 5. 正常工具调用流程：有实质性工具需要执行
        List<AgentStepEvent> events = new ArrayList<>();

        // 思考文字可能为空（模型只输出工具调用），此时不发送空的 think 事件
        if (thinkResult != null && !thinkResult.isEmpty()) {
            events.add(AgentStepEvent.builder()
                    .type("think")
                    .step(stepNumber)
                    .content(thinkResult)
                    .build());
        }

        events.add(AgentStepEvent.builder()
                .type("tool_call")
                .step(stepNumber)
                .content(toolCallInfo)
                .build());

        // 执行工具调用
        String actResult = act();
        events.add(AgentStepEvent.builder()
                .type("tool_result")
                .step(stepNumber)
                .content(actResult)
                .build());

        return events;
    }

    /**
     * 判断工具调用信息是否仅包含 doTerminate（无其他实质性工具）
     */
    private boolean isOnlyTerminateToolCall(String toolCallInfo) {
        if (toolCallInfo == null || toolCallInfo.isEmpty()) {
            return false;
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(toolCallInfo);
            if (arr.isEmpty()) {
                return false;
            }
            for (int i = 0; i < arr.size(); i++) {
                String name = arr.getJSONObject(i).getStr("name");
                if (!"doTerminate".equals(name)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取当前步骤的工具调用信息（JSON 格式）
     * 默认返回空，由 ToolCallAgent 覆写以提供具体的工具名和参数
     *
     * @return 工具调用信息 JSON 字符串
     */
    public String getToolCallInfo() {
        return "";
    }
}

