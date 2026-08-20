package space.huyuhao.myagent.model;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import space.huyuhao.myagent.entity.UserLlmConfig;
import space.huyuhao.myagent.exception.LlmNotConfiguredException;
import space.huyuhao.myagent.mapper.UserLlmConfigMapper;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户自定义 LLM 模型管理器：按用户从库中加载供应商的 api-key，
 * 动态构建并缓存 ChatModel（缓存维度为 userId:provider:modelName），
 * 配置变更时 invalidate 使下次请求重建。
 */
@Slf4j
@Component
public class UserChatModelManager {

    private static final String DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com";

    /** key = "userId:provider:modelName" -> ChatModel */
    private final ConcurrentHashMap<String, ChatModel> modelCache = new ConcurrentHashMap<>();
    /** 每用户一把锁，防止并发重复构建 */
    private final ConcurrentHashMap<Long, Object> userLocks = new ConcurrentHashMap<>();

    private final UserLlmConfigMapper userLlmConfigMapper;
    private final String deepseekBaseUrl;

    public UserChatModelManager(UserLlmConfigMapper userLlmConfigMapper,
                                @Value("${spring.ai.deepseek.base-url}") String deepseekBaseUrl) {
        this.userLlmConfigMapper = userLlmConfigMapper;
        this.deepseekBaseUrl = deepseekBaseUrl;
    }

    /**
     * 获取某用户指定供应商 + 模型名的 ChatModel。
     * 供应商未配置 key 或已禁用时抛 LlmNotConfiguredException。
     */
    public ChatModel getChatModel(Long userId, ModelEnum model, String modelName) {
        if (userId == null) {
            throw new LlmNotConfiguredException("未获取到登录用户，请先登录");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new LlmNotConfiguredException("未指定模型名称");
        }
        String provider = model.getProvider().name();
        UserLlmConfig config = userLlmConfigMapper.selectByUserIdAndProvider(userId, provider);
        if (config == null || config.getEnabled() == null || config.getEnabled() != 1) {
            throw new LlmNotConfiguredException(
                    "您还未配置" + providerLabel(provider) + "的 API Key，请先到「LLM 配置」页面完成配置");
        }
        String key = userId + ":" + provider + ":" + modelName;
        ChatModel cached = modelCache.get(key);
        if (cached != null) {
            return cached;
        }
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            cached = modelCache.get(key);
            if (cached != null) {
                return cached;
            }
            ChatModel chatModel = buildChatModel(model.getProvider(), config.getApiKey(), modelName);
            modelCache.put(key, chatModel);
            log.info("[LLM配置] 为用户构建模型: userId={}, provider={}, modelName={}",
                    userId, provider, modelName);
            return chatModel;
        }
    }

    /**
     * 使某用户的模型缓存失效（配置增删改/启停后调用），下次请求重新从库加载并构建。
     */
    public void invalidate(Long userId) {
        if (userId == null) {
            return;
        }
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            String prefix = userId + ":";
            modelCache.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    /**
     * 测试连接：用请求中的 key/模型名构建模型并 ping（缺失的 key 取库中旧值），不进入缓存。
     */
    public TestResult testConnection(Long userId, String provider, String apiKey, String modelName) {
        try {
            String p = normalizeProvider(provider);
            String key = apiKey;
            if (key == null || key.isBlank()) {
                UserLlmConfig config = userLlmConfigMapper.selectByUserIdAndProvider(userId, p);
                if (config == null) {
                    return new TestResult(false, "该供应商尚未配置，请输入 API Key");
                }
                key = config.getApiKey();
            }
            ChatModel chatModel = buildChatModelByProvider(p, key, modelName);
            ChatResponse response = chatModel.call(new Prompt("ping"));
            String text = response.getResult() != null ? response.getResult().getOutput().getText() : null;
            if (text != null && !text.isBlank()) {
                return new TestResult(true, "连接成功");
            }
            return new TestResult(false, "连接成功但模型无响应");
        } catch (Exception e) {
            log.warn("LLM 连接测试失败: provider={}", provider, e);
            return new TestResult(false, "连接失败: " + e.getMessage());
        }
    }

    /** 提供商枚举名（如 DASHSCOPE）转中文标签，未知值原样返回 */
    public static String providerLabel(String provider) {
        return switch (provider == null ? "" : provider.toUpperCase()) {
            case "DASHSCOPE" -> "通义千问";
            case "DEEPSEEK" -> "DeepSeek";
            default -> provider;
        };
    }

    /** 归一化前端传入的提供商：支持枚举名或 ModelEnum code（如 qwen/deepseek） */
    public static String normalizeProvider(String provider) {
        if (provider == null) {
            return null;
        }
        String p = provider.trim().toUpperCase();
        if (p.equals("QWEN")) {
            return "DASHSCOPE";
        }
        return p;
    }

    private ChatModel buildChatModel(ModelEnum.Provider provider, String apiKey, String modelName) {
        return switch (provider) {
            case DASHSCOPE -> buildDashScopeChatModel(apiKey, modelName);
            case DEEPSEEK -> buildDeepSeekChatModel(apiKey, modelName);
        };
    }

    private ChatModel buildChatModelByProvider(String provider, String apiKey, String modelName) {
        return switch (provider) {
            case "DASHSCOPE" -> buildDashScopeChatModel(apiKey, modelName);
            case "DEEPSEEK" -> buildDeepSeekChatModel(apiKey, modelName);
            default -> throw new IllegalArgumentException("不支持的提供商: " + provider);
        };
    }

    private ChatModel buildDashScopeChatModel(String apiKey, String modelName) {
        DashScopeApi api = new DashScopeApi(DASHSCOPE_BASE_URL, apiKey, null);
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .withModel(modelName)
                .withProxyToolCalls(true)
                .build();
        return new DashScopeChatModel(api, options);
    }

    private ChatModel buildDeepSeekChatModel(String apiKey, String modelName) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(deepseekBaseUrl)
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(modelName).build())
                .build();
    }

    /** LLM 连接测试结果 */
    public record TestResult(boolean success, String message) {
    }
}
