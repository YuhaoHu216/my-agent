package space.huyuhao.myagent.model;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 根据 ModelEnum 路由到对应的 ChatModel，并生成 provider 专属的 ChatOptions。
 */
@Slf4j
@Component
public class ModelRouter {

    private final Map<ModelEnum, ChatModel> chatModels;

    public ModelRouter(@Qualifier("dashscopeChatModel") ChatModel dashscopeChatModel,
                       @Qualifier("deepseekChatModel") ChatModel deepseekChatModel) {
        this.chatModels = Map.of(
                ModelEnum.QWEN, dashscopeChatModel,
                ModelEnum.DEEPSEEK, deepseekChatModel
        );
        // 启动时打印已注册的模型映射，方便调试模型切换
        chatModels.forEach((model, chatModel) -> log.info("[模型路由] 已注册模型: code={}, provider={}, modelName={}",
                model.getCode(), model.getProvider(), resolveModelName(chatModel)));
    }

    public ChatModel getChatModel(ModelEnum model) {
        return chatModels.get(model);
    }

    /** 返回模型实际名称（用于日志），未显式配置时回退为 "default" */
    public String getModelName(ModelEnum model) {
        return resolveModelName(chatModels.get(model));
    }

    private static String resolveModelName(ChatModel chatModel) {
        String modelName = chatModel.getDefaultOptions().getModel();
        return modelName != null ? modelName : "default";
    }

    /**
     * 生成 provider 专属的 ChatOptions，供 Agent 模式（ToolCallAgent）手动管理工具调用。
     * <p>
     * DashScope 沿用 withProxyToolCalls(true)；DeepSeek（OpenAI 兼容）关闭内部工具执行，
     * 由 ReAct 循环手动调用 ToolCallingManager 执行工具。
     */
    public ChatOptions createChatOptions(ModelEnum model, ToolCallback[] tools) {
        return switch (model.getProvider()) {
            case DASHSCOPE -> DashScopeChatOptions.builder()
                    .withProxyToolCalls(true)
                    .build();
            case DEEPSEEK -> OpenAiChatOptions.builder()
                    .toolCallbacks(tools)
                    .internalToolExecutionEnabled(false)
                    .build();
        };
    }
}
