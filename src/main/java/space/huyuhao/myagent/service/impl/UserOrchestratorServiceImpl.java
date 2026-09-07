package space.huyuhao.myagent.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserOrchestratorBindAgentRequest;
import space.huyuhao.myagent.dto.UserOrchestratorDto;
import space.huyuhao.myagent.dto.UserOrchestratorRequest;
import space.huyuhao.myagent.entity.UserAgent;
import space.huyuhao.myagent.entity.UserOrchestrator;
import space.huyuhao.myagent.entity.UserOrchestratorAgent;
import space.huyuhao.myagent.mapper.UserAgentMapper;
import space.huyuhao.myagent.mapper.UserOrchestratorAgentMapper;
import space.huyuhao.myagent.mapper.UserOrchestratorMapper;
import space.huyuhao.myagent.service.UserOrchestratorService;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserOrchestratorServiceImpl implements UserOrchestratorService {

    private final UserOrchestratorMapper userOrchestratorMapper;
    private final UserOrchestratorAgentMapper userOrchestratorAgentMapper;
    private final UserAgentMapper userAgentMapper;

    public UserOrchestratorServiceImpl(UserOrchestratorMapper userOrchestratorMapper,
                                       UserOrchestratorAgentMapper userOrchestratorAgentMapper,
                                       UserAgentMapper userAgentMapper) {
        this.userOrchestratorMapper = userOrchestratorMapper;
        this.userOrchestratorAgentMapper = userOrchestratorAgentMapper;
        this.userAgentMapper = userAgentMapper;
    }

    @Override
    public UserOrchestrator getValidated(Long userId, Long orchestratorId) {
        UserOrchestrator orchestrator = userOrchestratorMapper.selectByUserIdAndId(userId, orchestratorId);
        if (orchestrator == null) {
            throw new IllegalArgumentException("编排器不存在");
        }
        if (orchestrator.getEnabled() == null || orchestrator.getEnabled() != 1) {
            throw new IllegalArgumentException("编排器已停用，请先在「编排器管理」启用");
        }
        return orchestrator;
    }

    @Override
    public ResponseResult<List<UserOrchestratorDto>> list() {
        Long userId = UserContext.getUserId();
        List<UserOrchestratorDto> dtos = userOrchestratorMapper.selectByUserId(userId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseResult.success(dtos);
    }

    @Override
    public ResponseResult<String> add(UserOrchestratorRequest request) {
        Long userId = UserContext.getUserId();
        if (userOrchestratorMapper.selectByUserIdAndName(userId, request.getOrchestratorName()) != null) {
            return ResponseResult.error(400, "编排器名称已存在");
        }
        UserOrchestrator orchestrator = new UserOrchestrator();
        orchestrator.setUserId(userId);
        orchestrator.setOrchestratorName(request.getOrchestratorName());
        orchestrator.setSystemPrompt(request.getSystemPrompt());
        orchestrator.setProvider(request.getProvider());
        orchestrator.setModelName(request.getModelName());
        orchestrator.setEnabled(request.getEnabled() != null ? request.getEnabled() : 1);
        userOrchestratorMapper.insert(orchestrator);
        return ResponseResult.success("新增成功", null);
    }

    @Override
    public ResponseResult<String> update(UserOrchestratorRequest request) {
        Long userId = UserContext.getUserId();
        if (request.getId() == null) {
            return ResponseResult.error(400, "缺少编排器ID");
        }
        UserOrchestrator orchestrator = userOrchestratorMapper.selectByUserIdAndId(userId, request.getId());
        if (orchestrator == null) {
            return ResponseResult.error(404, "编排器不存在");
        }
        // 仅改名时做重名校验
        if (!orchestrator.getOrchestratorName().equals(request.getOrchestratorName())
                && userOrchestratorMapper.selectByUserIdAndName(userId, request.getOrchestratorName()) != null) {
            return ResponseResult.error(400, "编排器名称已存在");
        }
        UserOrchestrator update = new UserOrchestrator();
        update.setId(orchestrator.getId());
        update.setOrchestratorName(request.getOrchestratorName());
        update.setSystemPrompt(request.getSystemPrompt());
        update.setProvider(request.getProvider());
        update.setModelName(request.getModelName());
        update.setEnabled(request.getEnabled() != null ? request.getEnabled() : orchestrator.getEnabled());
        userOrchestratorMapper.updateById(update);
        return ResponseResult.success("修改成功", null);
    }

    @Override
    public ResponseResult<String> delete(Long id) {
        Long userId = UserContext.getUserId();
        if (userOrchestratorMapper.selectByUserIdAndId(userId, id) == null) {
            return ResponseResult.error(404, "编排器不存在");
        }
        userOrchestratorMapper.deleteById(id);
        // 级联删除绑定关系
        userOrchestratorAgentMapper.deleteByUserIdAndOrchestratorId(userId, id);
        return ResponseResult.success("删除成功", null);
    }

    @Override
    public ResponseResult<String> toggle(Long id) {
        Long userId = UserContext.getUserId();
        UserOrchestrator orchestrator = userOrchestratorMapper.selectByUserIdAndId(userId, id);
        if (orchestrator == null) {
            return ResponseResult.error(404, "编排器不存在");
        }
        UserOrchestrator update = new UserOrchestrator();
        update.setId(orchestrator.getId());
        update.setEnabled(orchestrator.getEnabled() != null && orchestrator.getEnabled() == 1 ? 0 : 1);
        userOrchestratorMapper.updateById(update);
        return ResponseResult.success("操作成功", null);
    }

    @Override
    public ResponseResult<String> bindAgents(UserOrchestratorBindAgentRequest request) {
        Long userId = UserContext.getUserId();
        if (request.getOrchestratorId() == null) {
            return ResponseResult.error(400, "缺少编排器ID");
        }
        if (userOrchestratorMapper.selectByUserIdAndId(userId, request.getOrchestratorId()) == null) {
            return ResponseResult.error(404, "编排器不存在");
        }
        List<Long> agentIds = request.getAgentIds() == null ? List.of() : request.getAgentIds();
        // 子 agent 必须属于当前用户且启用（防越权绑定他人 agent）
        for (Long agentId : agentIds) {
            UserAgent agent = userAgentMapper.selectByUserIdAndId(userId, agentId);
            if (agent == null || agent.getEnabled() == null || agent.getEnabled() != 1) {
                return ResponseResult.error(400, "子Agent不存在或未启用: id=" + agentId);
            }
        }
        // 全量替换
        userOrchestratorAgentMapper.deleteByUserIdAndOrchestratorId(userId, request.getOrchestratorId());
        for (Long agentId : agentIds) {
            UserOrchestratorAgent bind = new UserOrchestratorAgent();
            bind.setUserId(userId);
            bind.setOrchestratorId(request.getOrchestratorId());
            bind.setAgentId(agentId);
            userOrchestratorAgentMapper.insert(bind);
        }
        return ResponseResult.success("绑定成功", null);
    }

    @Override
    public ResponseResult<List<UserAgent>> listAgents(Long userId, Long orchestratorId) {
        if (userOrchestratorMapper.selectByUserIdAndId(userId, orchestratorId) == null) {
            return ResponseResult.error(404, "编排器不存在");
        }
        return ResponseResult.success(userOrchestratorAgentMapper.selectAgentsByOrchestratorId(userId, orchestratorId));
    }

    private UserOrchestratorDto toDto(UserOrchestrator orchestrator) {
        UserOrchestratorDto dto = new UserOrchestratorDto();
        BeanUtils.copyProperties(orchestrator, dto);
        return dto;
    }
}