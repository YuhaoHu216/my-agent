package space.huyuhao.myagent.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import space.huyuhao.myagent.entity.UserAgent;
import space.huyuhao.myagent.service.AgentExecutor;

/**
 * 子 agent 即工具：把一个自定义 agent 包装成 Spring AI 工具，供编排器调用。
 * <p>
 * 工具名 {@code run_agent__<agentId>}，参数 task；执行时非流式运行子 agent 并返回其结果文本
 * （子 agent 自身 skill/MCP 能力嵌套获得，内存记忆隔离，不污染编排器会话）。
 */
public class AgentInvocationTool implements ToolCallback {

    /** task 参数的 json schema */
    private static final String TASK_SCHEMA = """
            {"type":"object","properties":{"task":{"type":"string","description":"要交给该子智能体的任务描述"}},"required":["task"]}
            """;

    private final Long userId;
    private final Long agentId;
    private final AgentExecutor agentExecutor;
    private final ToolDefinition toolDefinition;

    public AgentInvocationTool(Long userId, UserAgent agent,
                               AgentExecutor agentExecutor) {
        this.userId = userId;
        this.agentId = agent.getId();
        this.agentExecutor = agentExecutor;
        this.toolDefinition = ToolDefinition.builder()
                .name("run_agent__" + agent.getId())
                .description("将任务委派给子智能体【" + agent.getAgentName() + "】。"
                        + "职责摘要：" + abbreviate(agent.getSystemPrompt(), 120)
                        + "。参数 task 为要交给该子智能体的任务描述。")
                .inputSchema(TASK_SCHEMA)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        String task = parseTask(toolInput);
        return agentExecutor.runSync(userId, agentId, task);
    }

    /** 从工具入参 JSON 中提取 task 字段 */
    private static String parseTask(String toolInput) {
        try {
            JSONObject args = JSONUtil.parseObj(toolInput);
            return args.getStr("task");
        } catch (Exception e) {
            return toolInput;
        }
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "...";
    }
}