package space.huyuhao.myagent.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import space.huyuhao.myagent.dto.McpCallResultDto;
import space.huyuhao.myagent.dto.McpToolCallRequest;
import space.huyuhao.myagent.dto.McpToolInfoDto;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserMcpServerDto;
import space.huyuhao.myagent.dto.UserMcpServerRequest;
import space.huyuhao.myagent.service.UserMcpServerService;

import java.util.List;

@RestController
@RequestMapping("/mcp")
public class UserMcpServerController {

    @Autowired
    private UserMcpServerService userMcpServerService;

    @GetMapping("/list")
    @Operation(summary = "获取我的 MCP 服务列表")
    public ResponseResult<List<UserMcpServerDto>> list() {
        return userMcpServerService.list();
    }

    @PostMapping("/add")
    @Operation(summary = "新增 MCP 服务")
    public ResponseResult<String> add(@Valid @RequestBody UserMcpServerRequest request) {
        return userMcpServerService.add(request);
    }

    @PutMapping("/update")
    @Operation(summary = "修改 MCP 服务名称/地址")
    public ResponseResult<String> update(@Valid @RequestBody UserMcpServerRequest request) {
        return userMcpServerService.update(request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除 MCP 服务")
    public ResponseResult<String> delete(@PathVariable Long id) {
        return userMcpServerService.delete(id);
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "启停切换 MCP 服务")
    public ResponseResult<String> toggle(@PathVariable Long id) {
        return userMcpServerService.toggle(id);
    }

    @PostMapping("/test/{id}")
    @Operation(summary = "测试连接 MCP 服务")
    public ResponseResult<String> test(@PathVariable Long id) {
        return userMcpServerService.test(id);
    }

    @GetMapping("/{id}/tools")
    @Operation(summary = "获取 MCP 服务工具列表")
    public ResponseResult<List<McpToolInfoDto>> listTools(@PathVariable Long id) {
        return userMcpServerService.listTools(id);
    }

    @PostMapping("/{id}/tools/call")
    @Operation(summary = "调用 MCP 服务工具（调试）")
    public ResponseResult<McpCallResultDto> callTool(@PathVariable Long id, @Valid @RequestBody McpToolCallRequest request) {
        return userMcpServerService.callTool(id, request);
    }
}
