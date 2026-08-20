package space.huyuhao.myagent.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.dto.McpCallResultDto;
import space.huyuhao.myagent.dto.McpToolCallRequest;
import space.huyuhao.myagent.dto.McpToolInfoDto;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserMcpServerDto;
import space.huyuhao.myagent.dto.UserMcpServerRequest;
import space.huyuhao.myagent.entity.UserMcpServer;
import space.huyuhao.myagent.mapper.UserMcpServerMapper;
import space.huyuhao.myagent.mcp.UserMcpToolManager;
import space.huyuhao.myagent.service.UserMcpServerService;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserMcpServerServiceImpl implements UserMcpServerService {

    private final UserMcpServerMapper userMcpServerMapper;
    private final UserMcpToolManager userMcpToolManager;

    public UserMcpServerServiceImpl(UserMcpServerMapper userMcpServerMapper,
                                    UserMcpToolManager userMcpToolManager) {
        this.userMcpServerMapper = userMcpServerMapper;
        this.userMcpToolManager = userMcpToolManager;
    }

    @Override
    public ResponseResult<List<UserMcpServerDto>> list() {
        Long userId = UserContext.getUserId();
        List<UserMcpServerDto> dtos = userMcpServerMapper.selectByUserId(userId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseResult.success(dtos);
    }

    @Override
    public ResponseResult<String> add(UserMcpServerRequest request) {
        Long userId = UserContext.getUserId();
        if (userMcpServerMapper.selectByUserIdAndName(userId, request.getServerName()) != null) {
            return ResponseResult.error(400, "服务名称已存在");
        }
        UserMcpServer server = new UserMcpServer();
        server.setUserId(userId);
        server.setServerName(request.getServerName());
        server.setUrl(request.getUrl());
        server.setEnabled(request.getEnabled() != null ? request.getEnabled() : 1);
        server.setConnectStatus(0);
        userMcpServerMapper.insert(server);
        userMcpToolManager.invalidate(userId);
        return ResponseResult.success("新增成功", null);
    }

    @Override
    public ResponseResult<String> update(UserMcpServerRequest request) {
        Long userId = UserContext.getUserId();
        if (request.getId() == null) {
            return ResponseResult.error(400, "缺少服务ID");
        }
        UserMcpServer server = userMcpServerMapper.selectByUserIdAndId(userId, request.getId());
        if (server == null) {
            return ResponseResult.error(404, "服务不存在");
        }
        // 仅改名时做重名校验
        if (!server.getServerName().equals(request.getServerName())
                && userMcpServerMapper.selectByUserIdAndName(userId, request.getServerName()) != null) {
            return ResponseResult.error(400, "服务名称已存在");
        }
        UserMcpServer update = new UserMcpServer();
        update.setId(server.getId());
        update.setServerName(request.getServerName());
        update.setUrl(request.getUrl());
        userMcpServerMapper.updateById(update);
        userMcpToolManager.invalidate(userId);
        return ResponseResult.success("修改成功", null);
    }

    @Override
    public ResponseResult<String> delete(Long id) {
        Long userId = UserContext.getUserId();
        if (userMcpServerMapper.selectByUserIdAndId(userId, id) == null) {
            return ResponseResult.error(404, "服务不存在");
        }
        userMcpServerMapper.deleteById(id);
        userMcpToolManager.invalidate(userId);
        return ResponseResult.success("删除成功", null);
    }

    @Override
    public ResponseResult<String> toggle(Long id) {
        Long userId = UserContext.getUserId();
        UserMcpServer server = userMcpServerMapper.selectByUserIdAndId(userId, id);
        if (server == null) {
            return ResponseResult.error(404, "服务不存在");
        }
        UserMcpServer update = new UserMcpServer();
        update.setId(server.getId());
        update.setEnabled(server.getEnabled() != null && server.getEnabled() == 1 ? 0 : 1);
        userMcpServerMapper.updateById(update);
        userMcpToolManager.invalidate(userId);
        return ResponseResult.success("操作成功", null);
    }

    @Override
    public ResponseResult<String> test(Long id) {
        Long userId = UserContext.getUserId();
        UserMcpServer server = userMcpServerMapper.selectByUserIdAndId(userId, id);
        if (server == null) {
            return ResponseResult.error(404, "服务不存在");
        }
        UserMcpToolManager.McpTestResult result = userMcpToolManager.testConnection(server.getUrl());
        UserMcpServer update = new UserMcpServer();
        update.setId(server.getId());
        update.setConnectStatus(result.success() ? 1 : 2);
        userMcpServerMapper.updateById(update);
        if (result.success()) {
            return ResponseResult.success(result.message(), null);
        }
        return ResponseResult.error(result.message());
    }

    @Override
    public ResponseResult<List<McpToolInfoDto>> listTools(Long id) {
        Long userId = UserContext.getUserId();
        UserMcpServer server = userMcpServerMapper.selectByUserIdAndId(userId, id);
        if (server == null) {
            return ResponseResult.error(404, "服务不存在");
        }
        try {
            return ResponseResult.success(userMcpToolManager.listTools(userId, server));
        } catch (Exception e) {
            log.error("获取 MCP 工具列表失败: id={}", id, e);
            return ResponseResult.error("获取工具列表失败: " + e.getMessage());
        }
    }

    @Override
    public ResponseResult<McpCallResultDto> callTool(Long id, McpToolCallRequest request) {
        Long userId = UserContext.getUserId();
        UserMcpServer server = userMcpServerMapper.selectByUserIdAndId(userId, id);
        if (server == null) {
            return ResponseResult.error(404, "服务不存在");
        }
        try {
            UserMcpToolManager.McpCallResult result = userMcpToolManager.callTool(
                    userId, server, request.getToolName(), request.getArguments());
            return ResponseResult.success(new McpCallResultDto(result.success(), result.content()));
        } catch (Exception e) {
            log.error("调用 MCP 工具失败: id={}, toolName={}", id, request.getToolName(), e);
            return ResponseResult.error("调用工具失败: " + e.getMessage());
        }
    }

    private UserMcpServerDto toDto(UserMcpServer server) {
        UserMcpServerDto dto = new UserMcpServerDto();
        BeanUtils.copyProperties(server, dto);
        return dto;
    }
}
