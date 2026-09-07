package space.huyuhao.myagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import space.huyuhao.myagent.agent.ToolCallAgent;
import space.huyuhao.myagent.config.PromptProperties;
import space.huyuhao.myagent.entity.UserAgent;
import space.huyuhao.myagent.entity.UserOrchestrator;
import space.huyuhao.myagent.exception.LlmNotConfiguredException;
import space.huyuhao.myagent.model.ModelEnum;
import space.huyuhao.myagent.model.ModelRouter;
import space.huyuhao.myagent.model.UserChatModelManager;
import space.huyuhao.myagent.tool.AgentInvocationTool;
import space.huyuhao.myagent.util.SseEmitterUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 编排器执行器：把一个编排器装配成 ToolCallAgent（工具 = 系统工具 + 每个子 agent 的调用工具），
 * 流式对话运行，复用 AgentStepEvent SSE 协议。子 agent 通过 AgentInvocationTool 非流式执行。
 */
@Slf4j
@Service
public class OrchestratorExecutor {

    private final UserOrchestratorService orchestratorService;
    private final AgentExecutor agentExecutor;
    private final UserChatModelManager userChatModelManager;
    private final ModelRouter modelRouter;
    private final ToolCallback[] allTools;
    private final VectorStore vectorStore;
    private final RedisTemplate<String, byte[]> redisTemplate;
    private final PromptProperties promptProperties;

    public OrchestratorExecutor(UserOrchestratorService orchestratorService,
                                AgentExecutor agentExecutor,
                                UserChatModelManager userChatModelManager,
                                ModelRouter modelRouter,
                                ToolCallback[] allTools,
                                VectorStore vectorStore,
                                RedisTemplate<String, byte[]> redisTemplate,
                                PromptProperties promptProperties) {
        this.orchestratorService = orchestratorService;
        this.agentExecutor = agentExecutor;
        this.userChatModelManager = userChatModelManager;
        this.modelRouter = modelRouter;
        this.allTools = allTools;
        this.vectorStore = vectorStore;
        this.redisTemplate = redisTemplate;
        this.promptProperties = promptProperties;
    }

    /**
     * 流式运行编排器；构建过程中的归属/启用/未配置 LLM 异常转成 SSE error 事件。
     */
    public SseEmitter chat(Long userId, Long orchestratorId, String message, String chatId) {
        try {
            UserOrchestrator orchestrator = orchestratorService.getValidated(userId, orchestratorId);
            List<UserAgent> subAgents = orchestratorService.listAgents(userId, orchestratorId).getData();

            // 1. 工具 = 系统工具 + 每个启用子 agent 一个调用工具（子 agent 的 skill/MCP 在其内部加载）
            List<ToolCallback> tools = new ArrayList<>();
            Collections.addAll(tools, allTools);
            for (UserAgent sub : subAgents) {
                tools.add(new AgentInvocationTool(userId, sub, agentExecutor));
            }
            ToolCallback[] toolArray = tools.toArray(new ToolCallback[0]);

            // 2. systemPrompt = 编排器自身提示词 + 自动附带的子 agent 职责清单
            String systemPrompt = buildSystemPrompt(orchestrator, subAgents);

            // 3. 模型与 provider 专属 ChatOptions
            ModelEnum modelEnum = ModelEnum.fromProvider(orchestrator.getProvider());
            ChatModel chatModel = userChatModelManager.getChatModel(userId, modelEnum, orchestrator.getModelName());
            ChatOptions chatOptions = modelRouter.createChatOptions(modelEnum, toolArray);

            // 4. 装配编排器 = ToolCallAgent（复用统一装配，编排器步数放开到 25）
            ToolCallAgent agent = agentExecutor.assemble(toolArray, chatModel, chatOptions, userId,
                    orchestrator.getOrchestratorName(), systemPrompt, promptProperties.getAgent().getNextStep());
            agent.setMaxSteps(25);
            return agent.runStream(message, chatId);
        } catch (LlmNotConfiguredException | IllegalArgumentException e) {
            log.warn("编排器对话构建失败: userId={}, orchestratorId={}, err={}", userId, orchestratorId, e.getMessage());
            return SseEmitterUtil.buildError(e.getMessage());
        }
    }

    /** 编排器提示词 = 自身 systemPrompt + 子 agent 清单（让模型知道可委派对象） */
    private static String buildSystemPrompt(UserOrchestrator orchestrator, List<UserAgent> subAgents) {
        if (subAgents.isEmpty()) {
            return orchestrator.getSystemPrompt();
        }
        String subList = subAgents.stream()
                .map(a -> a.getId() + "." + a.getAgentName() + "：" + abbreviate(a.getSystemPrompt(), 50))
                .collect(Collectors.joining("\n"));
        return orchestrator.getSystemPrompt() + "\n\n你有以下可调用的子智能体：\n" + subList;
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "...";
    }
}