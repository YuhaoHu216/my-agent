package space.huyuhao.myagent.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserAgentBindMcpRequest;
import space.huyuhao.myagent.dto.UserAgentBindSkillRequest;
import space.huyuhao.myagent.dto.UserAgentDetailDto;
import space.huyuhao.myagent.dto.UserAgentDto;
import space.huyuhao.myagent.dto.UserAgentRequest;
import space.huyuhao.myagent.service.UserAgentService;

import java.util.List;

@RestController
@RequestMapping("/agent")
public class AgentController {

    @Autowired
    private UserAgentService userAgentService;

    @GetMapping("/list")
    @Operation(summary = "获取我的 Agent 列表")
    public ResponseResult<List<UserAgentDto>> list() {
        return userAgentService.list();
    }

    @PostMapping("/add")
    @Operation(summary = "新增 Agent")
    public ResponseResult<String> add(@Valid @RequestBody UserAgentRequest request) {
        return userAgentService.add(request);
    }

    @PutMapping("/update")
    @Operation(summary = "修改 Agent")
    public ResponseResult<String> update(@Valid @RequestBody UserAgentRequest request) {
        return userAgentService.update(request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除 Agent（联动删绑定关系）")
    public ResponseResult<String> delete(@PathVariable Long id) {
        return userAgentService.delete(id);
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "启停切换 Agent")
    public ResponseResult<String> toggle(@PathVariable Long id) {
        return userAgentService.toggle(id);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取 Agent 详情（含绑定技能/MCP）")
    public ResponseResult<UserAgentDetailDto> detail(@PathVariable Long id) {
        return userAgentService.detail(id);
    }

    @PutMapping("/{id}/skills/bind")
    @Operation(summary = "绑定技能到 Agent（全量替换）")
    public ResponseResult<String> bindSkills(@PathVariable Long id,
                                             @RequestBody UserAgentBindSkillRequest request) {
        request.setAgentId(id);
        return userAgentService.bindSkills(request);
    }

    @PutMapping("/{id}/mcps/bind")
    @Operation(summary = "绑定 MCP 服务到 Agent（全量替换）")
    public ResponseResult<String> bindMcps(@PathVariable Long id,
                                           @RequestBody UserAgentBindMcpRequest request) {
        request.setAgentId(id);
        return userAgentService.bindMcps(request);
    }
}