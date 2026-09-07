package space.huyuhao.myagent.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.dto.ResponseResult;
import space.huyuhao.myagent.dto.UserOrchestratorBindAgentRequest;
import space.huyuhao.myagent.dto.UserOrchestratorDto;
import space.huyuhao.myagent.dto.UserOrchestratorRequest;
import space.huyuhao.myagent.entity.UserAgent;
import space.huyuhao.myagent.service.UserOrchestratorService;

import java.util.List;

@RestController
@RequestMapping("/orchestrator")
public class OrchestratorController {

    @Autowired
    private UserOrchestratorService orchestratorService;

    @GetMapping("/list")
    @Operation(summary = "获取我的编排器列表")
    public ResponseResult<List<UserOrchestratorDto>> list() {
        return orchestratorService.list();
    }

    @PostMapping("/add")
    @Operation(summary = "新增编排器")
    public ResponseResult<String> add(@Valid @RequestBody UserOrchestratorRequest request) {
        return orchestratorService.add(request);
    }

    @PutMapping("/update")
    @Operation(summary = "修改编排器")
    public ResponseResult<String> update(@Valid @RequestBody UserOrchestratorRequest request) {
        return orchestratorService.update(request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除编排器（联动删绑定关系）")
    public ResponseResult<String> delete(@PathVariable Long id) {
        return orchestratorService.delete(id);
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "启停切换编排器")
    public ResponseResult<String> toggle(@PathVariable Long id) {
        return orchestratorService.toggle(id);
    }

    @GetMapping("/{id}/agents")
    @Operation(summary = "获取编排器绑定的子 Agent")
    public ResponseResult<List<UserAgent>> listAgents(@PathVariable Long id) {
        return orchestratorService.listAgents(UserContext.getUserId(), id);
    }

    @PutMapping("/{id}/agents/bind")
    @Operation(summary = "绑定子 Agent 到编排器（全量替换）")
    public ResponseResult<String> bindAgents(@PathVariable Long id,
                                             @RequestBody UserOrchestratorBindAgentRequest request) {
        request.setOrchestratorId(id);
        return orchestratorService.bindAgents(request);
    }
}