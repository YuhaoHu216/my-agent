package space.huyuhao.myagent.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserAgentBindMcpRequest;
import space.huyuhao.myagent.dto.UserAgentBindSkillRequest;
import space.huyuhao.myagent.dto.UserAgentDetailDto;
import space.huyuhao.myagent.dto.UserAgentDto;
import space.huyuhao.myagent.dto.UserAgentRequest;
import space.huyuhao.myagent.dto.UserMcpServerDto;
import space.huyuhao.myagent.dto.UserSkillDto;
import space.huyuhao.myagent.entity.UserAgent;
import space.huyuhao.myagent.entity.UserAgentMcp;
import space.huyuhao.myagent.entity.UserAgentSkill;
import space.huyuhao.myagent.entity.UserMcpServer;
import space.huyuhao.myagent.entity.UserSkill;
import space.huyuhao.myagent.mapper.UserAgentMapper;
import space.huyuhao.myagent.mapper.UserAgentMcpMapper;
import space.huyuhao.myagent.mapper.UserAgentSkillMapper;
import space.huyuhao.myagent.mapper.UserMcpServerMapper;
import space.huyuhao.myagent.mapper.UserSkillMapper;
import space.huyuhao.myagent.service.UserAgentService;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserAgentServiceImpl implements UserAgentService {

    private final UserAgentMapper userAgentMapper;
    private final UserAgentSkillMapper userAgentSkillMapper;
    private final UserAgentMcpMapper userAgentMcpMapper;
    private final UserSkillMapper userSkillMapper;
    private final UserMcpServerMapper userMcpServerMapper;

    public UserAgentServiceImpl(UserAgentMapper userAgentMapper,
                                UserAgentSkillMapper userAgentSkillMapper,
                                UserAgentMcpMapper userAgentMcpMapper,
                                UserSkillMapper userSkillMapper,
                                UserMcpServerMapper userMcpServerMapper) {
        this.userAgentMapper = userAgentMapper;
        this.userAgentSkillMapper = userAgentSkillMapper;
        this.userAgentMcpMapper = userAgentMcpMapper;
        this.userSkillMapper = userSkillMapper;
        this.userMcpServerMapper = userMcpServerMapper;
    }

    @Override
    public UserAgent getValidated(Long userId, Long agentId) {
        UserAgent agent = userAgentMapper.selectByUserIdAndId(userId, agentId);
        if (agent == null) {
            throw new IllegalArgumentException("Agent不存在");
        }
        if (agent.getEnabled() == null || agent.getEnabled() != 1) {
            throw new IllegalArgumentException("Agent已停用，请先在「Agent 管理」启用");
        }
        return agent;
    }

    @Override
    public ResponseResult<List<UserAgentDto>> list() {
        Long userId = UserContext.getUserId();
        List<UserAgentDto> dtos = userAgentMapper.selectByUserId(userId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseResult.success(dtos);
    }

    @Override
    public ResponseResult<String> add(UserAgentRequest request) {
        Long userId = UserContext.getUserId();
        if (userAgentMapper.selectByUserIdAndName(userId, request.getAgentName()) != null) {
            return ResponseResult.error(400, "Agent名称已存在");
        }
        UserAgent agent = new UserAgent();
        agent.setUserId(userId);
        agent.setAgentName(request.getAgentName());
        agent.setSystemPrompt(request.getSystemPrompt());
        agent.setNextStepPrompt(request.getNextStepPrompt());
        agent.setProvider(request.getProvider());
        agent.setModelName(request.getModelName());
        agent.setEnabled(request.getEnabled() != null ? request.getEnabled() : 1);
        userAgentMapper.insert(agent);
        return ResponseResult.success("新增成功", null);
    }

    @Override
    public ResponseResult<String> update(UserAgentRequest request) {
        Long userId = UserContext.getUserId();
        if (request.getId() == null) {
            return ResponseResult.error(400, "缺少Agent ID");
        }
        UserAgent agent = userAgentMapper.selectByUserIdAndId(userId, request.getId());
        if (agent == null) {
            return ResponseResult.error(404, "Agent不存在");
        }
        // 仅改名时做重名校验
        if (!agent.getAgentName().equals(request.getAgentName())
                && userAgentMapper.selectByUserIdAndName(userId, request.getAgentName()) != null) {
            return ResponseResult.error(400, "Agent名称已存在");
        }
        UserAgent update = new UserAgent();
        update.setId(agent.getId());
        update.setAgentName(request.getAgentName());
        update.setSystemPrompt(request.getSystemPrompt());
        update.setNextStepPrompt(request.getNextStepPrompt());
        update.setProvider(request.getProvider());
        update.setModelName(request.getModelName());
        update.setEnabled(request.getEnabled() != null ? request.getEnabled() : agent.getEnabled());
        userAgentMapper.updateById(update);
        return ResponseResult.success("修改成功", null);
    }

    @Override
    public ResponseResult<String> delete(Long id) {
        Long userId = UserContext.getUserId();
        if (userAgentMapper.selectByUserIdAndId(userId, id) == null) {
            return ResponseResult.error(404, "Agent不存在");
        }
        userAgentMapper.deleteById(id);
        // 级联删除绑定关系
        userAgentSkillMapper.deleteByUserIdAndAgentId(userId, id);
        userAgentMcpMapper.deleteByUserIdAndAgentId(userId, id);
        return ResponseResult.success("删除成功", null);
    }

    @Override
    public ResponseResult<String> toggle(Long id) {
        Long userId = UserContext.getUserId();
        UserAgent agent = userAgentMapper.selectByUserIdAndId(userId, id);
        if (agent == null) {
            return ResponseResult.error(404, "Agent不存在");
        }
        UserAgent update = new UserAgent();
        update.setId(agent.getId());
        update.setEnabled(agent.getEnabled() != null && agent.getEnabled() == 1 ? 0 : 1);
        userAgentMapper.updateById(update);
        return ResponseResult.success("操作成功", null);
    }

    @Override
    public ResponseResult<UserAgentDetailDto> detail(Long id) {
        Long userId = UserContext.getUserId();
        UserAgent agent = userAgentMapper.selectByUserIdAndId(userId, id);
        if (agent == null) {
            return ResponseResult.error(404, "Agent不存在");
        }
        UserAgentDetailDto detail = new UserAgentDetailDto();
        BeanUtils.copyProperties(agent, detail);
        detail.setSkills(userAgentSkillMapper.selectSkillsByAgentId(userId, id));
        detail.setMcps(userAgentMcpMapper.selectMcpsByAgentId(userId, id));
        return ResponseResult.success(detail);
    }

    @Override
    public ResponseResult<String> bindSkills(UserAgentBindSkillRequest request) {
        Long userId = UserContext.getUserId();
        if (request.getAgentId() == null) {
            return ResponseResult.error(400, "缺少Agent ID");
        }
        if (userAgentMapper.selectByUserIdAndId(userId, request.getAgentId()) == null) {
            return ResponseResult.error(404, "Agent不存在");
        }
        List<Long> skillIds = request.getSkillIds() == null ? List.of() : request.getSkillIds();
        // 技能必须属于当前用户（防越权绑定他人技能）
        for (Long skillId : skillIds) {
            UserSkill skill = userSkillMapper.selectByUserIdAndId(userId, skillId);
            if (skill == null) {
                return ResponseResult.error(400, "技能不存在或不属于当前用户: id=" + skillId);
            }
        }
        // 全量替换
        userAgentSkillMapper.deleteByUserIdAndAgentId(userId, request.getAgentId());
        for (Long skillId : skillIds) {
            UserAgentSkill bind = new UserAgentSkill();
            bind.setUserId(userId);
            bind.setAgentId(request.getAgentId());
            bind.setSkillId(skillId);
            userAgentSkillMapper.insert(bind);
        }
        return ResponseResult.success("绑定成功", null);
    }

    @Override
    public ResponseResult<String> bindMcps(UserAgentBindMcpRequest request) {
        Long userId = UserContext.getUserId();
        if (request.getAgentId() == null) {
            return ResponseResult.error(400, "缺少Agent ID");
        }
        if (userAgentMapper.selectByUserIdAndId(userId, request.getAgentId()) == null) {
            return ResponseResult.error(404, "Agent不存在");
        }
        List<Long> mcpServerIds = request.getMcpServerIds() == null ? List.of() : request.getMcpServerIds();
        // MCP server 必须属于当前用户（防越权绑定他人服务）
        for (Long serverId : mcpServerIds) {
            UserMcpServer server = userMcpServerMapper.selectByUserIdAndId(userId, serverId);
            if (server == null) {
                return ResponseResult.error(400, "MCP服务不存在或不属于当前用户: id=" + serverId);
            }
        }
        // 全量替换
        userAgentMcpMapper.deleteByUserIdAndAgentId(userId, request.getAgentId());
        for (Long serverId : mcpServerIds) {
            UserAgentMcp bind = new UserAgentMcp();
            bind.setUserId(userId);
            bind.setAgentId(request.getAgentId());
            bind.setMcpServerId(serverId);
            userAgentMcpMapper.insert(bind);
        }
        return ResponseResult.success("绑定成功", null);
    }

    private UserAgentDto toDto(UserAgent agent) {
        UserAgentDto dto = new UserAgentDto();
        BeanUtils.copyProperties(agent, dto);
        return dto;
    }
}