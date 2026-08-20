package space.huyuhao.myagent.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserLlmConfigDto;
import space.huyuhao.myagent.dto.UserLlmConfigRequest;
import space.huyuhao.myagent.entity.UserLlmConfig;
import space.huyuhao.myagent.entity.UserLlmModel;
import space.huyuhao.myagent.mapper.UserLlmConfigMapper;
import space.huyuhao.myagent.mapper.UserLlmModelMapper;
import space.huyuhao.myagent.model.UserChatModelManager;
import space.huyuhao.myagent.service.UserLlmConfigService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserLlmConfigServiceImpl implements UserLlmConfigService {

    /** 预置模型名（按 provider 枚举名） */
    private static final Map<String, List<String>> PRESETS = Map.of(
            "DASHSCOPE", List.of("qwen-max", "qwen-plus", "qwen-turbo", "qwen-flash"),
            "DEEPSEEK", List.of("deepseek-v4-pro", "deepseek-v4-flash")
    );

    private final UserLlmConfigMapper userLlmConfigMapper;
    private final UserLlmModelMapper userLlmModelMapper;
    private final UserChatModelManager userChatModelManager;

    public UserLlmConfigServiceImpl(UserLlmConfigMapper userLlmConfigMapper,
                                    UserLlmModelMapper userLlmModelMapper,
                                    UserChatModelManager userChatModelManager) {
        this.userLlmConfigMapper = userLlmConfigMapper;
        this.userLlmModelMapper = userLlmModelMapper;
        this.userChatModelManager = userChatModelManager;
    }

    @Override
    public ResponseResult<List<UserLlmConfigDto>> list() {
        Long userId = UserContext.getUserId();
        List<UserLlmConfigDto> dtos = userLlmConfigMapper.selectByUserId(userId).stream()
                .map(config -> toDto(userId, config))
                .collect(Collectors.toList());
        return ResponseResult.success(dtos);
    }

    @Override
    public ResponseResult<String> save(UserLlmConfigRequest request) {
        Long userId = UserContext.getUserId();
        String provider = UserChatModelManager.normalizeProvider(request.getProvider());
        if (provider == null || provider.isBlank()) {
            return ResponseResult.error(400, "提供商不能为空");
        }
        // 清洗模型名列表：去空去重
        List<String> modelNames = request.getModelNames() == null ? List.of()
                : request.getModelNames().stream()
                        .filter(n -> n != null && !n.isBlank())
                        .distinct()
                        .collect(Collectors.toList());

        UserLlmConfig exist = userLlmConfigMapper.selectByUserIdAndProvider(userId, provider);
        if (exist == null) {
            if (request.getApiKey() == null || request.getApiKey().isBlank()) {
                return ResponseResult.error(400, "新增配置时 API Key 不能为空");
            }
            if (modelNames.isEmpty()) {
                return ResponseResult.error(400, "请至少添加一个模型");
            }
            UserLlmConfig config = new UserLlmConfig();
            config.setUserId(userId);
            config.setProvider(provider);
            config.setApiKey(request.getApiKey());
            config.setEnabled(request.getEnabled() != null ? request.getEnabled() : 1);
            userLlmConfigMapper.insert(config);
        } else {
            UserLlmConfig update = new UserLlmConfig();
            update.setId(exist.getId());
            // 编辑时 API Key 留空则保留旧值
            if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
                update.setApiKey(request.getApiKey());
            }
            update.setEnabled(request.getEnabled() != null ? request.getEnabled() : exist.getEnabled());
            userLlmConfigMapper.updateById(update);
        }
        // 全量替换该供应商的模型列表
        userLlmModelMapper.deleteByUserIdAndProvider(userId, provider);
        for (String modelName : modelNames) {
            UserLlmModel model = new UserLlmModel();
            model.setUserId(userId);
            model.setProvider(provider);
            model.setModelName(modelName);
            userLlmModelMapper.insert(model);
        }
        userChatModelManager.invalidate(userId);
        return ResponseResult.success("保存成功", null);
    }

    @Override
    public ResponseResult<String> delete(String provider) {
        Long userId = UserContext.getUserId();
        String p = UserChatModelManager.normalizeProvider(provider);
        UserLlmConfig exist = userLlmConfigMapper.selectByUserIdAndProvider(userId, p);
        if (exist == null) {
            return ResponseResult.error(404, "配置不存在");
        }
        userLlmConfigMapper.deleteById(exist.getId());
        userLlmModelMapper.deleteByUserIdAndProvider(userId, p);
        userChatModelManager.invalidate(userId);
        return ResponseResult.success("删除成功", null);
    }

    @Override
    public ResponseResult<String> test(UserLlmConfigRequest request) {
        Long userId = UserContext.getUserId();
        if (request.getProvider() == null || request.getProvider().isBlank()) {
            return ResponseResult.error(400, "提供商不能为空");
        }
        String provider = UserChatModelManager.normalizeProvider(request.getProvider());
        // 取请求中的模型名，缺失则用该供应商库中第一个模型
        String modelName = (request.getModelNames() != null && !request.getModelNames().isEmpty())
                ? request.getModelNames().get(0)
                : userLlmModelMapper.selectByUserIdAndProvider(userId, provider).stream()
                        .findFirst()
                        .map(UserLlmModel::getModelName)
                        .orElse(null);
        if (modelName == null || modelName.isBlank()) {
            return ResponseResult.error(400, "模型名称不能为空");
        }
        UserChatModelManager.TestResult result = userChatModelManager.testConnection(
                userId, provider, request.getApiKey(), modelName);
        return result.success() ? ResponseResult.success(result.message(), null)
                : ResponseResult.error(result.message());
    }

    @Override
    public ResponseResult<Map<String, List<String>>> presets() {
        return ResponseResult.success(PRESETS);
    }

    private UserLlmConfigDto toDto(Long userId, UserLlmConfig config) {
        UserLlmConfigDto dto = new UserLlmConfigDto();
        BeanUtils.copyProperties(config, dto);
        dto.setApiKey(maskKey(config.getApiKey()));
        dto.setProviderLabel(UserChatModelManager.providerLabel(config.getProvider()));
        dto.setModels(userLlmModelMapper.selectByUserIdAndProvider(userId, config.getProvider()).stream()
                .map(UserLlmModel::getModelName)
                .collect(Collectors.toList()));
        return dto;
    }

    /** 脱敏：只保留后 4 位 */
    private static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 4) {
            return "****";
        }
        return "****" + key.substring(key.length() - 4);
    }
}
