package space.huyuhao.myagent.agent.model;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Builder;

/**
 * Agent 步骤事件，用于结构化的 SSE 推送
 * <p>
 * 事件类型说明：
 * - think: 思考过程（LLM 推理输出、工具选择分析）
 * - tool_call: 选中的工具名称和参数（JSON 数组）
 * - tool_result: 工具执行结果
 * - finish: 最终回答（无需工具调用时，Agent 完成任务）
 * - error: 执行出错
 * - max_steps: 达到最大步骤限制
 * - step_start: 步骤开始标记（前端用于分组）
 * - step_end: 步骤结束标记
 *
 * @param type    事件类型
 * @param step    步骤编号
 * @param content 事件内容（文本或 JSON 字符串）
 */
@Builder
public record AgentStepEvent(
        String type,
        int step,
        String content
) {
    /**
     * 转换为 SSE data 行内容（JSON 字符串）
     * 注意：不能使用 JSONUtil.toJsonStr(this)，因为 Hutool 不识别 record 的无 get 前缀访问器
     */
    public String toSseData() {
        JSONObject obj = JSONUtil.createObj();
        obj.set("type", this.type);
        obj.set("step", this.step);
        obj.set("content", this.content);
        return obj.toString();
    }
}
