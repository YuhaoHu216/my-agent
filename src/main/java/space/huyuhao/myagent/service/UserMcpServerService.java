package space.huyuhao.myagent.service;

import space.huyuhao.myagent.dto.McpCallResultDto;
import space.huyuhao.myagent.dto.McpToolCallRequest;
import space.huyuhao.myagent.dto.McpToolInfoDto;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserMcpServerDto;
import space.huyuhao.myagent.dto.UserMcpServerRequest;

import java.util.List;

public interface UserMcpServerService {

    ResponseResult<List<UserMcpServerDto>> list();

    ResponseResult<String> add(UserMcpServerRequest request);

    ResponseResult<String> update(UserMcpServerRequest request);

    ResponseResult<String> delete(Long id);

    ResponseResult<String> toggle(Long id);

    ResponseResult<String> test(Long id);

    ResponseResult<List<McpToolInfoDto>> listTools(Long id);

    ResponseResult<McpCallResultDto> callTool(Long id, McpToolCallRequest request);
}
