package space.huyuhao.myagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import space.huyuhao.myagent.advisor.MyLoggerAdvisor;
import space.huyuhao.myagent.agent.ToolCallAgent;
import space.huyuhao.myagent.chatmemory.RedisChatMemory;
import space.huyuhao.myagent.config.PromptProperties;
import space.huyuhao.myagent.dto.UserMcpServerDto;
import space.huyuhao.myagent.dto.UserSkillDto;
import space.huyuhao.myagent.entity.UserAgent;
import space.huyuhao.myagent.exception.LlmNotConfiguredException;
import space.huyuhao.myagent.mapper.UserAgentMcpMapper;
import space.huyuhao.myagent.mapper.UserAgentSkillMapper;
import space.huyuhao.myagent.mcp.UserMcpToolManager;
import space.huyuhao.myagent.model.ModelEnum;
import space.huyuhao.myagent.model.ModelRouter;
import space.huyuhao.myagent.model.UserChatModelManager;
import space.huyuhao.myagent.util.SseEmitterUtil;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 按 agent 记录参数化构建可执行的 ToolCallAgent 实例，供自定义 agent / 系统内置智能体流式对话复用。
 * <p>
 * 一次对话 new 一个轻量实例（沿用 MyAgent.create 模式），不入 Spring 容器。
 */
@Slf4j
@Service
public class AgentExecutor {

    private final UserAgentService userAgentService;
    private final UserAgentSkillMapper userAgentSkillMapper;
    private final UserAgentMcpMapper userAgentMcpMapper;
    private final UserMcpToolManager userMcpToolManager;
    private final UserChatModelManager userChatModelManager;
    private final ModelRouter modelRouter;
    private final ToolCallback[] allTools;
    private final VectorStore vectorStore;
    private final RedisTemplate<String, byte[]> redisTemplate;
    private final PromptProperties promptProperties;

    public AgentExecutor(UserAgentService userAgentService,
                         UserAgentSkillMapper userAgentSkillMapper,
                         UserAgentMcpMapper userAgentMcpMapper,
                         UserMcpToolManager userMcpToolManager,
                         UserChatModelManager userChatModelManager,
                         ModelRouter modelRouter,
                         ToolCallback[] allTools,
                         VectorStore vectorStore,
                         RedisTemplate<String, byte[]> redisTemplate,
                         PromptProperties promptProperties) {
        this.userAgentService = userAgentService;
        this.userAgentSkillMapper = userAgentSkillMapper;
        this.userAgentMcpMapper = userAgentMcpMapper;
        this.userMcpToolManager = userMcpToolManager;
        this.userChatModelManager = userChatModelManager;
        this.modelRouter = modelRouter;
        this.allTools = allTools;
        this.vectorStore = vectorStore;
        this.redisTemplate = redisTemplate;
        this.promptProperties = promptProperties;
    }

    /**
     * 按自定义 agent 记录构建可执行 agent（不含会话，供 stream/run 复用）。
     * <p>
     * systemPrompt = agent.systemPrompt + 绑定 skill 的 skillContent；模型取自 agent.provider/modelName；
     * 工具 = 系统工具 + agent 绑定的 MCP server 工具 + extraTools。
     */
    public ToolCallAgent buildAgent(Long userId, Long agentId, ToolCallback[] extraTools) {
        UserAgent agent = userAgentService.getValidated(userId, agentId);
        // 1. systemPrompt：agent 自身提示词优先，skill 知识包拼接在后
        List<UserSkillDto> skills = userAgentSkillMapper.selectSkillsByAgentId(userId, agentId);
        String systemPrompt = buildSystemPrompt(agent.getSystemPrompt(), skills);

        // 2. 模型（库中存 provider 字符串，映射回枚举；未配置对应供应商 LLM 会抛 LlmNotConfiguredException）
        ModelEnum modelEnum = ModelEnum.fromProvider(agent.getProvider());
        ChatModel chatModel = userChatModelManager.getChatModel(userId, modelEnum, agent.getModelName());

        // 3. 工具 = 系统工具 + agent 绑定的 MCP 工具（selectMcpsByAgentId 已过滤未启用的 server）
        List<Long> serverIds = userAgentMcpMapper.selectMcpsByAgentId(userId, agentId).stream()
                .map(UserMcpServerDto::getId)
                .collect(Collectors.toList());
        ToolCallback[] mcpTools = userMcpToolManager.getToolsForUserByServerIds(userId, serverIds);
        ToolCallback[] tools = merge(allTools, merge(mcpTools, extraTools));

        // 4. provider 专属 ChatOptions（DEEPSEEK 关内置工具执行，由 ReAct 手动驱动）
        ChatOptions chatOptions = modelRouter.createChatOptions(modelEnum, tools);

        // 5. 装配：nextStepPrompt 可空，空则用内置兜底
        String nextStepPrompt = StringUtils.hasText(agent.getNextStepPrompt())
                ? agent.getNextStepPrompt() : promptProperties.getAgent().getNextStep();
        return assemble(tools, chatModel, chatOptions, userId, agent.getAgentName(),
                systemPrompt, nextStepPrompt);
    }

    /**
     * 构建系统内置智能体（Manus）：提示词来自 prompt.yml，工具 = 系统工具 + 该用户全部启用 MCP 工具。
     */
    public ToolCallAgent buildManus(Long userId, ModelEnum modelEnum, String modelName) {
        ChatModel chatModel = userChatModelManager.getChatModel(userId, modelEnum, modelName);
        ToolCallback[] mcpTools = userMcpToolManager.getToolsForUser(userId);
        ToolCallback[] tools = merge(allTools, mcpTools);
        ChatOptions chatOptions = modelRouter.createChatOptions(modelEnum, tools);
        return assemble(tools, chatModel, chatOptions, userId, "myManus",
                promptProperties.getAgent().getSystem(), promptProperties.getAgent().getNextStep());
    }

    /**
     * 非流式运行子 agent（供编排器委派调用）：最终文本作为工具结果回传，不落 Redis，记忆隔离。
     */
    public String runSync(Long userId, Long agentId, String task) {
        return buildAgent(userId, agentId, null).run(task);
    }

    /**
     * 自定义 agent 流式对话：SSE 推送给前端，与正常 chat 共享同一会话记忆。
     */
    public SseEmitter chat(Long userId, Long agentId, String message, String chatId) {
        try {
            return buildAgent(userId, agentId, null).runStream(message, chatId);
        } catch (LlmNotConfiguredException | IllegalArgumentException e) {
            log.warn("自定义 agent 对话构建失败: userId={}, agentId={}, err={}", userId, agentId, e.getMessage());
            return SseEmitterUtil.buildError(e.getMessage());
        }
    }

    /**
     * 系统内置智能体（Manus）流式对话。
     */
    public SseEmitter chatAsManus(Long userId, String message, String chatId, ModelEnum modelEnum, String modelName) {
        try {
            return buildManus(userId, modelEnum, modelName).runStream(message, chatId);
        } catch (LlmNotConfiguredException e) {
            log.warn("用户未配置 LLM: userId={}, model={}", userId, modelEnum.getCode());
            return SseEmitterUtil.buildError(e.getMessage());
        }
    }

    /** 装配 ToolCallAgent（提示词 / 模型 / 工具 / 会话记忆 / RAG 配置），供编排器等外部复用 */
    public ToolCallAgent assemble(ToolCallback[] tools, ChatModel chatModel, ChatOptions chatOptions,
                                  Long userId, String name, String systemPrompt, String nextStepPrompt) {
        ToolCallAgent agent = new ToolCallAgent(tools, chatOptions);
        agent.setName(name);
        agent.setUserId(userId);
        agent.setVectorStore(vectorStore);
        agent.setChatMemory(new RedisChatMemory(redisTemplate));
        agent.setSystemPrompt(systemPrompt);
        agent.setNextStepPrompt(nextStepPrompt);
        agent.setRagPrefix(promptProperties.getRag().getPrefix());
        agent.setRagSuffix(promptProperties.getRag().getSuffix());
        agent.setRagSeparator(promptProperties.getRag().getSeparator());
        agent.setMaxSteps(20);
        agent.setChatClient(ChatClient.builder(chatModel).defaultAdvisors(new MyLoggerAdvisor()).build());
        return agent;
    }

    /** systemPrompt 拼接：agent.systemPrompt + \n\n + 各 skill 的 skillContent（用 \n 串联），让 agent 提示词优先 */
    private static String buildSystemPrompt(String agentPrompt, List<UserSkillDto> skills) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(agentPrompt)) {
            sb.append(agentPrompt);
        }
        String skillsText = skills.stream()
                .map(UserSkillDto::getSkillContent)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
        if (!skillsText.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(skillsText);
        }
        return sb.toString();
    }

    /** 合并工具数组（容忍 null） */
    private static ToolCallback[] merge(ToolCallback[] a, ToolCallback[] b) {
        if (a == null) {
            return b == null ? new ToolCallback[0] : b;
        }
        if (b == null || b.length == 0) {
            return a;
        }
        ToolCallback[] merged = new ToolCallback[a.length + b.length];
        System.arraycopy(a, 0, merged, 0, a.length);
        System.arraycopy(b, 0, merged, a.length, b.length);
        return merged;
    }
}