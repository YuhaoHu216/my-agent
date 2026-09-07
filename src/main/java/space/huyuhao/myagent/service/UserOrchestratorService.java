package space.huyuhao.myagent.service;

import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserOrchestratorBindAgentRequest;
import space.huyuhao.myagent.dto.UserOrchestratorDto;
import space.huyuhao.myagent.dto.UserOrchestratorRequest;
import space.huyuhao.myagent.entity.UserAgent;
import space.huyuhao.myagent.entity.UserOrchestrator;

import java.util.List;

public interface UserOrchestratorService {

    /** 获取编排器并校验归属 + 启用状态，不存在或未启用时抛 IllegalArgumentException */
    UserOrchestrator getValidated(Long userId, Long orchestratorId);

    ResponseResult<List<UserOrchestratorDto>> list();

    ResponseResult<String> add(UserOrchestratorRequest request);

    ResponseResult<String> update(UserOrchestratorRequest request);

    ResponseResult<String> delete(Long id);

    ResponseResult<String> toggle(Long id);

    /** 全量替换编排器绑定的子 agent（绑定前校验 agent 归属） */
    ResponseResult<String> bindAgents(UserOrchestratorBindAgentRequest request);

    /** 查某编排器绑定的子 agent（仅启用中的，供编排/弹窗回显；userId 显式传入供异步线程使用） */
    ResponseResult<List<UserAgent>> listAgents(Long userId, Long orchestratorId);
}