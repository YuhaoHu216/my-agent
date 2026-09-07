package space.huyuhao.myagent.service;

import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserAgentBindMcpRequest;
import space.huyuhao.myagent.dto.UserAgentBindSkillRequest;
import space.huyuhao.myagent.dto.UserAgentDetailDto;
import space.huyuhao.myagent.dto.UserAgentDto;
import space.huyuhao.myagent.dto.UserAgentRequest;
import space.huyuhao.myagent.entity.UserAgent;

import java.util.List;

public interface UserAgentService {

    /** 获取 agent 并校验归属 + 启用状态，不存在或未启用时抛 IllegalArgumentException */
    UserAgent getValidated(Long userId, Long agentId);

    ResponseResult<List<UserAgentDto>> list();

    ResponseResult<String> add(UserAgentRequest request);

    ResponseResult<String> update(UserAgentRequest request);

    ResponseResult<String> delete(Long id);

    ResponseResult<String> toggle(Long id);

    ResponseResult<UserAgentDetailDto> detail(Long id);

    ResponseResult<String> bindSkills(UserAgentBindSkillRequest request);

    ResponseResult<String> bindMcps(UserAgentBindMcpRequest request);
}